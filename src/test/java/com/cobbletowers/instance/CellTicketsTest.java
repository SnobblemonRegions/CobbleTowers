package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the cleanup stage and the paste both rely on: who is holding what, and where a floor lands. */
class CellTicketsTest {

    @Test
    void recoveryFinishingCannotReleaseResumedRunsTicket() {
        var run = java.util.UUID.randomUUID();
        assertTrue(CellTickets.claim(4, run), "recovery adds the first physical ticket");
        assertFalse(CellTickets.claim(4, null), "resuming adds a claim, not a second physical ticket");
        assertFalse(CellTickets.releaseClaim(4, run), "recovery must not remove the physical ticket");
        assertTrue(CellTickets.isHeld(4));
        assertEquals(1, CellTickets.heldCount());
        assertTrue(CellTickets.releaseClaim(4, null), "the final holder removes the physical ticket");
        assertFalse(CellTickets.isHeld(4));
    }

    @Test
    void activeReleaseCannotReleaseRecoveryAndClaimsAreIdempotent() {
        var run = java.util.UUID.randomUUID();
        assertTrue(CellTickets.claim(4, null));
        assertFalse(CellTickets.claim(4, null));
        assertFalse(CellTickets.claim(4, run));
        assertFalse(CellTickets.claim(4, run));
        assertFalse(CellTickets.releaseClaim(4, null));
        assertFalse(CellTickets.releaseClaim(4, null), "releasing the same owner twice cannot steal recovery's claim");
        assertTrue(CellTickets.isHeld(4));
        assertTrue(CellTickets.releaseClaim(4, run));
        assertFalse(CellTickets.isHeld(4));
    }

    @Test
    void unrelatedRecoveryCannotReleaseAnotherRunsClaim() {
        var run = java.util.UUID.randomUUID();
        assertTrue(CellTickets.claim(4, run));
        assertFalse(CellTickets.releaseClaim(4, java.util.UUID.randomUUID()));
        assertTrue(CellTickets.isHeld(4));
        assertTrue(CellTickets.releaseClaim(4, run));
    }

    @BeforeEach
    @AfterEach
    void clear() {
        CellTickets.resetForTests();
    }

    @Test
    @DisplayName("a held cell is known to be held, and letting go is remembered too")
    void holding() {
        // This is the third cleanup stage's only input: a cell nobody has let go of is not reusable,
        // and before P4 that stage was left out rather than answered wrongly.
        assertFalse(CellTickets.isHeld(4));

        CellTickets.holdForTests(4);

        assertTrue(CellTickets.isHeld(4));
        assertEquals(1, CellTickets.heldCount());
        assertFalse(CellTickets.isHeld(5), "holding one cell says nothing about another");
    }

    @Test
    @DisplayName("shutdown forgets every hold, because these statics outlive a world")
    void shutdownClears() {
        CellTickets.holdForTests(1);
        CellTickets.holdForTests(2);

        assertEquals(2, CellTickets.onServerStopped());

        assertEquals(0, CellTickets.heldCount());
        assertFalse(CellTickets.isHeld(1));
    }

    @Test
    @DisplayName("a cell nothing has let go of is not fit to hand on")
    void ownershipStageBites() {
        // The ticket stage is proven here because a live release drops the ticket before checking.
        CellCleanup.Report clean = new CellCleanup.Report(4, java.util.List.of());
        assertTrue(CellCleanup.verifyReleased(4, clean).isClean());

        CellTickets.holdForTests(4);
        CellCleanup.Report withTicket = CellCleanup.verifyReleased(4, clean);

        assertFalse(withTicket.isClean(), "a cell whose chunks are still held is not reusable");
        assertTrue(withTicket.summary().contains("chunk tickets"), withTicket.summary());
    }

    @Test
    @DisplayName("a cell the warm pool still lists is not fit to hand on either")
    void warmCellIsNotReleased() {
        CellWarmPool.resetForTests();
        CellWarmPool.addForTests(net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("cobbletowers", "arena_floor"), 9);

        CellCleanup.Report report = CellCleanup.verifyReleased(9, new CellCleanup.Report(9, java.util.List.of()));

        assertFalse(report.isClean(), "a cell cannot be both released and waiting to be handed out");
        assertTrue(report.summary().contains("warm pool"), report.summary());
        CellWarmPool.resetForTests();
    }

    @Test
    @DisplayName("a floor is pasted centred in its cell")
    void pasteOriginCentres() {
        // 51 wide in a 256 interior: the origin sits back by half the structure so the middle of the
        // arena is the middle of the cell, whatever size the structure is.
        BlockPos origin = CellTickets.pasteOrigin(0, 51, 51);
        BlockPos centre = CellGrid.centerOf(0);

        assertEquals(centre.getX() - 25, origin.getX());
        assertEquals(centre.getZ() - 25, origin.getZ());
        assertEquals(CellGrid.FLOOR_Y, origin.getY(), "floors sit at the cell's floor height");
    }

    @Test
    @DisplayName("a pasted arena stays inside its own cell, at both arena sizes")
    void pasteStaysInsideTheCell() {
        for (int size : new int[]{51, 49}) {
            for (int cell : new int[]{0, 1, 63, 64, 4095}) {
                BlockPos origin = CellTickets.pasteOrigin(cell, size, size);
                assertEquals(java.util.OptionalInt.of(cell),
                        CellGrid.indexAt(origin.getX(), origin.getZ()),
                        "the near corner of a " + size + "-block arena left cell " + cell);
                assertEquals(java.util.OptionalInt.of(cell),
                        CellGrid.indexAt(origin.getX() + size - 1, origin.getZ() + size - 1),
                        "the far corner of a " + size + "-block arena left cell " + cell);
            }
        }
    }

    @Test
    @DisplayName("the ticket covers the arena, and the count it reports is the real one")
    void ticketFootprint() {
        // 11x11 chunks. The number matters because it is what an operator multiplies by the number of
        // active runs, so it is reported rather than guessed at.
        assertEquals(121, CellTickets.chunksPerCell());
        assertTrue(CellTickets.RADIUS_CHUNKS * 16 * 2 + 16 <= CellGrid.INTERIOR,
                "the held chunks must stay inside the cell's interior, clear of the next cell");

        BlockPos centre = CellGrid.centerOf(0);
        ChunkPos centreChunk = new ChunkPos(centre);
        int reach = CellTickets.RADIUS_CHUNKS * 16;
        assertTrue(reach >= 51 / 2 + 8,
                "the ticket radius must cover the widest arena from the middle, with a margin");
        assertEquals(centreChunk.x, new ChunkPos(centre).x);
    }
}
