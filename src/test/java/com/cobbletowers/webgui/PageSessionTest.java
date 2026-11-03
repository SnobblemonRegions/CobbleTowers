package com.cobbletowers.webgui;

import static com.cobbletowers.webgui.PageSession.Action.EMIT;
import static com.cobbletowers.webgui.PageSession.Action.NOTHING;
import static com.cobbletowers.webgui.PageSession.Action.OPEN;
import static com.cobbletowers.webgui.PageSession.Policy.REPLACE;
import static com.cobbletowers.webgui.PageSession.Policy.UPDATE_ONLY;
import static com.cobbletowers.webgui.PageSession.Policy.UPDATE_OR_OPEN;
import static com.cobbletowers.webgui.PageSession.Policy.UPDATE_OR_OPEN_IF_FLAGGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The open / update / replay rules, as a timeline in milliseconds. Heartbeat 8 s, load window 6 s. */
class PageSessionTest {

    @Test
    void revivalClearsEvenWhenHudIsStillLoading() {
        PageSession pending = new PageSession();
        pending.offer("SpectatorPanel", "following", true, UPDATE_OR_OPEN, true, 100, 8, 6000);
        pending.hold("SpectatorPanel", "cleared", 200);
        assertEquals(List.of("cleared"), pending.hello(true, 300));
        assertEquals(List.of(), pending.hello(false, 300), "HUD clear must never replace a full-screen page");
    }

    @Test
    void nextSpectatorSessionReplacesTheClear() {
        PageSession pending = new PageSession();
        pending.offer("SpectatorPanel", "following", true, UPDATE_OR_OPEN, true, 100, 8, 6000);
        pending.hello(true, 200);
        pending.hold("SpectatorPanel", "cleared", 300);
        assertEquals(EMIT, pending.offer("SpectatorPanel", "next teammate", true, UPDATE_OR_OPEN, true, 400, 8, 6000));
        assertEquals(List.of("next teammate"), pending.hello(true, 500));
    }

    private static final int HEARTBEAT = 8;
    private static final long LOAD = 6_000;

    private final PageSession session = new PageSession();

    private PageSession.Action offer(String kind, String json, boolean hud, PageSession.Policy policy, boolean flagged, long now) {
        return session.offer(kind, json, hud, policy, flagged, now, HEARTBEAT, LOAD);
    }

    @Test
    @DisplayName("an open payload opens the page once; what arrives while it loads is replayed at hello, not re-opened")
    void opensOnceAndReplays() {
        assertEquals(OPEN, offer("IntermissionState", "{open}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 0));
        // a second open-flagged payload while the page loads must not restart it
        assertEquals(NOTHING, offer("RentalDraft", "{draft}", false, UPDATE_OR_OPEN, true, 100));
        assertEquals(NOTHING, offer("IntermissionState", "{update}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 200));

        List<String> caught = session.hello(false, 300);
        assertEquals(List.of("{update}", "{draft}"), caught, "the latest of each kind, in the order they were first seen");
        // now the page is alive: updates go straight to it
        assertEquals(EMIT, offer("IntermissionState", "{later}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 1_000));
    }

    @Test
    @DisplayName("a non-flagged update to a closed page does nothing; a flagged one opens it")
    void flagsDecideWhetherToOpen() {
        assertEquals(NOTHING, offer("IntermissionState", "{a}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 0));
        assertEquals(NOTHING, offer("TrackState", "{t}", false, UPDATE_ONLY, true, 10));
        assertEquals(OPEN, offer("IntermissionState", "{b}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 20));
    }

    @Test
    @DisplayName("an open request reopens a page closed with Esc after 3 s; a plain update still waits out the heartbeat")
    void openRequestDistrustsHeartbeat() {
        offer("IntermissionState", "{a}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 0);
        session.hello(false, 500);
        session.ping(false, 2_000);
        assertEquals(EMIT, offer("IntermissionState", "{b}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 3_500), "1.5 s since the ping: open");
        assertEquals(EMIT, offer("IntermissionState", "{c}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 6_000), "4 s: an update still emits");
        assertEquals(OPEN, offer("IntermissionState", "{d}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 6_000), "4 s: an open request reopens");
    }

    @Test
    @DisplayName("a screen the player asked for by command opens the page even while it still pings, once")
    void askedScreenOpensDespitePings() {
        offer("IntermissionState", "{a}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 0);
        session.hello(false, 500);
        session.ping(false, 9_900);
        session.askOpen(10_000);
        assertEquals(OPEN, offer("TowerHallState", "{hall}", false, UPDATE_OR_OPEN, true, 10_100), "pinging, but asked: open");
        session.hello(false, 10_600);
        assertEquals(EMIT, offer("TowerHallState", "{reply}", false, UPDATE_OR_OPEN, true, 11_000), "the ask was used up: a reply updates");
        session.ping(false, 23_900);
        session.askOpen(20_000);
        assertEquals(EMIT, offer("TowerHallState", "{late}", false, UPDATE_OR_OPEN, true, 24_000), "an ask nobody answered within 3 s lapses");
    }

    @Test
    @DisplayName("a page that stops pinging is closed after the heartbeat, and the next open payload opens it again")
    void heartbeatLapses() {
        offer("RentalDraft", "{d}", false, UPDATE_OR_OPEN, true, 0);
        session.hello(false, 500);
        session.ping(false, 2_000);
        assertEquals(EMIT, offer("RentalDraft", "{d2}", false, UPDATE_OR_OPEN, true, 9_000), "7 s since the last ping: still open");
        assertEquals(OPEN, offer("RentalDraft", "{d3}", false, UPDATE_OR_OPEN, true, 11_000), "9 s since the last ping: closed");
    }

    @Test
    @DisplayName("a page that said goodbye is closed at once")
    void byeClosesImmediately() {
        offer("RentalDraft", "{d}", false, UPDATE_OR_OPEN, true, 0);
        session.hello(false, 500);
        assertTrue(session.alive(false, 600, HEARTBEAT));
        session.bye(false);
        assertFalse(session.alive(false, 700, HEARTBEAT));
        assertEquals(OPEN, offer("RentalDraft", "{d2}", false, UPDATE_OR_OPEN, true, 800));
    }

    @Test
    @DisplayName("a reveal replaces an open page instead of being sent into it")
    void replaceReopens() {
        offer("IntermissionState", "{i}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 0);
        session.hello(false, 500);
        assertEquals(OPEN, offer("RewardReveal", "{r}", false, REPLACE, true, 1_000));
        assertEquals(List.of("{i}", "{r}"), session.hello(false, 1_500), "the page that reopens gets both: the reveal and the screen behind it");
    }

    @Test
    @DisplayName("a page that never loads is given up on after the load window and opened again")
    void loadWindowExpires() {
        assertEquals(OPEN, offer("TowerHallState", "{h}", false, UPDATE_OR_OPEN, true, 0));
        assertEquals(NOTHING, offer("TowerHallState", "{h2}", false, UPDATE_OR_OPEN, true, 5_900));
        assertEquals(OPEN, offer("TowerHallState", "{h3}", false, UPDATE_OR_OPEN, true, 6_100));
    }

    @Test
    @DisplayName("hello replays only what was produced since the page was opened, never an earlier screen's leftovers")
    void staleStateIsNotReplayed() {
        offer("RewardReveal", "{old reward}", false, REPLACE, true, 0);
        session.hello(false, 100);
        offer("IntermissionState", "{new}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 60_000);
        // the first page's heartbeat lapsed long ago, so the intermission opened a new one
        assertEquals(List.of("{new}"), session.hello(false, 60_300));
    }

    @Test
    @DisplayName("the HUD page is tracked apart from the full-screen page")
    void hudIsSeparate() {
        assertEquals(OPEN, offer("SpectatorPanel", "{fx}", true, UPDATE_OR_OPEN, true, 0));
        assertEquals(OPEN, offer("IntermissionState", "{i}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 10));
        assertEquals(List.of("{fx}"), session.hello(true, 100));
        assertEquals(List.of("{i}"), session.hello(false, 120));
        assertEquals(EMIT, offer("SpectatorPanel", "{fx2}", true, UPDATE_OR_OPEN, true, 1_000));
        assertEquals(EMIT, offer("IntermissionState", "{i2}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 1_000));
    }

    @Test
    @DisplayName("a page nobody opened (opened by hand) is caught up with nothing")
    void handOpenedPageGetsNothing() {
        assertEquals(List.of(), session.hello(false, 0));
        offer("IntermissionState", "{i}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 100);
        assertEquals(EMIT, offer("IntermissionState", "{i2}", false, UPDATE_OR_OPEN_IF_FLAGGED, false, 200), "alive from its hello, so updates reach it");
    }

    @Test
    @DisplayName("a payload held back while another mod's screen is up opens nothing and marks nothing as loading, so the next flagged payload opens the page and it catches up")
    void heldPayloadsDoNotDisturbTheSession() {
        session.hold("IntermissionState", "{held}", 0);
        session.hold("IntermissionState", "{held2}", 3_000);
        assertFalse(session.alive(false, 3_000, HEARTBEAT));
        assertFalse(session.loading(false, 3_000, LOAD), "nothing was opened, so nothing is loading");
        assertEquals(OPEN, offer("IntermissionState", "{after}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 20_000));
        assertEquals(List.of("{after}"), session.hello(false, 20_100), "the newest state, not the stale held one");
    }

    @Test
    @DisplayName("an answer to a click on the page updates it, even if the payload asks to open or replace")
    void clickAnswersUpdate() {
        offer("IntermissionState", "{i}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 0);
        session.hello(false, 500);
        session.ping(false, 2_000);
        session.askOpen(2_100);
        assertEquals(EMIT, session.offer("IntermissionState", "{click}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, true, 2_200, HEARTBEAT, LOAD));
        assertEquals(EMIT, session.offer("RewardReveal", "{r}", false, REPLACE, true, true, 2_300, HEARTBEAT, LOAD));
        assertEquals(EMIT, offer("IntermissionState", "{later}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, 2_400), "the stale ask was cleared");
    }

    @Test
    @DisplayName("a click answer with no live page falls back to the normal rules")
    void clickAnswerWithoutPage() {
        assertEquals(OPEN, session.offer("IntermissionState", "{i}", false, UPDATE_OR_OPEN_IF_FLAGGED, true, true, 0, HEARTBEAT, LOAD));
    }

    @Test
    void theRareTrayIsReplayedToTheHudPageNotTheFullPage() {
        PageSession session = new PageSession();
        assertEquals(OPEN, session.offer("HauntedRareTray", "tray", true, UPDATE_OR_OPEN, true, 100, 8, 6000));
        assertEquals(List.of("tray"), session.hello(true, 200));
        assertEquals(List.of(), session.hello(false, 300));
    }

    @Test
    void takingTheTrayDownNeverOpensAPage() {
        PageSession session = new PageSession();
        assertEquals(NOTHING, session.offer("HauntedRareTray", "closed", true, UPDATE_ONLY, true, 100, 8, 6000));
    }
}
