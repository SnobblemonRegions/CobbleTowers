package com.cobbletowers.instance;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Cells already built and waiting so a party does not wait for a paste (#26). Kept per structure, since the paste is
 * the cost. Topped up on allocation and release, at most once per {@link #COOLDOWN_MILLIS}; no per-tick sweep (TDS
 * section 11).
 */
public final class CellWarmPool {

    /** How many ready cells to keep per structure. Two covers a party finishing as another starts. */
    public static final int TARGET_PER_STRUCTURE = 2;

    /** The shortest gap between top-ups, so a burst of runs cannot turn into a burst of pasting. */
    public static final long COOLDOWN_MILLIS = 5_000;

    /** The pool's own tenant id, so a warmed cell is leased rather than merely intended. */
    private static final UUID POOL = UUID.fromString("00000000-c0bb-1e70-0000-000000000001");

    private static final Map<ResourceLocation, Deque<Integer>> READY = new LinkedHashMap<>();
    private static long lastTopUp;

    private CellWarmPool() {}

    /**
     * A cell already built for this layout, if one is waiting. The lease moves to the caller, who must release it.
     */
    public static OptionalInt take(ResourceLocation structure, UUID runId) {
        Deque<Integer> ready = READY.get(structure);
        if (ready == null || ready.isEmpty()) return OptionalInt.empty();
        int cell = ready.removeFirst();
        InstanceAllocator.transferLease(cell, POOL, runId);
        TowerLog.info("Cell {} handed to run {} from the warm pool", cell, runId);
        return OptionalInt.of(cell);
    }

    /**
     * Builds cells for this layout up to the target if the cooldown has passed; returns how many. A failure stops the
     * round.
     */
    public static int topUp(MinecraftServer server, FloorLayout layout, long now) {
        return topUp(server, layout, now, Integer.MAX_VALUE);
    }

    /** As above, building at most {@code maxBuilds} cells in this call (the tick builds one at a time). */
    public static int topUp(MinecraftServer server, FloorLayout layout, long now, int maxBuilds) {
        if (now - lastTopUp < COOLDOWN_MILLIS) return 0;
        lastTopUp = now;

        Deque<Integer> ready = READY.computeIfAbsent(layout.structure(), key -> new ArrayDeque<>());
        int built = 0;
        while (ready.size() < TARGET_PER_STRUCTURE && built < maxBuilds) {
            InstanceAllocator.Allocation allocation = InstanceAllocator.allocate(server, poolTenant(ready.size()));
            if (!(allocation instanceof InstanceAllocator.Leased leased)) break;

            int cell = leased.cell();
            var prepared = CellPreparer.prepare(server, cell, layout);
            if (prepared.isEmpty() || !prepared.get().isPlayable()) {
                // Give it straight back; release decides whether it is merely empty or actually bad.
                InstanceAllocator.release(server, poolTenant(ready.size()), cell);
                break;
            }
            InstanceAllocator.transferLease(cell, poolTenant(ready.size()), POOL);
            ready.addLast(cell);
            built++;
        }
        if (built > 0) {
            TowerLog.info("Warm pool built {} cell(s) for {}; {} ready", built, layout.structure(), ready.size());
        }
        return built;
    }

    /**
     * Layouts a run has used, so the pool knows what to keep ready. Building happens in {@link #tick}, not on the
     * tick that started a run.
     */
    private static final Map<ResourceLocation, FloorLayout> WANTED = new LinkedHashMap<>();

    static {
        ServerState.onStop(CellWarmPool::onServerStopped);
    }

    public static void want(FloorLayout layout) {
        WANTED.put(layout.structure(), layout);
    }

    /**
     * The first-floor layouts of every tower a run can start in now, one per building. Pure. The pool learned what to keep ready only from a run
     * that had already started, so after every restart the first run of each tower built its floor on the server thread, holding the tick for the
     * whole paste (0.3 to 1 s, docs/design/cell-allocation-async.md). Wanting these at start lets the sliced build have them ready first.
     */
    static java.util.List<FloorLayout> startLayouts(com.cobbletowers.definition.TowerContent content, long nowMillis) {
        Map<ResourceLocation, FloorLayout> byStructure = new LinkedHashMap<>();
        for (var tower : content.openCatalogTowers(nowMillis)) {
            content.floorAt(tower.id(), 1).flatMap(com.cobbletowers.definition.FloorDefinition::layout)
                    .ifPresent(layout -> byStructure.putIfAbsent(layout.structure(), layout));
        }
        return java.util.List.copyOf(byStructure.values());
    }

    /** Asks the pool to keep every open tower's first floor ready; the once-a-second tick builds them, a slice at a time. */
    public static int prewarm(com.cobbletowers.definition.TowerContent content, long nowMillis) {
        var layouts = startLayouts(content, nowMillis);
        layouts.forEach(CellWarmPool::want);
        if (!layouts.isEmpty()) TowerLog.info("Warm pool will keep {} building(s) ready for run starts", layouts.size());
        return layouts.size();
    }

    /** The build in progress (one at a time) and who holds its lease. */
    private static SlicedBuild BUILD;
    private static UUID BUILD_TENANT;

    /** Per-tick time a warm build may take; the rest of the tick belongs to the game. */
    private static final long SLICE_BUDGET_NANOS = 4_000_000L;

    /**
     * Once a second: starts ONE missing cell if the tower is not busy with other heavy work. The build itself runs a
     * slice per tick ({@link #advance}), so it never holds the server thread for the whole paste. Returns how many
     * were started.
     */
    public static int tick(MinecraftServer server, long now) {
        if (BUILD != null) return 0;
        for (FloorLayout layout : WANTED.values()) {
            Deque<Integer> ready = READY.get(layout.structure());
            if (ready != null && ready.size() >= TARGET_PER_STRUCTURE) continue;
            if (now - lastTopUp < COOLDOWN_MILLIS) return 0;
            if (!HeavyWork.tryAcquire(now)) return 0;
            lastTopUp = now;
            return begin(server, layout) ? 1 : 0;
        }
        return 0;
    }

    private static boolean begin(MinecraftServer server, FloorLayout layout) {
        Deque<Integer> ready = READY.computeIfAbsent(layout.structure(), key -> new ArrayDeque<>());
        UUID tenant = poolTenant(ready.size());
        if (!(InstanceAllocator.allocate(server, tenant) instanceof InstanceAllocator.Leased leased)) return false;
        var build = SlicedBuild.start(server, leased.cell(), layout);
        if (build.isEmpty()) {
            InstanceAllocator.release(server, tenant, leased.cell());
            return false;
        }
        BUILD = build.get();
        BUILD_TENANT = tenant;
        return true;
    }

    /** Every tick: gives the build in progress one slice, and files the cell when it is finished. */
    static void advance(MinecraftServer server) {
        SlicedBuild build = BUILD;
        if (build == null) return;
        SlicedBuild.Status status;
        try {
            status = build.advance(server, SLICE_BUDGET_NANOS);
        } catch (RuntimeException ex) {
            // A build that throws would run again every tick and hold up the pool; it is given back like a failed one.
            TowerLog.error("Building warm cell " + build.cell + " threw", ex);
            status = SlicedBuild.Status.FAILED;
        }
        if (status == SlicedBuild.Status.WORKING) return;
        UUID tenant = BUILD_TENANT;
        BUILD = null;
        BUILD_TENANT = null;
        // The longest step, not the total: the allocation budget is about how long the server was held, and a sliced build's total
        // (spread over many ticks) read as a one-second stall in the diagnostics when no tick was held that long.
        com.cobbletowers.diagnostics.TowerMetrics.recordAllocation(server, build.longestStepMillis());
        if (status == SlicedBuild.Status.DONE && build.problems().isEmpty()) {
            InstanceAllocator.transferLease(build.cell, tenant, POOL);
            Deque<Integer> ready = READY.computeIfAbsent(build.layout.structure(), key -> new ArrayDeque<>());
            ready.addLast(build.cell);
            TowerLog.info("Warm pool built cell {} for {} in {} ms of slices (longest {} ms); {} ready", build.cell,
                    build.layout.structure(), build.workMillis(), build.longestStepMillis(), ready.size());
            return;
        }
        if (status == SlicedBuild.Status.DONE) TowerLog.error("Cell {} was built but is not playable: {}", build.cell, String.join("; ", build.problems()));
        // Give it straight back; release decides whether it is merely empty or actually bad.
        InstanceAllocator.release(server, tenant, build.cell);
    }

    /** Registers the per-tick slice and the once-a-second start. */
    private static int ticks;

    public static void install() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                prewarm(com.cobbletowers.definition.TowerDefinitionRegistry.content(), System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("The warm pool could not plan its start-up builds", ex);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                CellClearJobs.advance(server);
                advance(server);
                if (++ticks % 20 == 0) tick(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("The warm pool tick failed", ex);
            }
        });
    }

    /** A distinct tenant per in-flight warm build; the allocator refuses to lease twice to one holder. */
    private static UUID poolTenant(int index) {
        return new UUID(POOL.getMostSignificantBits(), POOL.getLeastSignificantBits() + index);
    }

    public static int readyCount() {
        return READY.values().stream().mapToInt(Deque::size).sum();
    }

    public static int readyCount(ResourceLocation structure) {
        Deque<Integer> ready = READY.get(structure);
        return ready == null ? 0 : ready.size();
    }

    /** True while this cell is being kept ready, rather than being played in. */
    public static boolean isWarm(int cell) {
        return READY.values().stream().anyMatch(ready -> ready.contains(cell));
    }

    /** Memory hygiene at shutdown; warmed cells are simply cells again on the next start. */
    public static int onServerStopped() {
        int ready = readyCount();
        READY.clear();
        WANTED.clear();
        BUILD = null;
        BUILD_TENANT = null;
        lastTopUp = 0;
        return ready;
    }

    /** Test seam. */
    static void resetForTests() {
        BUILD = null;
        BUILD_TENANT = null;
        READY.clear();
        WANTED.clear();
        lastTopUp = 0;
    }

    static void addForTests(ResourceLocation structure, int cell) {
        READY.computeIfAbsent(structure, key -> new ArrayDeque<>()).addLast(cell);
    }
}
