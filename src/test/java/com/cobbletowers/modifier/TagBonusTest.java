package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.persistence.RunModifierState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Battle operations carried as data, and the tag set bonuses (P45). */
class TagBonusTest {

    private static ModifierDefinition relic(String path, String tag, int percent) {
        return TestRuns.modifier(path, "custom", "\"battle\":[{\"op\":\"damage\",\"side\":\"self\",\"percent\":" + percent + "}]",
                "\"relic\":true", "\"tags\":[\"" + tag + "\"]");
    }

    private static ModifierDefinition bonus(String path, String tag, int count) {
        return TestRuns.modifier(path, "reward", "\"reward_percent\":110", "\"tag_bonus\":{\"tag\":\"" + tag + "\",\"count\":" + count + "}");
    }

    private static TowerContent content(ModifierDefinition... modifiers) {
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        for (ModifierDefinition modifier : modifiers) all.put(modifier.id(), modifier);
        return TestRuns.contentWith(all);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    @Test
    @DisplayName("a battle operation is validated at load: a bad one rejects the file")
    void badOperationRefused() {
        assertThrows(IllegalArgumentException.class, () ->
                TestRuns.modifier("x", "custom", "\"battle\":[{\"op\":\"teleport\"}]", "\"relic\":true"));
        // An out-of-range number is clamped by the same validator the armor sets use, not passed on.
        var clamped = TestRuns.modifier("y", "custom", "\"battle\":[{\"op\":\"damage\",\"side\":\"self\",\"percent\":9999}]", "\"relic\":true");
        assertEquals(300, clamped.effect().battle().get(0).getAsJsonObject().get("percent").getAsInt());
    }

    @Test
    @DisplayName("held battle operations reach the battle ops a run sends, after the coded behaviors")
    void opsReachTheBattle() {
        var ops = CustomEffects.of(List.of(relic("a", "offense", 120), relic("b", "offense", 110))).battleOps();
        assertEquals(3, ops.size(), "two relic ops plus the trailing cap op (balance phase 2)");
        assertEquals("cap", ops.get(2).getAsJsonObject().get("op").getAsString());
        assertEquals(120, ops.get(0).getAsJsonObject().get("percent").getAsInt());
        assertTrue(CustomEffects.NONE.battleOps().isEmpty());
    }

    @Test
    @DisplayName("a tag bonus applies from its count of relics with the tag, and not below it")
    void bonusNeedsItsCount() {
        TowerContent content = content(relic("a", "offense", 110), relic("b", "offense", 110), relic("c", "sustain", 110),
                bonus("set2", "offense", 2));
        RunModifierState one = RunModifierState.EMPTY.withRelic(id("a")).withRelic(id("c"));
        RunModifierState two = one.withRelic(id("b"));
        assertTrue(DraftService.setBonusesActive(content, one).isEmpty(), "one offense relic is not a set");
        assertEquals(List.of(id("set2")), DraftService.setBonusesActive(content, two).stream().map(ModifierDefinition::id).toList());
    }

    @Test
    @DisplayName("an active bonus counts in a run's effects but never as a challenge")
    void bonusIsNotAChallenge() {
        TowerContent content = content(relic("a", "offense", 110), relic("b", "offense", 110), bonus("set2", "offense", 2));
        RunModifierState two = RunModifierState.EMPTY.withRelic(id("a")).withRelic(id("b"));
        assertEquals(110, DraftService.effects(content, two).rewardPercent());
        assertEquals(0, two.challengeCount());
    }

    @Test
    @DisplayName("a bonus is never drafted, never in the relic pool, and cannot also be a relic")
    void bonusIsOutOfEveryPool() {
        TowerContent content = content(bonus("set2", "offense", 2), relic("a", "offense", 110));
        assertFalse(content.draftablePool(TestRuns.TOWER, 1).stream().anyMatch(m -> m.tagBonus().isPresent()));
        assertFalse(content.relicPool().stream().anyMatch(m -> m.tagBonus().isPresent()));
        assertEquals(1, content.tagBonuses().size());
        assertThrows(IllegalArgumentException.class, () ->
                TestRuns.modifier("z", "reward", "\"reward_percent\":110", "\"relic\":true", "\"tag_bonus\":{\"tag\":\"x\",\"count\":2}"));
        assertThrows(IllegalArgumentException.class, () -> bonus("w", "x", 1));
    }
}
