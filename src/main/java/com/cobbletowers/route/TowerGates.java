package com.cobbletowers.route;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The gates of a walkable tower (P55): iron bars that keep the party in the arena until the floor is won (gate A) and off the stair until the
 * team is ready (gate B). A gate is opened by clearing its box. The state is the blocks themselves, so a restart cannot disagree with it, and
 * opening twice is harmless. Server thread only.
 */
public final class TowerGates {

    private TowerGates() {}

    /** Opens gate A for the run's current floor. @return the blocks cleared; 0 when the floor has no such gate or it was open already */
    public static int openA(MinecraftServer server, PersistedRun run) {
        return open(server, run, FloorLayout::gateA, "A");
    }

    /** Opens gate B for the run's current floor. */
    public static int openB(MinecraftServer server, PersistedRun run) {
        return open(server, run, FloorLayout::gateB, "B");
    }

    private static int open(MinecraftServer server, PersistedRun run, Function<FloorLayout, Optional<FloorBox>> which, String name) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty()) return 0;
        Optional<FloorLayout> layout = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty()) return 0;
        Optional<FloorBox> box = which.apply(layout.get());
        if (box.isEmpty()) return 0;
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        if (origin.isEmpty()) return 0;
        BlockPos min = box.get().minIn(origin.get());
        BlockPos max = box.get().maxIn(origin.get());
        int cleared = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) {
                TowerLog.warn("Run {}: gate {} at {} is not loaded; it stays shut", run.runId(), name, pos);
                continue;
            }
            if (level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            cleared++;
        }
        if (cleared > 0) {
            BlockPos centre = new BlockPos((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2, (min.getZ() + max.getZ()) / 2);
            level.playSound(null, centre, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.4f, 0.7f);
            TowerLog.info("Run {}: gate {} of floor {} opened ({} blocks)", run.runId(), name, run.floorIndex(), cleared);
        }
        return cleared;
    }

    /**
     * Shuts both gates of the run's current floor as it begins. A fresh cell has them shut already; this is for the next cycle of an
     * ascending tower, which fights the same building again with every gate the last cycle opened still open. A gate with a player inside
     * it is left as it is (and logged): bars would trap them.
     * @return the blocks placed
     */
    public static int closeBoth(MinecraftServer server, PersistedRun run) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty()) return 0;
        Optional<FloorLayout> layout = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty()) return 0;
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        if (origin.isEmpty()) return 0;
        int placed = 0;
        for (Optional<FloorBox> box : java.util.List.of(layout.get().gateA(), layout.get().gateB())) {
            if (box.isEmpty()) continue;
            BlockPos min = box.get().minIn(origin.get());
            BlockPos max = box.get().maxIn(origin.get());
            var area = new net.minecraft.world.phys.AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            if (!level.getEntitiesOfClass(ServerPlayer.class, area).isEmpty()) {
                TowerLog.warn("Run {}: a gate of floor {} was left open because a player stands in it", run.runId(), run.floorIndex());
                continue;
            }
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) continue;
                level.setBlock(pos, Blocks.IRON_BARS.defaultBlockState(), Block.UPDATE_ALL);
                placed++;
            }
        }
        if (placed > 0) TowerLog.info("Run {}: the gates of floor {} were shut again ({} blocks)", run.runId(), run.floorIndex(), placed);
        return placed + TowerDoors.resetAll(server, run);
    }

    /**
     * Brings back anybody left on the gallery ledge: the spectator anchor is behind glass, so a player who was knocked out and then revived there
     * could not walk to the vendor. They are stood at the vendor's spot.
     * @return how many were moved
     */
    public static int rescueFromGallery(MinecraftServer server, PersistedRun run) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty()) return 0;
        Optional<FloorLayout> layout = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty() || layout.get().gateA().isEmpty()) return 0;
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        if (origin.isEmpty()) return 0;
        BlockPos vendor = layout.get().exit().in(origin.get());
        int moved = 0;
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null || player.serverLevel() != level) continue;
            if (!WalkRules.strandedAt(player.getX(), player.getY(), player.getZ(), layout.get().spectator(),
                    origin.get().getX(), origin.get().getY(), origin.get().getZ())) continue;
            player.teleportTo(level, vendor.getX() + 0.5 + moved, vendor.getY(), vendor.getZ() + 2.5, layout.get().exit().yaw(), 0.0f);
            LaneStairs.fadeIn(player);
            moved++;
        }
        return moved;
    }
}
