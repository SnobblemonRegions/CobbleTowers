package com.cobbletowers.route;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedRun;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * The lane stairs of a walkable tower (P58): the way up from a floor is the stair behind the door the vote opened, and its landing is a threshold.
 * When the whole team stands on it the screen fades and everyone is taken to that lane's arrival spot in the next floor's arrival hall, which is
 * dressed for the route. The rules are plain functions; the rest places players and runs on the server thread only.
 */
public final class LaneStairs {

    /** How long the screen is dark before the team is moved: the blindness lasts a little longer, so the arrival fades in. */
    public static final long FADE_OUT_MILLIS = 1200;
    /** Blindness at the fade out, in ticks: covers the wait and the first moments after the move. */
    public static final int FADE_OUT_TICKS = 44;
    /** Blindness for somebody who is moved by any other teleport (a cycle's end, a straggler, the first floor), in ticks. */
    public static final int FADE_IN_TICKS = 24;
    /** After the move, how long the team gets to walk from the arrival hall into the arena before stragglers are taken to its door. */
    public static final long ARRIVAL_WALK_MILLIS = 45_000;

    private LaneStairs() {}

    /**
     * Where the {@code ordinal}th member of the team stands, as a sideways step from the lane's arrival spot so nobody lands inside anybody:
     * 0, +1, -1, +2, -2 and so on.
     */
    public static int spread(int ordinal) {
        return (ordinal + 1) / 2 * (ordinal % 2 == 1 ? 1 : -1);
    }

    /** Whether a point is inside a box given as its lowest and highest block (the whole block counts, so x from minX to maxX + 1). */
    public static boolean inside(double x, double y, double z, BlockPos min, BlockPos max) {
        return x >= min.getX() && x < max.getX() + 1 && y >= min.getY() - 0.5 && y < max.getY() + 1 && z >= min.getZ() && z < max.getZ() + 1;
    }

    /** Whether every one of {@code fighters} is on the threshold of {@code lane}'s stair. Nobody there counts as not ready: the deadline decides. */
    public static boolean everyoneOnLanding(MinecraftServer server, PersistedRun run, int lane, List<ServerPlayer> fighters) {
        if (fighters.isEmpty()) return false;
        Optional<Placed> placed = place(server, run);
        if (placed.isEmpty() || lane < 0 || lane >= placed.get().layout.doors().size()) return false;
        Optional<FloorBox> top = placed.get().layout.doors().get(lane).stairTop();
        if (top.isEmpty()) return false;
        BlockPos min = top.get().minIn(placed.get().origin);
        BlockPos max = top.get().maxIn(placed.get().origin);
        for (ServerPlayer player : fighters) {
            if (player.serverLevel() != placed.get().level || !inside(player.getX(), player.getY(), player.getZ(), min, max)) return false;
        }
        return true;
    }

    /** The screen goes dark for these players, with a low sound: the first half of the transition. */
    public static void fadeOut(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, FADE_OUT_TICKS, 0, false, false, false));
            player.playNotifySound(SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.35f, 1.7f);
        }
    }

    /** A short blindness for somebody who has just been moved by any other teleport, so a change of floor is never a cut. */
    public static void fadeIn(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, FADE_IN_TICKS, 0, false, false, false));
    }

    /**
     * Takes {@code fighters} up the stair of {@code lane} to its arrival spot on the next floor, and dresses that spot for the route.
     * @param kind the kind of stop the lane led through (a {@link RouteGraph.Kind} name), or empty when unknown
     * @return how many were moved; 0 when the floor has no such stair or the next floor is not placed
     */
    public static int transfer(MinecraftServer server, PersistedRun run, int lane, String kind, List<ServerPlayer> fighters) {
        Optional<Placed> current = place(server, run);
        if (current.isEmpty() || lane < 0 || lane >= current.get().layout.doors().size()) return 0;
        FloorDoor door = current.get().layout.doors().get(lane);
        if (door.arrival().isEmpty()) return 0;
        Optional<FloorLayout> next = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex() + 1);
        if (next.isEmpty()) return 0;
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), next.get());
        if (origin.isEmpty()) return 0;
        ServerLevel level = current.get().level;
        FloorAnchor arrival = door.arrival().get();
        BlockPos at = arrival.in(origin.get());
        LaneArrival.dress(level, at, arrival.yaw(), kind);
        int moved = 0;
        for (int ordinal = 0; ordinal < fighters.size(); ordinal++) {
            ServerPlayer player = fighters.get(ordinal);
            player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5 + spread(ordinal), arrival.yaw(), 0.0f);
            player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.4f, 0.6f);
            moved++;
        }
        TowerLog.info("Run {}: {} fighter(s) climbed lane {} ({}) from floor {} to its arrival at {}", run.runId(), moved, lane, kind, run.floorIndex(), at);
        return moved;
    }

    private record Placed(ServerLevel level, FloorLayout layout, BlockPos origin) {}

    private static Optional<Placed> place(MinecraftServer server, PersistedRun run) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty()) return Optional.empty();
        Optional<FloorLayout> layout = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty()) return Optional.empty();
        return CellPreparer.originFor(server, run.cell().getAsInt(), layout.get()).map(origin -> new Placed(level, layout.get(), origin));
    }
}
