package com.cobbletowers.showdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.MilestoneDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Boss phases (P58): the Java half. The Showdown half is proven by validation/showdown/tower_fx_test.js. */
class BossPhasesTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final String OVERCLOCK = """
            {"op":"phase","id":"overclock","name":"Overclock","side":"foe","when":{"hp_below":60},
             "announce":"It overclocks!",
             "do":[{"op":"boost","side":"foe","stat":"spe","stages":2},
                   {"op":"terrain","id":"electricterrain"},
                   {"op":"resist","side":"foe","percent":115,"tag":"overclock"}]}""";

    @Test
    @DisplayName("a phase validates with its trigger, name, announcement and effects")
    void phaseValidates() {
        JsonObject phase = TowerBattleFx.validate(json(OVERCLOCK)).orElseThrow();
        assertEquals("foe", phase.get("side").getAsString());
        assertEquals(60, phase.getAsJsonObject("when").get("hp_below").getAsInt());
        assertEquals("Overclock", phase.get("name").getAsString());
        assertEquals(3, phase.getAsJsonArray("do").size());
        assertEquals("overclock", phase.getAsJsonArray("do").get(2).getAsJsonObject().get("tag").getAsString());
    }

    @Test
    @DisplayName("a phase with no trigger, no side or no valid effect is refused")
    void badPhasesAreRefused() {
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"phase\",\"side\":\"foe\",\"do\":[{\"op\":\"cure\",\"side\":\"foe\"}]}")).isEmpty(), "no trigger");
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"phase\",\"when\":{\"turn\":3},\"do\":[{\"op\":\"cure\",\"side\":\"foe\"}]}")).isEmpty(), "no side");
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"phase\",\"side\":\"foe\",\"when\":{\"turn\":3},\"do\":[{\"op\":\"teleport\"}]}")).isEmpty(), "no valid effect");
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"phase\",\"side\":\"foe\",\"when\":{\"turn\":3},\"do\":[]}")).isEmpty(), "no effect at all");
    }

    @Test
    @DisplayName("a phase cannot contain a phase, and its triggers are clamped")
    void nestedAndClamped() {
        JsonObject phase = TowerBattleFx.validate(json("""
                {"op":"phase","side":"foe","when":{"hp_below":500},"do":[
                  {"op":"phase","side":"foe","when":{"turn":2},"do":[{"op":"cure","side":"foe"}]},
                  {"op":"cure","side":"foe"}]}""")).orElseThrow();
        assertEquals(99, phase.getAsJsonObject("when").get("hp_below").getAsInt(), "clamped to 99");
        assertEquals(1, phase.getAsJsonArray("do").size(), "only the cure survives");
    }

    @Test
    @DisplayName("heal, cure and clear validate; a heal needs its percentage and a clear its tag")
    void newOps() {
        assertEquals(100, TowerBattleFx.validate(json("{\"op\":\"heal\",\"side\":\"foe\",\"to_percent\":100}")).orElseThrow().get("to_percent").getAsInt());
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"heal\",\"side\":\"foe\"}")).isEmpty());
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"cure\",\"side\":\"foe\"}")).isPresent());
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"clear\",\"tag\":\"overclock\"}")).isPresent());
        assertTrue(TowerBattleFx.validate(json("{\"op\":\"clear\"}")).isEmpty());
    }

    @Test
    @DisplayName("armed for a boss battle, the phase watches the boss's side and its effects resolve to it, once however many players")
    void resolvesAgainstTheBossSide() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        JsonArray phase = new JsonArray();
        phase.add(json(OVERCLOCK));
        TowerBattleFx.armBossBattle(List.of(a, b), id -> id.equals(a) ? phase : new JsonArray());
        Map<String, String> fields = TowerBattleFx.fieldsFor(UUID.randomUUID(), List.of(a, b));
        JsonArray fx = JsonParser.parseString(fields.get("towerFx")).getAsJsonArray();
        assertEquals(1, fx.size(), "one phase, not one per player");
        JsonObject armed = fx.get(0).getAsJsonObject();
        assertEquals("[\"p3\"]", armed.getAsJsonArray("sides").toString(), "two players: the boss is p3");
        JsonObject boost = armed.getAsJsonArray("do").get(0).getAsJsonObject();
        assertEquals("[\"p3\"]", boost.getAsJsonArray("sides").toString());
        assertFalse(boost.has("side"), "the logical side is gone");
    }

    @Test
    @DisplayName("a milestone reads its phases, keeps their names, and refuses more than three or an invalid one")
    void milestoneParses() {
        String head = "{\"floor\":5,\"kind\":\"boss\",\"raid_definition\":\"cobbleraids:regieleki\",\"phases\":[";
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", "boss");
        MilestoneDefinition ok = MilestoneDefinition.fromJson(id, json(head + OVERCLOCK.replace("\"op\":\"phase\",", "") + "]}"));
        assertEquals(1, ok.phases().size());
        assertEquals("Overclock", ok.phases().get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0, MilestoneDefinition.fromJson(id, json("{\"floor\":5,\"kind\":\"boss\",\"raid_definition\":\"a:b\"}")).phases().size());

        String phase = OVERCLOCK.replace("\"op\":\"phase\",", "");
        assertThrows(IllegalArgumentException.class,
                () -> MilestoneDefinition.fromJson(id, json(head + phase + "," + phase + "," + phase + "," + phase + "]}")), "four phases");
        assertThrows(IllegalArgumentException.class,
                () -> MilestoneDefinition.fromJson(id, json(head + "{\"side\":\"foe\"}]}")), "a phase with no trigger");
    }

    @Test
    @DisplayName("the shipped Regieleki milestone carries its two phases, ordered by HP")
    void shippedRegieleki() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/data/cobbletowers/cobbletowers/milestones/tideforge/boss_05.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            MilestoneDefinition milestone = MilestoneDefinition.fromJson(ResourceLocation.fromNamespaceAndPath("cobbletowers", "tideforge/boss_05"), root);
            assertEquals(2, milestone.phases().size());
            assertEquals("overclock", milestone.phases().get(0).getAsJsonObject().get("id").getAsString());
            assertEquals(60, milestone.phases().get(0).getAsJsonObject().getAsJsonObject("when").get("hp_below").getAsInt());
            assertEquals(30, milestone.phases().get(1).getAsJsonObject().getAsJsonObject("when").get("hp_below").getAsInt());
        }
    }
}
