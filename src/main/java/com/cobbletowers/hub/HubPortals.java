package com.cobbletowers.hub;

import com.cobbletowers.TowerLog;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.lobby.TowerLobby;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Tower Hub's portals (owner, 19 Nov 2026). A player who walks into a tower's portal is set back down just outside it, and
 * then: a host whose team is for that tower starts the run, through {@link LobbyService#start} and every check it makes; anyone
 * else gets the screen that says what is missing (that tower's page in the Hall, or their own team's lobby). Walking in never
 * does anything a screen button could not, and never twice in a row: a short cooldown per player.
 */
public final class HubPortals {

    /** Checked every few ticks: a walker is inside an opening for several. */
    private static final int EVERY = 4;
    /** After a walk-in, the same player is left alone this long. */
    private static final int COOLDOWN_TICKS = 40;

    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    /** One per portal, in layout order; null until drawn. A veil goes with its chunk when the chunk unloads, and is drawn again. */
    private static final List<PortalVeil> VEILS = new ArrayList<>();

    private HubPortals() {}

    /** What a walk-in does, from what is true of the walker. Pure, for the tests. */
    public enum Action { START, OPEN_TOWER, OPEN_LOBBY, OTHER_TOWER, IN_RUN }

    public static Action decide(boolean inRun, boolean inLobby, boolean isHost, boolean sameTower) {
        if (inRun) return Action.IN_RUN;
        if (!inLobby) return Action.OPEN_TOWER;
        if (!sameTower) return Action.OTHER_TOWER;
        return isHost ? Action.START : Action.OPEN_LOBBY;
    }

    public static void install() {
        ServerTickEvents.END_SERVER_TICK.register(HubPortals::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(HubPortals::showVeils);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            clearVeils();
            COOLDOWN.clear();
        });
    }

    /** The hub's world, if a hub has been built and that world is loaded. */
    static Optional<ServerLevel> level(MinecraftServer server, HubStore.Placed placed) {
        return Optional.ofNullable(server.getLevel(ResourceKey.create(Registries.DIMENSION, placed.dimension())));
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % EVERY != 0) return;
        Optional<HubStore.Placed> placed = HubStore.get(server).placed();
        if (placed.isEmpty()) return;
        Optional<ServerLevel> found = level(server, placed.get());
        if (found.isEmpty() || found.get().players().isEmpty()) return;
        ServerLevel level = found.get();
        if (server.getTickCount() % 20 == 0) keepVeils(level, placed.get().origin());
        BlockPos origin = placed.get().origin();
        HubLayout layout = HubLayout.load();
        long now = server.getTickCount();
        for (ServerPlayer player : List.copyOf(level.players())) {
            Long until = COOLDOWN.get(player.getUUID());
            if (until != null && now < until) continue;
            BlockPos at = player.blockPosition().subtract(origin);
            for (HubLayout.Portal portal : everyPortal(layout)) {
                if (!portal.contains(at.getX(), at.getY(), at.getZ())) continue;
                COOLDOWN.put(player.getUUID(), now + COOLDOWN_TICKS);
                try {
                    walkIn(server, level, origin, player, portal);
                } catch (RuntimeException ex) {
                    TowerLog.error("Hub portal walk-in failed for " + player.getGameProfile().getName(), ex);
                }
                break;
            }
        }
    }

    /** The towers' gates and the Trials Pavilion's shrines: every opening a player can walk into. */
    static List<HubLayout.Portal> everyPortal(HubLayout layout) {
        List<HubLayout.Portal> all = new ArrayList<>(layout.portals());
        for (HubLayout.Shrine shrine : layout.shrines()) all.add(shrine.portal());
        return all;
    }

    private static void walkIn(MinecraftServer server, ServerLevel level, BlockPos origin, ServerPlayer player, HubLayout.Portal portal) {
        stepBack(level, origin, player, portal);
        // a trial shrine (owner, 20 Nov 2026): the trial its kind has today, for a party's ready members or a player alone
        for (HubLayout.Shrine shrine : HubLayout.load().shrines()) {
            if (shrine.portal() == portal) {
                PartyService.trialWalkIn(server, new PartyService.Hub(level, origin, HubLayout.load()), player, shrine);
                return;
            }
        }
        // a Tower Hub party (owner, 20 Nov 2026) goes in by its leader, with whoever is gathered at the gate: no screen
        Optional<Party> party = PartyService.partyOf(player.getUUID());
        // a player alone (a party of one) who is in a team from the Tower Hall goes the Hall's way below, as before the hub's parties (audit,
        // 25 Nov 2026: they were refused instead of shown their team)
        boolean hallTeam = party.isPresent() && party.get().size() == 1 && LobbyService.lobbyOf(player.getUUID()).isPresent();
        if (party.isPresent() && !hallTeam && TowerRuns.forPlayer(player.getUUID()).isEmpty()) {
            PartyService.walkIn(server, new PartyService.Hub(level, origin, HubLayout.load()), player, portal, party.get());
            return;
        }
        // a player with no party and no team goes in alone, at the gate's defaults: no screen in the hub (owner, 25 Nov 2026)
        if (party.isEmpty() && LobbyService.lobbyOf(player.getUUID()).isEmpty() && TowerRuns.forPlayer(player.getUUID()).isEmpty()) {
            // a brand-new player's first walk-in is the Tutorial Tower instead (owner, 25 Nov 2026)
            if (com.cobbletowers.tutorial.Tutorial.firstTimer(server, player.getUUID())) {
                com.cobbletowers.tutorial.Tutorial.start(server, player, true)
                        .ifPresent(why -> player.sendSystemMessage(Component.literal(why)));
                return;
            }
            PartyService.walkInAlone(server, new PartyService.Hub(level, origin, HubLayout.load()), player, portal);
            return;
        }
        com.cobbletowers.webgui.WebGuiBridge.userAsked(player);
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        Action action = decide(TowerRuns.forPlayer(player.getUUID()).isPresent(), lobby.isPresent(),
                lobby.map(l -> l.host().equals(player.getUUID())).orElse(false),
                lobby.map(l -> l.tower().equals(portal.tower())).orElse(false));
        switch (action) {
            case IN_RUN -> player.sendSystemMessage(Component.literal("You are already in a tower run."));
            case OPEN_TOWER -> com.cobbletowers.menu.TowerHallService.open(server, player, "", portal.tower());
            case OTHER_TOWER -> {
                player.sendSystemMessage(Component.literal("Your team is set for " + towerName(lobby.get().tower())
                        + ". Use that tower's gate, or leave the team first."));
                LobbyService.openScreen(server, player);
            }
            case OPEN_LOBBY -> {
                player.sendSystemMessage(Component.literal("Your host starts the run from this gate."));
                LobbyService.openScreen(server, player);
            }
            case START -> {
                String said = LobbyService.start(server, player);
                player.sendSystemMessage(Component.literal(said));
                if (!lobby.get().counting()) LobbyService.openScreen(server, player);
            }
        }
    }

    /** Set down on the forecourt facing the plaza: nobody stands in a portal. */
    private static void stepBack(ServerLevel level, BlockPos origin, ServerPlayer player, HubLayout.Portal portal) {
        HubLayout.Spot back = portal.back();
        player.teleportTo(level, origin.getX() + back.x() + 0.5, origin.getY() + back.y(), origin.getZ() + back.z() + 0.5,
                back.yaw(), 0.0f);
    }

    private static String towerName(net.minecraft.resources.ResourceLocation tower) {
        var definition = com.cobbletowers.definition.TowerDefinitionRegistry.content().towers().get(tower);
        return definition == null ? tower.toString() : definition.displayName();
    }

    // ---- the veils -------------------------------------------------------------------------------------------------------------

    /** (Re)draws a veil in every portal of the built hub whose chunk is loaded; the rest are drawn when their chunk comes back. */
    public static void showVeils(MinecraftServer server) {
        clearVeils();
        Optional<HubStore.Placed> placed = HubStore.get(server).placed();
        if (placed.isEmpty()) return;
        Optional<ServerLevel> level = level(server, placed.get());
        if (level.isEmpty()) {
            TowerLog.warn("The Tower Hub's world {} is not loaded; its portals have no veils", placed.get().dimension());
            return;
        }
        keepVeils(level.get(), placed.get().origin());
    }

    /** Draws any veil that is missing or went with its chunk, where that chunk is loaded now. */
    private static void keepVeils(ServerLevel level, BlockPos origin) {
        List<HubLayout.Portal> portals = everyPortal(HubLayout.load());
        while (VEILS.size() < portals.size()) VEILS.add(null);
        int drawn = 0;
        for (int i = 0; i < portals.size(); i++) {
            PortalVeil veil = VEILS.get(i);
            if (veil != null && veil.getAttachment() != null) continue;
            HubLayout.Portal portal = portals.get(i);
            BlockPos at = origin.offset((portal.minX() + portal.maxX()) / 2, portal.minY(), (portal.minZ() + portal.maxZ()) / 2);
            if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) continue;
            VEILS.set(i, new PortalVeil(level, origin, portal));
            drawn++;
        }
        if (drawn > 0) TowerLog.info("Tower Hub: drew {} portal veil(s)", drawn);
    }

    static void clearVeils() {
        for (PortalVeil veil : VEILS) if (veil != null) veil.destroy();
        VEILS.clear();
    }
}
