package com.cobbletowers.webgui;

import com.cobbletowers.TowerLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/cobbletowers-webgui.json}: whether tower screens may be shown through the WebGUI mod, and where its page
 * lives. Written on first start (on) when the WebGUI mod is present; off when it is not.
 *
 * <pre>{"enabled": true, "url": "webgui://cobbletowers/index.html", "mode": "fallback", "heartbeat_seconds": 8}</pre>
 *
 * {@code mode}: {@code fallback} uses a web page only for a player whose client lacks CobbleTowers' own screens;
 * {@code prefer} uses it for everyone who has WebGUI.
 */
public record WebGuiConfig(boolean enabled, String url, Mode mode, int heartbeatSeconds) {

    public enum Mode { FALLBACK, PREFER }

    public static final String DEFAULT_URL = "webgui://cobbletowers/index.html";
    public static final WebGuiConfig OFF = new WebGuiConfig(false, DEFAULT_URL, Mode.FALLBACK, 8);

    public static final WebGuiConfig DEFAULT_ON = new WebGuiConfig(true, DEFAULT_URL, Mode.PREFER, 8);
    static final String DEFAULT_FILE = "{\n  \"enabled\": true,\n  \"url\": \"" + DEFAULT_URL
            + "\",\n  \"mode\": \"prefer\",\n  \"heartbeat_seconds\": 8\n}\n";

    public static WebGuiConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cobbletowers-webgui.json");
        if (!Files.exists(file)) {
            // No native client any more, so a server with WebGUI gets the pages on first start; edit the file to change it.
            if (!WebGuiLink.installed()) return OFF;
            try {
                Files.writeString(file, DEFAULT_FILE);
                TowerLog.info("Wrote {} (web pages on); edit it to change", file);
            } catch (IOException ex) {
                TowerLog.warn("Could not write {} ({}); using the defaults for this run", file, ex.toString());
            }
            return DEFAULT_ON;
        }
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException ex) {
            TowerLog.error("{} is not usable ({}); tower screens stay native", file, ex.toString());
            return OFF;
        }
    }

    /** Pure so a test can cover it: a bad value never half-enables the bridge, it throws and the caller keeps it off. */
    public static WebGuiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
        String url = root.has("url") ? root.get("url").getAsString().trim() : DEFAULT_URL;
        if (url.isEmpty()) throw new IllegalArgumentException("url is empty");
        if (url.indexOf('#') >= 0) throw new IllegalArgumentException("url must not carry a #fragment; the page chooses its own screen");
        // WebGUI 2 rejects the old single-slash spelling. Keep existing server configs usable on both versions.
        if (url.startsWith("webgui:/") && !url.startsWith("webgui://")) {
            url = "webgui://" + url.substring("webgui:/".length());
        }
        Mode mode = root.has("mode") ? Mode.valueOf(root.get("mode").getAsString().trim().toUpperCase(java.util.Locale.ROOT)) : Mode.FALLBACK;
        int heartbeat = root.has("heartbeat_seconds") ? root.get("heartbeat_seconds").getAsInt() : 8;
        if (heartbeat < 3 || heartbeat > 120) throw new IllegalArgumentException("heartbeat_seconds must be 3..120");
        return new WebGuiConfig(enabled, url, mode, heartbeat);
    }
}
