package com.cobbletowers.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.api.tower.MilestoneKind;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the registry reports after a reload: resolved content, or exactly which reference dangles. */
class TowerContentTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    private static FloorDefinition floor(int index, Optional<MilestoneKind> milestone) {
        return new FloorDefinition(id("floor_" + index), index, milestone, Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    private static TowerContent content(TowerDefinition tower, Map<ResourceLocation, FloorDefinition> floors,
                                        Map<ResourceLocation, MilestoneDefinition> milestones,
                                        boolean withRuleset) {
        return content(tower, floors, milestones, withRuleset, true);
    }

    private static TowerContent content(TowerDefinition tower, Map<ResourceLocation, FloorDefinition> floors,
                                        Map<ResourceLocation, MilestoneDefinition> milestones,
                                        boolean withRuleset, boolean withRewardTable) {
        return TowerContent.of(
                Map.of(tower.id(), tower),
                floors,
                withRuleset ? Map.of(id("standard"), RulesetDefinition.fromJson(id("standard"),
                        JsonParser.parseString("{\"schema_version\":1}").getAsJsonObject())) : Map.of(),
                milestones, Map.of(), Map.of(),
                withRewardTable ? Map.of(id("rewards"), rewardTable()) : Map.of(),
                Map.of(), Map.of(), Map.of(),
                Map.of(DefinitionKey.tower(id("neutral")), "digest-abc"));
    }

    private static RewardTableDefinition rewardTable() {
        return RewardTableDefinition.fromJson(id("rewards"),
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\"Rewards\"}").getAsJsonObject());
    }

    private static TowerDefinition tower(List<ResourceLocation> floorIds, List<ResourceLocation> milestoneIds) {
        return tower(floorIds, milestoneIds, Optional.empty());
    }

    private static TowerDefinition tower(List<ResourceLocation> floorIds, List<ResourceLocation> milestoneIds,
                                         Optional<ResourceLocation> regionalTheme) {
        return new TowerDefinition(id("neutral"), "Neutral", 1, 1, id("standard"), id("rewards"), floorIds,
                milestoneIds, regionalTheme, Optional.empty());
    }

    @Test
    @DisplayName("consistent content reports no problems and summarises the tower")
    void consistent() {
        FloorDefinition one = floor(1, Optional.empty());
        FloorDefinition two = floor(2, Optional.of(MilestoneKind.BOSS));
        MilestoneDefinition boss = new MilestoneDefinition(id("boss"), 2, MilestoneKind.BOSS,
                Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), true);

        TowerContent content = content(tower(List.of(one.id(), two.id()), List.of(boss.id())),
                Map.of(one.id(), one, two.id(), two), Map.of(boss.id(), boss), true);

        assertEquals(List.of(), content.problems());
        assertEquals(2, content.floorViews(id("neutral")).size());
        assertEquals(List.of(2), content.summary(id("neutral")).orElseThrow().milestoneFloors());
        assertEquals("digest-abc", content.summary(id("neutral")).orElseThrow().contentDigest());
    }

    @Test
    @DisplayName("a ruleset named after its tower does not take over the tower's digest")
    void digestsAreScopedToTheirFolder() {
        // One reload puts all five folders in one digest map. Ids are paths below the folder, so towers/neutral.json
        // and rulesets/neutral.json collide unless keyed by folder too.
        FloorDefinition one = floor(1, Optional.empty());
        Map<DefinitionKey, String> digests = new LinkedHashMap<>();
        digests.put(DefinitionKey.tower(id("neutral")), "tower-digest");
        digests.put(DefinitionKey.of("rulesets", id("neutral")), "ruleset-digest");

        TowerContent content = TowerContent.of(
                Map.of(id("neutral"), tower(List.of(one.id()), List.of())),
                Map.of(one.id(), one),
                Map.of(id("standard"), RulesetDefinition.fromJson(id("standard"),
                        JsonParser.parseString("{\"schema_version\":1}").getAsJsonObject())),
                Map.of(), Map.of(), Map.of(),
                Map.of(id("rewards"), rewardTable()),
                Map.of(), Map.of(), Map.of(),
                digests);

        assertEquals("tower-digest", content.summary(id("neutral")).orElseThrow().contentDigest(),
                "the tower's own digest, not whichever file was loaded last under that id");
    }

    @Test
    @DisplayName("a dangling reference is named, not thrown")
    void danglingReferences() {
        FloorDefinition one = floor(1, Optional.empty());

        TowerContent content = content(tower(List.of(one.id(), id("missing")), List.of()),
                Map.of(one.id(), one), Map.of(), false, false);

        String problems = String.join(" | ", content.problems());
        assertTrue(problems.contains("floor cobbletowers:missing"), problems);
        assertTrue(problems.contains("ruleset cobbletowers:standard"), problems);
        assertTrue(problems.contains("reward table cobbletowers:rewards"), problems);
    }

    @Test
    @DisplayName("a dangling regional theme reference is named, not thrown (P10)")
    void danglingRegionalReferences() {
        FloorDefinition one = floor(1, Optional.empty());
        TowerDefinition tower = tower(List.of(one.id()), List.of(), Optional.of(id("missing_theme")));

        TowerContent content = TowerContent.of(
                Map.of(tower.id(), tower),
                Map.of(one.id(), one),
                Map.of(id("standard"), RulesetDefinition.fromJson(id("standard"),
                        JsonParser.parseString("{\"schema_version\":1}").getAsJsonObject())),
                Map.of(), Map.of(), Map.of(),
                Map.of(id("rewards"), rewardTable()),
                Map.of(), Map.of(), Map.of(),
                Map.of(DefinitionKey.tower(id("neutral")), "digest-abc"));

        String problems = String.join(" | ", content.problems());
        assertTrue(problems.contains("names regional theme cobbletowers:missing_theme"), problems);
    }

    @Test
    @DisplayName("a dangling scouting profile reference is named, not thrown (P12)")
    void danglingScoutingProfile() {
        FloorDefinition one = floor(1, Optional.empty());
        TowerDefinition tower = new TowerDefinition(id("neutral"), "Neutral", 1, 1, id("standard"), id("rewards"),
                List.of(one.id()), List.of(), Optional.empty(), Optional.of(id("missing_profile")));

        TowerContent content = TowerContent.of(
                Map.of(tower.id(), tower),
                Map.of(one.id(), one),
                Map.of(id("standard"), RulesetDefinition.fromJson(id("standard"),
                        JsonParser.parseString("{\"schema_version\":1}").getAsJsonObject())),
                Map.of(), Map.of(), Map.of(),
                Map.of(id("rewards"), rewardTable()),
                Map.of(), Map.of(), Map.of(),
                Map.of(DefinitionKey.tower(id("neutral")), "digest-abc"));

        String problems = String.join(" | ", content.problems());
        assertTrue(problems.contains("names scouting profile cobbletowers:missing_profile"), problems);
    }

    @Test
    @DisplayName("floors must be numbered 1..n in listed order")
    void floorNumbering() {
        FloorDefinition one = floor(1, Optional.empty());
        FloorDefinition five = floor(5, Optional.empty());

        TowerContent content = content(tower(List.of(one.id(), five.id()), List.of()),
                Map.of(one.id(), one, five.id(), five), Map.of(), true);

        assertTrue(String.join(" ", content.problems()).contains("must be numbered"), content.problems().toString());
    }

    @Test
    @DisplayName("a milestone must land on a floor marked for it, of the same kind")
    void milestonePlacement() {
        FloorDefinition plain = floor(1, Optional.empty());
        MilestoneDefinition boss = new MilestoneDefinition(id("boss"), 1, MilestoneKind.BOSS,
                Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), true);

        TowerContent unmarked = content(tower(List.of(plain.id()), List.of(boss.id())),
                Map.of(plain.id(), plain), Map.of(boss.id(), boss), true);
        assertTrue(String.join(" ", unmarked.problems()).contains("not marked as a milestone floor"),
                unmarked.problems().toString());

        FloorDefinition champion = floor(1, Optional.of(MilestoneKind.CHAMPION));
        TowerContent mismatched = content(tower(List.of(champion.id()), List.of(boss.id())),
                Map.of(champion.id(), champion), Map.of(boss.id(), boss), true);
        assertTrue(String.join(" ", mismatched.problems()).contains("but milestone"), mismatched.problems().toString());
    }

    @Test
    @DisplayName("a digest ignores formatting and key order, and changes with content")
    void digestIsCanonical() {
        String compact = "{\"b\":2,\"a\":[1,2],\"c\":\"x\"}";
        String reordered = "{\n  \"a\": [1, 2],\n  \"c\": \"x\",\n  \"b\": 2\n}";
        String changed = "{\"a\":[2,1],\"b\":2,\"c\":\"x\"}";

        assertEquals(ContentDigest.of(JsonParser.parseString(compact)),
                ContentDigest.of(JsonParser.parseString(reordered)),
                "reformatting a definition must not read as an edit");
        assertNotEquals(ContentDigest.of(JsonParser.parseString(compact)),
                ContentDigest.of(JsonParser.parseString(changed)),
                "array order is meaning: floors are played in the order they are listed");
    }
}
