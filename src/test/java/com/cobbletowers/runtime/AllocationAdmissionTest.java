package com.cobbletowers.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.persistence.PersistedRun;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The operator allocation's admission rule: back off while ended runs still hold cells that are waiting to be cleared. */
class AllocationAdmissionTest {

    private static PersistedRun run(int index, RunState state, OptionalInt cell) {
        UUID id = UUID.fromString(String.format("eeeeeeee-0000-0000-0000-%012d", index));
        return TestRuns.at(id, state, TestRuns.NOW).withCell(cell, TestRuns.NOW);
    }

    @Test
    @DisplayName("only ended runs that still hold a cell count as a pending release")
    void countsEndedRunsHoldingACell() {
        List<PersistedRun> runs = List.of(
                run(1, RunState.ABANDONED, OptionalInt.of(3)),
                run(2, RunState.ABANDONED, OptionalInt.empty()),      // already released
                run(3, RunState.ENCOUNTER_ACTIVE, OptionalInt.of(4)), // live: its cell is in use, not waiting
                run(4, RunState.ABANDONED, OptionalInt.of(5)));

        assertEquals(2, RunLifecycle.releasesPending(runs));
    }

    @Test
    @DisplayName("allocations go ahead below the cap and are refused at it")
    void refusesAtTheCap() {
        assertTrue(RunLifecycle.admitsAllocation(0));
        assertTrue(RunLifecycle.admitsAllocation(RunLifecycle.MAX_RELEASES_PENDING - 1));
        assertFalse(RunLifecycle.admitsAllocation(RunLifecycle.MAX_RELEASES_PENDING));
    }
}
