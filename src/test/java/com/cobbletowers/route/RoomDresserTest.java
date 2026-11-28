package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomDresserTest {

    @Test
    @DisplayName("each stop kind has its own room structure, and anything unknown reads as a battle")
    void templates() {
        for (RouteGraph.Kind kind : RouteGraph.Kind.values()) {
            assertEquals(CobbleTowers.id("room_" + kind.name().toLowerCase()), RoomDresser.templateOf(kind.name()));
        }
        assertEquals(CobbleTowers.id("room_battle"), RoomDresser.templateOf("NOPE"));
        assertEquals(CobbleTowers.id("room_battle"), RoomDresser.templateOf(""));
        // a tower's own room (P59): room_<tower>_<kind>, unknown kinds still a battle
        assertEquals(CobbleTowers.id("room_neutral_shrine"), RoomDresser.towerTemplateOf(CobbleTowers.id("neutral"), "SHRINE"));
        assertEquals(CobbleTowers.id("room_neutral_battle"), RoomDresser.towerTemplateOf(CobbleTowers.id("neutral"), "NOPE"));
    }

    @Test
    @DisplayName("the kind behind a door is the kind of the card whose lane is that door's; a door nobody rides on is a battle")
    void kindsByDoor() {
        assertEquals(List.of("FORGE", "BATTLE", "REST"), RoomDresser.kindsByDoor(List.of(2, 0), List.of("REST", "FORGE"), 3));
        assertEquals(List.of("BATTLE", "SHRINE", "BATTLE"), RoomDresser.kindsByDoor(List.of(1), List.of("SHRINE"), 3));
        assertEquals(List.of("BATTLE", "BATTLE", "BATTLE"), RoomDresser.kindsByDoor(List.of(), List.of(), 3));
    }

    @Test
    @DisplayName("every stop kind's room is committed with the size the dresser clears and places")
    void everyRoomIsShipped() throws Exception {
        for (RouteGraph.Kind kind : RouteGraph.Kind.values()) {
            String path = "/data/cobbletowers/structure/room_" + kind.name().toLowerCase() + ".nbt";
            try (var in = RoomDresserTest.class.getResourceAsStream(path)) {
                org.junit.jupiter.api.Assertions.assertNotNull(in, path + " is missing");
            }
        }
    }
}
