package com.cobbletowers.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.trick.TrickRules;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The payout seed (audit finding): a run code's seed alone must not decide what the Halloween vault pays. */
class HiddenSeedTest {

    @Test
    void theSameRunReadsTheSameHiddenSeedEveryTime() {
        UUID run = new UUID(11, 22);
        assertEquals(EncounterSeed.hidden(123L, run), EncounterSeed.hidden(123L, run), "stable across a restart");
    }

    @Test
    void twoRunsFromTheSameCodeDoNotShareAHiddenSeed() {
        assertNotEquals(EncounterSeed.hidden(123L, new UUID(1, 2)), EncounterSeed.hidden(123L, new UUID(3, 4)));
        assertNotEquals(EncounterSeed.hidden(123L, new UUID(1, 2)), 123L, "and it is not the code's seed");
    }

    @Test
    void aSeedChosenByCodeCannotPickTheCoinForARunItDoesNotKnowTheIdOf() {
        // A forger finds a code seed whose plain coin is a treat on every floor 1..8. Under the hidden seed, runs started from that
        // same code come out like any other: about half of their floors are tricks, not none.
        long forged = -1;
        for (long candidate = 0; candidate < 200_000 && forged < 0; candidate++) {
            boolean allTreats = true;
            for (int floor = 1; floor <= 8 && allTreats; floor++) {
                allTreats = TrickRules.roll(candidate, floor) == TrickRules.Result.TREAT;
            }
            if (allTreats) forged = candidate;
        }
        assertTrue(forged >= 0, "the search finds a forged seed");
        int tricks = 0;
        int floors = 0;
        for (int run = 0; run < 500; run++) {
            long hidden = EncounterSeed.hidden(forged, new UUID(run, run * 31L + 7));
            for (int floor = 1; floor <= 8; floor++) {
                floors++;
                if (TrickRules.roll(hidden, floor) == TrickRules.Result.TRICK) tricks++;
            }
        }
        assertTrue(Math.abs(tricks - floors / 2) < floors / 10, "about half are tricks, got " + tricks + " of " + floors);
    }
}
