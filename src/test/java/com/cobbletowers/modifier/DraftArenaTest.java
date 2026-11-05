package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.persistence.PersistedDraft;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.route.FloorLayouts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Stage 2 of the route (P50): a stop whose battle is fought in another region's building, and where that building comes from. */
class DraftArenaTest {

    private static final UUID RUN = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final ResourceLocation HOME = TestRuns.id("neutral");
    private static final ResourceLocation ROOT = TestRuns.id("rootvale");
    private static final ResourceLocation TIDE = TestRuns.id("tideforge");
    private static final List<ResourceLocation> CARDS = List.of(TestRuns.id("a"), TestRuns.id("b"), TestRuns.id("c"));

    private static FloorLayout layout(String structure, int y) {
        return layout(structure, y, false);
    }

    private static FloorLayout layout(String structure, int y, boolean stairGate) {
        FloorAnchor at = new FloorAnchor(1, y, 1, 0);
        var gate = Optional.of(new com.cobbletowers.definition.FloorBox(0, y, 0, 1, y + 1, 1));
        return new FloorLayout(TestRuns.id(structure), at, at, at, at, gate, stairGate ? gate : Optional.empty());
    }

    /** Three catalog towers of six floors each, each in its own building; the home tower's sixth floor is a boss. */
    private static TowerContent content() {
        return content(false);
    }

    private static TowerContent content(boolean homeIsWalkable) {
        TowerContent base = TestRuns.content();
        Map<ResourceLocation, TowerDefinition> towers = new LinkedHashMap<>();
        Map<ResourceLocation, FloorDefinition> floors = new LinkedHashMap<>();
        Map<ResourceLocation, MilestoneDefinition> milestones = new LinkedHashMap<>();
        for (ResourceLocation tower : List.of(HOME, ROOT, TIDE)) {
            List<ResourceLocation> ids = new ArrayList<>();
            List<ResourceLocation> milestoneIds = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                ResourceLocation id = TestRuns.id(tower.getPath() + "_f" + i);
                boolean boss = tower.equals(HOME) && i == 6;
                floors.put(id, new FloorDefinition(id, i, boss ? Optional.of(MilestoneKind.BOSS) : Optional.empty(), Optional.empty(), List.of(),
                        Optional.of(layout("battle_tower_" + tower.getPath(), 10 * i, homeIsWalkable && tower.equals(HOME))), Optional.empty()));
                ids.add(id);
            }
            if (tower.equals(HOME)) {
                MilestoneDefinition boss = new MilestoneDefinition(TestRuns.id("boss_" + tower.getPath()), 6, MilestoneKind.BOSS,
                        Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), true);
                milestones.put(boss.id(), boss);
                milestoneIds.add(boss.id());
            }
            towers.put(tower, new TowerDefinition(tower, tower.getPath(), 1, 3, TestRuns.id("standard"), base.rewardTables().keySet().iterator().next(), ids, milestoneIds,
                    Optional.empty(), Optional.empty()));
        }
        return TowerContent.of(towers, floors, base.rulesets(), milestones, base.bossPools(), base.modifiers(), base.rewardTables(), base.regionalThemes(),
                base.vendorServices(), base.scoutingProfiles(), base.digests());
    }

    private static PersistedRun run(long seed, int floor, RunModifierState state) {
        PersistedRun base = TestRuns.fresh(RUN);
        return new PersistedRun(base.runId(), base.schemaVersion(), HOME, base.towerRevision(), base.towerDigest(), base.rulesetRevision(), base.structureRevision(), seed, floor,
                base.state(), base.participants(), base.lastCheckpoint(), base.committedTransactions(), base.updatedAt(), base.cell(), base.ledger(), state, base.lastBankedFloor(),
                base.vendorPurchases());
    }

    @Test
    void aFloorIsBuiltFromTheRunsOwnTowerUnlessADetourSaysOtherwise() {
        TowerContent content = content();
        PersistedRun plain = run(1, 2, RunModifierState.EMPTY);
        assertEquals(TestRuns.id("battle_tower_neutral"), FloorLayouts.layoutOf(content, plain, 3).orElseThrow().structure());
        assertEquals(HOME, FloorLayouts.placeOf(plain, 3));

        PersistedRun detour = run(1, 2, RunModifierState.EMPTY.withArena(3, ROOT));
        FloorLayout there = FloorLayouts.layoutOf(content, detour, 3).orElseThrow();
        assertEquals(TestRuns.id("battle_tower_rootvale"), there.structure());
        assertEquals(30, there.entry().y(), "the anchors are the other tower's own floor 3");
        assertEquals(ROOT, FloorLayouts.placeOf(detour, 3));
        assertEquals(TestRuns.id("battle_tower_neutral"), FloorLayouts.layoutOf(content, detour, 2).orElseThrow().structure(), "only the floor that was chosen");
        assertEquals(TestRuns.id("battle_tower_neutral"), FloorLayouts.layoutOf(content, detour, 4).orElseThrow().structure(), "and the party comes back");
    }

    @Test
    void aDetourToTheOwnTowerOrAnUnknownOneChangesNothing() {
        TowerContent content = content();
        assertEquals(TestRuns.id("battle_tower_neutral"), FloorLayouts.layoutOf(content, run(1, 2, RunModifierState.EMPTY.withArena(3, HOME)), 3).orElseThrow().structure());
        assertEquals(TestRuns.id("battle_tower_neutral"), FloorLayouts.layoutOf(content, run(1, 2, RunModifierState.EMPTY.withArena(3, TestRuns.id("gone"))), 3).orElseThrow().structure());
    }

    @Test
    void detoursAreRareOnOneLaneAndNeverBackToTheSameBuildingOrBeforeABoss() {
        TowerContent content = content();
        int detours = 0, steps = 0;
        for (long seed = 1; seed <= 400; seed++) {
            for (int floor = 1; floor <= 4; floor++) {
                List<String> places = new ArrayList<>();
                int lane = DraftService.detourFor(content, run(seed, floor, RunModifierState.EMPTY), floor, places);
                steps++;
                if (lane >= 0) {
                    detours++;
                    assertTrue(lane <= 2);
                    assertEquals(1, places.size());
                    assertTrue(places.get(0).equals(ROOT.toString()) || places.get(0).equals(TIDE.toString()), places.get(0));
                } else {
                    assertTrue(places.isEmpty());
                }
            }
        }
        assertTrue(detours > steps / 5 && detours < steps / 2, detours + " of " + steps);
        for (long seed = 1; seed <= 400; seed++) {
            // floor 5 is followed by the boss on floor 6: no detour into or out of a boss floor
            assertEquals(-1, DraftService.detourFor(content, run(seed, 5, RunModifierState.EMPTY), 5, new ArrayList<>()));
        }
    }

    @Test
    void aWalkableTowerIsClimbedNotLeft() {
        TowerContent content = content(true);
        for (long seed = 1; seed <= 400; seed++) {
            for (int floor = 1; floor <= 4; floor++) {
                assertEquals(-1, DraftService.detourFor(content, run(seed, floor, RunModifierState.EMPTY), floor, new ArrayList<>()), "seed " + seed + " floor " + floor);
            }
        }
    }

    @Test
    void aRunOnATowerTheContentDoesNotKnowHasNoDetours() {
        PersistedRun other = new PersistedRun(RUN, 7, TestRuns.id("rotating/daily"), 1, "d", 1, 0, 5L, 1, TestRuns.fresh(RUN).state(), TestRuns.fresh(RUN).participants(),
                Optional.empty(), List.of(), 0L, java.util.OptionalInt.empty(), List.of(), RunModifierState.EMPTY, 0, Map.of());
        for (long seed = 1; seed <= 100; seed++) assertEquals(-1, DraftService.detourFor(content(), other, 1, new ArrayList<>()));
    }

    @Test
    void aRouteDraftHasAtMostOneDetourAndItsPlaceIsNeverTheHomeTower() {
        TowerContent content = content();
        boolean seen = false;
        for (long seed = 1; seed <= 300; seed++) {
            PersistedDraft draft = DraftService.routeDraft(content, run(seed, 2, RunModifierState.EMPTY), CARDS, id -> Long.MAX_VALUE);
            assertEquals(draft.cards().size(), draft.arenas().size());
            long away = draft.arenas().stream().filter(a -> !a.isEmpty()).count();
            assertTrue(away <= 1);
            for (String arena : draft.arenas()) assertFalse(arena.equals(HOME.toString()));
            seen |= away == 1;
        }
        assertTrue(seen, "detours do happen");
    }

    @Test
    void theDetourIsRememberedByFloorAndTheLastOneWins() {
        RunModifierState state = RunModifierState.EMPTY.withArena(3, ROOT).withArena(5, TIDE).withArena(3, TIDE);
        assertEquals(Optional.of(TIDE), state.arenaAt(3));
        assertEquals(Optional.of(TIDE), state.arenaAt(5));
        assertEquals(Optional.empty(), state.arenaAt(4));
        RunModifierState back = RunModifierState.fromTag(state.toTag());
        assertEquals(state.arenas(), back.arenas());
        assertEquals(Optional.of(TIDE), back.arenaAt(5));
        assertEquals(Optional.empty(), RunModifierState.fromTag(RunModifierState.EMPTY.toTag()).arenaAt(3));
    }

    @Test
    void aRouteDraftKeepsItsArenasAndAnOlderOneGetsNone() {
        PersistedDraft draft = PersistedDraft.openingRoute(2, CARDS, List.of(0, 1, 2), List.of("BATTLE", "REST", "BATTLE"), List.of("", ROOT.toString(), ""));
        PersistedDraft back = PersistedDraft.fromTag(draft.toTag());
        assertEquals(List.of("", ROOT.toString(), ""), back.arenas());
        assertEquals(List.of("", ROOT.toString(), ""), PersistedDraft.fromTag(draft.withVote(UUID.randomUUID(), 1).resolvedAs(1, false).toTag()).arenas());
        // saved before arenas existed: lanes and kinds, no arenas
        var tag = draft.toTag();
        tag.remove("arenas");
        PersistedDraft old = PersistedDraft.fromTag(tag);
        assertTrue(old.route());
        assertEquals(List.of("", "", ""), old.arenas());
    }
}
