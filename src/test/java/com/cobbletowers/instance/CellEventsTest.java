package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CellEventsTest {

    @Test
    @DisplayName("a door position belongs to the cell whose interior holds it, never to a neighbour")
    void inCell() {
        var origin = CellGrid.originOf(0);
        int x = origin.getX(), z = origin.getZ();
        assertTrue(CellEvents.inCell(0, x, z));
        assertTrue(CellEvents.inCell(0, x + CellGrid.INTERIOR - 1, z + CellGrid.INTERIOR - 1));
        assertFalse(CellEvents.inCell(0, x + CellGrid.INTERIOR, z));
        assertFalse(CellEvents.inCell(0, x - 1, z));
        var centre = CellGrid.centerOf(1);
        assertTrue(CellEvents.inCell(1, centre.getX(), centre.getZ()));
        assertFalse(CellEvents.inCell(0, centre.getX(), centre.getZ()));
    }

    @Test
    @DisplayName("every listener hears a wipe, even after one of them fails")
    void wipedReachesEveryListener() {
        List<Integer> heard = new ArrayList<>();
        CellEvents.onWiped(cell -> { throw new IllegalStateException("boom"); });
        CellEvents.onWiped(heard::add);
        CellEvents.wiped(7);
        assertEquals(List.of(7), heard);
    }
}
