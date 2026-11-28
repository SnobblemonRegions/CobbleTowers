package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.PrepRoom;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Where a tower's preparation room (P59) goes in a run's cell: centred under the floor-1 building, {@link #DROP} blocks below the build floor.
 * That is inside the cell's held chunks, clear of anything a floor builds, and inside the cell reset (which clears down to there), so the room
 * goes when the cell is given back like the rest of the build. It is small (the Neutral one stores about 13,000 blocks), so it is pasted in one
 * go once the floor-1 building is up.
 */
public final class PrepPlacement {

    /** How far under the build floor the prep room's paste origin is. A prep structure must be lower than this. */
    public static final int DROP = 32;

    private PrepPlacement() {}

    /** Whether a prep structure this tall fits between its origin and the build floor, with a row of air between. */
    public static boolean fits(int height) {
        return height >= 1 && height <= DROP - 1;
    }

    /** The paste origin of a prep structure of this size in this cell. */
    public static BlockPos origin(int cell, Vec3i size) {
        BlockPos centre = CellGrid.centerOf(cell);
        return new BlockPos(centre.getX() - size.getX() / 2, CellGrid.FLOOR_Y - DROP, centre.getZ() - size.getZ() / 2);
    }

    /** Where the prep room of a cell was (or would be) pasted; empty when its structure is not loaded. */
    public static Optional<BlockPos> originFor(MinecraftServer server, int cell, PrepRoom prep) {
        return server.getStructureManager().get(prep.structure()).map(template -> origin(cell, template.getSize()));
    }

    /** Pastes the prep room into the cell. Returns its origin, or empty when it could not be placed (missing, too tall, or the paste failed). */
    public static Optional<BlockPos> place(MinecraftServer server, int cell, PrepRoom prep) {
        CellGrid.requireValid(cell);
        ServerLevel level = TowerDimension.level(server);
        Optional<StructureTemplate> template = server.getStructureManager().get(prep.structure());
        if (level == null || template.isEmpty()) {
            TowerLog.error("Prep room {} could not be placed in cell {}: {}", prep.structure(), cell,
                    level == null ? "no tower dimension" : "no such structure");
            return Optional.empty();
        }
        Vec3i size = template.get().getSize();
        if (!fits(size.getY())) {
            TowerLog.error("Prep room {} is {} high; it must be under {} to fit under the build", prep.structure(), size.getY(), DROP);
            return Optional.empty();
        }
        BlockPos origin = origin(cell, size);
        CellTickets.hold(server, cell);
        CellCleanliness.markDirty(cell);
        long started = System.nanoTime();
        boolean placed = template.get().placeInWorld(level, origin, origin, new StructurePlaceSettings().setIgnoreEntities(true),
                level.getRandom(), CellPreparer.PLACE_FLAGS);
        CellPreparer.settle(level, BoundingBox.fromCorners(origin, origin.offset(size).offset(-1, -1, -1)));
        if (!placed) {
            TowerLog.error("Placing prep room {} into cell {} failed", prep.structure(), cell);
            return Optional.empty();
        }
        TowerLog.info("Cell {}: prep room {} placed at {} in {} ms", cell, prep.structure(), origin, (System.nanoTime() - started) / 1_000_000);
        return Optional.of(origin);
    }
}
