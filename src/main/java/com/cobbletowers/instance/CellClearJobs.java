package com.cobbletowers.instance;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.diagnostics.TowerMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Clears a finished run's cell a slice per tick (docs/design/cell-allocation-async.md, option B) so the release that
 * follows finds it already empty. The lease stays with the run until the real release, which is unchanged; this only
 * moves the expensive block clearing out of that call. Server thread only.
 */
public final class CellClearJobs {

    private static final long BUDGET_NANOS = 10_000_000L;
    private static final long LOAD_TIMEOUT_MILLIS = 60_000;

    private static final class Job {
        final UUID owner;
        final List<ChunkPos> chunks = new ArrayList<>();
        final long createdAt = System.currentTimeMillis();
        int next;
        int section;                      // the next 16-high section of chunks[next] to clear
        int cleared;
        long workNanos;

        Job(UUID owner) {
            this.owner = owner;
        }
    }

    private static final Map<Integer, Job> JOBS = new LinkedHashMap<>();

    static {
        ServerState.onStop(JOBS::clear);
    }

    private CellClearJobs() {}

    /**
     * Whether the cell can be released now. When it is not known clean, a sliced clear is started (once) and this
     * returns false: ask again after it finishes. A clear that cannot finish is dropped, and then the release clears the
     * cell itself.
     */
    public static boolean ensureClear(MinecraftServer server, int cell, UUID owner) {
        if (!ownedBy(cell, owner)) return false;
        if (CellCleanliness.isClean(cell)) return true;
        Job job = JOBS.get(cell);
        if (job != null && job.owner.equals(owner)) return false;
        JOBS.remove(cell);
        if (TowerDimension.level(server) == null) return true;
        job = new Job(owner);
        ChunkPos middle = new ChunkPos(CellGrid.centerOf(cell));
        int reach = CellTickets.RADIUS_CHUNKS;
        for (int x = middle.x - reach; x <= middle.x + reach; x++) {
            for (int z = middle.z - reach; z <= middle.z + reach; z++) job.chunks.add(new ChunkPos(x, z));
        }
        // The chunks must stay loaded while it works; the real release drops this ticket.
        CellTickets.hold(server, cell);
        JOBS.put(cell, job);
        return false;
    }

    /** Whether releasing this cell is now cheap: its blocks are already cleared. */
    public static boolean isClean(int cell) {
        return CellCleanliness.isClean(cell);
    }

    public static boolean isClearing(int cell) {
        return JOBS.containsKey(cell);
    }

    /** Every tick: gives the oldest job a slice. */
    public static void advance(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        Map.Entry<Integer, Job> entry = JOBS.entrySet().iterator().next();
        int cell = entry.getKey();
        Job job = entry.getValue();
        if (!ownedBy(cell, job.owner)) {
            // A transferred/released lease invalidates the old job; its ticket belongs to the new tenant.
            JOBS.remove(cell);
            return;
        }
        ServerLevel level = TowerDimension.level(server);
        if (level == null) {
            JOBS.remove(cell);
            return;
        }
        long started = System.nanoTime();
        long deadline = started + BUDGET_NANOS;
        boolean waiting = false;
        try {
            do {
                if (job.next >= job.chunks.size()) break;
                ChunkPos pos = job.chunks.get(job.next);
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) {
                    waiting = true;
                    break;
                }
                job.cleared += CellPreparer.resetSection(level, chunk, job.section);   // a section a step, as in SlicedBuild
                if (++job.section >= chunk.getSections().length) {
                    job.section = 0;
                    job.next++;
                }
            } while (System.nanoTime() < deadline);
        } catch (RuntimeException ex) {
            // Always the first job that runs: one that throws would repeat every tick and hold up every other job and
            // the warm pool. The cell stays dirty, so the release clears it in full.
            TowerLog.error("Clearing cell " + cell + " in slices failed; the release will clear it", ex);
            JOBS.remove(cell);
            return;
        }
        job.workNanos += System.nanoTime() - started;

        if (job.next >= job.chunks.size()) {
            JOBS.remove(cell);
            CellCleanliness.markClean(cell);
            CellEvents.wiped(cell);
            TowerMetrics.recordCleanup(server, job.workNanos / 1_000_000);
            if (job.cleared > 0) TowerLog.info("Cell {} cleared in slices, {} block(s)", cell, job.cleared);
        } else if (waiting && System.currentTimeMillis() - job.createdAt > LOAD_TIMEOUT_MILLIS) {
            // Give up on the slow way; the release will clear it and wait for the chunks.
            TowerLog.warn("Cell {} chunks did not load for a sliced clear; the release will clear it", cell);
            JOBS.remove(cell);
            CellCleanliness.markClean(cell);
        }
    }

    /** No destructive work may outlive the lease which authorized it. */
    static boolean ownedBy(int cell, UUID owner) {
        return owner != null && InstanceAllocator.runIn(cell).filter(owner::equals).isPresent();
    }

    /** Test seam. */
    static void queueForTests(int cell, UUID owner) {
        JOBS.put(cell, new Job(owner));
    }

    /** Test seam. */
    static void resetForTests() {
        JOBS.clear();
    }
}
