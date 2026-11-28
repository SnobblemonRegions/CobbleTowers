package com.cobbletowers.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeasonChestLayoutTest {

    @Test
    @DisplayName("Season Chest (23 Nov 2026): the path winds through the top five rows, each tier beside the one before, and reads back")
    void path() {
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < SeasonChestLayout.PER_PAGE; i++) {
            int slot = SeasonChestLayout.slotOf(i);
            assertTrue(slot >= 0 && slot < 45, "off the path rows: " + slot);
            assertTrue(used.add(slot), "two tiers in one slot");
            assertEquals(i, SeasonChestLayout.indexAt(slot));
            if (i > 0) {
                int prev = SeasonChestLayout.slotOf(i - 1);
                int steps = Math.abs(prev / 9 - slot / 9) + Math.abs(prev % 9 - slot % 9);
                assertEquals(1, steps, "tier " + i + " is not beside tier " + (i - 1));
            }
        }
        assertEquals(-1, SeasonChestLayout.indexAt(SeasonChestLayout.CLAIM_ALL_SLOT));
        assertEquals(1, SeasonChestLayout.pages(30));               // the default track fits one page
        assertEquals(2, SeasonChestLayout.pages(46));
        assertEquals(1, SeasonChestLayout.pageOf(45, 46));
    }

    @Test
    @DisplayName("Season Chest: the sixteen floor marks share the track out, none lit at the start and all lit at its end")
    void marks() {
        for (int k = 0; k < 16; k++) assertFalse(SeasonChestLayout.lit(k, 16, 0, 30));
        for (int k = 0; k < 16; k++) assertTrue(SeasonChestLayout.lit(k, 16, 30, 30));
        assertFalse(SeasonChestLayout.lit(15, 16, 29, 30));               // the last mark waits for the last tier
        int lit = 0;
        for (int k = 0; k < 16; k++) if (SeasonChestLayout.lit(k, 16, 15, 30)) lit++;
        assertEquals(8, lit);                                             // half the season, half the ring
        assertFalse(SeasonChestLayout.lit(0, 16, 5, 0));                  // no track, nothing lit
    }
}
