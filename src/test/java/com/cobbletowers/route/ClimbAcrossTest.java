package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.definition.FloorLayout;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: a two-act tower's floor 5 climbs its lane stair into Act II, which is another building; a detour still does not. */
class ClimbAcrossTest {

    private static final FloorAnchor AT = new FloorAnchor(1, 2, 3, 0);
    private static final FloorBox BOX = new FloorBox(0, 0, 0, 1, 1, 1);

    private static FloorLayout stairs(String structure) {
        FloorDoor door = new FloorDoor(BOX, BOX, AT, Optional.of(BOX), Optional.of(AT));
        return new FloorLayout(ResourceLocation.fromNamespaceAndPath("cobbletowers", structure), AT, AT, AT, AT, Optional.of(BOX), Optional.empty(),
                List.of(door, door, door));
    }

    private static FloorLayout plain(String structure) {
        return new FloorLayout(ResourceLocation.fromNamespaceAndPath("cobbletowers", structure), AT, AT, AT, AT);
    }

    @Test
    @DisplayName("floor 5 of the own tower climbs into Act II")
    void intoTheNextAct() {
        assertTrue(WalkRules.climbsAcross(stairs("neutral_act1"), Optional.of(stairs("neutral_act2")), true));
        assertFalse(WalkRules.onFoot(stairs("neutral_act1"), Optional.of(stairs("neutral_act2"))), "on foot still means the same building");
    }

    @Test
    @DisplayName("not into a detour, not within one building, not from a floor without lane stairs, not past the last floor")
    void onlyBetweenOwnBuildings() {
        assertFalse(WalkRules.climbsAcross(stairs("neutral_act1"), Optional.of(stairs("duskvale_act1")), false), "a detour is left by teleport");
        assertFalse(WalkRules.climbsAcross(stairs("neutral_act1"), Optional.of(stairs("neutral_act1")), true), "the same building is onFoot's");
        assertFalse(WalkRules.climbsAcross(plain("neutral_act1"), Optional.of(stairs("neutral_act2")), true));
        assertFalse(WalkRules.climbsAcross(stairs("neutral_act1"), Optional.empty(), true));
    }
}
