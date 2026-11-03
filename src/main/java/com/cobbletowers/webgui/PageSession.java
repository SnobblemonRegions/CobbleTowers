package com.cobbletowers.webgui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the server knows about one player's WebGUI pages, and the rules for what to do with a screen payload meanwhile.
 *
 * <p>WebGUI tells the server nothing when a player closes a page (Esc), so "is a page open" is a heartbeat: the page pings
 * every second or two and is considered open while the last ping is recent. A page that was just opened is "loading" until its
 * first {@code hello}; payloads that arrive meanwhile are kept and replayed at the hello rather than sent into a page that
 * is not there yet or used to open it a second time.
 *
 * <p>Plain Java with the clock passed in, so the timeline is unit-testable. Server thread only.
 */
final class PageSession {

    /** The one kind shown on the HUD page; everything else belongs to the full-screen page. */
    static final String HUD_KIND = "SpectatorPanel";
    /** Every kind the HUD page shows (the spectator panel and the Haunted rare tray). */
    static final java.util.Set<String> HUD_KINDS = java.util.Set.of(HUD_KIND, "HauntedRareTray");

    /** What a payload does when it reaches a page, mirroring what the native client does with the same payload. */
    enum Policy {
        /** Replaces whatever is showing (a reward reveal, a scouting report). */
        REPLACE,
        /** Updates the open page, or opens one. */
        UPDATE_OR_OPEN,
        /** Updates the open page, or opens one only if the payload says to. */
        UPDATE_OR_OPEN_IF_FLAGGED,
        /** Updates an open page and does nothing otherwise. */
        UPDATE_ONLY
    }

    /** What the caller must do with the payload it offered. */
    enum Action { EMIT, OPEN, NOTHING }

    private record Stamped(long at, String json) {}

    /** "Has not happened", distinct from time 0 so the clock may read anything. */
    private static final long NEVER = -1;

    /** Two missed pings (the page pings every 1.5 s) and an open request treats the page as closed. */
    private static final int OPEN_BEAT_SECONDS = 3;
    /** How long an ask waits for the screen payload it is for. */
    private static final long ASK_MILLIS = 3_000;

    private final Map<String, Stamped> latest = new LinkedHashMap<>();
    private long guiOpenedAt = NEVER;
    private long hudOpenedAt = NEVER;
    private long pingGui = NEVER;
    private long pingHud = NEVER;
    /** When the player last asked for a screen by command; the next screen payload then opens the page whatever the heartbeat says. */
    private long openAskedAt = NEVER;
    /** Whether the page opened last has said hello yet: after that it is loaded, whether or not it is still open. */
    private boolean guiLoaded;
    private boolean hudLoaded;

    /** Records a payload (so a late page can be caught up) and says what to do with it. */
    Action offer(String kind, String json, boolean hud, Policy policy, boolean flagged, long now, int heartbeatSeconds, long loadMillis) {
        return offer(kind, json, hud, policy, flagged, false, now, heartbeatSeconds, loadMillis);
    }

    /**
     * As above. {@code fromPage}: the payload answers a click on the page itself, so that page is on screen and the
     * answer updates it whatever the payload's open flag or policy say (reopening would reload the page on every click).
     */
    Action offer(String kind, String json, boolean hud, Policy policy, boolean flagged, boolean fromPage, long now, int heartbeatSeconds, long loadMillis) {
        latest.put(kind, new Stamped(now, json));
        if (fromPage && alive(hud, now, heartbeatSeconds)) {
            openAskedAt = NEVER;
            return Action.EMIT;
        }
        // A screen the player just asked for must show: a page closed with Esc may keep pinging, so the heartbeat cannot be trusted.
        boolean asked = !hud && policy != Policy.UPDATE_ONLY && openAskedAt != NEVER && now - openAskedAt < ASK_MILLIS;
        if (asked) openAskedAt = NEVER;
        // A payload that asks to open the page (a /tower or the key) trusts the heartbeat less: Esc sends no goodbye, so
        // a page closed a moment ago still looks alive for the whole heartbeat and the open would be swallowed.
        int beat = flagged && policy == Policy.UPDATE_OR_OPEN_IF_FLAGGED ? Math.min(heartbeatSeconds, OPEN_BEAT_SECONDS) : heartbeatSeconds;
        if (!asked && alive(hud, now, beat) && policy != Policy.REPLACE) return Action.EMIT;
        // A page opened a moment ago is still loading and will ask for everything it missed; opening again would only
        // restart it, and what arrives meanwhile is already stored for that replay.
        if (loading(hud, now, loadMillis)) return Action.NOTHING;
        boolean open = asked || switch (policy) {
            case REPLACE, UPDATE_OR_OPEN -> true;
            case UPDATE_OR_OPEN_IF_FLAGGED -> flagged;
            case UPDATE_ONLY -> false;
        };
        if (!open) return Action.NOTHING;
        if (hud) {
            hudOpenedAt = now;
            pingHud = NEVER;
            hudLoaded = false;
        } else {
            guiOpenedAt = now;
            pingGui = NEVER;
            guiLoaded = false;
        }
        return Action.OPEN;
    }

    /**
     * Keeps a payload without showing it, for a moment when another mod's screen is up and ours must not cover it. Same bookkeeping as {@link #offer}
     * minus the page: the latest of each kind is stored so the page catches up when it does open.
     */
    void hold(String kind, String json, long now) {
        latest.put(kind, new Stamped(now, json));
    }

    /**
     * A freshly loaded page says hello. Returns what it missed while loading: the latest of each kind produced since it was
     * opened. Older ones belong to an earlier screen and would only show stale information. A page nobody opened (the player
     * used {@code /webgui gui} by hand) gets nothing, and asks for its own state.
     */
    List<String> hello(boolean hud, long now) {
        ping(hud, now);
        if (hud) hudLoaded = true;
        else guiLoaded = true;
        long since = hud ? hudOpenedAt : guiOpenedAt;
        List<String> out = new ArrayList<>();
        if (since == NEVER) return out;
        for (Map.Entry<String, Stamped> entry : latest.entrySet()) {
            if (HUD_KINDS.contains(entry.getKey()) != hud) continue;
            if (entry.getValue().at() >= since - 2_000) out.add(entry.getValue().json());
        }
        return out;
    }

    /** The player typed a command that opens a screen: the next screen payload opens the page. */
    void askOpen(long now) {
        openAskedAt = now;
    }

    void ping(boolean hud, long now) {
        if (hud) pingHud = now;
        else pingGui = now;
    }

    /** The page said goodbye (it is closing): not open from this moment, without waiting for the heartbeat to lapse. */
    void bye(boolean hud) {
        if (hud) pingHud = NEVER;
        else pingGui = NEVER;
    }

    boolean alive(boolean hud, long now, int heartbeatSeconds) {
        long last = hud ? pingHud : pingGui;
        return last != NEVER && now - last < heartbeatSeconds * 1000L;
    }

    boolean loading(boolean hud, long now, long loadMillis) {
        long opened = hud ? hudOpenedAt : guiOpenedAt;
        boolean loaded = hud ? hudLoaded : guiLoaded;
        return opened != NEVER && !loaded && now - opened < loadMillis;
    }
}
