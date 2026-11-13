package com.cobbletowers.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.api.tower.participant.ParticipantState;
import com.cobbletowers.definition.DefinitionKey;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure half: which arrivals actually pay out, and how what they earn is filtered and split. */
class RewardBankServiceTest {

    private static final UUID RUN = UUID.fromString("99999999-0000-0000-0000-000000000009");
    private static final UUID PLAYER_A = TestRuns.PLAYER;
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    /** A two-floor tower whose floor-2 milestone's banksRewards is configurable. */
    private static TowerContent contentWith(boolean banksRewards) {
        RulesetDefinition ruleset = RulesetDefinition.fromJson(id("standard"),
                JsonParser.parseString("{\"schema_version\":1}").getAsJsonObject());
        RewardTableDefinition rewardTable = RewardTableDefinition.fromJson(id("rewards"),
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\"Rewards\"}").getAsJsonObject());
        FloorDefinition one = new FloorDefinition(id("floor_1"), 1, Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), Optional.empty());
        FloorDefinition two = new FloorDefinition(id("floor_2"), 2, Optional.of(MilestoneKind.BOSS),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        MilestoneDefinition boss = new MilestoneDefinition(id("boss"), 2, MilestoneKind.BOSS,
                Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), banksRewards);
        TowerDefinition tower = new TowerDefinition(TestRuns.TOWER, "Neutral", 1, 1, ruleset.id(), rewardTable.id(),
                List.of(one.id(), two.id()), List.of(boss.id()), Optional.empty(), Optional.empty());

        return TowerContent.of(Map.of(TestRuns.TOWER, tower), Map.of(one.id(), one, two.id(), two),
                Map.of(ruleset.id(), ruleset), Map.of(boss.id(), boss), Map.of(), Map.of(),
                Map.of(rewardTable.id(), rewardTable), Map.of(), Map.of(), Map.of(),
                Map.of(DefinitionKey.tower(TestRuns.TOWER), "digest"));
    }

    @Test
    @DisplayName("an ordinary floor's intermission is not a payout point")
    void ordinaryFloorDoesNotBank() {
        TowerContent content = contentWith(true);
        PersistedRun run = ordinaryFloorOneRun();

        assertEquals(Optional.empty(), RewardBankService.bankPoint(content, run));
    }

    @Test
    @DisplayName("a milestone floor with banks_rewards pays out at its own intermission")
    void milestoneFloorBanks() {
        TowerContent content = contentWith(true);
        PersistedRun run = floorTwoIntermissionRun();

        assertEquals(content.rewardTable(id("rewards")), RewardBankService.bankPoint(content, run));
    }

    @Test
    @DisplayName("a milestone floor without banks_rewards grants nothing yet")
    void milestoneFloorWithoutBankingDoesNotBank() {
        TowerContent content = contentWith(false);
        PersistedRun run = floorTwoIntermissionRun();

        assertEquals(Optional.empty(), RewardBankService.bankPoint(content, run));
    }

    @Test
    @DisplayName("COMPLETED always pays out, milestone or not")
    void completedAlwaysBanks() {
        TowerContent noBanking = contentWith(false);
        PersistedRun run = new PersistedRun(RUN, PersistedRun.SCHEMA_VERSION, TestRuns.TOWER, 1, "digest", 1, 0, 1L,
                2, RunState.COMPLETED, List.of(new PersistedParticipant(PLAYER_A, ParticipantState.joined(), List.of())),
                Optional.empty(), List.of(), TestRuns.NOW, OptionalInt.empty(), List.of(),
                RunModifierState.EMPTY, 0, Map.of());

        assertEquals(noBanking.rewardTable(id("rewards")), RewardBankService.bankPoint(noBanking, run));
    }

    private static PersistedRun runIn(RunState state) {
        return new PersistedRun(RUN, PersistedRun.SCHEMA_VERSION, TestRuns.TOWER, 1, "digest", 1, 0, 1L,
                2, state, List.of(new PersistedParticipant(PLAYER_A, ParticipantState.joined(), List.of())),
                Optional.empty(), List.of(), TestRuns.NOW, OptionalInt.empty(), List.of(),
                RunModifierState.EMPTY, 0, Map.of());
    }

    @Test
    @DisplayName("a parked run is never an ordinary payout point")
    void parkedRunIsNotABankPoint() {
        assertEquals(Optional.empty(), RewardBankService.bankPoint(contentWith(true), runIn(RunState.RECOVERY_REQUIRED)));
    }

    @Test
    @DisplayName("a parked run being closed prices its cleared floors from the tower's table")
    void parkedRunPricesFromTheTowersTable() {
        TowerContent content = contentWith(false);
        assertEquals(content.rewardTable(id("rewards")), RewardBankService.parkedTable(content, runIn(RunState.RECOVERY_REQUIRED)));
        assertEquals(Optional.empty(), RewardBankService.parkedTable(content, runIn(RunState.ENCOUNTER_ACTIVE)),
                "only a parked run banks this way");
    }

    @Test
    @DisplayName("only entries earned since the last bank, through the current floor, are priced")
    void unbankedRange() {
        LedgerEntry floorOne = LedgerEntry.floorCleared(1, TestRuns.TOWER, 1L);
        LedgerEntry floorTwo = LedgerEntry.floorCleared(2, TestRuns.TOWER, 2L);
        LedgerEntry floorThree = LedgerEntry.floorCleared(3, TestRuns.TOWER, 3L);
        PersistedRun run = TestRuns.fresh(RUN).withEarned(floorOne, 1L).withEarned(floorTwo, 2L)
                .withEarned(floorThree, 3L);
        // On floor 2, already banked through floor 1: only floor 2's entry is newly priceable.
        PersistedRun onFloorTwo = new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(),
                run.towerRevision(), run.towerDigest(), run.rulesetRevision(), run.structureRevision(), run.seed(),
                2, run.state(), run.participants(), run.lastCheckpoint(), run.committedTransactions(),
                run.updatedAt(), run.cell(), run.ledger(), run.modifiers(), 1, run.vendorPurchases());

        assertEquals(List.of(floorTwo), RewardBankService.unbanked(onFloorTwo));
    }

    @Test
    @DisplayName("only members still in the run share a grant, not merely those who can fight right now")
    void currentParticipantsExcludesOnlyLeavers() {
        PersistedParticipant knockedOut = new PersistedParticipant(PLAYER_A, ParticipantState.joined().knockedOut(),
                List.of());
        PersistedParticipant left = new PersistedParticipant(PLAYER_B, ParticipantState.joined().left(), List.of());
        PersistedRun fresh = TestRuns.fresh(RUN);
        PersistedRun run = new PersistedRun(fresh.runId(), fresh.schemaVersion(), fresh.towerId(),
                fresh.towerRevision(), fresh.towerDigest(), fresh.rulesetRevision(), fresh.structureRevision(),
                fresh.seed(), fresh.floorIndex(), fresh.state(), List.of(knockedOut, left), fresh.lastCheckpoint(),
                fresh.committedTransactions(), fresh.updatedAt(), fresh.cell(), fresh.ledger(), fresh.modifiers(),
                fresh.lastBankedFloor(), fresh.vendorPurchases());

        assertEquals(List.of(PLAYER_A), RewardBankService.currentParticipants(run),
                "knocked out or disconnected still played the run; only a voluntary leaver is excluded");
    }

    @Test
    @DisplayName("an amount splits evenly, and the remainder goes to the first participants")
    void evenSplit() {
        Map<UUID, Integer> shares = RewardBankService.evenSplit(10, List.of(PLAYER_A, PLAYER_B));

        assertEquals(5, shares.get(PLAYER_A));
        assertEquals(5, shares.get(PLAYER_B));

        Map<UUID, Integer> withRemainder = RewardBankService.evenSplit(7, List.of(PLAYER_A, PLAYER_B));
        assertEquals(4, withRemainder.get(PLAYER_A), "the remainder goes to the first participant");
        assertEquals(3, withRemainder.get(PLAYER_B));
    }

    @Test
    @DisplayName("nothing to split grants nothing")
    void evenSplitEmpty() {
        assertTrue(RewardBankService.evenSplit(0, List.of(PLAYER_A)).isEmpty());
        assertTrue(RewardBankService.evenSplit(5, List.of()).isEmpty());
    }

    @Test
    @DisplayName("the grant's own key is distinct from any transition's checkpoint key")
    void grantKeyIsItsOwn() {
        String key = RewardBankService.grantKey(RUN, 5);

        assertEquals("run:" + RUN + ":floor:5:granted", key);
        assertTrue(key.endsWith(":granted"), "distinct from a transition's ...:banked / ...:completed key");
    }

    private static PersistedRun ordinaryFloorOneRun() {
        PersistedRun fresh = TestRuns.fresh(RUN);
        return new PersistedRun(fresh.runId(), fresh.schemaVersion(), fresh.towerId(), fresh.towerRevision(),
                fresh.towerDigest(), fresh.rulesetRevision(), fresh.structureRevision(), fresh.seed(), 1,
                RunState.INTERMISSION, fresh.participants(), fresh.lastCheckpoint(), fresh.committedTransactions(),
                fresh.updatedAt(), fresh.cell(), fresh.ledger(), fresh.modifiers(), fresh.lastBankedFloor(),
                fresh.vendorPurchases());
    }

    private static PersistedRun floorTwoIntermissionRun() {
        PersistedRun fresh = TestRuns.fresh(RUN);
        return new PersistedRun(fresh.runId(), fresh.schemaVersion(), fresh.towerId(), fresh.towerRevision(),
                fresh.towerDigest(), fresh.rulesetRevision(), fresh.structureRevision(), fresh.seed(), 2,
                RunState.INTERMISSION, fresh.participants(), fresh.lastCheckpoint(), fresh.committedTransactions(),
                fresh.updatedAt(), fresh.cell(), fresh.ledger(), fresh.modifiers(), fresh.lastBankedFloor(),
                fresh.vendorPurchases());
    }
}
