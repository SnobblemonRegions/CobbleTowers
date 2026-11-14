package com.cobbletowers.prep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.prep.PrepRules.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The preparation room's rules (P59): the five-minute clock and the gather, door, fade sequence. */
class PrepRulesTest {

    @Test
    @DisplayName("the party has five minutes from arrival, then the room times out")
    void fiveMinutes() {
        assertEquals(300_000L, PrepRules.TIMEOUT_MILLIS);
        assertEquals(300_000L, PrepRules.remaining(1_000, 1_000));
        assertEquals(1L, PrepRules.remaining(1_000, 300_999));
        assertFalse(PrepRules.timedOut(1_000, 300_999));
        assertTrue(PrepRules.timedOut(1_000, 301_000));
        assertEquals(0L, PrepRules.remaining(1_000, 900_000), "never below zero");
        assertEquals(1f, PrepRules.progress(0, 0));
        assertEquals(0.5f, PrepRules.progress(0, 150_000));
    }

    @Test
    @DisplayName("the clock rounds up, so it never shows 0:00 while time remains")
    void clock() {
        assertEquals("5:00", PrepRules.clock(300_000));
        assertEquals("4:59", PrepRules.clock(299_000));
        assertEquals("0:01", PrepRules.clock(1));
        assertEquals("0:00", PrepRules.clock(0));
    }

    @Test
    @DisplayName("the door opens only when every online fighter is in the ring, and the fade only when every one is on the threshold")
    void sequence() {
        assertEquals(Step.GATHERING, PrepRules.next(Step.GATHERING, 3, 2, 0));
        assertEquals(Step.DOOR_OPEN, PrepRules.next(Step.GATHERING, 3, 3, 0));
        assertEquals(Step.DOOR_OPEN, PrepRules.next(Step.DOOR_OPEN, 3, 0, 2), "the door stays open when someone steps off the ring");
        assertEquals(Step.FADING, PrepRules.next(Step.DOOR_OPEN, 3, 0, 3));
        assertEquals(Step.FADING, PrepRules.next(Step.FADING, 3, 0, 0));
    }

    @Test
    @DisplayName("nobody online is never 'everyone ready': the timeout decides")
    void nobodyOnline() {
        assertEquals(Step.GATHERING, PrepRules.next(Step.GATHERING, 0, 0, 0));
        assertEquals(Step.DOOR_OPEN, PrepRules.next(Step.DOOR_OPEN, 0, 0, 0));
    }
}
