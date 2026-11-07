package com.cobbletowers.encounter;

import com.cobbletowers.TowerLog;
import com.cobbletowers.instance.CellGrid;
import com.cobbletowers.instance.InstanceAllocator;
import com.cobbletowers.persistence.PersistedRun;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/** Releases a followed camera without leaving the revived player's eyes inside their teammate. */
public final class SpectatorRevivalLanding {
    private SpectatorRevivalLanding() {}

    public static void separate(MinecraftServer server, PersistedRun run, ServerPlayer player) {
        if (!run.state().isLive() || run.cell().isEmpty()
                || !InstanceAllocator.runIn(run.cell().getAsInt()).filter(run.runId()::equals).isPresent()) return;
        FloorSetup.resolve(server, run, false).ifPresent(setup -> {
            if (player.serverLevel() != setup.level()
                    || CellGrid.indexAt(player.getX(), player.getZ()).orElse(-1) != run.cell().getAsInt()) return;
            if (setup.level().players().stream().noneMatch(other -> other != player
                    && other.getBoundingBox().intersects(player.getBoundingBox()))) return;
            // Stay near a declared anchor, never guess a floor origin or move another participant.
            // Each landing is checked afresh, so several revived spectators cannot reuse one position.
            for (var anchor : java.util.List.of(setup.layout().entry(), setup.layout().spectator(), setup.layout().exit())) {
                BlockPos base = anchor.in(setup.origin());
                for (int radius = 0; radius <= 4; radius++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                            BlockPos feet = base.offset(dx, 0, dz);
                            if (CellGrid.indexAt(feet.getX(), feet.getZ()).orElse(-1) != run.cell().getAsInt()) continue;
                            var support = setup.level().getBlockState(feet.below());
                            if (!support.isFaceSturdy(setup.level(), feet.below(), Direction.UP)
                                    || support.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                                    || support.is(net.minecraft.world.level.block.Blocks.CACTUS)
                                    || support.is(net.minecraft.tags.BlockTags.CAMPFIRES)) continue;
                            // Empty collision shapes also include fire and powder snow. Require clear air,
                            // and check standing dimensions even if the spectator was crouching or swimming.
                            if (!setup.level().getBlockState(feet).isAir() || !setup.level().getBlockState(feet.above()).isAir()) continue;
                            AABB box = player.getDimensions(net.minecraft.world.entity.Pose.STANDING)
                                    .makeBoundingBox(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                            if (!setup.level().noCollision(player, box)) continue;
                            if (setup.level().players().stream().anyMatch(other -> other != player
                                    && other.getBoundingBox().intersects(box.inflate(0.1)))) continue;
                            player.teleportTo(setup.level(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, anchor.yaw(), 0);
                            com.cobbletowers.route.LaneStairs.fadeIn(player);
                            return;
                        }
                    }
                }
            }
            TowerLog.warn("No unoccupied revival landing for {} in run {}; leaving their position unchanged",
                    player.getGameProfile().getName(), run.runId());
        });
    }
}
