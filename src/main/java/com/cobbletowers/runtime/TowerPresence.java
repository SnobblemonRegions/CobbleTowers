package com.cobbletowers.runtime;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.participant.ConnectionState;
import com.cobbletowers.api.tower.participant.ParticipantState;
import com.cobbletowers.battle.cobblemon.CobblemonBattleAdapter;
import com.cobbletowers.diagnostics.TowerMetrics;
import com.cobbletowers.encounter.TowerEncounters;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.PersistedRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is actually here: disconnects, reconnects and stalled players (TDS #36, #37, #59). The stall check runs on a
 * slow timer.
 */
public final class TowerPresence {

    /**
     * How long one battle may produce nothing before its player is dropped. A cap on a stuck battle, not a turn
     * timer.
     */
    public static final long PLAYER_STALL_MILLIS = 10L * 60 * 1000;

    /** How long a whole floor may produce nothing before the run is parked as a technical fault (not a loss). */
    public static final long FLOOR_STALL_MILLIS = 30L * 60 * 1000;

    /** How often the watchdog looks. Nothing here is urgent; a stalled floor stays stalled. */
    static final long SWEEP_INTERVAL_MILLIS = 30L * 1000;

    /** When each disconnected player dropped. In memory only: a restart parks every interrupted run. */
    private static final Map<UUID, Long> DISCONNECTED_AT = new LinkedHashMap<>();

    private static long lastSweep;

    private TowerPresence() {}

    public static void install() {
        // Fabric fires this on the Netty thread that saw the socket close (several at once when a party drops
        // together), and everything onDisconnect touches is single-threaded state, so hop to the server thread.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            ServerThread.run(server, () -> guarded("handle a disconnect", () -> onDisconnect(server, player)));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                guarded("handle a join", () -> onJoin(server, handler.getPlayer())));
        // Dying in the tower leaves the run: the player respawns outside it, so a live run would keep their rental
        // team (and their own Pokemon in the boxes). Leaving lets the journal sweep put everything back.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            if (!player.level().dimension().equals(com.cobbletowers.instance.TowerDimension.LEVEL)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            guarded("handle a death", () -> {
                if (leave(server, player, System.currentTimeMillis())) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "You died, so you left the run. Your party will be put back."));
                }
            });
        });
        // Per tick, this compares two longs and returns. The work itself happens on the interval
        // above; TDS section 11 forbids per-tick scans, not per-tick clock reads.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            if (now - lastSweep < SWEEP_INTERVAL_MILLIS) return;
            lastSweep = now;
            long started = System.nanoTime();
            guarded("sweep the tower floors", () -> sweep(server, now));
            TowerMetrics.recordTick(server, "presence sweep", (System.nanoTime() - started) / 1_000_000);
        });
    }

    /** A player dropped out. Only the connection axis changes; their battle ends. */
    static void onDisconnect(MinecraftServer server, ServerPlayer player) {
        if (player == null) return;
        UUID playerId = player.getUUID();
        // This now runs after the event fired, possibly a tick later. If the same person has already logged back in
        // on a
        // new connection, this is the OLD connection's disconnect, and marking them disconnected would be wrong.
        ServerPlayer current = server.getPlayerList().getPlayer(playerId);
        if (current != null && current != player) return;
        Optional<PersistedRun> run = ParticipantService.runOf(playerId);
        if (run.isEmpty() || !run.get().state().isLive()) return;

        long now = System.currentTimeMillis();
        DISCONNECTED_AT.put(playerId, now);
        ParticipantService.update(server, run.get().runId(), playerId, ParticipantState::disconnected, now);
        TowerEncounters.dropPlayer(server, run.get().runId(), playerId, "disconnected", now);
        TowerLog.info("Player {} left run {} mid-floor; their place is held for {} minute(s)",
                player.getGameProfile().getName(), run.get().runId(),
                ParticipantService.RECONNECT_WINDOW_MILLIS / 60_000);
    }

    /**
     * A player came back. Inside the window they return as a spectator until the next intermission (TDS #37); past it
     * they are out of the floor but not marked as having left (TDS #39).
     */
    static void onJoin(MinecraftServer server, ServerPlayer player) {
        if (player == null) return;
        UUID playerId = player.getUUID();
        Long since = DISCONNECTED_AT.remove(playerId);
        closeParkedRuns(server, player);
        Optional<PersistedRun> found = ParticipantService.runOf(playerId);
        if (found.isEmpty() || !found.get().state().isLive()) return;
        PersistedRun run = found.get();

        long now = System.currentTimeMillis();
        boolean late = since != null && now - since > ParticipantService.RECONNECT_WINDOW_MILLIS;
        // Spectating only if a floor is actually in progress.
        boolean floorInProgress = TowerEncounters.of(run.runId()).isPresent();
        // A boss fight CobbleRaids is holding their slot in: they are still a participant, so resume it, not watch it.
        boolean resumes = TowerEncounters.of(run.runId())
                .filter(round -> round.phase() == TowerEncounters.Phase.BOSS
                        && round.byPlayer().get(playerId) != TowerEncounters.Status.OUT)
                .isPresent() && CobblemonBattleAdapter.inLiveBattle(player)
                && !com.cobbletowers.battle.cobbleraids.TowerBossAdapter.hasWithdrawnParticipant(player);
        boolean spectate = floorInProgress && !resumes;
        ParticipantService.update(server, run.runId(), playerId,
                state -> spectate ? state.reconnected().spectating() : state.reconnected(), now);
        if (resumes) {
            TowerLog.info("Player {} rejoined run {} and resumes the boss fight",
                    player.getGameProfile().getName(), run.runId());
            return;
        }
        if (!floorInProgress) {
            com.cobbletowers.spectator.SpectatorPresentation.stopSpectating(player);
            TowerLog.info("Player {} rejoined run {}{}, which is between floors",
                    player.getGameProfile().getName(), run.runId(), late ? " after their window closed" : "");
            return;
        }
        TowerRuns.get(run.runId()).ifPresent(current ->
                TowerEncounters.sendToSpectatorAnchor(server, current, player));
        TowerLog.info("Player {} rejoined run {}{} as a spectator until the next intermission",
                player.getGameProfile().getName(), run.runId(), late ? " after their window closed" : "");
    }

    /** A run parked by a restart cannot be resumed (its floor is gone), so it ends for whoever comes back. */
    private static void closeParkedRuns(MinecraftServer server, ServerPlayer player) {
        long now = System.currentTimeMillis();
        for (PersistedRun run : java.util.List.copyOf(TowerRuns.all())) {
            if (run.state() != com.cobbletowers.api.tower.RunState.RECOVERY_REQUIRED) continue;
            if (run.participants().stream().noneMatch(p -> p.playerId().equals(player.getUUID()))) continue;
            closeParked(server, run, now);
        }
    }

    /**
     * A run parked during play (a boss that could not start, a stalled floor) holds its players out of the lobby and
     * keeps their party until it is closed, and nothing resumes it. Closed here as soon as one of them is online,
     * rather than waiting for them to relog; the journal sweep then gives their Pokemon back.
     */
    private static void closeParkedRunsOfOnlinePlayers(MinecraftServer server, long now) {
        for (PersistedRun run : java.util.List.copyOf(TowerRuns.all())) {
            if (run.state() != com.cobbletowers.api.tower.RunState.RECOVERY_REQUIRED) continue;
            boolean anyoneOnline = run.participants().stream()
                    .anyMatch(p -> server.getPlayerList().getPlayer(p.playerId()) != null);
            if (anyoneOnline) closeParked(server, run, now);
        }
    }

    /** Ends a parked run (its cleared floors are banked by the transition) and tells whoever of its players is online. */
    private static void closeParked(MinecraftServer server, PersistedRun run, long now) {
        String cause = com.cobbletowers.report.TowerTelemetry.parkedCloseCause(run.runId());
        com.cobbletowers.report.TowerTelemetry.endCause(run.runId(), cause);
        RunTransitionService.Outcome outcome =
                RunTransitionService.apply(server, run.runId(), com.cobbletowers.api.tower.RunEvent.RECOVERY_ABANDONED, now);
        if (!(outcome instanceof RunTransitionService.Move)) return;
        TowerLog.info("Ended parked run {} ({})", run.runId(), cause);
        String why = "restart".equals(cause) ? "was interrupted by a server restart" : "broke on the server's side";
        for (var participant : run.participants()) {
            ServerPlayer online = server.getPlayerList().getPlayer(participant.playerId());
            if (online != null) {
                online.sendSystemMessage(net.minecraft.network.chat.Component.literal("Your last run " + why
                        + " and has ended. Rewards from the floors you cleared are kept, and your party will be put back."));
            }
        }
    }

    /** The player chose to leave. Terminal for them, and the end of the run if they were the last. */
    public static boolean leave(MinecraftServer server, ServerPlayer player, long now) {
        Optional<PersistedRun> found = ParticipantService.runOf(player.getUUID());
        if (found.isEmpty() || !found.get().state().isLive()) return false;
        UUID runId = found.get().runId();

        DISCONNECTED_AT.remove(player.getUUID());
        // Persist the irreversible membership change before callbacks can resolve the floor or throw.
        ParticipantService.update(server, runId, player.getUUID(), ParticipantState::left, now);
        // Settle the last membership before boss-withdrawal callbacks can report a party wipe.
        TowerRuns.get(runId).filter(run -> !anybodyCouldReturn(run, now)).ifPresent(run -> {
            TowerLog.info("Run {} has nobody left in it; ending it", runId);
            com.cobbletowers.report.TowerTelemetry.endCause(runId, "left");
            RunTransitionService.apply(server, runId, RunEvent.ABANDON_REQUESTED, now);
        });
        guarded("drop the departing player", () ->
                TowerEncounters.dropPlayer(server, runId, player.getUUID(), "left the run", now));
        // Boss battles belong to CobbleRaids and are not in the floor adapter's battle index.
        guarded("end the departing player's battle", () -> CobblemonBattleAdapter.endBattleOf(player));
        TowerLog.info("Player {} left run {}", player.getGameProfile().getName(), runId);
        return true;
    }

    /**
     * What the watchdog decided about one floor.
     * @param drop players who stopped answering
     * @param park the floor stalled, so the run is a technical fault
     */
    public record Verdict(List<UUID> drop, boolean park) {

        public Verdict {
            drop = List.copyOf(drop);
        }

        public boolean isQuiet() {
            return drop.isEmpty() && !park;
        }
    }

    /** The watchdog's judgement, pure: readings in, verdict out. A stalled floor outranks its stalled players. */
    public static Verdict judge(Map<UUID, Long> lastActivityByPlayer, long roundStartedAt, long now) {
        long newest = roundStartedAt;
        for (long last : lastActivityByPlayer.values()) newest = Math.max(newest, last);
        if (now - newest > FLOOR_STALL_MILLIS) return new Verdict(List.of(), true);

        List<UUID> stalled = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : lastActivityByPlayer.entrySet()) {
            if (now - entry.getValue() > PLAYER_STALL_MILLIS) stalled.add(entry.getKey());
        }
        return new Verdict(stalled, false);
    }

    /** When each run's draft was first seen with nobody able to vote. In memory, so a restart restarts the window. */
    private static final Map<UUID, Long> DRAFT_EMPTY_SINCE = new ConcurrentHashMap<>();

    static {
        ServerState.onStop(TowerPresence::onServerStopped);
    }

    /**
     * Expired grace windows, then stalled floors. Public so {@code runs watchdog} can run it with the clock wound
     * forward.
     */
    public static void sweep(MinecraftServer server, long now) {
        // Each step on its own: a fault in one must not leave the floors below unwatched.
        guarded("expire the reconnect windows", () -> expireGraceWindows(server, now));
        guarded("settle the abandoned drafts", () -> settleAbandonedDrafts(server, now));
        guarded("close the parked runs", () -> closeParkedRunsOfOnlinePlayers(server, now));

        for (TowerEncounters.Round round : TowerEncounters.activeRounds()) {
            guarded("judge floor " + round.floorIndex() + " of run " + round.runId(),
                    () -> judgeRound(server, round, now));
        }
    }

    private static void judgeRound(MinecraftServer server, TowerEncounters.Round round, long now) {
        // The boss is CobbleRaids', and it has its own lifetime cap on a boss that goes nowhere.
        // A second timer on one fight would only give the two something to disagree about.
        if (round.phase() != TowerEncounters.Phase.PREREQUISITE) return;

        Verdict verdict = judge(CobblemonBattleAdapter.lastActivityByPlayer(round.runId()),
                round.startedAt(), now);
        if (verdict.isQuiet()) return;

        if (verdict.park()) {
            TowerLog.error("Floor {} of run {} has produced nothing for {} minute(s); parking the run",
                    round.floorIndex(), round.runId(), FLOOR_STALL_MILLIS / 60_000);
            TowerEncounters.abandon(server, round.runId());
            RunTransitionService.apply(server, round.runId(), RunEvent.TECHNICAL_FAILURE, now);
            return;
        }
        for (UUID playerId : verdict.drop()) {
            TowerLog.warn("Player {} has not moved floor {} of run {} for {} minute(s); dropping them",
                    playerId, round.floorIndex(), round.runId(), PLAYER_STALL_MILLIS / 60_000);
            TowerEncounters.dropPlayer(server, round.runId(), playerId, "stopped answering", now);
        }
    }

    /**
     * Settles drafts nobody is left to answer, from the seed. A party that has gone would otherwise hold its cell
     * forever.
     */
    private static void settleAbandonedDrafts(MinecraftServer server, long now) {
        for (PersistedRun run : TowerRuns.all()) {
            if (run.isRetired() || !run.modifiers().hasOpenDraft()) {
                DRAFT_EMPTY_SINCE.remove(run.runId());
                continue;
            }
            boolean anybodyHere = false;
            for (UUID voter : DraftService.voters(run)) {
                if (server.getPlayerList().getPlayer(voter) != null) {
                    anybodyHere = true;
                    break;
                }
            }
            if (anybodyHere) {
                DRAFT_EMPTY_SINCE.remove(run.runId());
                continue;
            }

            // Emptiness must last the grace window: a restart sweeps before anyone has reconnected. The stamp is real
            // time; {@code now} may be wound forward by {@code runs watchdog}.
            long since = DRAFT_EMPTY_SINCE.computeIfAbsent(run.runId(), ignored -> System.currentTimeMillis());
            if (now - since <= ParticipantService.RECONNECT_WINDOW_MILLIS) continue;

            DRAFT_EMPTY_SINCE.remove(run.runId());
            TowerLog.warn("Run {} has had a draft open at floor {} with nobody to vote for {} minute(s);"
                            + " settling it", run.runId(), run.floorIndex(),
                    ParticipantService.RECONNECT_WINDOW_MILLIS / 60_000);
            guarded("settle the draft of run " + run.runId(), () -> DraftService.settle(server, run.runId(), now));
        }
    }

    /**
     * Disconnected players whose window is up: KNOCKED_OUT rather than VOLUNTARILY_LEFT, so an intermission can take
     * them back. The run ends if nobody is online.
     */
    private static void expireGraceWindows(MinecraftServer server, long now) {
        for (Map.Entry<UUID, Long> entry : Map.copyOf(DISCONNECTED_AT).entrySet()) {
            if (now - entry.getValue() <= ParticipantService.RECONNECT_WINDOW_MILLIS) continue;
            UUID playerId = entry.getKey();
            DISCONNECTED_AT.remove(playerId);

            Optional<PersistedRun> found = ParticipantService.runOf(playerId);
            if (found.isEmpty() || !found.get().state().isLive()) continue;
            UUID runId = found.get().runId();

            ParticipantService.update(server, runId, playerId, ParticipantState::knockedOut, now);
            TowerLog.info("Player {} did not come back within the window; they are out of the floor of run {}",
                    playerId, runId);

            TowerRuns.get(runId).ifPresent(run -> {
                if (anybodyCouldReturn(run, now)) return;
                TowerLog.info("Run {} has nobody online and nobody expected back; ending it", runId);
                com.cobbletowers.report.TowerTelemetry.endCause(runId, "disconnect");
                RunTransitionService.apply(server, runId, RunEvent.ABANDON_REQUESTED, now);
            });
        }
    }

    /** True while somebody is still playing this run, or still has time to come back to it. */
    private static boolean anybodyCouldReturn(PersistedRun run, long now) {
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().isInRun()) continue;
            if (participant.state().connection() == ConnectionState.ONLINE) return true;
            Long since = DISCONNECTED_AT.get(participant.playerId());
            if (since != null && now - since <= ParticipantService.RECONNECT_WINDOW_MILLIS) return true;
        }
        return false;
    }

    /** Contained: exceptions here would unwind into the tick. */
    private static void guarded(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            TowerLog.error("CobbleTowers could not " + what, ex);
        }
    }

    /** Per-server state on a class that outlives a server: cleared, like every other index. */
    public static void onServerStopped() {
        DISCONNECTED_AT.clear();
        DRAFT_EMPTY_SINCE.clear();
        lastSweep = 0;
    }

    /** Who is inside a grace window right now, for the debug command and the live test. */
    public static Optional<Long> disconnectedAt(UUID playerId) {
        return Optional.ofNullable(DISCONNECTED_AT.get(playerId));
    }
}
