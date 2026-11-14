package com.cobbletowers.prep;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.PrepRoom;
import com.cobbletowers.instance.PrepPlacement;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.route.LaneStairs;
import com.cobbletowers.runtime.RunExitService;
import com.cobbletowers.runtime.RunLifecycle;
import com.cobbletowers.runtime.RunTransitionService;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The preparation room (P59): once the floor-1 building is up and the keys are taken, the party is taken into a small room under it instead of
 * straight into the arena. There is a vendor of run supplies, the route map, and a ready ring in front of a great door. When every fighter stands
 * in the ring the door opens; when every fighter stands on the threshold beyond it the screen fades and floor 1 begins. A party that is still here
 * {@link PrepRules#TIMEOUT_MILLIS} after arriving is sent home and the run ends: the key was spent on the way in, so nothing is given back.
 * The run stays at PREPARING the whole time, so a restart parks it like any other. Server thread only.
 */
public final class PrepRoomService {

    private static final int TICK_EVERY = 5;
    private static int ticks;

    private static final class Session {
        final PrepRoom prep;
        final BlockPos origin;
        final long enteredAt;
        final Runnable refund;
        final ServerBossEvent bar;
        PrepRules.Step step = PrepRules.Step.GATHERING;
        long fadeAt;
        boolean warned;

        Session(PrepRoom prep, BlockPos origin, long enteredAt, Runnable refund, ServerBossEvent bar) {
            this.prep = prep;
            this.origin = origin;
            this.enteredAt = enteredAt;
            this.refund = refund;
            this.bar = bar;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();

    static {
        ServerState.onStop(() -> {
            SESSIONS.values().forEach(session -> session.bar.removeAllPlayers());
            SESSIONS.clear();
        });
    }

    private PrepRoomService() {}

    public static void install() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SESSIONS.isEmpty() || ++ticks % TICK_EVERY != 0) return;
            try {
                tick(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("The preparation room tick failed", ex);
            }
        });
    }

    /** Whether the run's party is in its preparation room (its vendor sells run supplies there). */
    public static boolean isInPrep(UUID runId) {
        return SESSIONS.containsKey(runId);
    }

    /**
     * Places the prep room in the run's cell and takes the party into it. Called once the floor-1 building is up and the keys were taken.
     * @param refund gives back what was paid to start, used only if floor 1 then fails to open (a fault, not the party's doing)
     * @return false when the room could not be placed or nobody is online to take in; nothing changed then, and floor 1 should open directly
     */
    public static boolean enter(MinecraftServer server, UUID runId, PrepRoom prep, Runnable refund, long now) {
        Optional<PersistedRun> run = TowerRuns.get(runId);
        ServerLevel level = TowerDimension.level(server);
        if (run.isEmpty() || run.get().cell().isEmpty() || level == null || run.get().state() != RunState.PREPARING) return false;
        List<ServerPlayer> party = online(server, run.get());
        if (party.isEmpty()) return false;
        Optional<BlockPos> origin = PrepPlacement.place(server, run.get().cell().getAsInt(), prep);
        if (origin.isEmpty()) return false;

        ServerBossEvent bar = new ServerBossEvent(Component.literal(title(PrepRules.TIMEOUT_MILLIS)), BossEvent.BossBarColor.WHITE,
                BossEvent.BossBarOverlay.PROGRESS);
        Session session = new Session(prep, origin.get(), now, refund, bar);
        SESSIONS.put(runId, session);        // before the vendor spawns: it only stays while the room is open
        BlockPos at = prep.entry().in(origin.get());
        for (int ordinal = 0; ordinal < party.size(); ordinal++) {
            ServerPlayer player = party.get(ordinal);
            RunExitService.remember(server, player);      // where they go home to (P20)
            player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5 + LaneStairs.spread(ordinal), prep.entry().yaw(), 0.0f);
            LaneStairs.fadeIn(player);
            bar.addPlayer(player);
            player.sendSystemMessage(Component.literal("Get ready: buy run supplies at the stand, then the whole team steps into the gold ring "
                    + "in front of the great door. You have " + PrepRules.clock(PrepRules.TIMEOUT_MILLIS) + "."));
        }
        com.cobbletowers.vendor.VendorNpc.spawnAt(server, run.get(), prep.vendor().in(origin.get()), prep.vendor().yaw());
        TowerLog.info("Run {}: {} player(s) entered the preparation room at {}", runId, party.size(), at);
        return true;
    }

    static void tick(MinecraftServer server, long now) {
        ServerLevel level = TowerDimension.level(server);
        for (Map.Entry<UUID, Session> entry : List.copyOf(SESSIONS.entrySet())) {
            UUID runId = entry.getKey();
            Session session = entry.getValue();
            Optional<PersistedRun> run = TowerRuns.get(runId);
            if (run.isEmpty() || run.get().state() != RunState.PREPARING || level == null) {
                close(server, runId, session, run);         // left, abandoned or parked by something else
                continue;
            }
            List<ServerPlayer> party = online(server, run.get());
            syncBar(session, party, now);
            if (session.step == PrepRules.Step.FADING) {
                if (now >= session.fadeAt) openFloorOne(server, runId, session, run.get(), now);
                continue;
            }
            if (PrepRules.timedOut(session.enteredAt, now)) {
                timeOut(server, runId, session, run.get(), party, now);
                continue;
            }
            if (!session.warned && PrepRules.remaining(session.enteredAt, now) <= PrepRules.WARNING_MILLIS) {
                session.warned = true;
                for (ServerPlayer player : party) {
                    player.sendSystemMessage(Component.literal("One minute left: step into the gold ring together, or the run ends."));
                }
            }
            List<ServerPlayer> fighters = party.stream().filter(player -> player.serverLevel() == level).toList();
            PrepRules.Step next = PrepRules.next(session.step, fighters.size(), countIn(fighters, session.prep.dais(), session.origin),
                    countIn(fighters, session.prep.threshold(), session.origin));
            if (session.step == PrepRules.Step.GATHERING && next == PrepRules.Step.DOOR_OPEN) openDoor(level, session, fighters);
            if (session.step == PrepRules.Step.DOOR_OPEN && next == PrepRules.Step.FADING) {
                LaneStairs.fadeOut(fighters);
                session.fadeAt = now + LaneStairs.FADE_OUT_MILLIS;
            }
            session.step = next;
        }
    }

    /** The great door opens: its bars go, with a sound, and the team is told to walk through together. */
    private static void openDoor(ServerLevel level, Session session, List<ServerPlayer> fighters) {
        BlockPos min = session.prep.door().minIn(session.origin);
        BlockPos max = session.prep.door().maxIn(session.origin);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (ServerPlayer player : fighters) {
            player.playNotifySound(SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8f, 0.7f);
            player.displayClientMessage(Component.literal("The great door is open. Walk through it together."), true);
        }
    }

    /** Everyone stood on the threshold and the screen went dark: the room closes and floor 1 begins, which takes the team to its arena. */
    private static void openFloorOne(MinecraftServer server, UUID runId, Session session, PersistedRun run, long now) {
        close(server, runId, session, Optional.of(run));
        if (!(RunTransitionService.apply(server, runId, RunEvent.PREPARATION_COMPLETE, now) instanceof RunTransitionService.Move)) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            failed(server, run, session);
            return;
        }
        if (!RunLifecycle.beginFloor(server, runId, now)) failed(server, run, session);
    }

    /** Floor 1 could not open: the run is parked (beginFloor did that), and what was paid to start goes back, since it was never played. */
    private static void failed(MinecraftServer server, PersistedRun run, Session session) {
        session.refund.run();
        for (ServerPlayer player : online(server, run)) {
            player.sendSystemMessage(Component.literal("Floor 1 could not be opened, so the run has ended and what it cost was given back."));
        }
    }

    private static void timeOut(MinecraftServer server, UUID runId, Session session, PersistedRun run, List<ServerPlayer> party, long now) {
        close(server, runId, session, Optional.of(run));
        for (ServerPlayer player : party) {
            player.sendSystemMessage(Component.literal("Time is up: the team did not step through the great door, so the run has ended. "
                    + "The key was spent when you came in."));
        }
        TowerLog.info("Run {}: the preparation room timed out after {} ms", runId, now - session.enteredAt);
        com.cobbletowers.report.TowerTelemetry.endCause(runId, "prep_timeout");
        RunTransitionService.apply(server, runId, RunEvent.ABANDON_REQUESTED, now);   // the exit sweep sends them home and puts their Pokemon back
    }

    private static void close(MinecraftServer server, UUID runId, Session session, Optional<PersistedRun> run) {
        SESSIONS.remove(runId);
        session.bar.removeAllPlayers();
        run.ifPresent(found -> com.cobbletowers.vendor.VendorNpc.despawn(server, found));
    }

    private static void syncBar(Session session, List<ServerPlayer> party, long now) {
        long left = PrepRules.remaining(session.enteredAt, now);
        session.bar.setProgress(PrepRules.progress(session.enteredAt, now));
        session.bar.setName(Component.literal(title(left)));
        session.bar.setColor(left <= PrepRules.WARNING_MILLIS ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.WHITE);
        for (ServerPlayer player : List.copyOf(session.bar.getPlayers())) {
            if (!party.contains(player)) session.bar.removePlayer(player);
        }
        for (ServerPlayer player : party) session.bar.addPlayer(player);
    }

    private static String title(long remainingMillis) {
        return "Preparation room: " + PrepRules.clock(remainingMillis) + " to step through the great door";
    }

    private static int countIn(List<ServerPlayer> players, FloorBox box, BlockPos origin) {
        BlockPos min = box.minIn(origin);
        BlockPos max = box.maxIn(origin);
        int count = 0;
        for (ServerPlayer player : players) {
            if (LaneStairs.inside(player.getX(), player.getY(), player.getZ(), min, max)) count++;
        }
        return count;
    }

    /** The run's fighters who are online. */
    private static List<ServerPlayer> online(MinecraftServer server, PersistedRun run) {
        List<ServerPlayer> out = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().isInRun() || !participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) out.add(player);
        }
        return out;
    }
}
