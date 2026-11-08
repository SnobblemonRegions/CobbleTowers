package com.cobbletowers.encounter;

import com.cobbletowers.TowerLog;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a party from falling out of a floor they have just been sent to.
 *
 * <p>A floor built from nothing (no warm cell was waiting) can stall the server for several seconds, and the ticks it owes are then run back to back.
 * A player placed over a spot that is not ready yet falls the whole way to the void inside that burst, so they die the moment they arrive. Two things stop it:
 * the arrival point is checked (and its chunk loaded) before anyone is sent, moving to a nearby standing place when the exact one is not solid; and for a few
 * seconds after arriving a player who has dropped well below the arrival point is put back on it. Ids and positions only, server thread only.
 */
final class ArrivalGuard {

    /** How long after arriving a player is watched, in ticks (15 seconds; catch-up bursts are run inside this). */
    private static final int WATCH_TICKS = 300;
    /** A drop this far below the arrival point inside the window is a fall out of the floor, not a step. */
    private static final int DROP_BLOCKS = 12;
    /** How far above and below the wanted spot a standing place is looked for. */
    private static final int SEARCH_BLOCKS = 6;

    private record Watch(ResourceKey<Level> dimension, double x, double y, double z, float yaw, long untilTick) {}

    private static final Map<UUID, Watch> WATCHED = new HashMap<>();

    private ArrivalGuard() {}

    static void install() {
        ServerTickEvents.END_SERVER_TICK.register(ArrivalGuard::tick);
    }

    /**
     * The place to send a party to: {@code wanted} when a player can stand there (solid below, two free blocks above), else the nearest place that way within a
     * few blocks, else {@code wanted} again with a warning. Loads the chunk, so a floor that is still being placed is complete before anyone arrives.
     */
    static BlockPos standingSpot(ServerLevel level, BlockPos wanted) {
        level.getChunk(wanted.getX() >> 4, wanted.getZ() >> 4);
        if (canStand(level, wanted)) return wanted;
        for (int offset = 1; offset <= SEARCH_BLOCKS; offset++) {
            BlockPos up = wanted.above(offset);
            if (canStand(level, up)) return up;
            BlockPos down = wanted.below(offset);
            if (canStand(level, down)) return down;
        }
        TowerLog.warn("No place to stand within {} blocks of the arrival point {}; sending the party there anyway", SEARCH_BLOCKS, wanted);
        return wanted;
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        if (!level.isInWorldBounds(feet)) return false;
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** Starts watching a player who has just been placed at {@code spot}. */
    static void watch(MinecraftServer server, ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw) {
        WATCHED.put(player.getUUID(), new Watch(level.dimension(), x, y, z, yaw, server.getTickCount() + WATCH_TICKS));
    }

    private static void tick(MinecraftServer server) {
        if (WATCHED.isEmpty()) return;
        long now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Watch>> it = WATCHED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Watch> entry = it.next();
            Watch watch = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || now > watch.untilTick() || !player.isAlive()) {
                it.remove();
                continue;
            }
            if (!player.level().dimension().equals(watch.dimension())) {
                it.remove();   // sent somewhere else on purpose (left the run, next floor): not a fall
                continue;
            }
            if (player.getY() >= watch.y() - DROP_BLOCKS) continue;
            ServerLevel level = server.getLevel(watch.dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            TowerLog.warn("{} fell {} blocks below the arrival point of their floor; put back on it", player.getGameProfile().getName(),
                    (int) (watch.y() - player.getY()));
            player.teleportTo(level, watch.x(), watch.y(), watch.z(), watch.yaw(), 0.0f);
            player.fallDistance = 0.0f;
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
        }
    }
}
