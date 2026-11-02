package com.cobbletowers.trick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CatchRulesTest {

    @Test
    void laterThrowsAreEasierAndDeeperFloorsHarder() {
        assertTrue(CatchRules.chancePercent(2, 3) > CatchRules.chancePercent(1, 3));
        assertTrue(CatchRules.chancePercent(1, 9) < CatchRules.chancePercent(1, 1));
    }

    @Test
    void chanceStaysInBounds() {
        for (int t = 1; t <= CatchRules.THROWS; t++) {
            for (int f = 0; f <= 40; f++) {
                int c = CatchRules.chancePercent(t, f);
                assertTrue(c >= 5 && c <= 95, "throw " + t + " floor " + f + " -> " + c);
            }
        }
    }

    @Test
    void draws_areSeededAndRoughlyOnRate() {
        int rares = 0;
        for (long seed = 0; seed < 4000; seed++) {
            boolean a = CatchRules.treatHasRare(seed, 4);
            assertEquals(a, CatchRules.treatHasRare(seed, 4));
            if (a) rares++;
        }
        assertTrue(rares > 400 && rares < 800, "rares " + rares);
        assertEquals(CatchRules.species(7, 3), CatchRules.species(7, 3));
    }

    @Test
    void throwsAreDeterministicAndExtremesHold() {
        assertEquals(CatchRules.lands(50, 9, 2, 1234L, 1), CatchRules.lands(50, 9, 2, 1234L, 1));
        assertFalse(CatchRules.lands(0, 9, 2, 1234L, 1));
        assertTrue(CatchRules.lands(100, 9, 2, 1234L, 1));
    }
}
