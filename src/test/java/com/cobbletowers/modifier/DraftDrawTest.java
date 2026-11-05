package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.encounter.EncounterSeed;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three cards: deterministic, distinct, and drawn clear of every other draw in the run. */
class DraftDrawTest {

    private static List<ModifierDefinition> pool(int size) {
        List<ModifierDefinition> pool = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pool.add(TestRuns.modifier("modifier_" + i, "enemy", "\"boss_level_offset\":" + (i + 1)));
        }
        return pool;
    }

    @Test
    @DisplayName("the same run and floor always draw the same three cards")
    void deterministic() {
        List<ModifierDefinition> pool = pool(8);
        assertEquals(DraftDraw.draw(pool, 12345L, 3), DraftDraw.draw(pool, 12345L, 3),
                "a crash mid-draft must not be able to reroll the offer (TDS #29)");
    }

    @Test
    @DisplayName("a different floor draws a different offer")
    void variesByFloor() {
        List<ModifierDefinition> pool = pool(8);
        assertNotEquals(DraftDraw.draw(pool, 12345L, 1), DraftDraw.draw(pool, 12345L, 2));
    }

    @Test
    @DisplayName("three cards are three different modifiers")
    void withoutReplacement() {
        List<ModifierDefinition> cards = DraftDraw.draw(pool(8), 999L, 1);
        assertEquals(DraftDraw.CARDS, cards.size());
        assertEquals(DraftDraw.CARDS, new HashSet<>(cards).size(), "the same card twice is not a choice");
    }

    @Test
    @DisplayName("a pool smaller than three offers what it has, without padding")
    void shortPool() {
        assertEquals(2, DraftDraw.draw(pool(2), 7L, 1).size());
        assertEquals(1, DraftDraw.draw(pool(1), 7L, 1).size());
        assertTrue(DraftDraw.draw(List.of(), 7L, 1).isEmpty(), "nothing to offer means no draft");
    }

    @Test
    @DisplayName("the draft's ordinals never collide with the opponents' or the boss's")
    void ownOrdinalSpace() {
        // The property that matters, checked as a property: no seed a draft uses is a seed anything
        // else on the floor uses. Asserting the constant is 2_000_029 would only restate the code.
        long seed = 4242L;
        Set<Long> others = new HashSet<>();
        for (int floor = 1; floor <= 10; floor++) {
            for (int opponent = 0; opponent < 8; opponent++) {
                others.add(EncounterSeed.of(seed, floor, opponent));
            }
            // BossDraw.BOSS_ORDINAL, which is package-private there and so is written out here.
            others.add(EncounterSeed.of(seed, floor, 1_000_003));
        }
        for (int floor = 1; floor <= 10; floor++) {
            for (int card = 0; card < DraftDraw.CARDS; card++) {
                assertTrue(others.add(EncounterSeed.of(seed, floor, DraftDraw.DRAFT_ORDINAL_BASE + card)),
                        "draft card " + card + " on floor " + floor + " shares a seed with another draw");
            }
        }
    }

    @Test
    @DisplayName("weight decides how often a card comes up")
    void weighted() {
        ModifierDefinition common = TestRuns.modifier("common", "enemy", "\"boss_level_offset\":1", "\"weight\":900");
        ModifierDefinition rare = TestRuns.modifier("rare", "enemy", "\"boss_level_offset\":2", "\"weight\":100");

        int commonFirst = 0;
        for (int floor = 1; floor <= 200; floor++) {
            if (DraftDraw.draw(List.of(common, rare), 1L, floor).get(0).equals(common)) commonFirst++;
        }
        assertTrue(commonFirst > 150, "a 9:1 weight should lead the offer far more often, got " + commonFirst);
    }
}
