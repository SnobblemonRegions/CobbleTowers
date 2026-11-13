package com.cobbletowers.runtime;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.encounter.TowerEncounters;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.CellWarmPool;
import com.cobbletowers.instance.InstanceAllocator;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/**
 * The steps that are more than one transition: taking an instance, building it, and giving up when there is none.
 * {@link RunTransitionService} knows only the table.
 */
public final class RunLifecycle {

    private RunLifecycle() {}

    /**
     * Submits a run's parties and settles them (TDS #41, #46): read, register, check against the ruleset, then
     * allocate an instance or abandon with reasons logged. Registration is written before the verdict event. {@code
     * partyOf} is empty for an offline player, which rejects the run.
     */
    public static RunTransitionService.Outcome validateParty(MinecraftServer server, UUID runId, long now,
                                                             Function<UUID, Optional<List<PartyMember>>> partyOf) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) {
            return new RunTransitionService.Refusal(RunTransitionService.Reason.UNKNOWN_RUN, "no run with id " + runId);
        }
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(found.get().towerId());
        RulesetDefinition ruleset = com.cobbletowers.definition.RulesetResolver.forRun(TowerDefinitionRegistry.content(),
                found.get(), Optional.empty());
        if (ruleset == null) {
            return new RunTransitionService.Refusal(RunTransitionService.Reason.ILLEGAL_EVENT,
                    "run " + runId + " has no ruleset to validate against");
        }

        RunTransitionService.Outcome submitted = RunTransitionService.apply(server, runId, RunEvent.PARTY_SUBMITTED, now);
        if (submitted instanceof RunTransitionService.Refusal) return submitted;

        // Re-read after the submit move: apply() wrote a fresh record, and saving participants onto
        // the stale one would undo the state change.
        PersistedRun run = TowerRuns.get(runId).orElseThrow();
        List<PersistedParticipant> registered = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            Optional<List<PartyMember>> party = partyOf.apply(participant.playerId());
            if (party.isEmpty()) {
                problems.add(participant.playerId() + " is not online to register a party");
                registered.add(participant);
                continue;
            }
            PartyValidation.Result result = PartyValidation.validate(party.get(), ruleset);
            result.problems().forEach(problem -> problems.add(participant.playerId() + " " + problem));
            // A playlist's clauses (P32) apply to the Pokemon that would be registered.
            run.options().playlist().flatMap(com.cobbletowers.definition.PlaylistRegistry::get)
                    .ifPresent(playlist -> PlaylistRules.problems(party.get().stream()
                                    .filter(member -> result.registered().contains(member.id())).toList(), playlist)
                            .forEach(problem -> problems.add(participant.playerId() + " " + problem)));
            registered.add(new PersistedParticipant(participant.playerId(), participant.state(), result.registered()));
        }
        TowerRuns.save(server, run.withParticipants(registered, now), false);

        if (problems.isEmpty()) {
            return RunTransitionService.apply(server, runId, RunEvent.PARTY_VALIDATED, now);
        }
        TowerLog.info("Run {} party rejected: {}", runId, problems);
        return RunTransitionService.apply(server, runId, RunEvent.PARTY_REJECTED, now);
    }

    /** How opening a floor went. */
    public enum Opened {
        /** The floor is up and the team is in it. */
        OPENED,
        /** The next floor is another building, still being built in its own cell: nothing changed yet, ask again shortly. */
        PREPARING,
        /** The table refused the move (an open draft, say); nothing changed and the run is not parked. */
        BLOCKED,
        /** Something broke and the run was parked for recovery: broken content is not a loss. */
        FAILED
    }

    /**
     * ENCOUNTER_STARTED and the floor's first round for a run at FLOOR_READY; parks the run with TECHNICAL_FAILURE if
     * either fails.
     */
    public static boolean beginFloor(MinecraftServer server, UUID runId, long now) {
        if (!(RunTransitionService.apply(server, runId, RunEvent.ENCOUNTER_STARTED, now)
                instanceof RunTransitionService.Move)) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            return false;
        }
        try {
            TowerRuns.get(runId).ifPresent(run -> com.cobbletowers.route.TowerGates.closeBoth(server, run));
            if (TowerEncounters.begin(server, runId).isPresent()) return true;
        } catch (RuntimeException ex) {
            TowerLog.error("Opening floor of run {} threw", runId, ex);
        }
        // Whatever the floor had started before it failed (a battle for the first player, say) is ended with it: a
        // parked run is not in the watchdog's sight, and nothing else would.
        try {
            TowerEncounters.abandon(server, runId);
        } catch (RuntimeException ex) {
            TowerLog.error("Ending the half-opened floor of run {} threw", runId, ex);
        }
        RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
        return false;
    }

    /**
     * Leaves an intermission for the next floor, rebuilding the cell in place if the next floor is a different
     * structure, then begins the floor.
     */
    public static Opened openNextFloor(MinecraftServer server, UUID runId, long now) {
        // A floor in another building is built in a cell of its own, a slice per tick, while the party stays where it is
        // (docs/design/cell-allocation-async.md, option B). Rebuilding the party's own cell could not be sliced: they stand in it.
        Optional<PersistedRun> current = TowerRuns.get(runId);
        if (current.isPresent() && current.get().state() == com.cobbletowers.api.tower.RunState.INTERMISSION
                && !current.get().modifiers().hasOpenDraft()) {
            Optional<FloorLayout> changed = changedLayout(current.get(), current.get().floorIndex() + 1);
            if (changed.isPresent()) {
                Optional<Opened> moved = openInNewCell(server, current.get(), changed.get(), now);
                if (moved.isPresent()) return moved.get();
                // Empty: the separate cell could not be had; the floor is rebuilt in place as before.
            }
        }
        if (!(RunTransitionService.apply(server, runId, RunEvent.INTERMISSION_COMPLETE, now)
                instanceof RunTransitionService.Move)) {
            return Opened.BLOCKED;
        }
        if (!(RunTransitionService.apply(server, runId, RunEvent.NEXT_FLOOR_CONFIRMED, now)
                instanceof RunTransitionService.Move)) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            return Opened.FAILED;
        }
        boolean rebuilt;
        try {
            rebuilt = rebuildIfStructureChanged(server, runId);
        } catch (RuntimeException ex) {
            // The run has already moved to the next floor: parking it is what keeps it from sitting there unwatched.
            TowerLog.error("Rebuilding the cell of run " + runId + " for its next floor threw", ex);
            rebuilt = false;
        }
        if (!rebuilt) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            return Opened.FAILED;
        }
        return beginFloor(server, runId, now) ? Opened.OPENED : Opened.FAILED;
    }

    /** The layout of {@code floorIndex} when it is a different building from the floor before it; empty when the party stays in its building. */
    static Optional<FloorLayout> changedLayout(PersistedRun run, int floorIndex) {
        var content = TowerDefinitionRegistry.content();
        Optional<FloorLayout> next = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, floorIndex);
        Optional<FloorLayout> previous = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, floorIndex - 1);
        if (next.isEmpty()) return Optional.empty();
        if (previous.isPresent() && previous.get().structure().equals(next.get().structure())) return Optional.empty();
        return next;
    }

    /**
     * Starts building the next floor's building in a cell of its own, if it is a different building and nothing is building yet: called when the
     * walk to the next floor begins, so the arena is often ready by the time the party arrives. Does nothing otherwise.
     */
    public static void prepareNextFloor(MinecraftServer server, UUID runId) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty() || found.get().state() != com.cobbletowers.api.tower.RunState.INTERMISSION
                || found.get().modifiers().hasOpenDraft() || found.get().cell().isEmpty()) return;
        Optional<FloorLayout> changed = changedLayout(found.get(), found.get().floorIndex() + 1);
        UUID key = com.cobbletowers.instance.RunBuildJobs.nextFloorTenant(runId);
        if (changed.isEmpty() || com.cobbletowers.instance.RunBuildJobs.poll(key).isPresent()) return;
        startNextFloorBuild(server, runId, changed.get());
    }

    /** A cell for the next floor, from the warm pool or built in slices, under the run's next-floor tenant. False when none could be had. */
    private static boolean startNextFloorBuild(MinecraftServer server, UUID runId, FloorLayout layout) {
        UUID tenant = com.cobbletowers.instance.RunBuildJobs.nextFloorTenant(runId);
        OptionalInt warm = CellWarmPool.take(layout.structure(), tenant);
        if (warm.isPresent()) {
            com.cobbletowers.instance.RunBuildJobs.adoptReady(tenant, tenant, warm.getAsInt());
            return true;
        }
        if (!(InstanceAllocator.allocate(server, tenant) instanceof InstanceAllocator.Leased leased)) return false;
        if (com.cobbletowers.instance.RunBuildJobs.start(server, tenant, tenant, leased.cell(), layout)) return true;
        InstanceAllocator.release(server, tenant, leased.cell());
        return false;
    }

    /**
     * The floor change into another building, through a cell of its own: PREPARING while it builds, then the move. Empty when no separate cell
     * could be had (or its build failed), so the caller rebuilds in place as it always did.
     */
    private static Optional<Opened> openInNewCell(MinecraftServer server, PersistedRun run, FloorLayout layout, long now) {
        UUID runId = run.runId();
        UUID key = com.cobbletowers.instance.RunBuildJobs.nextFloorTenant(runId);
        Optional<com.cobbletowers.instance.RunBuildJobs.Status> status = com.cobbletowers.instance.RunBuildJobs.poll(key);
        if (status.isEmpty()) {
            if (!startNextFloorBuild(server, runId, layout)) return Optional.empty();
            status = com.cobbletowers.instance.RunBuildJobs.poll(key);
        }
        var job = status.orElseThrow();
        switch (job.state()) {
            case BUILDING:
                return Optional.of(Opened.PREPARING);
            case FAILED:
                TowerLog.error("Cell {} could not be built as {} for run {}: {}; rebuilding its own cell instead", job.cell(), layout.structure(),
                        runId, String.join("; ", job.problems()));
                com.cobbletowers.instance.RunBuildJobs.forget(key);
                com.cobbletowers.instance.RunBuildJobs.retire(job.cell(), job.tenant());
                return Optional.empty();
            case READY:
            default:
                break;
        }
        if (!(RunTransitionService.apply(server, runId, RunEvent.INTERMISSION_COMPLETE, now) instanceof RunTransitionService.Move)) {
            return Optional.of(Opened.BLOCKED);   // the built cell stays ready for the next try
        }
        if (!(RunTransitionService.apply(server, runId, RunEvent.NEXT_FLOOR_CONFIRMED, now) instanceof RunTransitionService.Move)) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            com.cobbletowers.instance.RunBuildJobs.forget(key);
            com.cobbletowers.instance.RunBuildJobs.retire(job.cell(), job.tenant());
            return Optional.of(Opened.FAILED);
        }
        try {
            moveToCell(server, runId, job.cell(), job.tenant(), layout, now);
        } catch (RuntimeException ex) {
            TowerLog.error("Moving run " + runId + " into its next floor's cell threw", ex);
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            return Optional.of(Opened.FAILED);
        }
        return Optional.of(beginFloor(server, runId, now) ? Opened.OPENED : Opened.FAILED);
    }

    /**
     * The run leaves its cell for {@code newCell}: what the old floor left standing is swept (as the in-place rebuild's sweep did), the leases swap,
     * the run is checkpointed with its new cell, spectators are moved (fighters are moved by the floor's start), and the old cell is retired in slices.
     */
    private static void moveToCell(MinecraftServer server, UUID runId, int newCell, UUID tenant, FloorLayout layout, long now) {
        PersistedRun run = TowerRuns.get(runId).orElseThrow();
        int oldCell = run.cell().orElseThrow();
        // The vendor and the pedestals included: they find themselves by run, and an old vendor still standing would keep the new one from spawning.
        com.cobbletowers.instance.CellCleanup.sweepEntities(server, oldCell);
        UUID retiring = com.cobbletowers.instance.RunBuildJobs.retiringTenant(runId);
        InstanceAllocator.transferLease(oldCell, runId, retiring);
        InstanceAllocator.transferLease(newCell, tenant, runId);
        TowerRuns.save(server, run.withCell(OptionalInt.of(newCell), now), true);
        com.cobbletowers.instance.RunBuildJobs.forget(tenant);
        moveSpectators(server, run, newCell, layout);
        com.cobbletowers.instance.RunBuildJobs.retire(oldCell, retiring);
        TowerLog.info("Run {} moved from cell {} to cell {} for {}", runId, oldCell, newCell, layout.structure());
    }

    /** Spectators stay with the team: to the new floor's spectator spot. */
    private static void moveSpectators(MinecraftServer server, PersistedRun run, int cell, FloorLayout layout) {
        var level = com.cobbletowers.instance.TowerDimension.level(server);
        var origin = CellPreparer.originFor(server, cell, layout);
        if (level == null || origin.isEmpty()) return;
        var spot = layout.spectator().in(origin.get());
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().isInRun() || participant.state().canFight()) continue;
            var player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null || player.serverLevel() != level) continue;
            player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, layout.spectator().yaw(), 0.0f);
        }
    }

    private static boolean rebuildIfStructureChanged(MinecraftServer server, UUID runId) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty() || found.get().cell().isEmpty()) return false;
        PersistedRun run = found.get();
        Optional<FloorLayout> next = com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        Optional<FloorLayout> previous = com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex() - 1);
        if (next.isEmpty()) return false;
        if (previous.isPresent() && previous.get().structure().equals(next.get().structure())) return true;

        int cell = run.cell().getAsInt();
        // prepare() clears the previous floor itself (the cell is dirty), so there is no separate reset: one scan,
        // not two.
        Optional<CellPreparer.Prepared> prepared = CellPreparer.prepare(server, cell, next.get());
        // This cannot wait for a slot (the party is between floors), so it is noted: everything else backs off around
        // it.
        com.cobbletowers.instance.HeavyWork.note(System.currentTimeMillis());
        if (prepared.isEmpty() || !prepared.get().isPlayable()) {
            TowerLog.error("Cell {} could not be rebuilt as {} for run {}: {}", cell, next.get().structure(), runId,
                    prepared.map(CellPreparer.Prepared::summary).orElse("the structure did not place"));
            return false;
        }
        return true;
    }

    /**
     * Gives a run a cell, builds its floor and moves it on, or parks it. Order matters: warm pool first; lease
     * written onto the run before the INSTANCE_ALLOCATED checkpoint; any failure becomes ALLOCATION_FAILED (a
     * technical fault, not a loss).
     */
    public static RunTransitionService.Outcome allocateInstance(MinecraftServer server, UUID runId, long now) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) {
            return new RunTransitionService.Refusal(RunTransitionService.Reason.UNKNOWN_RUN, "no run with id " + runId);
        }
        PersistedRun run = found.get();

        Optional<FloorLayout> layout = com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty()) {
            TowerLog.error("Run {} is on {} floor {}, which declares no layout; parking it",
                    runId, run.towerId(), run.floorIndex());
            return RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now);
        }

        OptionalInt cell = obtainCell(server, runId, layout.get());
        if (cell.isEmpty()) {
            return RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now);
        }
        return finishAllocation(server, run, cell, layout.get(), now);
    }

    /** What starting a run's allocation came to: settled now, or a cell still being built (poll {@link #pollAllocation}). */
    public sealed interface Allocation permits Allocated, Building {}
    public record Allocated(RunTransitionService.Outcome outcome) implements Allocation {}
    public record Building() implements Allocation {}

    /**
     * {@link #allocateInstance} for a party that can wait: a ready cell from the warm pool is taken at once, as before, but an empty pool no longer
     * pastes on the server thread. The run's cell is leased to it and built a slice per tick, and the run stays at ALLOCATING_INSTANCE until
     * {@link #pollAllocation} finds it ready (a restart meanwhile parks the run like any other, and the unrecorded lease is free again).
     */
    public static Allocation beginAllocation(MinecraftServer server, UUID runId, long now) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) {
            return new Allocated(new RunTransitionService.Refusal(RunTransitionService.Reason.UNKNOWN_RUN, "no run with id " + runId));
        }
        PersistedRun run = found.get();
        Optional<FloorLayout> layout = com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty()) {
            TowerLog.error("Run {} is on {} floor {}, which declares no layout; parking it", runId, run.towerId(), run.floorIndex());
            return new Allocated(RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now));
        }
        OptionalInt warm = CellWarmPool.take(layout.get().structure(), runId);
        if (warm.isPresent()) return new Allocated(finishAllocation(server, run, warm, layout.get(), now));

        InstanceAllocator.Allocation allocation = InstanceAllocator.allocate(server, runId);
        if (allocation instanceof InstanceAllocator.Denied denied) {
            TowerLog.warn("Run {} could not be given an instance ({})", runId, denied.refusal());
            return new Allocated(RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now));
        }
        int cell = ((InstanceAllocator.Leased) allocation).cell();
        if (!com.cobbletowers.instance.RunBuildJobs.start(server, runId, runId, cell, layout.get())) {
            InstanceAllocator.release(server, runId, cell);
            return new Allocated(RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now));
        }
        return new Building();
    }

    /** The outcome of a {@link #beginAllocation} that was {@link Building}, once there is one; empty while the cell is still being built. */
    public static Optional<RunTransitionService.Outcome> pollAllocation(MinecraftServer server, UUID runId, long now) {
        Optional<com.cobbletowers.instance.RunBuildJobs.Status> status = com.cobbletowers.instance.RunBuildJobs.poll(runId);
        if (status.isEmpty()) {
            // The job is gone (the server stopped under it): nothing to finish.
            return Optional.of(new RunTransitionService.Refusal(RunTransitionService.Reason.UNKNOWN_RUN, "no cell is being built for run " + runId));
        }
        var job = status.get();
        if (job.state() == com.cobbletowers.instance.RunBuildJobs.State.BUILDING) return Optional.empty();
        com.cobbletowers.instance.RunBuildJobs.forget(runId);
        Optional<PersistedRun> run = TowerRuns.get(runId);
        Optional<FloorLayout> layout = run.flatMap(found -> com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), found,
                found.floorIndex()));
        if (job.state() == com.cobbletowers.instance.RunBuildJobs.State.FAILED || run.isEmpty() || layout.isEmpty()) {
            // A floor that builds wrong is broken content, not a bad cell: given back (cleared in slices), as the synchronous start did.
            TowerLog.error("Cell {} could not be made playable for run {}: {}", job.cell(), runId, String.join("; ", job.problems()));
            com.cobbletowers.instance.RunBuildJobs.retire(job.cell(), runId);
            return Optional.of(RunTransitionService.apply(server, runId, RunEvent.ALLOCATION_FAILED, now));
        }
        return Optional.of(finishAllocation(server, run.get(), OptionalInt.of(job.cell()), layout.get(), now));
    }

    /** A run has its built cell: the lease goes on the record before the INSTANCE_ALLOCATED checkpoint. */
    private static RunTransitionService.Outcome finishAllocation(MinecraftServer server, PersistedRun run, OptionalInt cell, FloorLayout layout,
                                                                 long now) {
        UUID runId = run.runId();
        TowerRuns.save(server, run.withCell(cell, now), false);
        RunTransitionService.Outcome outcome =
                RunTransitionService.apply(server, runId, RunEvent.INSTANCE_ALLOCATED, now);
        if (outcome instanceof RunTransitionService.Refusal refusal) {
            // The move was refused after the cell was built, so give it straight back rather than
            // leaving a lease attached to a run that never advanced.
            InstanceAllocator.release(server, runId, cell.getAsInt());
            TowerRuns.save(server, run.withCell(OptionalInt.empty(), now), false);
            TowerLog.error("Run {} took cell {} and then could not advance ({}); the cell was returned",
                    runId, cell.getAsInt(), refusal.reason());
            return outcome;
        }

        // Only now, once a run has actually taken one, is there evidence the pool should hold more.
        // The pool is told what to keep ready and builds it from its own tick, not here: a cell built in this tick
        // would stall the run start again.
        CellWarmPool.want(layout);
        return outcome;
    }

    /** A cell with this floor already built in it: from the pool if one is waiting, else built now. */
    private static OptionalInt obtainCell(MinecraftServer server, UUID runId, FloorLayout layout) {
        OptionalInt warm = CellWarmPool.take(layout.structure(), runId);
        if (warm.isPresent()) return warm;

        InstanceAllocator.Allocation allocation = InstanceAllocator.allocate(server, runId);
        if (allocation instanceof InstanceAllocator.Denied denied) {
            TowerLog.warn("Run {} could not be given an instance ({})", runId, denied.refusal());
            return OptionalInt.empty();
        }

        int cell = ((InstanceAllocator.Leased) allocation).cell();
        Optional<CellPreparer.Prepared> prepared = CellPreparer.prepare(server, cell, layout);
        if (prepared.isEmpty() || !prepared.get().isPlayable()) {
            // A floor that builds wrong is broken content, not a bad cell, so the cell goes back rather than into
            // quarantine.
            TowerLog.error("Cell {} could not be made playable for {}: {}", cell, layout.structure(),
                    prepared.map(CellPreparer.Prepared::summary).orElse("the structure did not place"));
            InstanceAllocator.release(server, runId, cell);
            return OptionalInt.empty();
        }
        return OptionalInt.of(cell);
    }
}
