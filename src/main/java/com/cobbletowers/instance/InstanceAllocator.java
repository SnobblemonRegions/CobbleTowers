package com.cobbletowers.instance;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.diagnostics.TowerMetrics;
import com.cobbletowers.persistence.CellQuarantine;
import com.cobbletowers.persistence.CellStateStore;
import com.cobbletowers.persistence.PersistedRun;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Who holds which cell, indexed both ways (TDS #34); the next free cell is the first clear bit of a {@link BitSet}.
 * The run is the authority ({@link PersistedRun#cell()}) and this index is rebuilt from the runs at start. Quarantine
 * lives in {@link CellStateStore} so it outlives the run.
 */
public final class InstanceAllocator {

    /** Cells currently leased. Rebuilt from the runs, never read from a file of its own. */
    private static final BitSet LEASED = new BitSet(CellGrid.MAX_CELLS);
    private static final Map<Integer, UUID> RUN_BY_CELL = new HashMap<>();
    private static final Map<UUID, Integer> CELL_BY_RUN = new HashMap<>();

    static {
        ServerState.onStop(InstanceAllocator::onServerStopped);
    }

    private InstanceAllocator() {}

    /** Why a cell could not be handed out. */
    public enum Refusal {
        /** Every cell is either leased or quarantined. */
        NO_CELL_FREE,
        /** The datapack dimension is missing, so there is nowhere to put anything. */
        NO_DIMENSION,
        /** The run already holds one. Allocating twice would strand the first. */
        ALREADY_LEASED
    }

    public sealed interface Allocation permits Leased, Denied {}

    public record Leased(int cell) implements Allocation {}

    public record Denied(Refusal refusal) implements Allocation {}

    /** What happened when a cell was given back. */
    public sealed interface Release permits Released, Quarantined {}

    public record Released(int cell) implements Release {}

    public record Quarantined(int cell, String reason) implements Release {}

    /**
     * Rebuilds the index from the loaded runs. A cell claimed twice (a hand-edited save) is quarantined and the
     * second claimant left without one.
     */
    public static int rebuild(MinecraftServer server, Collection<PersistedRun> runs) {
        LEASED.clear();
        RUN_BY_CELL.clear();
        CELL_BY_RUN.clear();
        CellStateStore cells = CellStateStore.get(server);

        for (PersistedRun run : runs) {
            OptionalInt held = run.cell();
            if (held.isEmpty()) continue;
            int cell = held.getAsInt();
            UUID existing = RUN_BY_CELL.get(cell);
            if (existing != null) {
                TowerLog.error("Runs {} and {} both claim cell {}; quarantining it and leaving {} without one",
                        existing, run.runId(), cell, run.runId());
                cells.quarantine(server, new CellQuarantine(cell,
                        "two runs claimed it: " + existing + " and " + run.runId(), System.currentTimeMillis()));
                continue;
            }
            LEASED.set(cell);
            RUN_BY_CELL.put(cell, run.runId());
            CELL_BY_RUN.put(run.runId(), cell);
        }
        return CELL_BY_RUN.size();
    }

    /**
     * The lowest cell that is neither leased nor quarantined. Separate from {@link #allocate} so it is testable
     * without a server.
     */
    static OptionalInt nextFree(java.util.function.IntPredicate quarantined) {
        for (int cell = LEASED.nextClearBit(0); cell < CellGrid.MAX_CELLS; cell = LEASED.nextClearBit(cell + 1)) {
            if (!quarantined.test(cell)) return OptionalInt.of(cell);
        }
        return OptionalInt.empty();
    }

    /** The lowest cell that is neither leased nor quarantined, handed to {@code runId}. */
    public static Allocation allocate(MinecraftServer server, UUID runId) {
        if (!TowerDimension.isLoaded(server)) return new Denied(Refusal.NO_DIMENSION);
        if (CELL_BY_RUN.containsKey(runId)) return new Denied(Refusal.ALREADY_LEASED);

        CellStateStore cells = CellStateStore.get(server);
        OptionalInt free = nextFree(cells::isQuarantined);
        if (free.isEmpty()) return new Denied(Refusal.NO_CELL_FREE);

        int cell = free.getAsInt();
        LEASED.set(cell);
        RUN_BY_CELL.put(cell, runId);
        CELL_BY_RUN.put(runId, cell);
        TowerLog.info("Cell {} leased to run {}", cell, runId);
        return new Leased(cell);
    }

    /**
     * Gives a cell back, verifying it first; a cell that does not verify is quarantined. The lease is dropped either
     * way.
     */
    public static Release release(MinecraftServer server, UUID runId, int cell) {
        CellGrid.requireValid(cell);
        long started = System.nanoTime();
        try {
            // Order is the whole of this method. Reset and sweep while the chunks are still held,
            // because both need them loaded; then let the chunks go; then check that they went.
            CellPreparer.reset(server, cell);
            // Items and experience orbs dropped by fallen opponents are expected debris, not a reason to lose
            // the cell; anything else still fails the check below.
            int debris = CellCleanup.sweepDebris(server, cell);
            if (debris > 0) TowerLog.info("Cell {} had {} dropped item(s) or orb(s) swept", cell, debris);
            CellCleanup.Report contents = CellCleanup.verify(server, cell);
            CellTickets.drop(server, cell);
            CellCleanup.Report report = CellCleanup.verifyReleased(cell, contents);

            if (report.isClean()) {
                TowerLog.info("Cell {} released by run {}", cell, runId);
                return new Released(cell);
            }
            CellCleanliness.markDirty(cell);
            CellStateStore.get(server).quarantine(server,
                    new CellQuarantine(cell, report.summary(), System.currentTimeMillis()));
            return new Quarantined(cell, report.summary());
        } catch (RuntimeException ex) {
            // The lease is dropped below either way, so a cell that could not even be checked must not go back in
            // service: another run would be handed a cell the finished run's record still names.
            TowerLog.error("Releasing cell " + cell + " threw; quarantining it", ex);
            CellCleanliness.markDirty(cell);
            String reason = "release threw: " + ex;
            CellStateStore.get(server).quarantine(server, new CellQuarantine(cell, reason, System.currentTimeMillis()));
            return new Quarantined(cell, reason);
        } finally {
            // TDS #60/section 11: the sweep-and-verify path, timed the same way allocation is.
            TowerMetrics.recordCleanup(server, (System.nanoTime() - started) / 1_000_000);
            // Must run whatever the verification did, including throwing: a lease nothing releases is
            // the one leak the allocator cannot recover from, because no later event refers to it.
            LEASED.clear(cell);
            RUN_BY_CELL.remove(cell, runId);
            CELL_BY_RUN.remove(runId, cell);
        }
    }

    /** Moves a lease between holders without the cell ever being free (used by the warm pool). */
    public static void transferLease(int cell, UUID from, UUID to) {
        CellGrid.requireValid(cell);
        RUN_BY_CELL.remove(cell, from);
        CELL_BY_RUN.remove(from, cell);
        LEASED.set(cell);
        RUN_BY_CELL.put(cell, to);
        CELL_BY_RUN.put(to, cell);
    }

    /** Takes a cell out of service by hand. Only an operator returns it; automatic recovery leaves it alone. */
    public static void quarantine(MinecraftServer server, int cell, String reason) {
        CellGrid.requireValid(cell);
        CellStateStore.get(server).quarantine(server,
                new CellQuarantine(cell, reason, System.currentTimeMillis(), true));
    }

    /** Returns a quarantined cell to service. False when it was not quarantined. */
    public static boolean clearQuarantine(MinecraftServer server, int cell) {
        CellGrid.requireValid(cell);
        // Whatever put it in quarantine may have left anything in it: its next use must scan it.
        CellCleanliness.markDirty(cell);
        return CellStateStore.get(server).clear(server, cell);
    }

    public static OptionalInt cellOf(UUID runId) {
        Integer cell = CELL_BY_RUN.get(runId);
        return cell == null ? OptionalInt.empty() : OptionalInt.of(cell);
    }

    public static Optional<UUID> runIn(int cell) {
        return Optional.ofNullable(RUN_BY_CELL.get(cell));
    }

    public static int leasedCount() {
        return CELL_BY_RUN.size();
    }

    /** Memory hygiene at shutdown; the index is rebuilt from the runs on the next start. */
    public static int onServerStopped() {
        int held = CELL_BY_RUN.size();
        CellCleanliness.clearAll();
        HeavyWork.reset();
        LEASED.clear();
        RUN_BY_CELL.clear();
        CELL_BY_RUN.clear();
        return held;
    }

    /** Test seam: the index without a server. */
    static void resetForTests() {
        LEASED.clear();
        RUN_BY_CELL.clear();
        CELL_BY_RUN.clear();
    }

    /** Test seam: lease a specific cell without a server or a store. */
    static void leaseForTests(int cell, UUID runId) {
        LEASED.set(cell);
        RUN_BY_CELL.put(cell, runId);
        CELL_BY_RUN.put(runId, cell);
    }
}
