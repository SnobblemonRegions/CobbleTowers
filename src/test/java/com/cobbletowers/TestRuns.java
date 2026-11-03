package com.cobbletowers;

import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.api.tower.participant.ParticipantState;
import com.cobbletowers.definition.DefinitionKey;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.runtime.RunFactory;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Runs and content for tests, so each test says what it is about rather than how to build one. */
public final class TestRuns {

    public static final ResourceLocation TOWER = id("neutral");
    public static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final long NOW = 1_726_000_000_000L;

    private TestRuns() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", path);
    }

    /** A run at its opening state, on floor 1, with one player and nothing committed. */
    public static PersistedRun fresh(UUID runId) {
        return fresh(runId, PLAYER, NOW);
    }

    public static PersistedRun fresh(UUID runId, UUID playerId, long updatedAt) {
        return new PersistedRun(runId, PersistedRun.SCHEMA_VERSION, TOWER, 3, "digest-abc", 2, 0, 42L,
                RunFactory.FIRST_FLOOR, RunState.CREATED,
                List.of(new PersistedParticipant(playerId, ParticipantState.joined(), List.of())),
                Optional.empty(), List.of(), updatedAt, OptionalInt.empty(), List.of(),
                RunModifierState.EMPTY, 0, Map.of());
    }

    /** The same run moved to a state directly, for tests about storage rather than transitions. */
    public static PersistedRun at(UUID runId, RunState state, long updatedAt) {
        PersistedRun run = fresh(runId, PLAYER, updatedAt);
        return new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(), run.towerRevision(),
                run.towerDigest(), run.rulesetRevision(), run.structureRevision(), run.seed(), run.floorIndex(),
                state, run.participants(), run.lastCheckpoint(), run.committedTransactions(), updatedAt,
                run.cell(), run.ledger(), run.modifiers(), run.lastBankedFloor(), run.vendorPurchases());
    }

    /** One tower, two floors, a ruleset and a boss milestone -- enough for every reference to resolve. */
    public static TowerContent content() {
        RulesetDefinition ruleset = RulesetDefinition.fromJson(id("standard"),
                JsonParser.parseString("{\"schema_version\":1,\"revision\":5}").getAsJsonObject());

        FloorDefinition one = floor(1, Optional.empty());
        FloorDefinition two = floor(2, Optional.of(MilestoneKind.BOSS));
        // A boss milestone must name the CobbleRaids boss it reuses; the record refuses one without.
        MilestoneDefinition boss = new MilestoneDefinition(id("boss"), 2, MilestoneKind.BOSS,
                Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), true);
        RewardTableDefinition rewardTable = RewardTableDefinition.fromJson(id("rewards"),
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\"Rewards\"}").getAsJsonObject());
        TowerDefinition tower = new TowerDefinition(TOWER, "Neutral", 1, 3, id("standard"), rewardTable.id(),
                List.of(one.id(), two.id()), List.of(boss.id()), Optional.empty(), Optional.empty());

        return TowerContent.of(Map.of(TOWER, tower), Map.of(one.id(), one, two.id(), two), Map.of(ruleset.id(), ruleset), Map.of(boss.id(), boss), Map.of(), Map.of(),
                Map.of(rewardTable.id(), rewardTable), Map.of(), Map.of(), Map.of(),
                Map.of(DefinitionKey.tower(TOWER), "digest-abc"));
    }

    /** The same content, plus a set of modifiers to draft from. */
    public static TowerContent contentWith(Map<ResourceLocation, ModifierDefinition> modifiers) {
        TowerContent base = content();
        return TowerContent.of(base.towers(), base.floors(), base.rulesets(), base.milestones(),
                base.bossPools(), modifiers, base.rewardTables(), base.regionalThemes(), base.vendorServices(),
                base.scoutingProfiles(), base.digests());
    }

    /**
     * One modifier built from JSON, so tests use the parser content uses.
     * @param extra extra top-level JSON fields, e.g. {@code "\"stack_limit\":2"}
     */
    public static ModifierDefinition modifier(String path, String type, String effect, String... extra) {
        StringBuilder json = new StringBuilder("{\"schema_version\":1,\"type\":\"" + type
                + "\",\"display_name\":\"" + path + "\",\"effect\":{" + effect + "}");
        for (String field : extra) json.append(',').append(field);
        return ModifierDefinition.fromJson(id(path),
                JsonParser.parseString(json.append('}').toString()).getAsJsonObject());
    }

    /** The run with modifiers already drafted, as if it had taken them at earlier intermissions. */
    public static PersistedRun holding(PersistedRun run, ResourceLocation... modifiers) {
        RunModifierState state = run.modifiers();
        for (ResourceLocation modifier : modifiers) state = state.accumulating(modifier);
        return run.withModifiers(state, run.updatedAt());
    }

    private static FloorDefinition floor(int index, Optional<MilestoneKind> milestone) {
        return new FloorDefinition(id("floor_" + index), index, milestone, Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }
}
