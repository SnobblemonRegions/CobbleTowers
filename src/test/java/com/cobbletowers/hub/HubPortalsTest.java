package com.cobbletowers.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.hub.HubPortals.Action;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Tower Hub's portals (owner, 19 Nov 2026): what a walk-in does, and the layout the mod reads the hub by. */
class HubPortalsTest {

    @Test
    @DisplayName("only a host whose team is for that tower starts a run; everyone else is shown what is missing")
    void decide() {
        assertEquals(Action.START, HubPortals.decide(false, true, true, true));
        assertEquals(Action.OPEN_LOBBY, HubPortals.decide(false, true, false, true), "a member waits for the host");
        assertEquals(Action.OTHER_TOWER, HubPortals.decide(false, true, true, false));
        assertEquals(Action.OTHER_TOWER, HubPortals.decide(false, true, false, false));
        assertEquals(Action.OPEN_TOWER, HubPortals.decide(false, false, false, false), "no team: that tower's page");
        assertEquals(Action.IN_RUN, HubPortals.decide(true, true, true, true), "a run under way wins over everything");
    }

    @Test
    @DisplayName("the shipped layout: four towers, each portal's step-back spot outside it, each host's dais outside every portal")
    void layout() {
        HubLayout layout = HubLayout.load();
        assertEquals(4, layout.portals().size());
        Set<String> towers = layout.portals().stream().map(p -> p.tower().getPath()).collect(Collectors.toSet());
        assertEquals(Set.of("neutral", "tideforge", "duskvale", "rootvale"), towers);
        for (HubLayout.Portal portal : layout.portals()) {
            assertFalse(portal.contains(portal.back().x(), portal.back().y(), portal.back().z()), portal.tower() + ": set back inside it");
            for (HubLayout.Portal any : layout.portals()) {
                assertFalse(any.contains(portal.host().x(), portal.host().y(), portal.host().z()), portal.tower() + "'s host stands in a portal");
            }
            int cx = (portal.minX() + portal.maxX()) / 2;
            int cz = (portal.minZ() + portal.maxZ()) / 2;
            assertTrue(portal.contains(cx, portal.minY(), cz), portal.tower() + ": its middle is in it");
            int across = portal.wideAlongX() ? portal.maxX() - portal.minX() + 1 : portal.maxZ() - portal.minZ() + 1;
            assertEquals(7, across, portal.tower() + ": the opening is seven wide");
        }
        assertTrue(layout.pad().y() >= 0 && layout.pad().y() < layout.sizeY());
    }
}
