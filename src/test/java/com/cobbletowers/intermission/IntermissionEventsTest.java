package com.cobbletowers.intermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.intermission.IntermissionEvents.Option;
import com.cobbletowers.intermission.IntermissionEvents.Room;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Intermission event rooms (P34b): when they appear, and what each option does. */
class IntermissionEventsTest {

    private static final UUID RUN = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static ModifierDefinition relic(String path) {
        return TestRuns.modifier(path, "reward", "\"reward_percent\":125", "\"relic\":true", "\"tags\":[\"economy\"]");
    }

    private static ModifierDefinition curse(String path) {
        return TestRuns.modifier(path, "enemy", "\"boss_level_offset\":3", "\"risk\":\"severe\"");
    }

    private static TowerContent content(ModifierDefinition... modifiers) {
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        for (ModifierDefinition modifier : modifiers) all.put(modifier.id(), modifier);
        return TestRuns.contentWith(all);
    }

    private static PersistedRun run(long seed, int floor, RunModifierState state) {
        PersistedRun base = TestRuns.fresh(RUN);
        return new PersistedRun(base.runId(), base.schemaVersion(), base.towerId(), base.towerRevision(),
                base.towerDigest(), base.rulesetRevision(), base.structureRevision(), seed, floor, base.state(),
                base.participants(), base.lastCheckpoint(), base.committedTransactions(), base.updatedAt(),
                base.cell(), base.ledger(), state, base.lastBankedFloor(), base.vendorPurchases());
    }

    @Test
    @DisplayName("every room offers two options, each a card the option table can read back")
    void roomsHaveTwoReadableOptions() {
        for (Room room : Room.values()) {
            assertEquals(2, IntermissionEvents.cardsOf(room).size(), room + " offers two options");
            for (ResourceLocation card : IntermissionEvents.cardsOf(room)) {
                assertEquals(room, Option.fromCard(card).orElseThrow().room());
            }
        }
    }

    @Test
    @DisplayName("a milestone floor has no ordinary room (it pays a relic); the Echo Duel is its only room, and only with an Echo to meet")
    void milestoneRooms() {
        TowerContent content = content();
        for (long seed = 0; seed < 200; seed++) {
            PersistedRun milestone = run(seed, 2, RunModifierState.EMPTY);
            assertTrue(IntermissionEvents.roomFor(content, milestone, 2, false).isEmpty());
            assertEquals(Room.ECHO_DUEL, IntermissionEvents.roomFor(content, milestone, 2, true).orElseThrow());
            assertTrue(IntermissionEvents.roomFor(content, run(seed, 1, RunModifierState.EMPTY), 1, true)
                    .filter(room -> room == Room.ECHO_DUEL).isEmpty(), "an ordinary floor never offers the duel");
        }
    }

    @Test
    @DisplayName("fighting the Echo starts a duel and changes nothing about the run; declining does neither")
    void duelOptions() {
        PersistedRun run = run(3, 2, RunModifierState.EMPTY);
        var fight = IntermissionEvents.resolve(content(), run, 2, Option.ECHO_FIGHT);
        assertTrue(fight.duel());
        assertEquals(run.modifiers(), fight.state());
        var decline = IntermissionEvents.resolve(content(), run, 2, Option.ECHO_DECLINE);
        assertFalse(decline.duel());
        assertEquals(2, IntermissionEvents.cardsOf(Room.ECHO_DUEL).size());
    }

    @Test
    @DisplayName("an ordinary floor has a room about half the time, and the same seed always gives the same answer")
    void roomChance() {
        TowerContent content = content();
        int rooms = 0;
        for (long seed = 0; seed < 400; seed++) {
            PersistedRun run = run(seed, 1, RunModifierState.EMPTY);
            var first = IntermissionEvents.roomFor(content, run, 1, false);
            assertEquals(first, IntermissionEvents.roomFor(content, run, 1, false));
            if (first.isPresent()) rooms++;
        }
        assertTrue(rooms > 140 && rooms < 260, "expected about 200 of 400, got " + rooms);
    }

    @Test
    @DisplayName("the shrine needs a relic to give and a challenge to charge; the gambler needs a relic to stake")
    void eligibility() {
        TowerContent bare = content();
        PersistedRun empty = run(1, 1, RunModifierState.EMPTY);
        assertTrue(IntermissionEvents.eligible(bare, empty, 1, Room.REST));
        assertFalse(IntermissionEvents.eligible(bare, empty, 1, Room.SHRINE));
        assertFalse(IntermissionEvents.eligible(bare, empty, 1, Room.GAMBLER));

        ModifierDefinition lucky = relic("lucky");
        TowerContent full = content(lucky, curse("harder"));
        assertTrue(IntermissionEvents.eligible(full, empty, 1, Room.SHRINE));
        assertFalse(IntermissionEvents.eligible(full, empty, 1, Room.GAMBLER));
        assertTrue(IntermissionEvents.eligible(full, run(1, 1, RunModifierState.EMPTY.withRelic(lucky.id())), 1,
                Room.GAMBLER));
    }

    @Test
    @DisplayName("taking the curse adds a challenge and a relic; walking away changes nothing")
    void shrine() {
        ModifierDefinition lucky = relic("lucky");
        ModifierDefinition harder = curse("harder");
        TowerContent content = content(lucky, harder);
        PersistedRun run = run(7, 1, RunModifierState.EMPTY);

        var taken = IntermissionEvents.resolve(content, run, 1, Option.SHRINE_CURSE);
        assertEquals(java.util.List.of(harder.id()), taken.state().accumulated());
        assertEquals(java.util.List.of(lucky.id()), taken.state().relics());

        var left = IntermissionEvents.resolve(content, run, 1, Option.SHRINE_LEAVE);
        assertEquals(run.modifiers(), left.state());
    }

    @Test
    @DisplayName("a gamble either wins a second relic or loses the staked one, by seed, and passing changes nothing")
    void gambler() {
        ModifierDefinition a = relic("a");
        ModifierDefinition b = relic("b");
        TowerContent content = content(a, b);
        boolean won = false;
        boolean lost = false;
        for (long seed = 0; seed < 60 && !(won && lost); seed++) {
            PersistedRun run = run(seed, 1, RunModifierState.EMPTY.withRelic(a.id()));
            var outcome = IntermissionEvents.resolve(content, run, 1, Option.GAMBLER_STAKE);
            if (outcome.state().relics().size() == 2) {
                won = true;
                assertTrue(outcome.state().relics().contains(b.id()));
            } else {
                lost = true;
                assertTrue(outcome.state().relics().isEmpty());
            }
            assertEquals(outcome, IntermissionEvents.resolve(content, run, 1, Option.GAMBLER_STAKE),
                    "a crash and a replay cannot turn a lost gamble into a won one");
            assertEquals(run.modifiers(), IntermissionEvents.resolve(content, run, 1, Option.GAMBLER_PASS).state());
        }
        assertTrue(won && lost, "both outcomes are reachable");
    }

    @Test
    @DisplayName("resting heals the party; pressing on does not")
    void rest() {
        PersistedRun run = run(1, 1, RunModifierState.EMPTY);
        assertTrue(IntermissionEvents.resolve(content(), run, 1, Option.REST_HEAL).healParty());
        assertFalse(IntermissionEvents.resolve(content(), run, 1, Option.REST_PRESS_ON).healParty());
    }

    @Test
    @DisplayName("refusing the rest pays every player a purse that grows with the floor; resting is free")
    void refusingTheRestPays() {
        PersistedRun run = run(1, 1, RunModifierState.EMPTY);
        var pressed = IntermissionEvents.resolve(content(), run, 1, Option.REST_PRESS_ON);
        assertEquals(IntermissionEvents.restPurse(1), pressed.walletEach());
        assertTrue(pressed.walletEach() >= 50, "at least twice the vendor's 25 CobbleDollar heal");
        assertTrue(IntermissionEvents.resolve(content(), run, 6, Option.REST_PRESS_ON).walletEach() > pressed.walletEach());
        assertEquals(0, IntermissionEvents.resolve(content(), run, 1, Option.REST_HEAL).walletEach());
        assertTrue(pressed.message().contains(String.valueOf(pressed.walletEach())), pressed.message());
    }

    @Test
    @DisplayName("every refusal answers in its own words, says it was a refusal, and none is the old generic line")
    void refusalsAnswerSpecifically() {
        PersistedRun run = run(1, 1, RunModifierState.EMPTY);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Option option : Option.values()) {
            if (!IntermissionEvents.isRefusal(option)) continue;
            String message = IntermissionEvents.resolve(content(), run, 1, option).message();
            assertFalse(message.equals("You press on."), option + " still answers with the generic line");
            assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("refuse"), option + ": " + message);
            assertTrue(seen.add(message), option + " repeats another refusal's line: " + message);
        }
        assertEquals(9, seen.size(), "one refusal per room");
    }

    // ---- P45 rooms ---------------------------------------------------------------------------------

    private static ModifierDefinition tagged(String path, String tag) {
        return TestRuns.modifier(path, "reward", "\"reward_percent\":125", "\"relic\":true", "\"tags\":[\"" + tag + "\"]");
    }

    @Test
    @DisplayName("the Forge gives a battle relic and charges every player; it needs one to give, room, and everyone able to pay")
    void forge() {
        ModifierDefinition blade = tagged("blade", "offense");
        ModifierDefinition purse = tagged("purse", "economy");
        PersistedRun run = run(7, 3, RunModifierState.EMPTY);
        int cost = IntermissionEvents.forgeCost(3);
        assertEquals(175, cost);

        TowerContent economyOnly = content(purse);
        assertFalse(IntermissionEvents.eligible(economyOnly, run, 3, Room.FORGE), "an economy relic is not a battle relic");
        TowerContent content = content(blade, purse);
        assertTrue(IntermissionEvents.eligible(content, run, 3, Room.FORGE, id -> cost));
        assertFalse(IntermissionEvents.eligible(content, run, 3, Room.FORGE, id -> cost - 1), "everyone must afford it");

        var paid = IntermissionEvents.resolve(content, run, 3, Option.FORGE_PAY, id -> cost);
        assertEquals(java.util.List.of(blade.id()), paid.state().relics());
        assertEquals(-cost, paid.walletEach());
        var broke = IntermissionEvents.resolve(content, run, 3, Option.FORGE_PAY, id -> 0);
        assertEquals(run.modifiers(), broke.state(), "someone who can no longer pay makes the forge do nothing");
        assertEquals(0, broke.walletEach());
        var left = IntermissionEvents.resolve(content, run, 3, Option.FORGE_LEAVE, id -> cost);
        assertEquals(run.modifiers(), left.state());
        assertEquals(0, left.walletEach());
    }

    @Test
    @DisplayName("the Library gives a scouting relic and nothing else")
    void library() {
        ModifierDefinition lens = tagged("lens", "scouting");
        ModifierDefinition blade = tagged("blade", "offense");
        PersistedRun run = run(5, 1, RunModifierState.EMPTY);
        assertFalse(IntermissionEvents.eligible(content(blade), run, 1, Room.LIBRARY));
        TowerContent content = content(lens, blade);
        assertTrue(IntermissionEvents.eligible(content, run, 1, Room.LIBRARY));
        assertEquals(java.util.List.of(lens.id()), IntermissionEvents.resolve(content, run, 1, Option.LIBRARY_STUDY).state().relics());
        assertEquals(run.modifiers(), IntermissionEvents.resolve(content, run, 1, Option.LIBRARY_SKIP).state());
    }

    @Test
    @DisplayName("the Vault pays a purse or springs an ambush, by seed, both deterministic; leaving changes nothing")
    void vault() {
        ModifierDefinition harder = curse("harder");
        TowerContent content = content(harder);
        assertFalse(IntermissionEvents.eligible(content(), run(1, 1, RunModifierState.EMPTY), 1, Room.VAULT), "no ambush to spring");
        boolean purse = false;
        boolean ambush = false;
        for (long seed = 0; seed < 60 && !(purse && ambush); seed++) {
            PersistedRun run = run(seed, 2, RunModifierState.EMPTY);
            var first = IntermissionEvents.resolve(content, run, 2, Option.VAULT_CRACK);
            assertEquals(first, IntermissionEvents.resolve(content, run, 2, Option.VAULT_CRACK));
            if (first.walletEach() > 0) {
                purse = true;
                assertEquals(IntermissionEvents.vaultPurse(2), first.walletEach());
                assertEquals(run.modifiers(), first.state());
            } else {
                ambush = true;
                assertEquals(java.util.List.of(harder.id()), first.state().accumulated());
            }
            assertEquals(run.modifiers(), IntermissionEvents.resolve(content, run, 2, Option.VAULT_LEAVE).state());
        }
        assertTrue(purse && ambush, "both outcomes occur across seeds");
    }

    @Test
    @DisplayName("the Mystic Pool swaps a held relic for a different one and needs one to swap")
    void pool() {
        ModifierDefinition a = tagged("a", "offense");
        ModifierDefinition b = tagged("b", "sustain");
        TowerContent content = content(a, b);
        PersistedRun empty = run(3, 1, RunModifierState.EMPTY);
        assertFalse(IntermissionEvents.eligible(content, empty, 1, Room.POOL), "nothing to give");
        PersistedRun holding = run(3, 1, RunModifierState.EMPTY.withRelic(a.id()));
        assertTrue(IntermissionEvents.eligible(content, holding, 1, Room.POOL));
        var dived = IntermissionEvents.resolve(content, holding, 1, Option.POOL_DIVE);
        assertEquals(java.util.List.of(b.id()), dived.state().relics());
        assertEquals(holding.modifiers(), IntermissionEvents.resolve(content, holding, 1, Option.POOL_STAY).state());
        assertFalse(IntermissionEvents.eligible(content(a), holding, 1, Room.POOL), "no other relic to swap for");
    }

    @Test
    @DisplayName("every ordinary room can be the one an eligible floor gets, and rooms stay about as likely as their weights")
    void everyRoomIsReachable() {
        TowerContent content = content(tagged("blade", "offense"), tagged("lens", "scouting"), tagged("held", "economy"), curse("harder"));
        java.util.Map<Room, Integer> seen = new java.util.EnumMap<>(Room.class);
        for (long seed = 0; seed < 4000; seed++) {
            PersistedRun run = run(seed, 1, RunModifierState.EMPTY.withRelic(ResourceLocation.fromNamespaceAndPath("cobbletowers", "held")));
            IntermissionEvents.roomFor(content, run, 1, false, id -> 10_000).ifPresent(room -> seen.merge(room, 1, Integer::sum));
        }
        for (Room room : Room.values()) {
            if (room == Room.ECHO_DUEL || room == Room.SHRINE || room == Room.TRICK_OR_TREAT) continue;   // the shrine needs relic room and a relic to give: covered above; the vault is only ever a route stop of an event tower
            assertTrue(seen.getOrDefault(room, 0) > 0, room + " never came up: " + seen);
        }
        assertTrue(seen.getOrDefault(Room.REST, 0) > seen.getOrDefault(Room.LIBRARY, 0), "Rest is the most common room: " + seen);
    }

    @Test
    @DisplayName("the Trick or Treat vault is never the random room of an intermission, and its option says what losing costs before anyone opens it")
    void trickOrTreatIsRouteOnlyAndWarnsFirst() {
        TowerContent content = content(tagged("blade", "offense"));
        for (long seed = 0; seed < 2000; seed++) {
            assertTrue(IntermissionEvents.roomFor(content, run(seed, 1, RunModifierState.EMPTY), 1, false, id -> 10_000)
                    .filter(room -> room == Room.TRICK_OR_TREAT).isEmpty());
        }
        String label = IntermissionEvents.labelAt(IntermissionEvents.Option.TRICK_OPEN, 3);
        assertTrue(label.contains("mimic") && label.contains("relic") && label.contains("5%"), label);
        assertEquals(2, IntermissionEvents.cardsOf(Room.TRICK_OR_TREAT).size());
    }
}
