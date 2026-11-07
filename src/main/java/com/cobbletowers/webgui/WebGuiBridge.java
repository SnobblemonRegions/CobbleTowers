package com.cobbletowers.webgui;

import com.cobbletowers.TowerLog;
import com.cobbletowers.network.IntermissionStatePayload;
import com.cobbletowers.network.MasteryScreenPayload;
import com.cobbletowers.network.PlayStatePayload;
import com.cobbletowers.network.RanksStatePayload;
import com.cobbletowers.network.RegistrationStatePayload;
import com.cobbletowers.network.SpectatorPanelPayload;
import com.cobbletowers.network.RentalDraftPayload;
import com.cobbletowers.network.RewardRevealPayload;
import com.cobbletowers.network.ScoutingRevealPayload;
import com.cobbletowers.network.TowerFeatureState;
import com.cobbletowers.network.TowerHallStatePayload;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.network.TrackStatePayload;
import com.cobbletowers.network.VendorCatalogPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets the WebGUI mod stand in for CobbleTowers' own client screens (docs/design/webgui-bridge.md).
 *
 * <p>Nothing about the game changes: every screen already has a state payload the server builds and an action payload the
 * server handles. This only changes the transport. A state payload is sent to the player's WebGUI page as JSON instead of
 * to a native screen, and the page's reply is decoded back into the same action payload and given to the same handler.
 * A player with neither client gets what they always got: chat.
 *
 * <p>Everything here runs on the server thread. Without WebGUI installed, or with the config off, {@link #takesOver} is
 * always false and no WebGUI class is ever loaded.
 */
public final class WebGuiBridge {

    /** The handshake channel WebGUI's client registers; its presence is how we know a player can see a page. */
    /** WebGUI refuses an emitted payload longer than this, so a longer one is dropped here with a warning. */
    private static final int MAX_EMIT = 32_000;
    /** How long a page just opened is given to load and say hello before it is considered lost. */
    private static final long LOAD_MILLIS = 6_000;

    /**
     * What the page can show today (P44, one entry per screen built). Anything else keeps its old behaviour: a native screen if
     * the client has one, else chat. Add a payload here in the same change that adds its screen to {@code web/}.
     */
    private static final Set<CustomPacketPayload.Type<?>> SUPPORTED = Set.of(
            IntermissionStatePayload.TYPE, RentalDraftPayload.TYPE, RewardRevealPayload.TYPE, VendorCatalogPayload.TYPE,
            TowerHallStatePayload.TYPE, PlayStatePayload.TYPE, TowerFeatureState.TYPE, TrackStatePayload.TYPE,
            RegistrationStatePayload.TYPE, MasteryScreenPayload.TYPE, RanksStatePayload.TYPE,
            ScoutingRevealPayload.TYPE, SpectatorPanelPayload.TYPE, com.cobbletowers.network.HauntedRareTrayPayload.TYPE);

    private static volatile WebGuiConfig config = WebGuiConfig.OFF;
    private static volatile boolean linked;
    private static final Map<UUID, PageSession> SESSIONS = new ConcurrentHashMap<>();
    /** The player whose page message is being handled right now (server thread only); null otherwise. */
    private static UUID actingPlayer;

    private WebGuiBridge() {}

    /** Called once from the mod entrypoint. */
    public static void install() {
        WebGuiConfig loaded = WebGuiConfig.load();
        if (!loaded.enabled()) return;
        if (!WebGuiLink.installed()) {
            TowerLog.warn("cobbletowers-webgui.json is enabled but neither the Snobblemon bridge nor the WebGUI mod is installed; tower screens stay native");
            return;
        }
        try {
            WebGuiLink.install();
        } catch (LinkageError | RuntimeException ex) {
            // A bridge or WebGUI too old (or too new) to have the API we use: say so once and stay native, never break startup.
            TowerLog.error("The Snobblemon bridge or WebGUI is installed but its API did not match ({}); tower screens stay native", ex.toString());
            return;
        }
        // the bridge draws the screens itself from its own layouts, so there is no web bundle to put on disk
        if (!WebGuiLink.direct()) WebUiFiles.install();
        config = loaded;
        linked = true;
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SESSIONS.remove(handler.getPlayer().getUUID());
            LAST_TOO_LARGE.remove(handler.getPlayer().getUUID());
            LAST_PONG.remove(handler.getPlayer().getUUID());
        });
        TowerLog.info("Tower screens can be shown through {} at {} ({} mode)", WebGuiLink.direct() ? "the Snobblemon bridge" : "WebGUI", loaded.url(), loaded.mode());
    }

    // ---- the decision
    // -------------------------------------------------------------------------------------------------

    /**
     * Whether the WebGUI page, rather than a native screen or chat, shows this payload type to this player. In
     * {@code fallback} mode that is only a player with no native channel for it; in {@code prefer} mode, every player who has
     * WebGUI.
     */
    public static boolean takesOver(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        if (!linked || !SUPPORTED.contains(type)) return false;
        if (!WebGuiLink.clientReady(player)) return false;
        return config.mode() == WebGuiConfig.Mode.PREFER || !ServerPlayNetworking.canSend(player, type);
    }

    private static PageSession.Policy policyOf(CustomPacketPayload payload) {
        if (payload instanceof RewardRevealPayload || payload instanceof ScoutingRevealPayload) return PageSession.Policy.REPLACE;
        if (payload instanceof IntermissionStatePayload || payload instanceof PlayStatePayload
                || payload instanceof RegistrationStatePayload || payload instanceof MasteryScreenPayload) return PageSession.Policy.UPDATE_OR_OPEN_IF_FLAGGED;
        if (payload instanceof TrackStatePayload || payload instanceof RanksStatePayload) return PageSession.Policy.UPDATE_ONLY;
        // taking the tray down must never open a page just to close it
        if (payload instanceof com.cobbletowers.network.HauntedRareTrayPayload tray && !tray.open()) return PageSession.Policy.UPDATE_ONLY;
        return PageSession.Policy.UPDATE_OR_OPEN;
    }

    private static boolean flagged(CustomPacketPayload payload) {
        if (payload instanceof IntermissionStatePayload p) return p.open();
        if (payload instanceof PlayStatePayload p) return p.open();
        if (payload instanceof RegistrationStatePayload p) return p.open();
        if (payload instanceof MasteryScreenPayload p) return p.open();
        return true;
    }

    // ---- server to page
    // ----------------------------------------------------------------------------------------------

    /** The player asked for a screen by command: the next screen payload opens their page even if it looks open. */
    public static void userAsked(ServerPlayer player) {
        if (!linked) return;
        SESSIONS.computeIfAbsent(player.getUUID(), id -> new PageSession()).askOpen(System.currentTimeMillis());
    }

    private static final Map<UUID, Long> LAST_TOO_LARGE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Says so in chat when a screen was dropped for size, at most once every 30 s per player, so it is not a silent blank. */
    private static void tellTooLarge(ServerPlayer player, String kind) {
        long now = System.currentTimeMillis();
        Long last = LAST_TOO_LARGE.get(player.getUUID());
        if (last != null && now - last < 30_000) return;
        LAST_TOO_LARGE.put(player.getUUID(), now);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "The " + kind + " screen is too large to show. Please tell a server admin (it is in the log).")
                .withStyle(net.minecraft.ChatFormatting.RED));
    }

    /**
     * Sends a state payload to the player's page if the page is to show it. Returns {@code true} when the bridge handled it
     * (including when it deliberately dropped it), so the caller sends nothing natively.
     */
    public static boolean deliver(ServerPlayer player, CustomPacketPayload payload) {
        if (!takesOver(player, payload.type())) return false;
        String json = PayloadJson.envelope(payload).toString();
        if (json.length() > MAX_EMIT) {
            TowerLog.warn("A {} for {} is {} characters, over WebGUI's limit; not sent", payload.getClass().getSimpleName(),
                    player.getGameProfile().getName(), json.length());
            tellTooLarge(player, PayloadJson.kindOf(payload.getClass()));
            return true;
        }
        long now = System.currentTimeMillis();
        PageSession session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new PageSession());
        boolean hud = payload instanceof SpectatorPanelPayload || payload instanceof com.cobbletowers.network.HauntedRareTrayPayload;
        // The completion reveal of AscensionLib is on screen (it opens in the same tick a cycle's champion falls): do not cover it. Our page is not showing,
        // so this only holds back an open, and the state is kept for the next payload or the player's own request.
        if (!hud && !session.alive(false, now, config.heartbeatSeconds()) && WebGuiLink.otherModScreenShowing(player)) {
            session.hold(PayloadJson.kindOf(payload.getClass()), json, now);
            if (flagged(payload)) TowerLog.info("WebGUI {} for {}: held back, a completion reveal is showing", PayloadJson.kindOf(payload.getClass()), player.getGameProfile().getName());
            return true;
        }
        // An answer to a click on the page goes to the open page as an update: reopening it on every click reloads it.
        boolean fromPage = player.getUUID().equals(actingPlayer);
        PageSession.Action action = session.offer(PayloadJson.kindOf(payload.getClass()), json, hud, policyOf(payload), flagged(payload), fromPage, now,
                config.heartbeatSeconds(), LOAD_MILLIS);
        if (flagged(payload) && !hud) TowerLog.info("WebGUI {} for {}: {}", PayloadJson.kindOf(payload.getClass()), player.getGameProfile().getName(), action);
        switch (action) {
            case EMIT -> WebGuiLink.emit(player, json, hud);
            // The page is not loaded when the open arrives, so nothing is emitted now: it asks for what it missed with a
            // `hello` as soon as it has loaded (see onPage).
            case OPEN -> {
                if (hud) WebGuiLink.openHud(player, config.url() + "#hud");
                else WebGuiLink.openGui(player, config.url());
            }
            case NOTHING -> { }
        }
        return true;
    }

    /** Clear only our panel, including a HUD that has not finished loading. Never close another mod's page. */
    public static void clearSpectatorPanel(ServerPlayer player) {
        if (!takesOver(player, SpectatorPanelPayload.TYPE)) return;
        String json = "{\"kind\":\"SpectatorPanel\",\"data\":null}";
        SESSIONS.computeIfAbsent(player.getUUID(), id -> new PageSession())
                .hold(PageSession.HUD_KIND, json, System.currentTimeMillis());
        WebGuiLink.emit(player, json, true);
    }

    // ---- page to server
    // ----------------------------------------------------------------------------------------------

    private static final Map<UUID, Long> LAST_PONG = new ConcurrentHashMap<>();
    /** The page says it has lost the server when it hears nothing for a while, so the server says something every few seconds. */
    private static final long PONG_MILLIS = 4_000;

    private static void pong(ServerPlayer player, long now) {
        Long last = LAST_PONG.get(player.getUUID());
        if (last != null && now - last < PONG_MILLIS) return;
        LAST_PONG.put(player.getUUID(), now);
        WebGuiLink.emit(player, "{\"kind\":\"Pong\",\"data\":{}}");
    }

    /** Every message a page posts on the {@code cobbletowers} channel. Runs on the server thread. */
    static void onPage(ServerPlayer player, String raw) {
        JsonObject message;
        try {
            message = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        String kind = message.has("kind") && message.get("kind").isJsonPrimitive() ? message.get("kind").getAsString() : "";
        boolean hud = message.has("hud") && message.get("hud").isJsonPrimitive() && message.get("hud").getAsBoolean();
        long now = System.currentTimeMillis();
        switch (kind) {
            // the bridge's overlay page says hello with its own kind, since its layout cannot add the page's {"hud": true}
            case "hello", "hud_hello" -> {
                boolean overlay = hud || "hud_hello".equals(kind);
                for (String missed : SESSIONS.computeIfAbsent(player.getUUID(), id -> new PageSession()).hello(overlay, now)) {
                    WebGuiLink.emit(player, missed, overlay);
                }
            }
            case "ping" -> {
                SESSIONS.computeIfAbsent(player.getUUID(), id -> new PageSession()).ping(hud, now);
                if (!hud) pong(player, now);
            }
            case "bye" -> {
                PageSession session = SESSIONS.get(player.getUUID());
                if (session != null) session.bye(hud);
            }
            default -> {
                actingPlayer = player.getUUID();
                try {
                    PageActions.decode(message).ifPresentOrElse(
                            action -> TowerNetworking.handleAction(player.server, player, action),
                            () -> TowerLog.warn("Ignored an unreadable page message from {}: kind '{}'", player.getGameProfile().getName(), kind));
                } finally {
                    actingPlayer = null;
                }
            }
        }
    }
}
