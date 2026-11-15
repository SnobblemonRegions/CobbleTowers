package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * One cell build done a slice at a time (docs/design/cell-allocation-async.md, option B), for cells nobody is waiting
 * on (the warm pool): the reset and the paste each go chunk by chunk inside a per-tick time budget, and a chunk that is
 * not loaded yet is waited for, never blocked on. Same result as {@link CellPreparer#prepare}. Server thread only.
 */
final class SlicedBuild {

    enum Status { WORKING, DONE, FAILED }

    /** How tall one paste step is. A step pastes its column's own piece (StructurePieces), so a step costs that column, not the building. */
    private static final int BAND_HEIGHT = 4096;

    /** Longest a build may wait on chunks that will not load. */
    private static final long LOAD_TIMEOUT_MILLIS = 60_000;

    final int cell;
    final FloorLayout layout;
    private final StructureTemplate template;
    private final BlockPos origin;
    private final Vec3i size;
    private final List<ChunkPos> cellChunks = new ArrayList<>();
    private final List<BoundingBox> pasteBoxes = new ArrayList<>();
    private final long createdAt = System.currentTimeMillis();
    private boolean needsReset;
    private int resetNext;
    private int resetSection;
    private int pasteNext;
    private boolean markedDirty;
    private long workNanos;
    private long longestStep;
    private int cleared;
    private List<String> problems = List.of();

    private SlicedBuild(int cell, FloorLayout layout, StructureTemplate template, BlockPos origin) {
        this.cell = cell;
        this.layout = layout;
        this.template = template;
        this.origin = origin;
        this.size = template.getSize();
    }

    /** Holds the cell's chunks and plans the work; empty when the structure is not loaded. */
    static Optional<SlicedBuild> start(MinecraftServer server, int cell, FloorLayout layout) {
        Optional<StructureTemplate> found = server.getStructureManager().get(layout.structure());
        if (found.isEmpty()) {
            TowerLog.error("Floor structure {} is not loaded; cell {} cannot be prepared", layout.structure(), cell);
            return Optional.empty();
        }
        StructureTemplate template = found.get();
        Vec3i size = template.getSize();
        SlicedBuild build = new SlicedBuild(cell, layout, template,
                CellTickets.pasteOrigin(cell, size.getX(), size.getZ()));
        CellTickets.hold(server, cell);
        ChunkPos middle = new ChunkPos(CellGrid.centerOf(cell));
        int reach = CellTickets.RADIUS_CHUNKS;
        for (int x = middle.x - reach; x <= middle.x + reach; x++) {
            for (int z = middle.z - reach; z <= middle.z + reach; z++) build.cellChunks.add(new ChunkPos(x, z));
        }
        // Only the chunks the structure reaches need a paste.
        int minChunkX = build.origin.getX() >> 4;
        int maxChunkX = (build.origin.getX() + size.getX() - 1) >> 4;
        int minChunkZ = build.origin.getZ() >> 4;
        int maxChunkZ = (build.origin.getZ() + size.getZ() - 1) >> 4;
        // Only the held chunks are pasted and cleared: a building whose stored blocks reach past them loses its edges in play (P59 rig run).
        // validate_definitions.py checks every stored block of every shipped structure against that window; a box check here could not tell
        // empty padding from blocks.
        // Each reached chunk in bands of BAND_HEIGHT blocks, so no single step holds a tick for long.
        int bottom = build.origin.getY();
        int top = bottom + size.getY() - 1;
        for (ChunkPos chunk : build.cellChunks) {
            if (chunk.x < minChunkX || chunk.x > maxChunkX || chunk.z < minChunkZ || chunk.z > maxChunkZ) continue;
            for (int low = bottom; low <= top; low += BAND_HEIGHT) {
                build.pasteBoxes.add(new BoundingBox(chunk.getMinBlockX(), low, chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(), Math.min(top, low + BAND_HEIGHT - 1), chunk.getMaxBlockZ()));
            }
        }
        build.needsReset = !CellCleanliness.isClean(cell);
        CellEvents.wiped(cell);                                    // a new building goes in: what the cell held before is gone
        StructurePieces.of(layout.structure(), template, build.origin);      // starts the cut if it is not cached: ready by the paste, usually
        return Optional.of(build);
    }

    /** Does as much as fits in {@code budgetNanos} (at least one step). */
    Status advance(MinecraftServer server, long budgetNanos) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return Status.FAILED;
        long started = System.nanoTime();
        long deadline = started + budgetNanos;
        try {
            Status status;
            do {
                long stepStart = System.nanoTime();
                String what = describeNextStep();
                status = step(level);
                long took = System.nanoTime() - stepStart;
                longestStep = Math.max(longestStep, took);
                // A step over this holds the server tick visibly: say which one, so a slow building can be cut finer where it is slow.
                if (took > SLOW_STEP_NANOS) {
                    TowerLog.warn("Cell {} build of {}: {} took {} ms", cell, layout.structure(), what, took / 1_000_000);
                }
            } while (status == Status.WORKING && !waited && System.nanoTime() < deadline);
            return status;
        } finally {
            workNanos += System.nanoTime() - started;
        }
    }

    private static final long SLOW_STEP_NANOS = 50_000_000L;

    /** What the next step will do, for the slow-step warning. */
    private String describeNextStep() {
        if (needsReset && resetNext < cellChunks.size()) return "clearing chunk " + cellChunks.get(resetNext) + " section " + resetSection;
        if (pasteNext < pasteBoxes.size()) {
            BoundingBox box = pasteBoxes.get(pasteNext);
            return "pasting chunk [" + (box.minX() >> 4) + ", " + (box.minZ() >> 4) + "] y " + box.minY() + ".." + box.maxY();
        }
        return "checking anchors";
    }

    /** True when the last step found a chunk not loaded yet: stop for this tick. */
    private boolean waited;

    private Status step(ServerLevel level) {
        waited = false;
        if (needsReset && resetNext < cellChunks.size()) {
            LevelChunk chunk = loaded(level, cellChunks.get(resetNext));
            if (chunk == null) return waiting();
            cleared += CellPreparer.resetSection(level, chunk, resetSection);    // a section a step: a whole column of a dense building was 50-86 ms
            if (++resetSection < chunk.getSections().length) return Status.WORKING;
            resetSection = 0;
            resetNext++;
            if (resetNext == cellChunks.size()) CellCleanliness.markClean(cell);
            return Status.WORKING;
        }
        if (!markedDirty) {
            // From here the cell holds (some of) a build, whether or not the paste finishes.
            if (cleared > 0) TowerLog.warn("Cell {} held {} leftover block(s) before it was prepared; cleared", cell, cleared);
            int swept = CellCleanup.sweepBeforeBuild(level.getServer(), cell);
            if (swept > 0) TowerLog.warn("Cell {} held {} leftover entity/entities before it was prepared; swept", cell, swept);
            CellCleanliness.markDirty(cell);
            markedDirty = true;
        }
        if (pasteNext < pasteBoxes.size()) {
            BoundingBox box = pasteBoxes.get(pasteNext);
            if (loaded(level, new ChunkPos(box.minX() >> 4, box.minZ() >> 4)) == null) return waiting();
            // The column's own piece when the cut is ready (it costs only that column); the whole template until then.
            StructureTemplate source = template;
            var pieces = StructurePieces.of(layout.structure(), template, origin);
            if (pieces.isPresent()) {
                source = pieces.get().get(StructurePieces.index((box.minX() >> 4) - (origin.getX() >> 4),
                        (box.minZ() >> 4) - (origin.getZ() >> 4)));
                if (source == null) {                                      // nothing stored in this column
                    pasteNext++;
                    return Status.WORKING;
                }
            }
            boolean placed = source.placeInWorld(level, origin, origin,
                    new StructurePlaceSettings().setIgnoreEntities(true).setBoundingBox(box), level.getRandom(),
                    CellPreparer.PLACE_FLAGS);
            // Before the next tick: the fall checks this band just scheduled fire two ticks later.
            CellPreparer.settle(level, box);
            if (!placed) {
                TowerLog.error("Placing {} into cell {} failed", layout.structure(), cell);
                return Status.FAILED;
            }
            pasteNext++;
            return Status.WORKING;
        }
        problems = CellAnchors.validate(level, cell, origin, layout);
        return Status.DONE;
    }

    private Status waiting() {
        waited = true;
        if (System.currentTimeMillis() - createdAt > LOAD_TIMEOUT_MILLIS) {
            TowerLog.error("Cell {} chunks did not load within {} s; giving the build up", cell, LOAD_TIMEOUT_MILLIS / 1000);
            return Status.FAILED;
        }
        return Status.WORKING;
    }

    /** The chunk when it is already loaded, else null (never a blocking load). */
    private static LevelChunk loaded(ServerLevel level, ChunkPos pos) {
        return level.getChunkSource().getChunkNow(pos.x, pos.z);
    }

    List<String> problems() {
        return problems;
    }

    /** The longest single step, the most one tick was held. */
    long longestStepMillis() {
        return longestStep / 1_000_000;
    }

    /** Time spent working, not waiting between ticks. */
    long workMillis() {
        return workNanos / 1_000_000;
    }
}
