package com.cobbletowers.intermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.PersistedDraft;
import com.cobbletowers.persistence.RunModifierState;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The event flow at the doors (TODOs #3, #4, #5, #8): the event cards stand beside the door the party chose (accept on the right, refuse on the left), the
 * door opens only once every pick is made, a refusal says what it is, and refusing a rest pays.
 */
class EventFlowTest {

    // ---- where the event cards stand --------------------------------------------------------------

    private static final PedestalRules.Slot DOOR = new PedestalRules.Slot(100.5, 64, 200.5);

    @Test
    @DisplayName("facing south, the accept card stands on the player's right (west) and the refuse card on the left (east), level with the door's pedestal")
    void facingSouth() {
        List<PedestalRules.Slot> slots = PedestalRules.beside(DOOR, 0, 1, 2).orElseThrow();
        assertEquals(2, slots.size());
        assertTrue(slots.get(0).x() < DOOR.x(), "accept (card 0) is to the west when facing south");
        assertTrue(slots.get(1).x() > DOOR.x(), "refuse (card 1) is to the east when facing south");
        assertEquals(DOOR.z(), slots.get(0).z(), 1e-9);
        assertEquals(DOOR.z(), slots.get(1).z(), 1e-9);
        assertEquals(DOOR.y(), slots.get(0).y(), 1e-9);
    }

    @Test
    @DisplayName("facing east the accept card is to the south, facing north it is to the east, facing west it is to the north")
    void otherFacings() {
        assertTrue(PedestalRules.beside(DOOR, 1, 0, 2).orElseThrow().get(0).z() > DOOR.z());
        assertTrue(PedestalRules.beside(DOOR, 0, -1, 2).orElseThrow().get(0).x() > DOOR.x());
        assertTrue(PedestalRules.beside(DOOR, -1, 0, 2).orElseThrow().get(0).z() < DOOR.z());
    }

    @Test
    @DisplayName("the two cards stand the same distance either side, and only the direction of the facing matters, not its length")
    void symmetricAndScaleFree() {
        List<PedestalRules.Slot> near = PedestalRules.beside(DOOR, 0, 1, 2).orElseThrow();
        List<PedestalRules.Slot> far = PedestalRules.beside(DOOR, 0, 40, 2).orElseThrow();
        assertEquals(near, far);
        assertEquals(DOOR.x() - near.get(0).x(), near.get(1).x() - DOOR.x(), 1e-9);
        assertTrue(Math.abs(near.get(0).x() - near.get(1).x()) >= 2.0, "the cards are far enough apart to be two cards");
    }

    @Test
    @DisplayName("a facing of no length falls back to south instead of putting both cards in one spot")
    void noDirection() {
        List<PedestalRules.Slot> slots = PedestalRules.beside(DOOR, 0, 0, 2).orElseThrow();
        assertNotEquals(slots.get(0), slots.get(1));
    }

    @Test
    @DisplayName("one card stands on the door's own pedestal, and a set of other than one or two is left to the old layout")
    void otherCounts() {
        assertEquals(List.of(DOOR), PedestalRules.beside(DOOR, 0, 1, 1).orElseThrow());
        assertEquals(Optional.empty(), PedestalRules.beside(DOOR, 0, 1, 3));
        assertEquals(Optional.empty(), PedestalRules.beside(DOOR, 0, 1, 0));
    }

    // ---- when the door opens -----------------------------------------------------------------------

    private static final ResourceLocation CARD_A = ResourceLocation.fromNamespaceAndPath("cobbletowers", "event/shrine_curse");
    private static final ResourceLocation CARD_B = ResourceLocation.fromNamespaceAndPath("cobbletowers", "event/shrine_leave");

    @Test
    @DisplayName("a lane with no pick pending opens its door at once, as it always did")
    void doorOpensWhenNothingIsPending() {
        RunModifierState state = RunModifierState.EMPTY.withRouteStep(2, 1);
        assertTrue(DraftService.doorMayOpen(state, 2, false));
    }

    @Test
    @DisplayName("the door stays shut while the event round's pick is open, and opens once it is answered")
    void doorWaitsForTheEventPick() {
        PersistedDraft event = PersistedDraft.openingEvent(2, List.of(CARD_A, CARD_B));
        RunModifierState waiting = RunModifierState.EMPTY.withRouteStep(2, 1).withDraft(event);
        assertFalse(DraftService.doorMayOpen(waiting, 2, false), "an event pick is still open");

        RunModifierState answered = RunModifierState.EMPTY.withRouteStep(2, 1).withDraft(event.resolvedAs(1, false));
        assertTrue(DraftService.doorMayOpen(answered, 2, false));
    }

    @Test
    @DisplayName("no lane taken on this floor means there is no door to open")
    void noLaneNoDoor() {
        assertFalse(DraftService.doorMayOpen(RunModifierState.EMPTY, 2, false));
        assertFalse(DraftService.doorMayOpen(RunModifierState.EMPTY.withRouteStep(1, 1), 2, false), "a lane taken on another floor does not count");
    }

    @Test
    @DisplayName("a pick of a pedestal that stands in front of a door (a draft with no lanes) opens that door once nothing is left to pick")
    void aPedestalPickCountsAsADoorChoice() {
        assertTrue(DraftService.doorMayOpen(RunModifierState.EMPTY, 4, true), "no lane, but a pedestal was picked");
        PersistedDraft event = PersistedDraft.openingEvent(4, List.of(CARD_A, CARD_B));
        assertFalse(DraftService.doorMayOpen(RunModifierState.EMPTY.withDraft(event), 4, true), "still waiting on the event pick");
    }

    // ---- which door a pedestal stands in front of ------------------------------------------------------

    @Test
    @DisplayName("with three cards and three doors, the left pedestal is the left door, the middle the middle and the right the right")
    void threeCardsThreeDoors() {
        assertEquals(0, PedestalRules.doorOfCard(0, 3, 3));
        assertEquals(1, PedestalRules.doorOfCard(1, 3, 3));
        assertEquals(2, PedestalRules.doorOfCard(2, 3, 3));
    }

    @Test
    @DisplayName("two cards stand at the two outer doors, and one card stands at the middle one (the same rule that places the pedestals)")
    void fewerCardsThanDoors() {
        assertEquals(0, PedestalRules.doorOfCard(0, 2, 3));
        assertEquals(2, PedestalRules.doorOfCard(1, 2, 3));
        assertEquals(1, PedestalRules.doorOfCard(0, 1, 3));
    }

    @Test
    @DisplayName("a card that does not stand at a door gives no door, rather than a guess")
    void noSuchDoor() {
        assertEquals(-1, PedestalRules.doorOfCard(3, 3, 3));
        assertEquals(-1, PedestalRules.doorOfCard(-1, 3, 3));
        assertEquals(-1, PedestalRules.doorOfCard(0, 4, 3), "more cards than doors");
        assertEquals(-1, PedestalRules.doorOfCard(0, 0, 3));
        assertEquals(-1, PedestalRules.doorOfCard(0, 3, 0), "no doors");
    }

    @Test
    @DisplayName("every card that atDoors places in front of a door maps back to that same door")
    void agreesWithThePlacement() {
        List<PedestalRules.Slot> doors = List.of(new PedestalRules.Slot(0, 0, 0), new PedestalRules.Slot(10, 0, 0), new PedestalRules.Slot(20, 0, 0));
        for (int count = 1; count <= 3; count++) {
            List<PedestalRules.Slot> placed = PedestalRules.atDoors(List.of(), doors, count).orElseThrow();
            for (int card = 0; card < count; card++) {
                assertEquals(doors.indexOf(placed.get(card)), PedestalRules.doorOfCard(card, count, doors.size()), count + " cards, card " + card);
            }
        }
    }

    // ---- refusing an option says so -----------------------------------------------------------------

    @Test
    @DisplayName("every refusal is worded as a refusal and none of them is the vague old wording")
    void refusalsReadAsRefusals() {
        List<IntermissionEvents.Option> refusals = List.of(IntermissionEvents.Option.SHRINE_LEAVE, IntermissionEvents.Option.GAMBLER_PASS,
                IntermissionEvents.Option.REST_PRESS_ON, IntermissionEvents.Option.FORGE_LEAVE, IntermissionEvents.Option.LIBRARY_SKIP,
                IntermissionEvents.Option.VAULT_LEAVE, IntermissionEvents.Option.POOL_STAY, IntermissionEvents.Option.TRICK_LEAVE,
                IntermissionEvents.Option.ECHO_DECLINE);
        for (IntermissionEvents.Option option : refusals) {
            String label = IntermissionEvents.labelAt(option, 3);
            assertTrue(label.contains("Refuse"), option + ": " + label);
            assertFalse(label.contains("walk away") || label.contains("stay dry") || label.contains("skip it"), option + ": " + label);
        }
    }

    @Test
    @DisplayName("a refusal is the second card of its room and an accept is the first, so the sides are always the same")
    void acceptFirstRefuseSecond() {
        for (IntermissionEvents.Room room : IntermissionEvents.Room.values()) {
            List<ResourceLocation> cards = IntermissionEvents.cardsOf(room);
            if (cards.isEmpty()) continue;
            assertEquals(2, cards.size(), room.toString());
            IntermissionEvents.Option first = IntermissionEvents.Option.fromCard(cards.get(0)).orElseThrow();
            IntermissionEvents.Option second = IntermissionEvents.Option.fromCard(cards.get(1)).orElseThrow();
            assertFalse(IntermissionEvents.isRefusal(first), room + " card 0 must be the accept: " + first);
            assertTrue(IntermissionEvents.isRefusal(second), room + " card 1 must be the refusal: " + second);
        }
    }

    // ---- refusing a rest pays ------------------------------------------------------------------------

    @Test
    @DisplayName("the rest purse is at least twice the vendor's 25 CobbleDollar full heal and grows with the floor")
    void restPurse() {
        assertTrue(IntermissionEvents.restPurse(1) >= 50);
        assertTrue(IntermissionEvents.restPurse(8) > IntermissionEvents.restPurse(1));
    }

    @Test
    @DisplayName("refusing the rest label names the purse; resting stays free")
    void restLabels() {
        String refuse = IntermissionEvents.labelAt(IntermissionEvents.Option.REST_PRESS_ON, 4);
        assertTrue(refuse.contains(String.valueOf(IntermissionEvents.restPurse(4))), refuse);
        assertTrue(refuse.contains("CobbleDollars"), refuse);
        assertEquals("Rest: fully heal the party", IntermissionEvents.labelAt(IntermissionEvents.Option.REST_HEAL, 4));
    }
}
