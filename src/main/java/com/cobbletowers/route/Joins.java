package com.cobbletowers.route;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bars, panes, walls and fences joined to their neighbours after a box of them was put back. Setting blocks one by one
 * joins each new block's neighbours to it, but never the new block to the neighbours set before it, so a gate shut
 * again came back partly as loose posts (rig 2, 14 Nov; the structures store their joins since the same day).
 */
public final class Joins {

    private Joins() {}

    /** Recomputes each block's shape in the box (and its ring) from its now-finished neighbours. */
    public static void settle(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos pos : BlockPos.betweenClosed(min.offset(-1, 0, -1), max.offset(1, 0, 1))) {
            if (!level.isLoaded(pos)) continue;
            BlockState now = level.getBlockState(pos);
            BlockState joined = Block.updateFromNeighbourShapes(now, level, pos);
            if (joined != now) level.setBlock(pos, joined, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
