package com.cobbletowers.ascension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.modifier.ForcedModifiers;
import com.cobbletowers.modifier.ModifierEffects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ascension (P30) against real definitions: content lookups cycle, forced modifiers are only ones that harden. */
class AscensionContentTest {

    /** TestRuns' two-floor tower (floor 2 a boss milestone), made to ascend. */
    private static TowerContent ascending(boolean ascends) {
        TowerContent base = TestRuns.content();
        TowerDefinition plain = base.towers().get(TestRuns.TOWER);
        TowerDefinition tower = new TowerDefinition(plain.id(), plain.displayName(), plain.schemaVersion(),
                plain.revision(), plain.rulesetId(), plain.rewardTableId(), plain.floorIds(), plain.milestoneIds(),
                plain.regionalTheme(), plain.scoutingProfile(), ascends);
        return TowerContent.of(Map.of(TestRuns.TOWER, tower), base.floors(), base.rulesets(),
                base.milestones(), base.bossPools(), Map.of(), base.rewardTables(), base.regionalThemes(),
                base.vendorServices(), base.scoutingProfiles(), base.digests());
    }

    @Test
    @DisplayName("an ascending tower looks floor 3 up as floor 1 and floor 4 as its boss milestone floor")
    void contentCycles() {
        TowerContent content = ascending(true);
        assertEquals(1, content.floorAt(TestRuns.TOWER, 3).orElseThrow().index());
        assertEquals(2, content.floorAt(TestRuns.TOWER, 4).orElseThrow().index());
        assertEquals(1, content.floorAt(TestRuns.TOWER, 9).orElseThrow().index());
        assertTrue(content.milestoneAt(TestRuns.TOWER, 4).isPresent(), "floor 4 is the milestone again");
        assertTrue(content.milestoneAt(TestRuns.TOWER, 6).isPresent());
        assertFalse(content.milestoneAt(TestRuns.TOWER, 5).isPresent());
    }

    @Test
    @DisplayName("a tower that does not ascend still has nothing past its last floor")
    void plainTowerEnds() {
        TowerContent content = ascending(false);
        assertTrue(content.floorAt(TestRuns.TOWER, 3).isEmpty());
        assertFalse(content.towers().get(TestRuns.TOWER).ascension());
        assertEquals(0, content.towers().get(TestRuns.TOWER).ascensionOf(99));
    }

    @Test
    @DisplayName("an ascending tower reports its Ascension from the run floor")
    void ascensionOfRunFloor() {
        TowerDefinition tower = ascending(true).towers().get(TestRuns.TOWER);
        assertEquals(0, tower.ascensionOf(2));
        assertEquals(1, tower.ascensionOf(3));
        assertEquals(3, tower.ascensionOf(8));
    }

    @Test
    @DisplayName("Ascension adds boss health and reward factor to the modifiers' own effects, and the base cycle adds nothing")
    void effectsGrow() {
        ModifierEffects base = ModifierEffects.NONE;
        assertEquals(base, base.withAscension(0));
        ModifierEffects deep = base.withAscension(3);
        assertEquals(136, deep.bossHealthPercent());
        assertEquals(AscensionPolicy.rewardPercent(3), deep.rewardPercent());
        assertTrue(deep.changesBattleRules(), "a bigger boss pool is a battle rule");
    }

    private static Map<ResourceLocation, ModifierDefinition> pool() {
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        for (ModifierDefinition modifier : List.of(
                TestRuns.modifier("brutal", "enemy", "\"boss_level_offset\":4", "\"risk\":\"severe\""),
                TestRuns.modifier("crowded", "enemy", "\"boss_level_offset\":2", "\"risk\":\"moderate\""),
                TestRuns.modifier("rain", "field", "\"weather\":\"raindance\"", "\"risk\":\"moderate\""),
                TestRuns.modifier("soft", "enemy", "\"boss_health_percent\":70", "\"risk\":\"minor\""),
                TestRuns.modifier("bounty", "reward", "\"reward_percent\":150", "\"risk\":\"moderate\""),
                TestRuns.modifier("sight", "scouting", "\"scouting_bonus\":2", "\"risk\":\"moderate\""),
                TestRuns.modifier("boon", "custom", "\"custom\":\"black_market\"", "\"risk\":\"severe\""))) {
            all.put(modifier.id(), modifier);
        }
        return all;
    }

    @Test
    @DisplayName("only modifiers that make the run harder can be forced: not rewards, scouting, customs or minor ones")
    void onlyHardeningModifiersAreForcible() {
        List<ModifierDefinition> forcible = pool().values().stream().filter(ForcedModifiers::forcible).toList();
        assertEquals(3, forcible.size(), forcible.toString());
        assertTrue(forcible.stream().noneMatch(m -> m.id().getPath().equals("soft")));
    }

    @Test
    @DisplayName("a forced draw is deterministic, stays inside the forcible pool, and respects what the run holds")
    void forcedDraw() {
        List<ModifierDefinition> sorted = pool().values().stream()
                .sorted(java.util.Comparator.comparing(m -> m.id().toString())).toList();
        for (int ascension = 1; ascension <= 20; ascension++) {
            var first = ForcedModifiers.draw(sorted, List.of(), 99L, ascension).orElseThrow();
            assertEquals(first, ForcedModifiers.draw(sorted, List.of(), 99L, ascension).orElseThrow());
            assertTrue(ForcedModifiers.forcible(first));
        }
        // With all three forcible modifiers already held at their stack limit, nothing is left to force.
        List<ModifierDefinition> held = sorted.stream().filter(ForcedModifiers::forcible).toList();
        assertTrue(ForcedModifiers.draw(sorted, held, 99L, 5).isEmpty());
    }

    @Test
    @DisplayName("a direct start at Ascension N forces up to N modifiers in order, and stops when the pool runs dry")
    void drawAll() {
        List<ModifierDefinition> sorted = pool().values().stream()
                .sorted(java.util.Comparator.comparing(m -> m.id().toString())).toList();
        assertEquals(0, ForcedModifiers.drawAll(sorted, List.of(), 7L, 0).size());
        assertEquals(2, ForcedModifiers.drawAll(sorted, List.of(), 7L, 2).size());
        assertEquals(3, ForcedModifiers.drawAll(sorted, List.of(), 7L, 10).size(), "only three can ever be forced here");
        assertEquals(ForcedModifiers.drawAll(sorted, List.of(), 7L, 3), ForcedModifiers.drawAll(sorted, List.of(), 7L, 3));
    }
}
