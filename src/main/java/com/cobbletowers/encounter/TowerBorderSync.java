package com.cobbletowers.encounter;

import com.cobbletowers.instance.TowerDimension;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Tells a player's game the tower's own world border while they are in the tower.
 *
 * <p>Minecraft sends every player the main world's border, whatever dimension they are in, and a client will not send a right-click on an entity that stands
 * outside the border it was told. The tower's cells are built far out (thousands of blocks), so on a server whose main border is smaller, a player in the
 * tower could hit the pedestals and the vendor but not right-click them. The tower's border is kept wide open by {@link ArrivalGuard}; this sends that one
 * instead whenever a player arrives in the tower, joins inside it or respawns into it, and again after any change to the main border (which the game sends to
 * everybody).
 */
final class TowerBorderSync {

    private TowerBorderSync() {}

    static void install() {
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> sendIfInTower(player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendIfInTower(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sendIfInTower(newPlayer));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.overworld().getWorldBorder().addListener(new MainBorderWatcher(server)));
        // Other mods may send the main border again after any of the above (on a change of world, for instance); once a second the tower's is sent again so
        // the last word is always the tower's. One small packet a second per player in the tower.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            java.util.Set<java.util.UUID> inside = new java.util.HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.level().dimension().equals(TowerDimension.LEVEL)) continue;
                inside.add(player.getUUID());
                send(player, !TOLD.contains(player.getUUID()));
            }
            TOLD.clear();
            TOLD.addAll(inside);
        });
    }

    /** Players in the tower who have had the tower's border at least once since they arrived (so the log says it once, not every second). */
    private static final java.util.Set<java.util.UUID> TOLD = new java.util.HashSet<>();

    /** Sends the tower's border to a player who is in the tower; anyone else keeps the main border the game already sent them. */
    static void sendIfInTower(ServerPlayer player) {
        send(player, true);
    }

    private static void send(ServerPlayer player, boolean log) {
        if (!(player.level() instanceof ServerLevel level) || !level.dimension().equals(TowerDimension.LEVEL)) return;
        ArrivalGuard.keepBorderOpen(level);
        player.connection.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        if (log) com.cobbletowers.TowerLog.info("Sent the tower's world border ({} blocks) to {}", (long) level.getWorldBorder().getSize(), player.getGameProfile().getName());
    }

    /** The main border changed and the game told everybody; the players in the tower get the tower's border back. */
    private record MainBorderWatcher(MinecraftServer server) implements BorderChangeListener {
        private void resend() {
            // The game sends its own packet for the change first; this one, sent after it, is the one the client keeps.
            server.execute(() -> server.getPlayerList().getPlayers().forEach(TowerBorderSync::sendIfInTower));
        }

        @Override public void onBorderSizeSet(WorldBorder border, double size) { resend(); }
        @Override public void onBorderSizeLerping(WorldBorder border, double from, double to, long time) { resend(); }
        @Override public void onBorderCenterSet(WorldBorder border, double x, double z) { resend(); }
        @Override public void onBorderSetWarningTime(WorldBorder border, int time) { resend(); }
        @Override public void onBorderSetWarningBlocks(WorldBorder border, int blocks) { resend(); }
        @Override public void onBorderSetDamagePerBlock(WorldBorder border, double damage) { resend(); }
        @Override public void onBorderSetDamageSafeZOne(WorldBorder border, double distance) { resend(); }
    }
}
