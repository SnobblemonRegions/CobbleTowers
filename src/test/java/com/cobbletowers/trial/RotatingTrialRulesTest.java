package com.cobbletowers.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.RotatingRecipeDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TrialPoolDefinition.Kind;
import com.cobbletowers.definition.TrialRecipeRegistry;
import com.cobbletowers.intermission.IntermissionEvents;
import com.cobbletowers.intermission.IntermissionEvents.Room;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunOptions;
import com.cobbletowers.runtime.RunFactory;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rotating trial wired in (P46c, P46d): the schedule, the weekly rules, the room lean, the starting relic. */
class RotatingTrialRulesTest {

    private static final List<String> RULES = List.of("no_vendor", "no_healing_rooms", "double_risk", "relics_every_second_floor");

    private static RotatingRecipeDefinition recipe(String name) throws Exception {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", name);
        try (Reader reader = Files.newBufferedReader(Paths.get("src/main/resources/data/cobbletowers/cobbletowers/trial_recipes/" + name + ".json"))) {
            return RotatingRecipeDefinition.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    private RotatingRecipeDefinition daily;
    private RotatingRecipeDefinition weekly;

    @BeforeEach
    void load() throws Exception {
        daily = recipe("daily");
        weekly = recipe("weekly");
        TrialRecipeRegistry.setForTests(Map.of(daily.id(), daily, weekly.id(), weekly));
    }

    @AfterEach
    void unload() {
        TrialRecipeRegistry.setForTests(Map.of());
    }

    private static PersistedRun trialRun(TrialSchedule.Instance trial, long seed) {
        PersistedRun run = TestRuns.fresh(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        RunOptions options = new RunOptions(Optional.empty(), Optional.of(trial.id()), trial.floors(), true, trial.entry().enemyLevel());
        return new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(), run.towerRevision(), run.towerDigest(), run.rulesetRevision(),
                run.structureRevision(), seed, run.floorIndex(), run.state(), run.participants(), run.lastCheckpoint(), run.committedTransactions(),
                run.updatedAt(), run.cell(), run.ledger(), run.modifiers(), run.lastBankedFloor(), run.vendorPurchases(), options);
    }

    private static PersistedRun withSeed(PersistedRun run, long seed) {
        return new PersistedRun(run.runId(), run.schemaVersion(), run.towerId(), run.towerRevision(), run.towerDigest(), run.rulesetRevision(),
                run.structureRevision(), seed, run.floorIndex(), run.state(), run.participants(), run.lastCheckpoint(), run.committedTransactions(),
                run.updatedAt(), run.cell(), run.ledger(), run.modifiers(), run.lastBankedFloor(), run.vendorPurchases(), run.options());
    }

    /** The first week whose trial carries {@code rule}. */
    private TrialSchedule.Instance weekWith(String rule) {
        LocalDate day = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 400; i++, day = day.plusWeeks(1)) {
            TrialSchedule.Instance trial = TrialSchedule.of(weekly, day);
            if (TrialParts.weeklyRule(trial).filter(rule::equals).isPresent()) return trial;
        }
        throw new AssertionError("no week has " + rule);
    }

    @Test
    @DisplayName("a day composes the same trial every time; featured days use a real tower, the rest a rotating one")
    void schedule() {
        int featured = 0;
        for (int i = 0; i < 365; i++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(i);
            TrialSchedule.Instance trial = TrialSchedule.of(daily, day);
            assertEquals(trial, TrialSchedule.of(daily, day));
            assertEquals("daily:" + day, trial.id());
            assertEquals(5, trial.floors());
            assertEquals(3, trial.streakMinFloors());
            assertEquals(TrialSchedule.boardIdOf(trial.id()).orElseThrow(), trial.boardId());
            if (trial.roll().isEmpty()) {
                featured++;
                assertFalse(TowerContent.isRotating(trial.entry().tower()));
            } else {
                assertEquals(RotatingTowers.idFor(Kind.DAILY, day.toString()), trial.entry().tower());
                assertEquals(trial.roll().get().label(), trial.entry().label());
                assertEquals(trial.roll().get().level(), trial.entry().enemyLevel());
            }
        }
        assertTrue(featured >= 52 && featured <= 54, "featured " + featured);
        assertEquals(TrialSchedule.of(weekly, LocalDate.of(2026, 10, 5)).seed(), TrialSchedule.of(weekly, LocalDate.of(2026, 10, 7)).seed());
    }

    @Test
    @DisplayName("a run re-rolls its own weekly rule from its trial id, and a plain run has none")
    void rulesFromRun() {
        for (String rule : RULES) {
            PersistedRun run = trialRun(weekWith(rule), 1L);
            assertEquals(Optional.of(rule), TrialRules.weeklyRule(run));
            assertTrue(TrialRules.hasRule(run, rule));
            for (String other : RULES) {
                if (!other.equals(rule)) assertFalse(TrialRules.hasRule(run, other));
            }
        }
        PersistedRun plain = TestRuns.fresh(UUID.randomUUID());
        assertTrue(TrialRules.weeklyRule(plain).isEmpty());
        assertFalse(PlaylistRegistry.vendorClosed(plain));
        assertTrue(PlaylistRegistry.vendorClosed(trialRun(weekWith("no_vendor"), 1L)));
        assertFalse(PlaylistRegistry.vendorClosed(trialRun(weekWith("double_risk"), 1L)));
    }

    private static TowerContent roomContent() {
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        for (ModifierDefinition m : List.of(
                TestRuns.modifier("gem", "reward", "\"reward_percent\":125", "\"relic\":true", "\"tags\":[\"economy\"]"),
                TestRuns.modifier("brutal", "enemy", "\"boss_level_offset\":3", "\"risk\":\"severe\""))) all.put(m.id(), m);
        return TestRuns.contentWith(all);
    }

    @Test
    @DisplayName("no_healing_rooms never gives a Rest room, and without it Rest does come up")
    void noRest() {
        TowerContent content = roomContent();
        PersistedRun ruled = trialRun(weekWith("no_healing_rooms"), 0L);
        PersistedRun open = trialRun(weekWith("double_risk"), 0L);
        boolean restSeen = false;
        for (long seed = 0; seed < 300; seed++) {
            Optional<Room> withRule = IntermissionEvents.roomFor(content, withSeed(ruled, seed), 1, false);
            assertFalse(withRule.filter(r -> r == Room.REST).isPresent(), "rest at seed " + seed);
            restSeen |= IntermissionEvents.roomFor(content, withSeed(open, seed), 1, false).filter(r -> r == Room.REST).isPresent();
        }
        assertTrue(restSeen);
    }

    @Test
    @DisplayName("relics_every_second_floor gives a relic room on every ordinary even floor")
    void relicEveryOther() {
        TowerContent content = roomContent();
        PersistedRun ruled = trialRun(weekWith("relics_every_second_floor"), 0L);
        int checked = 0;
        for (long seed = 0; seed < 100; seed++) {
            // the test tower's floor 2 is a milestone floor, which has no ordinary room
            if (content.milestoneAt(ruled.towerId(), 4).isPresent()) continue;
            Optional<Room> room = IntermissionEvents.roomFor(content, withSeed(ruled, seed), 4, false);
            assertTrue(room.isPresent() && List.of(Room.SHRINE, Room.FORGE, Room.LIBRARY).contains(room.get()), "seed " + seed + " " + room);
            checked++;
        }
        assertTrue(checked > 0);
    }

    @Test
    @DisplayName("the room a trial leans toward counts triple; others keep their weight")
    void roomLean() {
        assertEquals(3 * IntermissionEvents.weightOf(Room.FORGE, Optional.empty()), IntermissionEvents.weightOf(Room.FORGE, Optional.of("forge")));
        assertEquals(IntermissionEvents.weightOf(Room.REST, Optional.empty()), IntermissionEvents.weightOf(Room.REST, Optional.of("forge")));
    }

    @Test
    @DisplayName("a trial's starting relic is held from floor 1; a curse or an unknown id is skipped")
    void startingRelic() {
        TowerContent content = roomContent();
        ResourceLocation gem = TestRuns.id("gem");
        PersistedRun run = RunFactory.create(content, TestRuns.TOWER, List.of(TestRuns.PLAYER), Map.of(), 0, RunOptions.NONE,
                List.of(), List.of(gem, TestRuns.id("brutal"), TestRuns.id("missing")), 1L, TestRuns.NOW).orElseThrow();
        assertEquals(List.of(gem), run.modifiers().relics());
    }

    @Test
    @DisplayName("the briefing lines name the route and the weekly rule")
    void lines() {
        List<String> lines = TrialParts.lines(weekWith("double_risk"), ResourceLocation::getPath);
        assertTrue(lines.get(0).startsWith("Route: "));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Weekly rule: Double risk")));
    }
}
