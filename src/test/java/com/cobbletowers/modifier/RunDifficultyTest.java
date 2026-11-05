package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.persistence.RunModifierState;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunDifficultyTest {

    private static ModifierDefinition card(String path, String risk) {
        return TestRuns.modifier(path, "enemy", "\"boss_level_offset\":1", "\"risk\":\"" + risk + "\"");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    @Test
    @DisplayName("an empty run is Standard; each challenge scores by its risk tier, a lock-in scores again, an Ascension scores three")
    void scoring() {
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        for (ModifierDefinition m : new ModifierDefinition[] {card("a", "minor"), card("b", "moderate"), card("c", "severe")}) all.put(m.id(), m);
        var content = TestRuns.contentWith(all);

        assertEquals(new RunDifficulty(0, "Standard", 0, 0), RunDifficulty.of(content, RunModifierState.EMPTY, 0));
        RunModifierState two = RunModifierState.EMPTY.accumulating(id("a")).accumulating(id("c"));
        assertEquals(4, RunDifficulty.of(content, two, 0).points());
        assertEquals("Hard", RunDifficulty.of(content, two, 0).label());
        assertEquals(5, RunDifficulty.of(content, two.lockingIn(id("a")), 0).points(), "a locked-in copy counts again");
        assertEquals(7, RunDifficulty.of(content, two, 1).points(), "an Ascension is three");
    }

    @Test
    @DisplayName("the labels step at 1, 4, 8 and 13 points, and the line reads well")
    void labels() {
        assertEquals("Standard", RunDifficulty.labelOf(0));
        assertEquals("Challenging", RunDifficulty.labelOf(1));
        assertEquals("Challenging", RunDifficulty.labelOf(3));
        assertEquals("Hard", RunDifficulty.labelOf(4));
        assertEquals("Brutal", RunDifficulty.labelOf(8));
        assertEquals("Nightmare", RunDifficulty.labelOf(13));
        assertEquals("Hard (6): 3 challenges, Ascension 1", new RunDifficulty(6, "Hard", 3, 1).line());
        assertEquals("Challenging (1): 1 challenge", new RunDifficulty(1, "Challenging", 1, 0).line());
    }
}
