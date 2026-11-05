package com.cobbletowers.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VendorNpcRulesTest {

    private static final UUID RUN = UUID.randomUUID();

    @Test
    void aTagRoundTripsItsRun() {
        assertEquals(Optional.of(RUN), VendorNpcRules.runOf(VendorNpcRules.runTag(RUN)));
    }

    @Test
    void otherTagsAndMalformedIdsNameNoRun() {
        assertTrue(VendorNpcRules.runOf("cobbletowers_vendor").isEmpty());
        assertTrue(VendorNpcRules.runOf("something_else").isEmpty());
        assertTrue(VendorNpcRules.runOf("cobbletowers_vendor_run:not-a-uuid").isEmpty());
    }

    @Test
    void aParticipantMayUseTheVendorAtAnIntermission() {
        assertEquals(VendorNpcRules.Access.ALLOWED, VendorNpcRules.access(RUN, Optional.of(RUN), true));
    }

    @Test
    void anOutsiderOrAnotherTeamIsRefused() {
        assertEquals(VendorNpcRules.Access.NOT_IN_THIS_RUN, VendorNpcRules.access(RUN, Optional.empty(), true));
        assertEquals(VendorNpcRules.Access.NOT_IN_THIS_RUN,
                VendorNpcRules.access(RUN, Optional.of(UUID.randomUUID()), true));
    }

    @Test
    void theVendorIsClosedOutsideAnIntermission() {
        assertEquals(VendorNpcRules.Access.CLOSED, VendorNpcRules.access(RUN, Optional.of(RUN), false));
    }

    @Test
    void everyRefusalSaysSomething() {
        assertEquals("", VendorNpcRules.describe(VendorNpcRules.Access.ALLOWED));
        assertTrue(!VendorNpcRules.describe(VendorNpcRules.Access.CLOSED).isEmpty());
        assertTrue(!VendorNpcRules.describe(VendorNpcRules.Access.NOT_IN_THIS_RUN).isEmpty());
    }

    @Test
    void onlyTheHalloweenTowerHasTheGoblinWitch() {
        assertTrue(VendorNpcRules.isGoblinWitch(com.cobbletowers.CobbleTowers.id("halloween")));
        assertTrue(!VendorNpcRules.isGoblinWitch(com.cobbletowers.CobbleTowers.id("neutral")));
        assertTrue(!VendorNpcRules.witchGreeting(1).isEmpty());
    }

    @Test
    void theWitchSpeaksOnEveryFloorAndNeverRepeatsOnTheNext() {
        for (int floor = 1; floor <= 12; floor++) {
            assertTrue(!VendorNpcRules.witchGreeting(floor).isBlank(), "floor " + floor + " is silent");
            if (floor > 1) assertNotEquals(VendorNpcRules.witchGreeting(floor - 1), VendorNpcRules.witchGreeting(floor), "floors " + (floor - 1) + " and " + floor);
        }
    }

    @Test
    void theWitchTalksDifferentlyEarlyMiddleLateAndAtTheBoss() {
        java.util.Set<String> bands = new java.util.HashSet<>();
        for (int floor : new int[] {1, 4, 7, 10}) {
            String line = VendorNpcRules.witchGreeting(floor);
            assertTrue(bands.add(line), "floor " + floor + " repeats an earlier band's line");
        }
        assertTrue(VendorNpcRules.witchGreeting(10).contains("last door"));
    }

    @Test
    void aFloorOutsideTheTowerStillGetsAGreeting() {
        assertEquals(VendorNpcRules.witchGreeting(1), VendorNpcRules.witchGreeting(0));
        assertEquals(VendorNpcRules.witchGreeting(1), VendorNpcRules.witchGreeting(-5));
        assertTrue(!VendorNpcRules.witchGreeting(99).isBlank());
    }

    @Test
    void yawFacesTheTarget() {
        assertEquals(0f, VendorNpcRules.yawToward(0, 0, 0, 5), 0.01f);     // south
        assertEquals(180f, Math.abs(VendorNpcRules.yawToward(0, 0, 0, -5)), 0.01f); // north
        assertEquals(-90f, VendorNpcRules.yawToward(0, 0, 5, 0), 0.01f);   // east
        assertEquals(90f, VendorNpcRules.yawToward(0, 0, -5, 0), 0.01f);   // west
    }
}
