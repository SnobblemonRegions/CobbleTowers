package com.cobbletowers.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: a tower's preparation room, and where a vendor service is sold. */
class PrepRoomDefinitionTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject shipped(String path) throws Exception {
        try (Reader reader = new InputStreamReader(PrepRoomDefinitionTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static final String TOWER = "{\"schema_version\": 1, \"display_name\": \"T\", \"ruleset\": \"cobbletowers:standard\","
            + " \"reward_table\": \"cobbletowers:r\", \"floors\": [\"cobbletowers:t/floor_01\"] %s}";

    private static String prep(boolean threshold) {
        return ", \"prep\": {\"structure\": \"cobbletowers:t_prep\", \"entry\": {\"x\": 1, \"y\": 4, \"z\": 2, \"yaw\": 270},"
                + " \"vendor\": {\"x\": 3, \"y\": 4, \"z\": 5, \"yaw\": 180},"
                + " \"dais\": {\"min\": [1, 5, 1], \"max\": [2, 6, 2]}, \"door\": {\"min\": [3, 5, 1], \"max\": [3, 9, 2]}"
                + (threshold ? ", \"threshold\": {\"min\": [5, 5, 1], \"max\": [6, 6, 2]}" : "") + "}";
    }

    @Test
    @DisplayName("a tower reads its prep room; a tower without one has none")
    void parses() {
        TowerDefinition with = TowerDefinition.fromJson(id("t"), json(TOWER.formatted(prep(true))));
        PrepRoom room = with.prep().orElseThrow();
        assertEquals(id("t_prep"), room.structure());
        assertEquals(new FloorBox(3, 5, 1, 3, 9, 2), room.door());
        assertEquals(180f, room.vendor().yaw());
        assertFalse(TowerDefinition.fromJson(id("t"), json(TOWER.formatted(""))).prep().isPresent());
    }

    @Test
    @DisplayName("a prep room missing a box is refused")
    void missingBox() {
        assertThrows(IllegalArgumentException.class, () -> TowerDefinition.fromJson(id("t"), json(TOWER.formatted(prep(false)))));
    }

    @Test
    @DisplayName("a service is sold in the atrium unless its file says where; sold_in may name the prep room")
    void soldIn() {
        String base = "{\"schema_version\": 1, \"display_name\": \"Full Heal\", \"price_cobble_dollars\": 25, \"effect\": \"full_heal\" %s}";
        VendorServiceDefinition plain = VendorServiceDefinition.fromJson(id("full_heal"), json(base.formatted("")));
        assertEquals(Set.of(VendorPlace.ATRIUM), plain.soldIn());
        assertFalse(plain.soldAt(VendorPlace.PREP));
        VendorServiceDefinition both = VendorServiceDefinition.fromJson(id("full_heal"), json(base.formatted(", \"sold_in\": [\"atrium\", \"prep\"]")));
        assertTrue(both.soldAt(VendorPlace.PREP) && both.soldAt(VendorPlace.ATRIUM));
        assertThrows(IllegalArgumentException.class,
                () -> VendorServiceDefinition.fromJson(id("full_heal"), json(base.formatted(", \"sold_in\": [\"lobby\"]"))));
        assertThrows(IllegalArgumentException.class,
                () -> VendorServiceDefinition.fromJson(id("full_heal"), json(base.formatted(", \"sold_in\": []"))));
    }

    @Test
    @DisplayName("shipped: the prep stand sells run supplies only (owner, 2026-11-13), the Mystery Bag is atrium only")
    void shippedStock() throws Exception {
        String dir = "/data/cobbletowers/cobbletowers/vendor_services/";
        for (String supply : new String[] {"full_heal", "cure_status"}) {
            assertTrue(VendorServiceDefinition.fromJson(id(supply), shipped(dir + supply + ".json")).soldAt(VendorPlace.PREP), supply);
        }
        assertFalse(VendorServiceDefinition.fromJson(id("mystery_bag"), shipped(dir + "mystery_bag.json")).soldAt(VendorPlace.PREP));
    }

    @Test
    @DisplayName("shipped: Neutral has a prep room, Act I holds floors 1-5 and Act II floors 6-10, and floor 5 climbs into Act II")
    void shippedNeutral() throws Exception {
        TowerDefinition neutral = TowerDefinition.fromJson(id("neutral"), shipped("/data/cobbletowers/cobbletowers/towers/neutral.json"));
        assertEquals(id("neutral_prep"), neutral.prep().orElseThrow().structure());
        assertTrue(neutral.routeMap() && neutral.pedestalVotes());
        for (int floor = 1; floor <= 10; floor++) {
            JsonObject root = shipped("/data/cobbletowers/cobbletowers/floors/neutral/floor_%02d.json".formatted(floor));
            FloorLayout layout = FloorLayout.fromJson(root.getAsJsonObject("layout"));
            assertEquals(id(floor <= 5 ? "neutral_act1" : "neutral_act2"), layout.structure(), "floor " + floor);
            assertEquals(3, layout.doors().size(), "floor " + floor);
            assertEquals(floor < 10, layout.laneStairs(), "floor " + floor + ": every floor but the last climbs");
        }
    }
}
