package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.persistence.RunModifierState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The room behind the door the party chose must stay what the lane promised while that room's event round is open (found in the first rig test: picking a
 * Shrine lane replaced the shrine room with a battle gauntlet, and the arrival hall was dressed as a battle too).
 */
class RoomDresserKindsTest {

    private static final String B = RouteGraph.Kind.BATTLE.name();
    private static final String SHRINE = RouteGraph.Kind.SHRINE.name();
    private static final String REST = RouteGraph.Kind.REST.name();
    private static final String TOT = RouteGraph.Kind.TRICK_OR_TREAT.name();

    @Test
    @DisplayName("while a lane's event round is open (a draft with no lanes), every chamber keeps what it was dressed as, so the shrine stays a shrine")
    void keepsWhatWasDressed() {
        assertEquals(List.of(B, SHRINE, B), RoomDresser.kindsWithoutRoute(List.of(B, SHRINE, B), 3, 1, SHRINE, false, -1));
    }

    @Test
    @DisplayName("after a restart nothing is remembered as dressed, but the chosen lane's kind is in the run, so its room comes back and the others are battles")
    void restoresTheChosenLaneAfterARestart() {
        assertEquals(List.of(B, B, REST), RoomDresser.kindsWithoutRoute(List.of(), 3, 2, REST, false, -1));
    }

    @Test
    @DisplayName("the chosen lane's recorded kind wins over a stale dressing, so an earlier wrong room is corrected")
    void theRecordedKindWins() {
        assertEquals(List.of(B, SHRINE, B), RoomDresser.kindsWithoutRoute(List.of(B, B, B), 3, 1, SHRINE, false, -1));
    }

    @Test
    @DisplayName("with no recorded kind but a vault room held, the middle-or-last-lane rule still puts the Trick or Treat room behind the lane taken")
    void theVaultRuleStillWorks() {
        assertEquals(List.of(B, TOT, B), RoomDresser.kindsWithoutRoute(List.of(B, B, B), 3, -1, "", true, 1));
    }

    @Test
    @DisplayName("a chosen lane outside the doors is ignored rather than throwing")
    void outOfRange() {
        assertEquals(List.of(B, B, B), RoomDresser.kindsWithoutRoute(List.of(), 3, 7, SHRINE, false, -1));
        assertEquals(List.of(B, B, B), RoomDresser.kindsWithoutRoute(List.of(), 3, -1, SHRINE, false, -1));
    }

    @Test
    @DisplayName("a blank dressing (a room that could not be placed) is a battle until it is tried again")
    void blankIsABattle() {
        assertEquals(List.of(B, SHRINE, B), RoomDresser.kindsWithoutRoute(List.of("", SHRINE, ""), 3, -1, "", false, -1));
    }

    // ---- the run remembers what each route step was --------------------------------------------------

    @Test
    @DisplayName("the kind of the stop taken after a floor is read back from the run, and a floor with no step reads as blank")
    void routeKindAtFloor() {
        RunModifierState state = RunModifierState.EMPTY.withRouteStep(1, 0, B).withRouteStep(2, 2, SHRINE).withRouteStep(3, 1, REST);
        assertEquals(B, state.routeKindAtFloor(1));
        assertEquals(SHRINE, state.routeKindAtFloor(2));
        assertEquals(REST, state.routeKindAtFloor(3));
        assertEquals("", state.routeKindAtFloor(4));
        assertEquals("", RunModifierState.EMPTY.routeKindAtFloor(1));
    }

    @Test
    @DisplayName("a step taken before kinds were kept reads as blank, never as the next step's kind")
    void oldStepsAreBlank() {
        RunModifierState state = RunModifierState.EMPTY.withRouteStep(1, 0).withRouteStep(2, 1, SHRINE);
        assertEquals("", state.routeKindAtFloor(1));
        assertEquals(SHRINE, state.routeKindAtFloor(2));
    }
}
