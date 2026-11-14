package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: the preparation room sits centred under the build, inside the cell and inside its reset. */
class PrepPlacementTest {

    @Test
    @DisplayName("centred in the cell, DROP blocks under the build floor")
    void origin() {
        BlockPos centre = CellGrid.centerOf(3);
        BlockPos origin = PrepPlacement.origin(3, new Vec3i(56, 26, 44));
        assertEquals(centre.getX() - 28, origin.getX());
        assertEquals(centre.getZ() - 22, origin.getZ());
        assertEquals(CellGrid.FLOOR_Y - PrepPlacement.DROP, origin.getY());
    }

    @Test
    @DisplayName("a prep structure must stop a row under the build floor, and stay above the world floor")
    void fits() {
        assertTrue(PrepPlacement.fits(26), "Neutral's prep room is 26 high");
        assertTrue(PrepPlacement.fits(PrepPlacement.DROP - 1));
        assertFalse(PrepPlacement.fits(PrepPlacement.DROP));
        assertFalse(PrepPlacement.fits(0));
        assertTrue(CellGrid.FLOOR_Y - PrepPlacement.DROP >= CellGrid.MIN_Y);
    }
}
