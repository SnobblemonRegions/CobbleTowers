package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StructurePiecesTest {

    @Test
    @DisplayName("a block's piece is the chunk the sliced build pastes it in, whatever the origin's place in its chunk")
    void pieceMatchesTheChunkItIsPastedIn() {
        for (int originX : new int[]{0, 7, 15, 16, 1205, 1279, -9}) {
            int offset = originX & 15;
            for (int local = 0; local < 200; local++) {
                int world = originX + local;
                // what SlicedBuild looks up for the box of the chunk this block is in
                int pasted = (world >> 4) - (originX >> 4);
                assertEquals(pasted, StructurePieces.column(local, offset), "origin " + originX + ", local " + local);
            }
        }
    }

    @Test
    @DisplayName("every cell pastes a building at the same place in its chunk, so one cut serves them all")
    void everyCellSharesTheCut() {
        for (int width : new int[]{56, 66, 93, 150}) {
            BlockPos first = CellTickets.pasteOrigin(0, width, width + 10);
            for (int cell : new int[]{1, 63, 64, 4095}) {
                BlockPos other = CellTickets.pasteOrigin(cell, width, width + 10);
                assertEquals(first.getX() & 15, other.getX() & 15);
                assertEquals(first.getZ() & 15, other.getZ() & 15);
            }
        }
    }

    @Test
    @DisplayName("piece keys never collide between columns")
    void keysAreDistinct() {
        assertNotEquals(StructurePieces.index(1, 0), StructurePieces.index(0, 1));
        assertNotEquals(StructurePieces.index(-1, 0), StructurePieces.index(0, -1));
        assertEquals(StructurePieces.index(3, 9), StructurePieces.index(3, 9));
    }
}
