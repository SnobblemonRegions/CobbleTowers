package com.cobbletowers.instance;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.diagnostics.TowerMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Floors a party is waiting on, built a slice per tick (docs/design/cell-allocation-async.md, option B): a run start when the warm pool has no
 * ready cell, and a floor change into another building. Before this both pasted on the server thread and held every player's tick for the whole
 * paste. Each job is keyed by who waits on it and built into a cell its {@code tenant} already leases; the caller polls and takes the cell when it
 * is ready. Jobs live in memory only: after a restart every live run is parked anyway, and a lease that was never written to a run is simply free
 * again (and dirty, so its next build resets it). Server thread only.
 *
 * <p>Also retires cells: a cell a run has moved out of is cleared in slices ({@link CellClearJobs}), swept of anything left, and released.
 */
public final class RunBuildJobs {

    public enum State { BUILDING, READY, FAILED }

    /** Where a job stands: the cell and its tenant, and why it failed. */
    public record Status(State state, int cell, UUID tenant, List<String> problems) {}

    private static final class Job {
        final UUID tenant;
        final int cell;
        final SlicedBuild build;   // null for a cell that came ready from the warm pool
        State state;
        List<String> problems = List.of();

        Job(UUID tenant, int cell, SlicedBuild build, State state) {
            this.tenant = tenant;
            this.cell = cell;
            this.build = build;
            this.state = state;
        }
    }

    /** Per-tick time for the job in progress; one paste step can exceed it (about 60 ms on the rig), which is the whole of one tick's hold. */
    private static final long SLICE_BUDGET_NANOS = 6_000_000L;

    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    /** Cells being retired, by the tenant that holds them until the release. */
    private static final Map<Integer, UUID> RETIRING = new LinkedHashMap<>();

    static {
        ServerState.onStop(RunBuildJobs::clear);
    }

    private RunBuildJobs() {}

    /**
     * Starts building {@code layout} into {@code cell}, which {@code tenant} must already lease. False when the structure is not loaded (nothing
     * started; the caller still holds the lease).
     */
    public static boolean start(MinecraftServer server, UUID key, UUID tenant, int cell, FloorLayout layout) {
        Optional<SlicedBuild> build = SlicedBuild.start(server, cell, layout);
        if (build.isEmpty()) return false;
        JOBS.put(key, new Job(tenant, cell, build.get(), State.BUILDING));
        TowerLog.info("Cell {} is being built for {} a slice at a time", cell, layout.structure());
        return true;
    }

    /** Records a cell that is already built (taken from the warm pool) as ready for {@code key}. */
    public static void adoptReady(UUID key, UUID tenant, int cell) {
        JOBS.put(key, new Job(tenant, cell, null, State.READY));
    }

    /** Where the job for {@code key} stands, if there is one. */
    public static Optional<Status> poll(UUID key) {
        Job job = JOBS.get(key);
        return job == null ? Optional.empty() : Optional.of(new Status(job.state, job.cell, job.tenant, job.problems));
    }

    /** Drops the job once its caller has taken the cell or given it back. */
    public static void forget(UUID key) {
        JOBS.remove(key);
    }

    /**
     * Drops the job for {@code key} (its run ended) and retires its cell, built or not. The run's own first-floor job is skipped once the run has
     * taken the cell: then it is the run's, and the run's release gives it back.
     */
    public static void abandon(UUID key) {
        Job job = JOBS.remove(key);
        if (job == null) return;
        if (InstanceAllocator.runIn(job.cell).filter(job.tenant::equals).isPresent()) retire(job.cell, job.tenant);
    }

    /** Whether a party is waiting on a build: the warm pool steps aside until none is. */
    public static boolean busy() {
        for (Job job : JOBS.values()) if (job.state == State.BUILDING) return true;
        return false;
    }

    /**
     * Retires a cell nobody plays in any more: cleared in slices, swept, then released by {@code tenant}, who must lease it. A cell that does not
     * verify is quarantined by the release as usual.
     */
    public static void retire(int cell, UUID tenant) {
        RETIRING.put(cell, tenant);
    }

    /** Every tick: one slice of the oldest waiting build, and one step of the oldest retirement. */
    public static void advance(MinecraftServer server) {
        advanceBuild(server);
        advanceRetirement(server);
    }

    private static void advanceBuild(MinecraftServer server) {
        for (Job job : JOBS.values()) {
            if (job.state != State.BUILDING) continue;
            SlicedBuild.Status status;
            try {
                status = job.build.advance(server, SLICE_BUDGET_NANOS);
            } catch (RuntimeException ex) {
                TowerLog.error("Building cell " + job.cell + " for a waiting party threw", ex);
                status = SlicedBuild.Status.FAILED;
            }
            if (status == SlicedBuild.Status.WORKING) return;
            TowerMetrics.recordAllocation(server, job.build.longestStepMillis());
            if (status == SlicedBuild.Status.DONE && job.build.problems().isEmpty()) {
                job.state = State.READY;
                TowerLog.info("Cell {} built for {} in {} ms of slices (longest {} ms)", job.cell, job.build.layout.structure(),
                        job.build.workMillis(), job.build.longestStepMillis());
            } else {
                job.state = State.FAILED;
                job.problems = status == SlicedBuild.Status.DONE ? job.build.problems() : List.of("the structure did not place");
            }
            return;   // one build a tick, so two waiting parties never double one tick's hold
        }
    }

    private static void advanceRetirement(MinecraftServer server) {
        if (RETIRING.isEmpty()) return;
        Map.Entry<Integer, UUID> next = RETIRING.entrySet().iterator().next();
        int cell = next.getKey();
        UUID tenant = next.getValue();
        if (InstanceAllocator.runIn(cell).filter(tenant::equals).isEmpty()) {
            RETIRING.remove(cell);   // the lease moved on (or a restart dropped it): not ours to release
            return;
        }
        try {
            if (!CellClearJobs.ensureClear(server, cell, tenant)) return;   // still clearing
            // Whatever the floor left standing (the vendor, pedestals, a sent-out Pokemon) goes, as the old in-place rebuild's sweep took it.
            CellCleanup.sweepEntities(server, cell);
            InstanceAllocator.Release release = InstanceAllocator.release(server, tenant, cell);
            if (release instanceof InstanceAllocator.Quarantined quarantined) {
                TowerLog.warn("Cell {} was left unfit for reuse after its party moved on: {}", cell, quarantined.reason());
            }
        } catch (RuntimeException ex) {
            TowerLog.error("Retiring cell " + cell + " threw", ex);
        }
        RETIRING.remove(cell);
    }

    /** The tenant a floor change builds its next cell under: the run itself holds the cell it plays in until the move. */
    public static UUID nextFloorTenant(UUID runId) {
        return new UUID(runId.getMostSignificantBits() ^ 0x5eedce11L, runId.getLeastSignificantBits());
    }

    /** The tenant a cell is retired under once its run has moved out. */
    public static UUID retiringTenant(UUID runId) {
        return new UUID(runId.getMostSignificantBits() ^ 0x01dce11L, runId.getLeastSignificantBits() ^ 1L);
    }

    static int retiringCount() {
        return RETIRING.size();
    }

    /** Server stop, and a test seam. */
    static void clear() {
        JOBS.clear();
        RETIRING.clear();
    }
}
