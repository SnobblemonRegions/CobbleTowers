package com.cobbletowers.network;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.VendorServiceDefinition;
import com.cobbletowers.economy.VendorPurchaseService;
import com.cobbletowers.intermission.IntermissionService;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.runtime.ParticipantService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** CobbleTowers' networking channel (P11, P12), registered once for the life of the JVM. */
public final class TowerNetworking {

    private TowerNetworking() {}

    /**
     * Common to both sides: a payload must be registered wherever it is encoded or decoded, so this runs from {@code
     * CobbleTowers.onInitialize()}.
     */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(TowerFeatureState.TYPE, TowerFeatureState.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(TowerHallStatePayload.TYPE, TowerHallStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(TrackStatePayload.TYPE, TrackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SpectatorPanelPayload.TYPE, SpectatorPanelPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(HauntedRareTrayPayload.TYPE, HauntedRareTrayPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RewardRevealPayload.TYPE, RewardRevealPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(VendorCatalogPayload.TYPE, VendorCatalogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ScoutingRevealPayload.TYPE, ScoutingRevealPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlayStatePayload.TYPE, PlayStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RegistrationStatePayload.TYPE, RegistrationStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(IntermissionStatePayload.TYPE, IntermissionStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ArmorSetsPayload.TYPE, ArmorSetsPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(MasteryScreenPayload.TYPE, MasteryScreenPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RanksStatePayload.TYPE, RanksStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RentalDraftPayload.TYPE, RentalDraftPayload.STREAM_CODEC);
    }

    /** The server-side half: the rate limiter's upkeep. Screen actions arrive through the WebGUI bridge, not a native channel. */
    public static void installServerReceivers() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.execute(() -> ACTION_RATE.forget(id));
        });
        com.cobbletowers.ServerState.onStop(ACTION_RATE::clear);
    }

    private static final ActionRate ACTION_RATE = new ActionRate();

    /** Hands a client's action to the server thread, where it is taken like any other. */
    private static void queue(MinecraftServer server, ServerPlayer player, Runnable action) {
        server.execute(() -> guarded(player, action));
    }

    /**
     * A screen action from a client, whichever transport it came by: rate-limited, and a fault ends that action, not the
     * tick. A modified client is untrusted; the handlers still check everything themselves.
     */
    private static void guarded(ServerPlayer player, Runnable action) {
        switch (ACTION_RATE.check(player.getUUID(), System.currentTimeMillis())) {
            case DROP -> { return; }
            case DROP_AND_SAY -> {
                TowerLog.warn("Dropping screen actions from {}: more than {} a second", player.getGameProfile().getName(),
                        (int) ActionRate.PER_SECOND);
                return;
            }
            case ALLOW -> { }
        }
        try {
            action.run();
        } catch (RuntimeException ex) {
            TowerLog.error("A screen action from " + player.getUUID() + " failed", ex);
        }
    }

    /**
     * The server half of any screen action, whichever transport it arrived on. The WebGUI bridge ends here, so a click on a web page is handled by exactly the code a native screen's click was.
     */
    public static void handleAction(MinecraftServer server, ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload action) {
        guarded(player, () -> dispatchAction(server, player, action));
    }

    private static void dispatchAction(MinecraftServer server, ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload action) {
        if (action instanceof IntermissionActionPayload a) handleIntermissionAction(server, player, a);
        else if (action instanceof PlayActionPayload a) handlePlayAction(server, player, a);
        else if (action instanceof RentalDraftActionPayload a) com.cobbletowers.lobby.RentalDraftService.handle(server, player, a);
        else if (action instanceof TowerHallActionPayload a) com.cobbletowers.menu.TowerHallService.handle(server, player, a);
        else if (action instanceof TowerFeatureRequest a) com.cobbletowers.menu.TowerFeatureService.handle(server, player, a);
        else if (action instanceof TrackActionPayload a) com.cobbletowers.track.TrackService.handle(server, player, a);
        else if (action instanceof VendorPurchasePayload a) handlePurchase(server, player, a);
        else if (action instanceof MasteryRequestPayload a) MasteryScreens.handleRequest(server, player, a);
        else if (action instanceof RanksRequestPayload a) RanksScreens.handleRequest(server, player, a);
        else TowerLog.warn("handleAction was given {}, which is not a screen action", action.getClass().getSimpleName());
    }

    /** The play screen's buttons: each does what the matching {@code /cobbletowers play} subcommand does. */
    private static void handlePlayAction(MinecraftServer server, ServerPlayer player, PlayActionPayload payload) {
        String reply;
        boolean keepScreen = false;
        try {
            reply = switch (payload.action()) {
                case SELECT_TOWER -> LobbyService.select(server, player, ResourceLocation.parse(payload.argument()));
                case INVITE -> {
                    ServerPlayer target = server.getPlayerList().getPlayerByName(payload.argument());
                    yield target == null ? "No player named " + payload.argument() + " is online."
                            : LobbyService.invite(server, player, target);
                }
                case ACCEPT, DECLINE -> {
                    ServerPlayer host = server.getPlayerList().getPlayerByName(payload.argument());
                    if (host == null) yield "That team no longer exists.";
                    if (payload.action() == PlayActionPayload.Action.ACCEPT) {
                        String joined = LobbyService.accept(server, player, host.getUUID());
                        // Accepting into a rental lobby opens the draft screen; reopening the play screen would cover
                        // it.
                        keepScreen = LobbyService.lobbyOf(player.getUUID())
                                .filter(lobby -> com.cobbletowers.lobby.RentalDraftService.opensScreen(player, lobby)).isPresent();
                        yield joined;
                    }
                    yield LobbyService.decline(server, player, host.getUUID());
                }
                case START -> LobbyService.start(server, player);
                case LEAVE -> LobbyService.leave(server, player);
                case REFRESH -> "";
                case OPEN_CHOOSER -> {
                    LobbyService.sendRegistration(server, player, "", true);
                    yield "";
                }
                case TOGGLE_POKEMON -> LobbyService.choose(server, player, java.util.UUID.fromString(payload.argument()));
                case CLEAR_CHOICE -> LobbyService.clearChoice(server, player);
                case SET_ASCENSION -> LobbyService.setAscension(server, player, Integer.parseInt(payload.argument()));
                case SET_PLAYLIST -> LobbyService.setPlaylist(server, player, payload.argument());
                case CONFIRM_MODE -> {
                    String confirmed = LobbyService.confirmMode(server, player);
                    // Confirming a rental mode opens everyone's draft, the host's included; the play screen must not
                    // cover it.
                    keepScreen = LobbyService.lobbyOf(player.getUUID())
                            .filter(lobby -> com.cobbletowers.lobby.RentalDraftService.opensScreen(player, lobby)).isPresent();
                    yield confirmed;
                }
                case READY -> LobbyService.ready(server, player, true);
                case UNREADY -> LobbyService.ready(server, player, false);
            };
        } catch (RuntimeException ex) {
            // A malformed id or similar from a modified client: refused, never trusted, never fatal.
            TowerLog.warn("Play action {} from {} was refused: {}", payload.action(), player.getUUID(), ex.toString());
            reply = "That did not work.";
        }
        if (keepScreen) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(reply));
        else LobbyService.openScreenWithMessage(server, player, reply);
    }

    /** The intermission screen's buttons: each does what the matching {@code /cobbletowers play} subcommand does. */
    private static void handleIntermissionAction(MinecraftServer server, ServerPlayer player,
                                                 IntermissionActionPayload payload) {
        String reply = switch (payload.action()) {
            case PICK_CARD -> IntermissionService.pick(server, player, payload.argument());
            case READY -> IntermissionService.ready(server, player, true);
            case UNREADY -> IntermissionService.ready(server, player, false);
            case CASH_OUT -> IntermissionService.cashOut(server, player, true);
            case STAY -> IntermissionService.cashOut(server, player, false);
            case VENDOR -> IntermissionService.vendor(server, player);
            case REFRESH -> "";
        };
        if (payload.action() != IntermissionActionPayload.Action.VENDOR) {
            IntermissionService.openScreen(server, player, reply);
        }
    }

    private static void handlePurchase(MinecraftServer server, ServerPlayer buyer, VendorPurchasePayload payload) {
        Optional<PersistedRun> run = ParticipantService.runOf(buyer.getUUID());
        if (run.isEmpty()) {
            TowerLog.warn("Player {} asked to buy {} while not in a run; ignored", buyer.getUUID(), payload.serviceId());
            return;
        }
        VendorPurchaseService.Result result = VendorPurchaseService.purchase(
                server, run.get().runId(), buyer.getUUID(), payload.targetPlayerId(), payload.serviceId());
        if (result != VendorPurchaseService.Result.SUCCESS) {
            TowerLog.info("Player {} could not buy {}: {}", buyer.getUUID(), payload.serviceId(), result);
        }
        String targetName = nameOf(server, payload.targetPlayerId());
        String message = VendorPurchaseService.describe(result, targetName, buyer.getUUID().equals(payload.targetPlayerId()));
        if (result == VendorPurchaseService.Result.SUCCESS && !buyer.getUUID().equals(payload.targetPlayerId())) {
            // The teammate is told who paid, since their party just changed under them (TDS #18).
            ServerPlayer target = server.getPlayerList().getPlayer(payload.targetPlayerId());
            if (target != null) {
                target.sendSystemMessage(Component.literal(buyer.getGameProfile().getName() + " bought a service for you."));
            }
        }
        // Refreshed either way: a failed purchase (sold out, insufficient funds) still needs the
        // caller's screen to show the balance and remaining-purchase count it actually has now.
        ParticipantService.runOf(buyer.getUUID())
                .ifPresent(current -> sendVendorCatalog(server, buyer, current, message));
        if (result == VendorPurchaseService.Result.SUCCESS) {
            // The party's health or status may have changed (a heal): the intermission screen behind the vendor shows it, so it is sent again,
            // to the buyer and to the teammate who got the service.
            IntermissionService.refresh(server, buyer);
            ServerPlayer target = server.getPlayerList().getPlayer(payload.targetPlayerId());
            if (target != null && !target.getUUID().equals(buyer.getUUID())) IntermissionService.refresh(server, target);
        }
    }

    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? playerId.toString().substring(0, 8) : player.getGameProfile().getName();
    }

    /** The catalog for one player's own run, sent on request (the vendor command) or after a purchase. */
    public static void sendVendorCatalog(MinecraftServer server, ServerPlayer player, PersistedRun run) {
        sendVendorCatalog(server, player, run, "");
    }

    public static void sendVendorCatalog(MinecraftServer server, ServerPlayer player, PersistedRun run, String message) {
        TowerContent content = TowerDefinitionRegistry.content();
        long balance = TowerWalletStore.get(server).balanceOf(player.getUUID());
        List<VendorCatalogPayload.Entry> entries = content.vendorCatalog().stream()
                .map(service -> catalogEntry(service, run, player))
                .toList();
        // Everyone still in the run, the caller first: the picker defaults to "yourself".
        List<VendorCatalogPayload.Teammate> team = new java.util.ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().isInRun()) continue;
            ServerPlayer member = server.getPlayerList().getPlayer(participant.playerId());
            VendorCatalogPayload.Teammate entry = new VendorCatalogPayload.Teammate(participant.playerId(),
                    nameOf(server, participant.playerId()), member != null);
            if (participant.playerId().equals(player.getUUID())) team.add(0, entry);
            else team.add(entry);
        }
        sendVendorCatalog(player, new VendorCatalogPayload(balance, entries, team, message));
    }

    private static VendorCatalogPayload.Entry catalogEntry(VendorServiceDefinition service, PersistedRun run,
                                                          ServerPlayer player) {
        int cap = service.maxPurchasesPerRun();
        int remaining = cap <= 0 ? -1 : Math.max(0, cap - run.purchasesOf(service.id()));
        return new VendorCatalogPayload.Entry(service.id(), service.displayName(),
                com.cobbletowers.armor.ArmorBonusEffects.vendorPrice(player, service.priceCobbleDollars()), remaining);
    }

    private static void sendVendorCatalog(ServerPlayer player, VendorCatalogPayload payload) {
        if (ClientUi.canShow(player, VendorCatalogPayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Never sent to a client that has not registered the channel -- see {@code RewardDelivery}'s chat fallback. */
    public static void sendRewardReveal(ServerPlayer player, RewardRevealPayload payload) {
        if (ClientUi.canShow(player, RewardRevealPayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Silently does nothing for a client that never registered the channel. */
    public static void sendSpectatorPanel(ServerPlayer player, SpectatorPanelPayload payload) {
        if (ClientUi.canShow(player, SpectatorPanelPayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Silently does nothing for a client that never registered the channel (the commands still work). */
    public static void sendIntermissionState(ServerPlayer player, IntermissionStatePayload payload) {
        if (ClientUi.canShow(player, IntermissionStatePayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Silently does nothing for a client that never registered the channel (the commands still work). */
    public static void sendRegistration(ServerPlayer player, RegistrationStatePayload payload) {
        if (ClientUi.canShow(player, RegistrationStatePayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Silently does nothing for a client that never registered the channel (the commands still work). */
    public static void sendPlayState(ServerPlayer player, PlayStatePayload payload) {
        if (ClientUi.canShow(player, PlayStatePayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }

    /** Silently does nothing for a client that never registered the channel. */
    public static void sendScoutingReveal(ServerPlayer player, ScoutingRevealPayload payload) {
        if (ClientUi.canShow(player, ScoutingRevealPayload.TYPE)) {
            ClientUi.send(player, payload);
        }
    }
}
