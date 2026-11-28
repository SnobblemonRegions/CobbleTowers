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

    @Test
    @DisplayName("party fixtures (20 Nov 2026): a set-back walker lands in the portal's mouth; each gate's altar faces the plaza, clear of the portal and the host")
    void partyFixtures() {
        HubLayout layout = HubLayout.load();
        assertTrue(layout.ring().present());
        assertEquals(Set.of(0, 1, 2, 3), layout.ring().cells().stream().map(c -> c[2]).collect(Collectors.toSet()), "four arcs");
        for (HubLayout.Portal portal : layout.portals()) {
            HubLayout.Gate gate = portal.gate().orElseThrow();
            assertTrue(gate.mouth(portal.back().x(), portal.back().y(), portal.back().z()), portal.tower() + ": set back outside the mouth");
            HubLayout.Altar altar = gate.altar();
            assertFalse(portal.contains(altar.x(), altar.y(), altar.z()), portal.tower() + ": the altar in the portal");
            double hx = portal.host().x() - altar.x(), hz = portal.host().z() - altar.z();
            assertTrue(hx * hx + hz * hz >= 16, portal.tower() + ": the altar crowds the host");
            // its front (+z turned by yaw) points at the plaza's middle, away from the gate
            double fx = Math.sin(Math.toRadians(altar.yaw())), fz = Math.cos(Math.toRadians(altar.yaw()));
            double tx = layout.sizeX() / 2.0 - altar.x(), tz = layout.sizeZ() / 2.0 - altar.z();
            assertTrue(fx * tx + fz * tz > 0, portal.tower() + ": the altar faces away from the plaza");
            assertTrue(gate.ready(altar.x() + 0.5 + 2.8, altar.y(), altar.z() + 0.5), portal.tower() + ": the ready ring");
            assertFalse(gate.ready(altar.x() + 0.5 + 5, altar.y(), altar.z() + 0.5), portal.tower() + ": the ready ring reaches too far");
        }
    }

    @Test
    @DisplayName("Trials Pavilion (20 Nov 2026): a Daily and a Weekly shrine, each walker set down outside its opening and on its ready ring's side")
    void shrines() {
        HubLayout layout = HubLayout.load();
        assertEquals(Set.of("daily", "weekly"), layout.shrines().stream().map(HubLayout.Shrine::kind).collect(Collectors.toSet()));
        for (HubLayout.Shrine shrine : layout.shrines()) {
            HubLayout.Portal portal = shrine.portal();
            HubLayout.Spot back = portal.back();
            assertFalse(portal.contains(back.x(), back.y(), back.z()), shrine.kind() + ": set back inside the opening");
            assertTrue(shrine.ready(shrine.readyX(), portal.minY(), shrine.readyZ()), shrine.kind() + ": the ring's middle");
            for (HubLayout.Portal gate : layout.portals()) {
                assertFalse(gate.contains(back.x(), back.y(), back.z()), shrine.kind() + ": set back inside a tower gate");
            }
            assertTrue(shrine.plate().y() > portal.maxY(), shrine.kind() + ": the plate hangs over the opening");
        }
    }

    @Test
    @DisplayName("Records Hall (20 Nov 2026): three board columns, three statues, sixteen tier marks in the floor ring, the Season Chest on the dais step, a trophy case per tower")
    void records() {
        HubLayout.Records r = HubLayout.load().records().orElseThrow();
        assertEquals(3, r.board().size());
        assertEquals(3, r.statues().size());
        assertEquals(16, r.track().size());
        assertEquals(Set.of("neutral", "tideforge", "duskvale", "rootvale"),
                r.shelves().stream().map(s -> s.tower().getPath()).collect(Collectors.toSet()));
        for (HubLayout.Shelf shelf : r.shelves()) {
            HubLayout.Point at = shelf.at();
            assertTrue(r.inside(at.x(), at.y(), at.z()), shelf.tower() + ": its case stands outside the hall");
        }
        // the tier marks lie in the floor ring (radius 8.5, floor level), clear of the dais (5.4) and of the cases' fronts (12.5)
        HubLayout.Point c = r.centre();
        for (HubLayout.Point p : r.track()) {
            double d = Math.hypot(p.x() - (c.x() + 0.5), p.z() - (c.z() + 0.5));
            assertTrue(d > 7.4 && d < 9.4, "a tier mark off the ring path: " + d);
            assertEquals(c.y(), p.y(), 1e-9, "a tier mark not at floor level");
        }
        // the Season Chest (2 wide, 1.1 deep) sits on the dais's front step (3.9 to 5.4 out, one block up) and faces the door
        HubLayout.Point chest = r.chest();
        double cx = chest.x() - (c.x() + 0.5), cz = chest.z() - (c.z() + 0.5);
        double d = Math.hypot(cx, cz);
        assertTrue(d - 0.55 >= 3.9 && Math.hypot(d + 0.55, 1.0) <= 5.4, "the chest hangs off the dais step: " + d);
        assertEquals(c.y() + 1, chest.y(), 1e-9);
        double faces = Math.toRadians(r.chestFacing());
        assertTrue((Math.sin(faces) * cx + Math.cos(faces) * cz) / d > 0.99, "the chest's front does not face out from the dais");
        HubLayout.Point door = r.rank();
        assertTrue(Math.hypot(door.x() - chest.x(), door.z() - chest.z()) < Math.hypot(door.x() - (c.x() + 0.5), door.z() - (c.z() + 0.5)),
                "the chest is not on the door's side of the dais");
        for (HubLayout.Point p : r.statues()) assertTrue(r.inside(p.x(), p.y(), p.z()), "a statue outside the hall");
        assertTrue(r.inside(r.rank().x(), r.rank().y(), r.rank().z()), "the rank plate outside the hall");
        // the statues stand first highest
        assertTrue(r.statues().get(0).y() > r.statues().get(1).y() && r.statues().get(1).y() > r.statues().get(2).y());
    }
}
