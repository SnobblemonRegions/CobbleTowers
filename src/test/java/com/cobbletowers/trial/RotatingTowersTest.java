package com.cobbletowers.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.*;
import com.cobbletowers.definition.TrialPoolDefinition.Kind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The synthetic towers of rotating trials (P46b), built from the shipped definitions. */
class RotatingTowersTest {

    private static final Path DATA = Paths.get("src/main/resources/data/cobbletowers/cobbletowers");

    private static <T> Map<ResourceLocation, T> load(String folder, BiFunction<ResourceLocation, JsonObject, T> parser) throws Exception {
        Map<ResourceLocation, T> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(DATA.resolve(folder))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String relative = DATA.resolve(folder).relativize(file).toString().replace('\\', '/');
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", relative.substring(0, relative.length() - 5));
                try (Reader reader = Files.newBufferedReader(file)) {
                    loaded.put(id, parser.apply(id, JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        }
        return loaded;
    }

    static TowerContent shipped() throws Exception {
        return TowerContent.of(load("towers", TowerDefinition::fromJson), load("floors", FloorDefinition::fromJson),
                load("rulesets", RulesetDefinition::fromJson),
                load("milestones", MilestoneDefinition::fromJson), load("boss_pools", BossPoolDefinition::fromJson),
                load("modifiers", ModifierDefinition::fromJson), load("reward_tables", RewardTableDefinition::fromJson),
                load("regional_themes", RegionalThemeDefinition::fromJson), load("vendor_services", VendorServiceDefinition::fromJson),
                load("scouting_profiles", ScoutingProfileDefinition::fromJson), Map.of());
    }

    private static RotatingRecipeDefinition recipe(String name) throws Exception {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", name);
        try (Reader reader = Files.newBufferedReader(DATA.resolve("trial_recipes/" + name + ".json"))) {
            return RotatingRecipeDefinition.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    @Test
    @DisplayName("ids round trip: a daily and a weekly id name their kind, period and number")
    void ids() {
        var daily = RotatingTowers.parse(RotatingTowers.idFor(Kind.DAILY, "2026-10-14")).orElseThrow();
        assertEquals(Kind.DAILY, daily.kind());
        assertEquals(java.time.LocalDate.of(2026, 10, 14).toEpochDay(), daily.number());
        var weekly = RotatingTowers.parse(RotatingTowers.idFor(Kind.WEEKLY, "2026-w41")).orElseThrow();
        assertEquals(Kind.WEEKLY, weekly.kind());
        assertEquals(java.time.LocalDate.of(2026, 10, 5).toEpochDay() / 7, weekly.number());
        assertTrue(RotatingTowers.parse(ResourceLocation.fromNamespaceAndPath("cobbletowers", "neutral")).isEmpty());
        assertTrue(RotatingTowers.parse(ResourceLocation.fromNamespaceAndPath("cobbletowers", "rotating/daily-nope")).isEmpty());
    }

    @Test
    @DisplayName("every daily and weekly over a year builds a tower the content accepts with no new problems")
    void yearOfTowers() throws Exception {
        TowerContent base = shipped();
        for (String name : List.of("daily", "weekly")) {
            RotatingRecipeDefinition recipe = recipe(name);
            int periods = name.equals("daily") ? 366 : 53;
            for (int i = 0; i < periods; i++) {
                long number = name.equals("daily") ? 20000 + i : 2850 + i;
                RotatingRoll roll = RotatingRoll.of(recipe, number);
                ResourceLocation id = CobbleId.rotating(name, number);
                RotatingTowers.Built built = RotatingTowers.build(recipe, roll, id, base);
                TowerContent content = base.withRotating(built.tower(), built.floors());
                assertEquals(base.problems(), content.problems(), name + " " + number);
                assertEquals(recipe.floors(), built.tower().floorCount());
                for (int floor = 1; floor <= recipe.floors(); floor++) {
                    FloorDefinition got = content.floorAt(id, floor).orElseThrow();
                    assertEquals(floor, got.index());
                    FloorDefinition source = base.floorAt(roll.regionAt(recipe, floor), floor).orElseThrow();
                    assertEquals(source.bossPoolId(), got.bossPoolId());
                    assertEquals(source.layout(), got.layout());
                    assertEquals(source.milestone(), got.milestone());
                    assertEquals(source.milestone().isPresent(), content.milestoneAt(id, floor).isPresent());
                }
                // the daily ends on a boss; the weekly has a boss at 5 and a champion at 10
                assertTrue(content.milestoneAt(id, 5).isPresent());
                if (name.equals("weekly")) assertTrue(content.milestoneAt(id, 10).isPresent());
            }
        }
    }

    @Test
    @DisplayName("a region's ruleset applies on its own segment only, and the rotating tower is not in the catalog")
    void overridesAndCatalog() throws Exception {
        TowerContent base = shipped();
        RotatingRecipeDefinition recipe = recipe("daily");
        long number = 20000;
        for (; number < 20400; number++) {
            RotatingRoll roll = RotatingRoll.of(recipe, number);
            if (roll.segmentRegions().stream().distinct().count() > 1) break;
        }
        RotatingRoll roll = RotatingRoll.of(recipe, number);
        ResourceLocation id = CobbleId.rotating("daily", number);
        var built = RotatingTowers.build(recipe, roll, id, base);
        TowerContent content = base.withRotating(built.tower(), built.floors());
        for (int floor = 1; floor <= recipe.floors(); floor++) {
            TowerDefinition source = base.towers().get(roll.regionAt(recipe, floor));
            FloorDefinition got = content.floorAt(id, floor).orElseThrow();
            boolean standard = source.rulesetId().getPath().equals("standard");
            assertEquals(standard, got.rulesetOverride().isEmpty(), "floor " + floor + " " + source.id());
            if (!standard) assertEquals(source.rulesetId(), got.rulesetOverride().orElseThrow());
        }
        assertEquals(base.towers().get(roll.regionAt(recipe, recipe.floors())).rewardTableId(), built.tower().rewardTableId());
        assertTrue(content.towers().containsKey(id));
        assertFalse(content.catalogTowers().stream().anyMatch(t -> t.id().equals(id)));
        assertFalse(content.sortedTowerIds().contains(id));
        assertEquals(base.catalogTowers().size(), content.catalogTowers().size());
    }

    @Test
    @DisplayName("a region that is not loaded is refused with a message, not built half way")
    void missingRegion() throws Exception {
        TowerContent base = shipped();
        RotatingRecipeDefinition recipe = recipe("daily");
        RotatingRoll roll = RotatingRoll.of(recipe, 20000);
        TowerContent without = base.withRotating(base.towers().get(roll.segmentRegions().get(0)), List.of());
        Map<ResourceLocation, TowerDefinition> towers = new LinkedHashMap<>(without.towers());
        towers.remove(roll.segmentRegions().get(0));
        TowerContent gone = new TowerContent(towers, without.floors(), without.rulesets(), without.milestones(),
                without.bossPools(), without.modifiers(), without.rewardTables(), without.regionalThemes(), without.vendorServices(),
                without.scoutingProfiles(), without.digests(), without.problems(), without.sortedTowerIds());
        try {
            RotatingTowers.build(recipe, roll, CobbleId.rotating("daily", 20000), gone);
            org.junit.jupiter.api.Assertions.fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not loaded"));
        }
    }

    /** Ids the way the trial schedule will make them. */
    private static final class CobbleId {
        static ResourceLocation rotating(String kind, long number) {
            if (kind.equals("daily")) return RotatingTowers.idFor(Kind.DAILY, java.time.LocalDate.ofEpochDay(number).toString());
            java.time.LocalDate monday = java.time.LocalDate.ofEpochDay(number * 7);
            return RotatingTowers.idFor(Kind.WEEKLY, TrialClock.weekKey(monday));
        }
    }
}
