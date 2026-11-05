package com.cobbletowers.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Opening a physical Mystery Bag: rolled once, paid once, and a duplicated bag opens only the first time. */
class MysteryBagOpeningTest {

    private static final MysteryBag.Prize DUST = new MysteryBag.Prize("resonance_dust", 50);

    /** A source of numbers that always answers 0: the first ticket (dust) and the lowest quantity (40). */
    private static int zero(int bound) {
        return 0;
    }

    private static final class Payer implements MysteryBagOpening.Payer {
        final List<MysteryBag.Prize> paid = new ArrayList<>();
        final List<UUID> keys = new ArrayList<>();
        MysteryBagOpening.Pay answer = MysteryBagOpening.Pay.PAID;

        @Override
        public MysteryBagOpening.Pay pay(UUID bag, MysteryBag.Prize prize) {
            keys.add(bag);
            paid.add(prize);
            return answer;
        }
    }

    @Test
    @DisplayName("the first opening rolls, pays the prize under the bag's own key, and marks the bag opened")
    void firstOpeningPays() {
        MysteryBagBook book = new MysteryBagBook();
        Payer payer = new Payer();
        UUID bag = UUID.randomUUID();

        MysteryBagOpening.Result result = MysteryBagOpening.open(book, bag, MysteryBagOpeningTest::zero, payer);

        assertEquals(MysteryBagOpening.Status.OPENED, result.status());
        assertEquals(new MysteryBag.Prize("resonance_dust", 40), result.prize());
        assertEquals(List.of(bag), payer.keys, "the bag's id is the idempotency key");
        assertEquals(1, payer.paid.size());
        assertTrue(book.isOpened(bag));
    }

    @Test
    @DisplayName("a duplicated bag (the same id) opens only once, and the second copy pays nothing")
    void aDuplicateOpensOnlyOnce() {
        MysteryBagBook book = new MysteryBagBook();
        Payer payer = new Payer();
        UUID bag = UUID.randomUUID();

        MysteryBagOpening.open(book, bag, MysteryBagOpeningTest::zero, payer);
        MysteryBagOpening.Result second = MysteryBagOpening.open(book, bag, MysteryBagOpeningTest::zero, payer);

        assertEquals(MysteryBagOpening.Status.ALREADY_OPENED, second.status());
        assertEquals(1, payer.paid.size(), "no second payment");
    }

    @Test
    @DisplayName("when the prize cannot be paid, the bag is not opened, and the retry pays the SAME prize however the dice fall")
    void aFailedPaymentKeepsTheRolledPrize() {
        MysteryBagBook book = new MysteryBagBook();
        Payer payer = new Payer();
        payer.answer = MysteryBagOpening.Pay.UNAVAILABLE;
        UUID bag = UUID.randomUUID();

        MysteryBagOpening.Result first = MysteryBagOpening.open(book, bag, MysteryBagOpeningTest::zero, payer);
        assertEquals(MysteryBagOpening.Status.NOT_PAID, first.status());
        assertFalse(book.isOpened(bag), "the bag stays closed so the player keeps it");

        payer.answer = MysteryBagOpening.Pay.PAID;
        // the dice would now give the jackpot, but the bag's prize was settled by the first roll
        MysteryBagOpening.Result retry = MysteryBagOpening.open(book, bag, bound -> bound - 1, payer);

        assertEquals(MysteryBagOpening.Status.OPENED, retry.status());
        assertEquals(first.prize(), retry.prize(), "reopening is never a reroll");
        assertTrue(book.isOpened(bag));
    }

    @Test
    @DisplayName("a refused payment is treated like an unavailable one: nothing is opened")
    void aRefusedPaymentOpensNothing() {
        MysteryBagBook book = new MysteryBagBook();
        Payer payer = new Payer();
        payer.answer = MysteryBagOpening.Pay.FAILED;
        UUID bag = UUID.randomUUID();

        assertEquals(MysteryBagOpening.Status.NOT_PAID, MysteryBagOpening.open(book, bag, MysteryBagOpeningTest::zero, payer).status());
        assertFalse(book.isOpened(bag));
    }

    @Test
    @DisplayName("two different bags are two different rolls and two payments")
    void twoBagsAreTwoRolls() {
        MysteryBagBook book = new MysteryBagBook();
        Payer payer = new Payer();

        MysteryBagOpening.open(book, UUID.randomUUID(), MysteryBagOpeningTest::zero, payer);
        MysteryBagOpening.open(book, UUID.randomUUID(), MysteryBagOpeningTest::zero, payer);

        assertEquals(2, payer.paid.size());
    }

    @Test
    @DisplayName("an opened bag cannot be rolled again")
    void anOpenedBagCannotBeRolled() {
        MysteryBagBook book = new MysteryBagBook();
        UUID bag = UUID.randomUUID();
        book.recordRoll(bag, DUST);
        book.recordOpened(bag);

        assertThrows(IllegalStateException.class, () -> book.recordRoll(bag, DUST));
        assertEquals(java.util.Optional.empty(), book.rolledFor(bag), "an opened bag keeps no pending prize");
    }

    @Test
    @DisplayName("the ledger survives a save and a load: opened bags stay opened and a pending prize stays pending")
    void theLedgerRoundTrips() {
        MysteryBagBook book = new MysteryBagBook();
        UUID opened = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        book.recordRoll(opened, DUST);
        book.recordOpened(opened);
        book.recordRoll(pending, new MysteryBag.Prize("unique_catalyst", 1));

        CompoundTag tag = book.toTag();
        MysteryBagBook loaded = MysteryBagBook.fromTag(tag);

        assertTrue(loaded.isOpened(opened));
        assertFalse(loaded.isOpened(pending));
        assertEquals(java.util.Optional.of(new MysteryBag.Prize("unique_catalyst", 1)), loaded.rolledFor(pending));
    }
}
