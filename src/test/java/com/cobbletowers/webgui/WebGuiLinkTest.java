package com.cobbletowers.webgui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The page the Snobblemon bridge is asked to show is the first part of the configured URL. */
class WebGuiLinkTest {

    @Test
    void theConfiguredUrlNamesThePage() {
        assertEquals("cobbletowers", WebGuiLink.pageId("webgui://cobbletowers/index.html"));
        assertEquals("cobbletowers", WebGuiLink.pageId("webgui:/cobbletowers/index.html"));
        assertEquals("cobbletowers", WebGuiLink.pageId("webgui://cobbletowers/index.html#hud"));
        assertEquals("cobbletowers", WebGuiLink.pageId("cobbletowers"));
        assertEquals("wiki", WebGuiLink.pageId("webgui://wiki/index.html"));
    }

    @Test
    void theOverlayIsThePagesOwnHudPage() {
        assertEquals("cobbletowers_hud", WebGuiLink.hudPageId("webgui://cobbletowers/index.html#hud"));
        assertEquals("cobbletowers_hud", WebGuiLink.hudPageId("webgui:/cobbletowers/index.html"));
    }
}
