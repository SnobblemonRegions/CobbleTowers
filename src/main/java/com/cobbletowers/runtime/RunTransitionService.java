package com.cobbletowers.runtime;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.diagnostics.TowerMetrics;
import com.cobbletowers.encounter.TowerEncounters;
import com.cobbletowers.instance.InstanceAllocator;
import com.cobbletowers.persistence.CellStateStore;
import com.cobbletowers.intermission.IntermissionService;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunCheckpoint;
import com.cobbletowers.reward.RewardBankService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * The only thing that moves a run. {@link #decide} is pure; {@link #apply} writes the result. Checkpoint policy lives
 * in {@code Transition}.
 */
public final class RunTransitionService {

    /** What an event does to a run. */
    public sealed interface Outcome permits Move, Refusal {}

    /**
     * The run as it will be, and how it must be written.
     * @param key the idempotency key when this move checkpoints, otherwise empty
     */
    public record Move(PersistedRun next, RunState from, boolean checkpoint, String key) implements Outcome {}

    public record Refusal(Reason reason, String detail) implements Outcome {}

    public enum Reason {
        UNKNOWN_RUN,
        /** The table has no move for this event from this state. Includes replaying a move already made. */
        ILLEGAL_EVENT,
        /** Nothing was ever committed, so there is no state to put the run back into. */
        NO_CHECKPOINT,
        /** The run's cell is gone or quarantined, so resuming would put it somewhere unusable. */
        CELL_UNAVAILABLE,
        /** A checkpoint key already committed is being committed again for a different outcome. */
        KEY_REUSED,
        /** The party is still being offered a modifier and has not settled on one (TDS #2). */
        DRAFT_OPEN
    }

    private RunTransitionService() {}

    /**
     * What {@code event} does to {@code run}, without touching anything. A replayed move finds no transition and is
     * refused as {@link Reason#ILLEGAL_EVENT}; the key ledger detects two outcomes under one key.
     */
    public static Outcome decide(PersistedRun run, RunEvent event, long now) {
        Optional<RunTransitions.Transition> found = RunTransitions.lookup(run.state(), event);
        if (found.isEmpty()) {
            return new Refusal(Reason.ILLEGAL_EVENT, event + " is not legal from " + run.state()
                    + "; legal here: " + RunTransitions.eventsFrom(run.state()));
        }
        RunTransitions.Transition transition = found.get();
        if (transition.resumeFromCheckpoint()) {
            return resume(run, now);
        }
        // Pure-half rule: no leaving an intermission with a draft still open.
        if (event == RunEvent.INTERMISSION_COMPLETE && run.modifiers().hasOpenDraft()) {
            return new Refusal(Reason.DRAFT_OPEN, "run " + run.runId() + " is still choosing a modifier;"
                    + " vote with /cobbletowers runs draft vote, or settle it with draft force");
        }

        // The floor BEFORE the move, which is what makes {nextFloor} name the floor being opened.
        String key = transition.checkpoint() ? transition.key(run.runId(), run.floorIndex()) : "";
        if (!key.isEmpty() && run.hasCommitted(key)) {
            return new Refusal(Reason.KEY_REUSED, "checkpoint key " + key + " has already been committed by this run;"
                    + " committing it again would mean two outcomes under one key");
        }

        int floor = event == RunEvent.NEXT_FLOOR_CONFIRMED ? run.floorIndex() + 1 : run.floorIndex();
        List<String> committed = new ArrayList<>(run.committedTransactions());
        if (!key.isEmpty()) committed.add(key);
        // Only a keyed move moves the checkpoint; keyless ones (park, abandon, resume) force a write but leave it
        // where it was.
        Optional<RunCheckpoint> checkpoint = key.isEmpty()
                ? run.lastCheckpoint()
                : Optional.of(new RunCheckpoint(key, transition.next()));

        PersistedRun next = new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(), run.towerRevision(),
                run.towerDigest(), run.rulesetRevision(), run.structureRevision(), run.seed(), floor,
                transition.next(), run.participants(), checkpoint, committed, now, run.cell(), run.ledger(),
                run.modifiers(), run.lastBankedFloor(), run.vendorPurchases(), run.options());
        return new Move(next, run.state(), transition.checkpoint(), key);
    }

    /** Puts a parked run back at its checkpoint state. A run with nothing committed is refused. */
    private static Outcome resume(PersistedRun run, long now) {
        Optional<RunCheckpoint> checkpoint = run.lastCheckpoint();
        if (checkpoint.isEmpty()) {
            return new Refusal(Reason.NO_CHECKPOINT, "run " + run.runId() + " was parked before it committed"
                    + " anything, so there is no state to resume into; abandon it instead");
        }
        RunState target = checkpoint.get().state();
        PersistedRun next = new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(), run.towerRevision(),
                run.towerDigest(), run.rulesetRevision(), run.structureRevision(), run.seed(), run.floorIndex(),
                target, run.participants(), run.lastCheckpoint(), run.committedTransactions(), now, run.cell(),
                run.ledger(), run.modifiers(), run.lastBankedFloor(), run.vendorPurchases(), run.options());
        // Durable, because a resume that a crash undoes leaves a run reported as recovered and
        // parked on disk -- the two states nobody can tell apart afterwards.
        return new Move(next, run.state(), true, "");
    }

    /**
     * Applies {@code event} to the run with this id and writes the result. A checkpointing move is flushed before
     * returning.
     */
    public static Outcome apply(MinecraftServer server, UUID runId, RunEvent event, long now) {
        long started = System.nanoTime();
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) {
            return new Refusal(Reason.UNKNOWN_RUN, "no run with id " + runId);
        }
        PersistedRun run = found.get();
        if (event == RunEvent.RECOVERY_COMPLETED) {
            // Checked here rather than in decide, because whether a cell is usable is a question
            // about the world and decide is deliberately pure.
            Optional<Refusal> blocked = cellUnusable(server, run);
            if (blocked.isPresent()) return blocked.get();
        }
        Outcome outcome = decide(run, event, now);
        if (outcome instanceof Move move) {
            if (event == RunEvent.RECOVERY_COMPLETED && move.next().cell().isPresent()) {
                com.cobbletowers.instance.CellTickets.hold(server, move.next().cell().getAsInt());
            }
            TowerRuns.save(server, move.next(), move.checkpoint());
            TowerLog.info("Run {} {} -> {} on {}{}", runId, move.from(), move.next().state(), event,
                    move.checkpoint() ? " [checkpoint " + move.key() + "]" : "");
            // Beta telemetry and the per-run history a player report reads (P48); never disturbs the run.
            com.cobbletowers.report.RunTelemetry.onTransition(server, runId, move.from(), move.next().state(), event);
            // The physical vendor stands only for an intermission (P28), so it goes the moment the run leaves one.
            // Each side effect on its own: the state is already saved, and a fault in one (a draft that will not open)
            // must not skip the ones after it (banking the rewards, releasing the cell).
            if (move.from() == RunState.INTERMISSION) {
                effect("despawn the vendor", runId, () -> com.cobbletowers.vendor.VendorNpc.despawn(server, run));
                effect("despawn the pedestals", runId, () -> com.cobbletowers.intermission.PedestalService.despawn(server, run));
                effect("cancel the echo duels", runId, () -> com.cobbletowers.echo.EchoDuels.cancel(server, runId));
            }
            // Everybody who is out comes back on arriving at an intermission, whatever road got there.
            if (move.next().state() == RunState.INTERMISSION) {
                effect("revive the party", runId, () -> ParticipantService.reviveAtIntermission(server, runId, now));
                effect("open the draft", runId, () -> DraftService.open(server, runId, now));
                // Before banking below, so a reward reveal sent when a floor banks lands on top of the
                // intermission screen rather than under it.
                effect("open the intermission", runId, () -> IntermissionService.onArrival(server, runId));
            }
            // Tied to arrival so a resume after a crash re-attempts banking; bank() decides whether it is a payout
            // point (docs/design/P9-economy.md section 4a).
            if (move.next().state() == RunState.INTERMISSION || move.next().state() == RunState.COMPLETED
                    || move.next().state() == RunState.CASHED_OUT) {
                effect("bank the rewards", runId, () -> RewardBankService.bank(server, runId, now));
            }
            // A rental run that completed earns real cards (P33b), read from the party journal before the player is
            // given their own
            // Pokemon back. Banked above, granted here, each once.
            if (move.next().state() == RunState.COMPLETED) {
                try {
                    com.cobbletowers.reward.CardRewardService.onCompleted(server, runId, now);
                } catch (RuntimeException ex) {
                    TowerLog.error("Could not grant the card rewards of run {}", runId, ex);
                }
            }
            // The settled draft is cleared on the way out rather than when it settles, so that
            // `runs show` and the reward-reveal still have it to read for the whole intermission.
            if (move.next().state() == RunState.NEXT_FLOOR_READY) {
                effect("clear the settled draft", runId, () -> DraftService.clearIfSettled(server, runId, now));
            }
            // Re-fetched: bank() may have written a newer record, and saving the stale move.next() would overwrite
            // it.
            if (move.next().state().isTerminal()) {
                // The exit sweep releases a finished run's cell too, so a fault here only delays it.
                effect("release the cell", runId, () -> releaseInstance(server, TowerRuns.get(runId).orElse(move.next()), now));
            }
            // Crossing into a new Ascension (P30) happens as the first floor of the cycle is confirmed.
            if (event == RunEvent.NEXT_FLOOR_CONFIRMED) {
                effect("cross into the next ascension", runId,
                        () -> com.cobbletowers.ascension.AscensionService.onFloorConfirmed(server, runId, now));
                // A rental draft's team grows as the floors climb (P40); a no-op for everyone else.
                effect("grow the rental team", runId,
                        () -> com.cobbletowers.rental.RentalScaling.onFloorConfirmed(server, runId));
            }
            // Mastery (P31): floor timing, cycle clears, run endings. Never allowed to disturb the run it is
            // watching.
            // A trial attempt is judged first: it reads the run stats that mastery drops when a run ends.
            com.cobbletowers.trial.TrialService.onTransition(server, runId, move.from(), move.next().state(), now);
            com.cobbletowers.mastery.MasteryService.onTransition(server, runId, move.from(), move.next().state(), now);
            // TDS #60/section 11: what a transition costs end to end, including the revival,
            // reward-bank and draft side effects above -- not only the record write.
            TowerMetrics.recordTransition(server, runId, (System.nanoTime() - started) / 1_000_000);
        }
        return outcome;
    }

    /** Runs one side effect of a move, logging a fault instead of letting it skip the effects after it. */
    private static void effect(String what, UUID runId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            TowerLog.error("Run " + runId + " could not " + what, ex);
        }
    }

    /**
     * Gives a finished run's cell back and drops the lease, for every way a run can end. A cell that fails
     * verification is quarantined.
     */
    private static void releaseInstance(MinecraftServer server, PersistedRun run, long now) {
        // Stop the run's battles first so a leftover opponent cannot quarantine the cell.
        TowerEncounters.abandon(server, run.runId());
        // A cell still being built for it (its first floor, or a next floor in another building) is nobody's now: retired, not leaked.
        effect("give back a cell built for it", run.runId(), () -> {
            com.cobbletowers.instance.RunBuildJobs.abandon(run.runId());
            com.cobbletowers.instance.RunBuildJobs.abandon(com.cobbletowers.instance.RunBuildJobs.nextFloorTenant(run.runId()));
        });

        if (run.cell().isEmpty()) return;
        // P20: never reset a cell with a player standing in it. The run keeps its lease for the beat and the
        // exit sweep releases it once everybody has been sent home; otherwise the cell is quarantined.
        if (RunExitService.mustDefer(server, run)) {
            RunExitService.announce(server, run);
            return;
        }
        // Clearing a cell is heavy work. When the tower is busy the run keeps its lease for a beat and the once-a-
        // second exit sweep
        // releases it (nobody is inside, so it is due at once); nothing is lost by waiting.
        if (!com.cobbletowers.instance.HeavyWork.tryAcquire(now)) {
            TowerLog.info("Run {} ended; its cell {} will be released by the exit sweep (the tower is busy)", run.runId(), run.cell().getAsInt());
            return;
        }
        releaseCell(server, run, now);
    }

    /** The release itself: reset, verify, give the lease back. Public so the exit sweep can finish a deferred one. */
    public static void releaseCell(MinecraftServer server, PersistedRun run, long now) {
        OptionalInt cell = run.cell();
        if (cell.isEmpty()) return;
        // A cell another run now holds is not this run's to reset: only its record of it is stale.
        Optional<UUID> holder = InstanceAllocator.runIn(cell.getAsInt());
        if (holder.isEmpty() || !holder.get().equals(run.runId())) {
            TowerLog.warn("Run {} names cell {}, which run {} holds; dropping the stale claim without touching it",
                    run.runId(), cell.getAsInt(), holder.map(UUID::toString).orElse("none"));
            TowerRuns.save(server, run.withCell(OptionalInt.empty(), now), true);
            return;
        }
        // Transfer recovery's hold to cleanup before releasing it, so the chunks never lose their ticket in between.
        com.cobbletowers.instance.CellTickets.hold(server, cell.getAsInt());
        RecoverySweep.cancel(server, run.runId(), cell.getAsInt());
        // Only the current tenant may schedule clearing, and the job rechecks that lease each slice.
        if (!com.cobbletowers.instance.CellClearJobs.ensureClear(server, cell.getAsInt(), run.runId())) {
            TowerLog.info("Run {} ended; its cell {} is being cleared in slices before it is released", run.runId(), cell.getAsInt());
            return;
        }
        com.cobbletowers.vendor.VendorNpc.despawn(server, run);
        com.cobbletowers.intermission.PedestalService.despawn(server, run);
        com.cobbletowers.halloween.door.TrickDoors.dropRun(run.runId());
        InstanceAllocator.Release release = InstanceAllocator.release(server, run.runId(), cell.getAsInt());
        if (release instanceof InstanceAllocator.Quarantined quarantined) {
            TowerLog.warn("Run {} ended leaving cell {} unfit for reuse: {}",
                    run.runId(), quarantined.cell(), quarantined.reason());
        }
        // Checkpointed: a dropped lease that a crash undoes would leave the cell claimed by a run
        // that has already finished, and nothing later would ever come back to release it.
        TowerRuns.save(server, run.withCell(OptionalInt.empty(), now), true);
    }

    /** Whether the run's instance is fit to go back to; a missing or quarantined cell is refused. */
    private static Optional<Refusal> cellUnusable(MinecraftServer server, PersistedRun run) {
        OptionalInt cell = run.cell();
        if (cell.isEmpty()) {
            return Optional.of(new Refusal(Reason.CELL_UNAVAILABLE,
                    "run " + run.runId() + " holds no instance cell to resume into"));
        }
        if (CellStateStore.get(server).isQuarantined(cell.getAsInt())) {
            return Optional.of(new Refusal(Reason.CELL_UNAVAILABLE,
                    "cell " + cell.getAsInt() + " is quarantined; it must be cleared before the run can resume"));
        }
        return Optional.empty();
    }
}
