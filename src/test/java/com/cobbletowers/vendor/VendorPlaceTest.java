package com.cobbletowers.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.VendorPlace;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: where a run's vendor is open. One rule for the mob, the catalog and the purchase. */
class VendorPlaceTest {

    @Test
    @DisplayName("the atrium stand at an intermission, the prep stand while the party is in the preparation room, nowhere else")
    void openPlace() {
        assertEquals(Optional.of(VendorPlace.ATRIUM), VendorNpcRules.openPlace(RunState.INTERMISSION, false));
        assertEquals(Optional.of(VendorPlace.PREP), VendorNpcRules.openPlace(RunState.PREPARING, true));
        assertEquals(Optional.empty(), VendorNpcRules.openPlace(RunState.PREPARING, false), "a run waiting on its arena has no room yet");
        assertEquals(Optional.empty(), VendorNpcRules.openPlace(RunState.ENCOUNTER_ACTIVE, false), "never mid-fight (TDS #16)");
        assertEquals(Optional.empty(), VendorNpcRules.openPlace(RunState.ENCOUNTER_ACTIVE, true));
        assertEquals(Optional.empty(), VendorNpcRules.openPlace(RunState.FLOOR_READY, true));
    }
}
