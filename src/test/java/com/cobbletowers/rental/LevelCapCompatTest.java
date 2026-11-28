package com.cobbletowers.rental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LevelCapCompatTest {

    @Test
    @DisplayName("a rental below the cap keeps its floor level")
    void underCap() {
        assertEquals(50, LevelCapCompat.capped(50, 85));
        assertEquals(80, LevelCapCompat.capped(RentalScaling.levelFor(12), 85));
    }

    @Test
    @DisplayName("from floor 13 a player capped at 85 gets level 85 rentals, not 90 or 100")
    void heldAtCap() {
        assertEquals(85, LevelCapCompat.capped(RentalScaling.levelFor(13), 85));
        assertEquals(85, LevelCapCompat.capped(RentalScaling.levelFor(16), 85));
        assertEquals(85, LevelCapCompat.capped(RentalScaling.levelFor(5000), 85));
    }

    @Test
    @DisplayName("a player at cap 100 (or with no cap mod) climbs exactly as before")
    void noCapChangesNothing() {
        for (int floor = 1; floor <= 40; floor++) {
            assertEquals(RentalScaling.levelFor(floor), LevelCapCompat.capped(RentalScaling.levelFor(floor), RentalScaling.MAX_LEVEL), "floor " + floor);
        }
    }

    @Test
    @DisplayName("a low cap holds the starting team down too, and never below level 1")
    void lowCaps() {
        assertEquals(20, LevelCapCompat.capped(RentalScaling.BASE_LEVEL, 20));
        assertEquals(1, LevelCapCompat.capped(50, 0));
    }
}
