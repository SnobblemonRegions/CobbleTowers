package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.diagnostics.TowerMetrics;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Turns an empty cell into a floor and back again. The template is vanilla structure NBT (converted once from
 * WorldEdit by {@code validation/schem_to_structure.py}). Chunks are held before the paste.
 */
public final class CellPreparer {

    /** Placement flags: tell clients, and skip the neighbour-shape updates a solid build does not need. */
    static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * What {@link #reset} sweeps: every chunk the cell holds, from just under the floor to the top of the world. A
     * cell is not told what was pasted into it, so all of it is covered; air-only sections are skipped.
     */
    private static final int RESET_BELOW = 1;

    private CellPreparer() {}

    /**
     * What preparing a cell produced.
     * @param millis how long the paste took
     * @param problems anchors not fit to use; empty means playable
     */
    public record Prepared(int cell, BlockPos origin, Vec3i size, long millis, List<String> problems) {

        public Prepared {
            problems = List.copyOf(problems);
        }

        public boolean isPlayable() {
            return problems.isEmpty();
        }

        public String summary() {
            return problems.isEmpty() ? "playable" : String.join("; ", problems);
        }
    }

    /**
     * Where this floor's structure sits in this cell. The origin depends on the structure's size, so anchors must be
     * resolved through this.
     */
    public static Optional<BlockPos> originFor(MinecraftServer server, int cell, FloorLayout layout) {
        return server.getStructureManager().get(layout.structure())
                .map(template -> CellTickets.pasteOrigin(cell, template.getSize().getX(), template.getSize().getZ()));
    }

    /**
     * Pastes a floor's structure into a cell and checks it is playable. Returns problems rather than throwing: a
     * floor that cannot be built is a cell to quarantine and a run to park.
     */
    public static Optional<Prepared> prepare(MinecraftServer server, int cell, FloorLayout layout) {
        CellGrid.requireValid(cell);
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return Optional.empty();

        Optional<StructureTemplate> found = server.getStructureManager().get(layout.structure());
        if (found.isEmpty()) {
            TowerLog.error("Floor structure {} is not loaded; cell {} cannot be prepared", layout.structure(), cell);
            return Optional.empty();
        }
        StructureTemplate template = found.get();
        Vec3i size = template.getSize();
        BlockPos origin = CellTickets.pasteOrigin(cell, size.getX(), size.getZ());
        // originFor() must agree with this; it is the same call, kept together on purpose.

        CellTickets.hold(server, cell);
        // A structure does not place its air, so an older build could show through; clear first unless the cell is
        // known clean.
        if (!CellCleanliness.isClean(cell)) {
            int leftover = reset(server, cell);
            if (leftover > 0) TowerLog.warn("Cell {} held {} leftover block(s) before it was prepared; cleared", cell, leftover);
        }
        int swept = CellCleanup.sweepBeforeBuild(server, cell);
        if (swept > 0) TowerLog.warn("Cell {} held {} leftover entity/entities before it was prepared; swept", cell, swept);
        // From here the cell holds (some of) a build, whether or not the paste succeeds.
        CellCleanliness.markDirty(cell);

        long started = System.nanoTime();
        boolean placed = template.placeInWorld(level, origin, origin,
                new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(), PLACE_FLAGS);
        settle(level, BoundingBox.fromCorners(origin, origin.offset(size).offset(-1, -1, -1)));
        long millis = (System.nanoTime() - started) / 1_000_000;
        TowerMetrics.recordAllocation(server, millis);
        if (!placed) {
            TowerLog.error("Placing {} into cell {} failed", layout.structure(), cell);
            return Optional.empty();
        }

        List<String> problems = CellAnchors.validate(level, cell, origin, layout);
        Prepared prepared = new Prepared(cell, origin, size, millis, problems);
        if (prepared.isPlayable()) {
            TowerLog.info("Cell {} prepared with {} at {} in {} ms", cell, layout.structure(), origin, millis);
        } else {
            TowerLog.error("Cell {} was built but is not playable: {}", cell, prepared.summary());
        }
        return Optional.of(prepared);
    }

    /**
     * Clears whatever is in the cell and returns how many blocks were removed. Reads the whole volume but only writes
     * where something is.
     */
    public static int reset(MinecraftServer server, int cell) {
        CellGrid.requireValid(cell);
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return 0;

        ChunkPos middle = new ChunkPos(CellGrid.centerOf(cell));
        int cleared = 0;
        int reach = CellTickets.RADIUS_CHUNKS;
        for (int chunkX = middle.x - reach; chunkX <= middle.x + reach; chunkX++) {
            for (int chunkZ = middle.z - reach; chunkZ <= middle.z + reach; chunkZ++) {
                cleared += resetChunk(level, level.getChunk(chunkX, chunkZ));
            }
        }
        if (cleared > 0) TowerLog.info("Cell {} reset, {} block(s) cleared", cell, cleared);
        CellCleanliness.markClean(cell);
        return cleared;
    }

    /**
     * Cancels the block ticks a paste just scheduled. A gravity block schedules a fall check whenever it is placed, and
     * the battle towers (built in WorldEdit, which skips physics) rest about 430 of them on air. In a cell's outer
     * chunks, which tick blocks but not entities, each one became a falling block frozen in place for good.
     */
    static void settle(ServerLevel level, BoundingBox box) {
        level.getBlockTicks().clearArea(box);
    }

    /** Clears one loaded chunk of the cell and returns how many blocks were removed. */
    static int resetChunk(ServerLevel level, LevelChunk chunk) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int lowest = CellGrid.FLOOR_Y - RESET_BELOW;
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int cleared = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int localY = 0; localY < 16; localY++) {
                int y = baseY + localY;
                if (y < lowest) continue;
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        if (section.getBlockState(localX, localY, localZ).isAir()) continue;
                        cursor.set(chunkX * 16 + localX, y, chunkZ * 16 + localZ);
                        level.setBlock(cursor, air, PLACE_FLAGS);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }
}
