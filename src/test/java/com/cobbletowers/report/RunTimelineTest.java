package com.cobbletowers.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunTimelineTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();

    private final RunTimeline timeline = new RunTimeline();
    private final List<RunTimeline.Event> written = new ArrayList<>();

    private static RunTimeline.RunFacts at(int floor, int banked, int threat) {
        return new RunTimeline.RunFacts(RUN, "cobbletowers:spire", floor, banked, threat, "plain", List.of(P1, P2));
    }

    private void move(int floor, RunState from, RunState to, RunEvent event, long now) {
        written.addAll(timeline.onTransition(at(floor, Math.max(0, floor - 1), 3), from, to, event, now));
    }

    private List<RunTimeline.Event> of(String type) {
        return written.stream().filter(e -> e.type().equals(type)).toList();
    }

    /** Start, wait for the first arena, clear floor 1, wait on the second arena, wipe on floor 2. */
    private void playToAWipe() {
        move(1, RunState.VALIDATING_PARTY, RunState.ALLOCATING_INSTANCE, RunEvent.PARTY_VALIDATED, 0);
        move(1, RunState.ALLOCATING_INSTANCE, RunState.PREPARING, RunEvent.INSTANCE_ALLOCATED, 1_000);
        move(1, RunState.PREPARING, RunState.FLOOR_READY, RunEvent.PREPARATION_COMPLETE, 4_000);
        move(1, RunState.FLOOR_READY, RunState.ENCOUNTER_ACTIVE, RunEvent.ENCOUNTER_STARTED, 10_000);
        move(1, RunState.ENCOUNTER_ACTIVE, RunState.FLOOR_RESOLVING, RunEvent.ENCOUNTER_RESOLVED_CLEARED, 70_000);
        move(1, RunState.FLOOR_RESOLVING, RunState.INTERMISSION, RunEvent.REWARDS_BANKED, 71_000);
        move(1, RunState.INTERMISSION, RunState.NEXT_FLOOR_READY, RunEvent.INTERMISSION_COMPLETE, 80_000);
        timeline.arenaWaitStarted(RUN, 81_000);
        move(2, RunState.NEXT_FLOOR_READY, RunState.FLOOR_READY, RunEvent.NEXT_FLOOR_CONFIRMED, 93_000);
        move(2, RunState.FLOOR_READY, RunState.ENCOUNTER_ACTIVE, RunEvent.ENCOUNTER_STARTED, 100_000);
        move(2, RunState.ENCOUNTER_ACTIVE, RunState.FAILED, RunEvent.ENCOUNTER_RESOLVED_WIPED, 190_000);
    }

    @Test void aWipedRunReportsEachFloorBothWaitsAndWhereItEnded() {
        playToAWipe();

        var floors = of("tower_floor");
        assertEquals(2, floors.size());
        assertEquals("cleared", floors.get(0).fields().get("result"));
        assertEquals(60L, floors.get(0).fields().get("seconds"));
        assertEquals("wiped", floors.get(1).fields().get("result"));
        assertEquals(90L, floors.get(1).fields().get("seconds"));
        assertEquals(2, floors.get(1).fields().get("floor"));

        var waits = of("tower_arena_wait");
        assertEquals(2, waits.size());
        assertEquals(true, waits.get(0).fields().get("first"));
        assertEquals(4_000L, waits.get(0).fields().get("ms"));
        assertEquals(false, waits.get(1).fields().get("first"));
        assertEquals(12_000L, waits.get(1).fields().get("ms"));
        assertEquals("ready", waits.get(1).fields().get("outcome"));

        var end = of("tower_run_end");
        assertEquals(1, end.size());
        var f = end.get(0).fields();
        assertEquals("failed", f.get("state"));
        assertEquals("wiped", f.get("cause"));
        assertEquals(2, f.get("floor"));
        assertEquals(3, f.get("threat"));
        assertEquals(190L, f.get("seconds"));
        assertEquals(1, f.get("floorsCleared"));
        assertEquals(60L, f.get("avgFloorSeconds"));
        assertEquals(List.of(P1, P2), f.get("players"));
        assertEquals(RUN.toString(), f.get("run"), "the run id is text, so AscensionLib does not take it for a player");
        assertEquals(0, timeline.tracked(), "an ended run is forgotten");
    }

    @Test void aCallerSaidCauseWinsOverTheEvent() {
        move(1, RunState.VALIDATING_PARTY, RunState.ALLOCATING_INSTANCE, RunEvent.PARTY_VALIDATED, 0);
        move(1, RunState.ALLOCATING_INSTANCE, RunState.PREPARING, RunEvent.INSTANCE_ALLOCATED, 100);
        move(1, RunState.PREPARING, RunState.FLOOR_READY, RunEvent.PREPARATION_COMPLETE, 200);
        move(1, RunState.FLOOR_READY, RunState.ENCOUNTER_ACTIVE, RunEvent.ENCOUNTER_STARTED, 1_000);
        timeline.noteEndCause(RUN, "disconnect");
        move(1, RunState.ENCOUNTER_ACTIVE, RunState.ABANDONED, RunEvent.ABANDON_REQUESTED, 31_000);
        assertEquals("left", of("tower_floor").get(0).fields().get("result"));
        assertEquals("disconnect", of("tower_run_end").get(0).fields().get("cause"));
        assertEquals("abandoned", of("tower_run_end").get(0).fields().get("state"));
    }

    @Test void aPartyThatGivesUpWhileTheArenaIsBuiltIsAWaitThatEnded() {
        move(1, RunState.VALIDATING_PARTY, RunState.ALLOCATING_INSTANCE, RunEvent.PARTY_VALIDATED, 0);
        timeline.busyRefusal(RUN);
        timeline.busyRefusal(RUN);
        move(1, RunState.ALLOCATING_INSTANCE, RunState.PREPARING, RunEvent.INSTANCE_ALLOCATED, 5_000);
        move(1, RunState.PREPARING, RunState.ABANDONED, RunEvent.ABANDON_REQUESTED, 45_000);
        var wait = of("tower_arena_wait").get(0).fields();
        assertEquals("ended", wait.get("outcome"));
        assertEquals(45_000L, wait.get("ms"));
        assertEquals(2, wait.get("busyRefusals"));
        assertEquals("abandoned", of("tower_run_end").get(0).fields().get("cause"));
        assertFalse(of("tower_run_end").get(0).fields().containsKey("avgFloorSeconds"));
    }

    @Test void aTechnicalParkIsItsOwnEventAndNotAnEnd() {
        move(1, RunState.ALLOCATING_INSTANCE, RunState.RECOVERY_REQUIRED, RunEvent.ALLOCATION_FAILED, 0);
        assertEquals(1, of("tower_run_parked").size());
        assertEquals("allocation_failed", of("tower_run_parked").get(0).fields().get("event"));
        assertTrue(of("tower_run_end").isEmpty());
        move(1, RunState.RECOVERY_REQUIRED, RunState.ABANDONED, RunEvent.RECOVERY_ABANDONED, 10);
        var f = of("tower_run_end").get(0).fields();
        assertEquals("parked_abandoned", f.get("cause"));
        assertFalse(f.containsKey("seconds"), "a run whose start was not seen has no length");
    }

    @Test void everyEndingEventHasAPlainCause() {
        assertEquals("completed", RunTimeline.causeOf(RunEvent.FINAL_FLOOR_CLEARED));
        assertEquals("cashed_out", RunTimeline.causeOf(RunEvent.CASH_OUT_CHOSEN));
        assertEquals("technical", RunTimeline.causeOf(RunEvent.TECHNICAL_FAILURE));
        assertEquals("party_rejected", RunTimeline.causeOf(RunEvent.PARTY_REJECTED));
    }
}
