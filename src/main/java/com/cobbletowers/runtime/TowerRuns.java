package com.cobbletowers.runtime;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.diagnostics.TowerMetrics;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerRunStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * The live index of tower runs by run id and by player. Indexed because TDS section 11 forbids per-tick scans. A run
 * is its {@link PersistedRun}; a mutation replaces the record. Maps are static, so they are cleared on SERVER_STOPPED
 * (an integrated client keeps the JVM across worlds).
 */
public final class TowerRuns {

    private static final Map<UUID, PersistedRun> BY_ID = new LinkedHashMap<>();
    private static final Map<UUID, UUID> RUN_BY_PLAYER = new HashMap<>();

    static {
        ServerState.onStop(TowerRuns::onServerStopped);
    }

    private TowerRuns() {}

    /**
     * Loads every stored run into the index, retiring old ones, and returns how many are indexed. Recovery runs
     * separately so loading and classifying do not half-fail together.
     */
    public static int load(MinecraftServer server, long now) {
        BY_ID.clear();
        RUN_BY_PLAYER.clear();
        TowerRunStore store = TowerRunStore.get(server);
        int retired = store.retireOldRuns(now);
        for (PersistedRun run : store.runs().values()) index(run);
        if (retired > 0) {
            TowerLog.info("Retired {} finished tower run(s) older than the retention window.", retired);
        }
        // Per-run figures kept beside the runs: a run that is gone has no use for them.
        int stale = com.cobbletowers.persistence.TowerRunStatsStore.get(server).retainOnly(BY_ID.keySet());
        if (stale > 0) TowerLog.info("Dropped the stats of {} tower run(s) that no longer exist.", stale);
        return BY_ID.size();
    }

    public static Optional<PersistedRun> get(UUID runId) {
        return Optional.ofNullable(BY_ID.get(runId));
    }

    /** The run this player belongs to, if any. One player is in at most one run. */
    public static Optional<PersistedRun> forPlayer(UUID playerId) {
        UUID runId = RUN_BY_PLAYER.get(playerId);
        return runId == null ? Optional.empty() : get(runId);
    }

    /** Every indexed run, in load order. */
    public static List<PersistedRun> all() {
        return List.copyOf(BY_ID.values());
    }

    /**
     * Writes a run to the index and the store together. The store's memory is updated before the disk write, so a
     * failed flush leaves a consistent pair. A flush failure is reported, not swallowed.
     */
    public static void save(MinecraftServer server, PersistedRun run, boolean forceCheckpoint) {
        PersistedRun previous = BY_ID.get(run.runId());
        index(run);
        // Every relic arrives through here, whatever gave it: show it to the party (P59). After indexing, so the card sees the run as saved.
        com.cobbletowers.modifier.RelicReveal.announce(server, run, com.cobbletowers.modifier.RelicReveal.gained(previous, run));
        // the HUD's strip of held relics follows every change (a gain, a loss, a swap, a player leaving)
        if (com.cobbletowers.modifier.RelicReveal.shelfChanged(previous, run) || participantsChanged(previous, run)) {
            com.cobbletowers.modifier.RelicReveal.shelf(server, run);
        }
        TowerRunStore store = TowerRunStore.get(server);
        if (!forceCheckpoint) {
            store.put(run);
            // TDS section 11's "persistence backlog": how many writes have landed since the last
            // checkpoint cleared it.
            TowerMetrics.recordNonCheckpointedWrite(server);
            return;
        }
        try {
            store.checkpoint(server, run);
            TowerMetrics.recordCheckpoint(server);
        } catch (RuntimeException ex) {
            // checkpoint() stores before it writes, so the run is held in memory either way.
            TowerLog.error("Checkpoint for tower run {} could not be written to disk; it will be saved"
                    + " at the next world save instead", run.runId(), ex);
        }
    }

    /** Whether who is in the run (or whether the run is over) changed between two saves: the relic strip shows or goes for those players. */
    static boolean participantsChanged(PersistedRun before, PersistedRun after) {
        if (before == null || after == null) return false;
        if (before.state().isTerminal() != after.state().isTerminal()) return true;
        java.util.Set<UUID> was = new java.util.HashSet<>(), now = new java.util.HashSet<>();
        for (PersistedParticipant p : before.participants()) if (p.state().isInRun()) was.add(p.playerId());
        for (PersistedParticipant p : after.participants()) if (p.state().isInRun()) now.add(p.playerId());
        return !was.equals(now);
    }

    /** Drops a run from the index and from disk. */
    public static void forget(MinecraftServer server, UUID runId) {
        PersistedRun run = BY_ID.remove(runId);
        if (run != null) {
            for (PersistedParticipant participant : run.participants()) {
                RUN_BY_PLAYER.remove(participant.playerId(), runId);
            }
        }
        TowerRunStore.get(server).remove(runId);
    }

    /** Memory hygiene at shutdown. Returns how many runs were dropped. */
    public static int onServerStopped() {
        int size = BY_ID.size();
        BY_ID.clear();
        RUN_BY_PLAYER.clear();
        return size;
    }

    private static void index(PersistedRun run) {
        BY_ID.put(run.runId(), run);
        for (PersistedParticipant participant : run.participants()) {
            if (run.isRetired() || !participant.state().isInRun()) {
                // A finished run, or someone who left it, must not keep a player from starting
                // another one. Removed by value so a player already in a newer run is left alone.
                RUN_BY_PLAYER.remove(participant.playerId(), run.runId());
            } else {
                RUN_BY_PLAYER.put(participant.playerId(), run.runId());
            }
        }
    }

    /** Test seam: the index without a server. */
    static void resetForTests(List<PersistedRun> runs) {
        BY_ID.clear();
        RUN_BY_PLAYER.clear();
        for (PersistedRun run : new ArrayList<>(runs)) index(run);
    }
}
