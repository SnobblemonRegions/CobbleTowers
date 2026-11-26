package com.cobbletowers.webgui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The only class that talks to the thing that shows the tower screens, and it does so by looking up that mod's public API methods by name at
 * run time, so CobbleTowers neither compiles against it nor needs it present: no build dependency, no class that fails to link on a server
 * without it, and no coupling to the Loom version it happens to be built with.
 *
 * <p>That is Snobblemon GUI (SnobbleGUIOverhaul's server mod, {@code snobblemon_server}): {@code SnobbleUi.open}, {@code openHud}, {@code emitJson} and
 * {@code onMessage}, with the page named by the first part of the configured URL ({@code cobbletowers}). {@link #install()} throws if it is missing a
 * method, which {@link WebGuiBridge#install()} turns into "stay native".
 */
final class WebGuiLink {

    /** The channel the tower page's messages arrive on. */
    static final String CHANNEL = "cobbletowers";
    /** The event the tower page's payloads are sent as. */
    static final String EVENT = "tower";

    /** Whether the Snobblemon bridge's handles (the {@code b*} ones) are bound. */
    private static boolean direct;
    private static MethodHandle bAvailable;
    private static MethodHandle bOpen;
    private static MethodHandle bOpenHud;
    private static MethodHandle bIsHudLayout;
    private static MethodHandle bOpenPage;
    private static MethodHandle bHudPage;
    private static MethodHandle bEmitJson;

    private static final String BRIDGE_MOD = "snobblemon_server";
    private static final String BRIDGE_CLASS = "net.snobblemon.server.SnobbleUi";

    private WebGuiLink() {}

    /** Whether the Snobblemon bridge is installed. */
    static boolean bridgeInstalled() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(BRIDGE_MOD);
    }

    /** Whether something that can show the screens is installed: the bridge. */
    static boolean installed() {
        return bridgeInstalled();
    }

    /** Whether the bridge is bound and shows the screens (known once {@link #install()} has run). */
    static boolean direct() {
        return direct;
    }

    /**
     * Whether this player's game can show a page: with the bridge, it has the Snobblemon Client and the server's art; with WebGUI, it announced WebGUI's hello
     * channel.
     */
    static boolean clientReady(ServerPlayer player) {
        if (!direct) return false;
        try {
            return (boolean) bAvailable.invokeExact(player);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** The bridge's page id for a configured URL such as {@code webgui://cobbletowers/index.html} (or {@code webgui:/cobbletowers/...}): its first path part. */
    static String pageId(String url) {
        String rest = url.replaceFirst("^[a-zA-Z]+:/+", "");
        int cut = rest.indexOf('/');
        return cut < 0 ? rest : rest.substring(0, cut);
    }

    /**
     * The bridge page a HUD request ({@code <url>#hud}) is shown on: the tower page's own overlay, {@code <page>_hud}, so the player keeps the mouse (the Haunted
     * rare tray is clicked through to the vault). The suffix is the convention of the bridge's layouts.
     */
    static String hudPageId(String url) {
        return pageId(url) + "_hud";
    }

    private static void installDirect() throws Throwable {
        Class<?> api = Class.forName(BRIDGE_CLASS);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        bAvailable = lookup.findStatic(api, "available", MethodType.methodType(boolean.class, ServerPlayer.class));
        bOpen = lookup.findStatic(api, "open", MethodType.methodType(boolean.class, ServerPlayer.class, String.class));
        bOpenHud = lookup.findStatic(api, "openHud", MethodType.methodType(boolean.class, ServerPlayer.class, String.class));
        bIsHudLayout = lookup.findStatic(api, "isHudLayout", MethodType.methodType(boolean.class, String.class));
        bOpenPage = lookup.findStatic(api, "openPage", MethodType.methodType(String.class, ServerPlayer.class));
        bHudPage = lookup.findStatic(api, "hudPage", MethodType.methodType(String.class, ServerPlayer.class));
        bEmitJson = lookup.findStatic(api, "emitJson", MethodType.methodType(void.class, ServerPlayer.class, String.class, String.class, String.class));
        MethodHandle onMessage = lookup.findStatic(api, "onMessage", MethodType.methodType(void.class, String.class, BiConsumer.class));
        BiConsumer<ServerPlayer, String> handler = WebGuiBridge::onPage;
        onMessage.invoke(CHANNEL, handler);
        direct = true;
    }

    static void install() {
        try {
            installDirect();
        } catch (Throwable ex) {
            throw new IllegalStateException("The Snobblemon bridge's SnobbleUi is not what this build expects: " + ex, ex);
        }
    }

    private static boolean revealLooked;
    private static MethodHandle revealShowing;

    /**
     * Whether AscensionLib's completion reveal is on this player's screen. Looked up once by name, so an older or absent AscensionLib simply answers
     * no. While it is up, a screen of ours would cover it, so {@link WebGuiBridge} keeps its payloads for later instead of opening a page.
     */
    static boolean otherModScreenShowing(ServerPlayer player) {
        if (!revealLooked) {
            revealLooked = true;
            try {
                Class<?> screens = Class.forName("com.ascensionlib.AscensionScreens");
                revealShowing = MethodHandles.publicLookup().findStatic(screens, "revealShowing",
                        MethodType.methodType(boolean.class, java.util.UUID.class));
            } catch (ReflectiveOperationException | LinkageError ex) {
                revealShowing = null;
            }
        }
        if (revealShowing == null) return false;
        try {
            return (boolean) revealShowing.invoke(player.getUUID());
        } catch (Throwable ex) {
            return false;
        }
    }

    static void openGui(ServerPlayer player, String url) {
        try {
            if (direct) {
                boolean shown = (boolean) bOpen.invokeExact(player, pageId(url));
                if (!shown) com.cobbletowers.TowerLog.warn("No Snobblemon page '{}' to show {} (or {} has no Snobblemon Client)", pageId(url), url, player.getGameProfile().getName());
            }
        } catch (Throwable ex) {
            throw rethrow(ex);
        }
    }

    static void openHud(ServerPlayer player, String url) {
        try {
            if (direct) {
                // the tower page's overlay when the bridge has one; otherwise the full page
                String hud = hudPageId(url);
                boolean overlay = (boolean) bIsHudLayout.invokeExact(hud);
                String id = overlay ? hud : pageId(url);
                boolean shown = overlay ? (boolean) bOpenHud.invokeExact(player, id) : (boolean) bOpen.invokeExact(player, id);
                if (!shown) com.cobbletowers.TowerLog.warn("No Snobblemon page '{}' to show {} (or {} has no Snobblemon Client)", id, url, player.getGameProfile().getName());
            }
        } catch (Throwable ex) {
            throw rethrow(ex);
        }
    }

    static void emit(ServerPlayer player, String json) {
        emit(player, json, false);
    }

    /** @param hud whether this is for the overlay (the spectator panel, the Haunted rare tray) rather than the full page */
    static void emit(ServerPlayer player, String json, boolean hud) {
        try {
            if (direct) {
                String overlay = hud ? (String) bHudPage.invokeExact(player) : null;
                String page = overlay != null ? overlay : (String) bOpenPage.invokeExact(player);
                if (page != null) bEmitJson.invokeExact(player, page, EVENT, json);
            }
        } catch (Throwable ex) {
            throw rethrow(ex);
        }
    }

    private static RuntimeException rethrow(Throwable ex) {
        if (ex instanceof RuntimeException runtime) return runtime;
        if (ex instanceof Error error) throw error;
        return new IllegalStateException(ex);
    }
}
