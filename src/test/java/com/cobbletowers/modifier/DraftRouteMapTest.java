package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.route.RouteGraph.Kind;
import com.cobbletowers.route.RouteMapView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** P56: on a tower with a branching map the route draft offers the lanes the map links from where the party stands. */
class DraftRouteMapTest {

    private static final UUID RUN = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final ResourceLocation HOME = TestRuns.id("mapped");
    private static final List<ResourceLocation> CARDS = List.of(TestRuns.id("a"), TestRuns.id("b"), TestRuns.id("c"));

    /** One tower of six floors with a map, a boss on the sixth. */
    private static TowerContent content(boolean routeMap) {
        TowerContent base = TestRuns.content();
        Map<ResourceLocation, FloorDefinition> floors = new LinkedHashMap<>();
        Map<ResourceLocation, MilestoneDefinition> milestones = new LinkedHashMap<>();
        List<ResourceLocation> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ResourceLocation id = TestRuns.id("mapped_f" + i);
            floors.put(id, new FloorDefinition(id, i, i == 6 ? Optional.of(MilestoneKind.BOSS) : Optional.empty(), Optional.empty(), List.of(),
                    Optional.empty(), Optional.empty()));
            ids.add(id);
        }
        MilestoneDefinition boss = new MilestoneDefinition(TestRuns.id("boss_mapped"), 6, MilestoneKind.BOSS,
                Optional.of(ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario")), true);
        milestones.put(boss.id(), boss);
        Map<ResourceLocation, TowerDefinition> towers = new LinkedHashMap<>();
        towers.put(HOME, new TowerDefinition(HOME, "Mapped", 1, 1, TestRuns.id("standard"), base.rewardTables().keySet().iterator().next(), ids, List.of(boss.id()),
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, routeMap));
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
    void theDraftOffersExactlyTheLanesTheMapLinksFromWhereThePartyStands() {
        TowerContent content = content(true);
        TowerDefinition tower = content.towers().get(HOME);
        for (long seed = 1; seed <= 60; seed++) {
            RunModifierState state = RunModifierState.EMPTY;
            for (int floor = 1; floor <= 4; floor++) {
                PersistedRun run = run(seed, floor, state);
                var spot = DraftService.mapSpot(content, tower, run);
                var draft = DraftService.routeDraft(content, run, CARDS, id -> 0L);
                assertEquals(spot.reachable(), draft.lanes(), "seed " + seed + " floor " + floor);
                assertFalse(draft.lanes().isEmpty());
                for (int i = 0; i < draft.lanes().size(); i++) {
                    Kind placed = RouteMapView.kindOf(spot.map().stopAt(spot.row() + 1, draft.lanes().get(i)));
                    Kind shown = Kind.parse(draft.kinds().get(i));
                    assertTrue(shown == placed || shown == Kind.BATTLE, "a stop shows what the map placed, or a battle when its room could do nothing: " + shown + " vs " + placed);
                }
                // walk the first offered lane; the next draft starts from there
                state = state.withRouteStep(floor, draft.lanes().get(draft.lanes().size() - 1));
            }
        }
    }

    @Test
    void aTowerWithoutAMapKeepsTheOldPerStepDraw() {
        TowerContent content = content(false);
        for (long seed = 1; seed <= 20; seed++) {
            var draft = DraftService.routeDraft(content, run(seed, 2, RunModifierState.EMPTY), CARDS, id -> 0L);
            assertEquals(List.of(0, 1, 2), draft.lanes(), "a first step can start in any lane");
        }
    }
}
