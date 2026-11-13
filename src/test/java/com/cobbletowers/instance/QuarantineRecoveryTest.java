package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.persistence.CellQuarantine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuarantineRecoveryTest {

    private static final long SINCE = 1_791_074_979_803L; // cell 0 on the live server, quarantined in early October
    private static final long LATER = SINCE + QuarantineRecovery.GRACE_MILLIS;

    @Test
    @DisplayName("an automatic quarantine returns once it is old enough, unleased and empty of players")
    void automaticQuarantineRecovers() {
        var entry = new CellQuarantine(0, "3 player(s) still inside: [a, b, c]", SINCE);
        assertTrue(QuarantineRecovery.recoverable(entry, false, 0, LATER));
        assertTrue(QuarantineRecovery.recoverable(new CellQuarantine(5, "1 entity/entities still inside", SINCE), false, 0, LATER + 1),
                "a stray entity is swept by the next build, so it is no reason to keep the cell out");
    }

    @Test
    @DisplayName("it stays out while a player is inside, a run holds it, or it is too fresh")
    void staysOutWhileUnsafe() {
        var entry = new CellQuarantine(0, "release threw", SINCE);
        assertFalse(QuarantineRecovery.recoverable(entry, false, 1, LATER), "a player is still inside");
        assertFalse(QuarantineRecovery.recoverable(entry, true, 0, LATER), "a run still holds the cell");
        assertFalse(QuarantineRecovery.recoverable(entry, false, 0, LATER - 1), "a party may still be walking out");
    }

    @Test
    @DisplayName("a quarantine an operator set by hand is never returned automatically, and survives a save")
    void manualQuarantineStays() {
        var manual = new CellQuarantine(2, "operator: rebuilding the floor", SINCE, true);
        assertFalse(QuarantineRecovery.recoverable(manual, false, 0, LATER + 365L * 24 * 3600 * 1000));
        assertTrue(CellQuarantine.fromTag(manual.toTag()).manual());
        assertFalse(CellQuarantine.fromTag(new CellQuarantine(2, "auto", SINCE).toTag()).manual(), "older files read as automatic");
    }
}
