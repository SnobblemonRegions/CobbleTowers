package com.cobbletowers.ascension;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbletowers.economy.ScoutingTiers;
import org.junit.jupiter.api.Test;

/** Balance phase 2: the band a tower fight is at, and the growth that only starts past Threat VII. */
class ThreatPolicyTest {

    @Test void bandIsEntryPlusCyclePlusChampionCappedAtSeven() {
        assertEquals(0, ThreatPolicy.bandFor(0, 3, true), "no entry band: no Threat");
        assertEquals(2, ThreatPolicy.bandFor(2, 0, false));
        assertEquals(3, ThreatPolicy.bandFor(2, 0, true), "the champion is one band up");
        assertEquals(4, ThreatPolicy.bandFor(2, 2, false), "each Ascension cycle adds one");
        assertEquals(7, ThreatPolicy.bandFor(6, 0, true), "VII is reached through the champion or cycles");
        assertEquals(7, ThreatPolicy.bandFor(6, 9, true), "never past VII");
        assertEquals("threat_4", ThreatPolicy.tierId(4));
        assertEquals("threat_7", ThreatPolicy.tierId(12));
    }

    @Test void growthCountsOnlyCyclesPastSeven() {
        assertEquals(3, ThreatPolicy.rampAscension(0, 3), "a run with no band grows every cycle, as before");
        assertEquals(0, ThreatPolicy.rampAscension(3, 3), "Threat III + 3 cycles = VI: no growth yet");
        assertEquals(0, ThreatPolicy.rampAscension(4, 3), "exactly VII: still none");
        assertEquals(2, ThreatPolicy.rampAscension(4, 5), "two cycles past VII");
        assertEquals(0, ThreatPolicy.rampAscension(6, 0));
    }

    @Test void theDoorOffersNoneOrOneToSix() {
        assertEquals(0, ThreatPolicy.clampEntry(-3));
        assertEquals(6, ThreatPolicy.clampEntry(7), "VII is never picked at the door");
        assertEquals(4, ThreatPolicy.clampEntry(4));
    }

    @Test void scoutingTiersUseTheBandOnlyWhenThreatIsOnAndPicked() {
        assertEquals("trial_rank_2", ScoutingTiers.tierFor(0, 0, 6, false, false, true), "no band: floor tiers");
        assertEquals("trial_rank_2", ScoutingTiers.tierFor(3, 0, 6, false, false, false), "Threat off: floor tiers");
        assertEquals("boss", ScoutingTiers.tierFor(3, 0, 5, true, false, false));
        assertEquals("threat_3", ScoutingTiers.tierFor(3, 0, 6, false, false, true));
        assertEquals("threat_4", ScoutingTiers.tierFor(3, 0, 10, true, true, true), "champion one band up");
        assertEquals("threat_5", ScoutingTiers.tierFor(3, 2, 25, true, false, true), "a deeper cycle");
    }
}
