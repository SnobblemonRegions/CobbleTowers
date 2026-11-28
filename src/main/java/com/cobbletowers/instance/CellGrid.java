package com.cobbletowers.instance;

import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * Where a cell is: index in, coordinates out. One cell per 512-block square (one region file), so cells never share a
 * chunk (TDS #26). The interior is centred with a {@value #BUFFER}-block margin. Pure.
 */
public final class CellGrid {

    /** One region file. Cells never share a region, let alone a chunk. */
    public static final int CELL_SPAN = 512;

    /** The usable square inside a cell's span. */
    public static final int INTERIOR = 256;

    /** Dead space between one cell's interior and its span's edge. */
    public static final int BUFFER = (CELL_SPAN - INTERIOR) / 2;

    /** Cells are laid out row-major, this many per row. */
    public static final int CELLS_PER_ROW = 64;

    /** So the grid stays inside sane coordinates: 64 x 64 cells is 32,768 blocks square. */
    public static final int MAX_CELLS = CELLS_PER_ROW * CELLS_PER_ROW;

    /** Matches the dimension's min_y and height. */
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 512;

    /**
     * Where a floor sits, and where anything entering a cell is put down. The dimension keeps the overworld sky (a fixed noon), whose
     * clouds are drawn at y 192 through everything: inside a hall they hung under the Tideforge dome (owner, 15 Nov 2026). Every cell
     * is built above them: the preparation room at FLOOR_Y - {@link PrepPlacement#DROP} = 200 clears their top (196.3), and the
     * tallest building (258 high, Duskvale) ends at 490, under MAX_Y.
     */
    public static final int FLOOR_Y = 232;

    /**
     * How far below the cell a cleanup sweep still looks: the tower is a void, so leftovers keep falling below y=0
     * for a while.
     */
    public static final int SWEEP_BELOW = 320;

    private CellGrid() {}

    public static void requireValid(int index) {
        if (index < 0 || index >= MAX_CELLS) {
            throw new IllegalArgumentException("cell index " + index + " is outside 0.." + (MAX_CELLS - 1));
        }
    }

    /** The north-west corner of the cell's interior. */
    public static BlockPos originOf(int index) {
        requireValid(index);
        int column = index % CELLS_PER_ROW;
        int row = index / CELLS_PER_ROW;
        return new BlockPos(column * CELL_SPAN + BUFFER, FLOOR_Y, row * CELL_SPAN + BUFFER);
    }

    /** The middle of the cell's interior, at floor height: where a player is put down. */
    public static BlockPos centerOf(int index) {
        BlockPos origin = originOf(index);
        return origin.offset(INTERIOR / 2, 0, INTERIOR / 2);
    }

    /** The whole interior column, floor to ceiling: where gameplay happens. */
    public static AABB boundsOf(int index) {
        BlockPos origin = originOf(index);
        return new AABB(origin.getX(), MIN_Y, origin.getZ(),
                origin.getX() + INTERIOR, MAX_Y, origin.getZ() + INTERIOR);
    }

    /**
     * The volume a cleanup sweep covers: the interior plus the void beneath it. Wider than {@link #boundsOf}; see
     * {@link #SWEEP_BELOW}.
     */
    public static AABB sweepBoundsOf(int index) {
        BlockPos origin = originOf(index);
        return new AABB(origin.getX(), MIN_Y - SWEEP_BELOW, origin.getZ(),
                origin.getX() + INTERIOR, MAX_Y, origin.getZ() + INTERIOR);
    }

    /** Which cell contains this position, or empty for the buffer between cells and outside the grid. */
    public static OptionalInt indexAt(double x, double z) {
        int column = Math.floorDiv((int) Math.floor(x), CELL_SPAN);
        int row = Math.floorDiv((int) Math.floor(z), CELL_SPAN);
        if (column < 0 || column >= CELLS_PER_ROW || row < 0 || row >= CELLS_PER_ROW) return OptionalInt.empty();

        int localX = (int) Math.floor(x) - column * CELL_SPAN;
        int localZ = (int) Math.floor(z) - row * CELL_SPAN;
        if (localX < BUFFER || localX >= BUFFER + INTERIOR) return OptionalInt.empty();
        if (localZ < BUFFER || localZ >= BUFFER + INTERIOR) return OptionalInt.empty();
        return OptionalInt.of(row * CELLS_PER_ROW + column);
    }

    /** The chunks the interior covers, for the ticket work P4 owns. */
    public static ChunkPos minChunk(int index) {
        BlockPos origin = originOf(index);
        return new ChunkPos(origin);
    }

    public static ChunkPos maxChunk(int index) {
        BlockPos origin = originOf(index);
        return new ChunkPos(origin.offset(INTERIOR - 1, 0, INTERIOR - 1));
    }
}
