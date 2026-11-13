package com.cobbletowers.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DailyCompletionBudgetTest {

    @Test
    void thePayoutStepsDownAfterThreeAndAfterSix() {
        for (int done = 0; done < 3; done++) assertEquals(100, DailyCompletionBudget.percentFor(done));
        for (int done = 3; done < 6; done++) assertEquals(50, DailyCompletionBudget.percentFor(done));
        assertEquals(25, DailyCompletionBudget.percentFor(6));
        assertEquals(25, DailyCompletionBudget.percentFor(22));
    }

    @Test
    void reachingTheTopAlwaysCounts() {
        assertTrue(DailyCompletionBudget.countsAsCompletion(com.cobbletowers.api.tower.RunState.COMPLETED, 10, 10));
    }

    @Test
    void cashingOutPastHalfwayCounts() {
        var cashedOut = com.cobbletowers.api.tower.RunState.CASHED_OUT;
        assertTrue(DailyCompletionBudget.countsAsCompletion(cashedOut, 9, 10), "one floor short of the top");
        assertTrue(DailyCompletionBudget.countsAsCompletion(cashedOut, 5, 10));
        assertFalse(DailyCompletionBudget.countsAsCompletion(cashedOut, 4, 10), "bailing early stays cheap");
        assertTrue(DailyCompletionBudget.countsAsCompletion(cashedOut, 3, 5), "odd towers round the halfway floor up");
        assertFalse(DailyCompletionBudget.countsAsCompletion(cashedOut, 0, 10), "a cash-out that priced nothing");
    }

    @Test
    void midRunAndParkedPayoutsDoNotCount() {
        assertFalse(DailyCompletionBudget.countsAsCompletion(com.cobbletowers.api.tower.RunState.INTERMISSION, 5, 10));
        assertFalse(DailyCompletionBudget.countsAsCompletion(com.cobbletowers.api.tower.RunState.RECOVERY_REQUIRED, 9, 10));
    }

    @Test
    void fullPayoutAlwaysKeepsEverything() {
        for (long seed = 0; seed < 500; seed++) assertTrue(DailyCompletionBudget.keeps(seed, 100));
    }

    @Test
    void aReducedPayoutKeepsAboutItsShareAndRepeats() {
        for (int percent : new int[] {50, 25}) {
            int kept = 0;
            for (long seed = 0; seed < 20_000; seed++) {
                boolean once = DailyCompletionBudget.keeps(seed, percent);
                assertEquals(once, DailyCompletionBudget.keeps(seed, percent));
                if (once) kept++;
            }
            double rate = kept * 100.0 / 20_000;
            assertTrue(Math.abs(rate - percent) < 2.0, percent + "% kept " + rate);
        }
        assertFalse(DailyCompletionBudget.keeps(1L, 0));
    }
}
