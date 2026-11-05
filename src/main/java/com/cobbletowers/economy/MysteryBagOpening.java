package com.cobbletowers.economy;

import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * Opening one physical Mystery Bag, as plain rules: the prize is rolled once (when the bag is first opened) and remembered, it is paid under the bag's own
 * id, and only a payment that went through marks the bag open. So a duplicated bag pays once, a failed payment keeps the bag (and its prize) for a retry,
 * and opening again is never a reroll. Pure; {@link MysteryBagService} supplies the dice, the payer and the saving.
 */
public final class MysteryBagOpening {

    public enum Status {
        /** The prize was paid and the bag is open for good. */
        OPENED,
        /** This bag (this id) was opened before: a duplicate, which pays nothing. */
        ALREADY_OPENED,
        /** The prize could not be paid: the bag stays closed with its prize kept. */
        NOT_PAID
    }

    /** What the payer says about paying a prize. */
    public enum Pay { PAID, UNAVAILABLE, FAILED }

    /** Pays a bag's prize. Must be safe to repeat with the same bag id: the same bag is never paid twice. */
    public interface Payer {
        Pay pay(UUID bag, MysteryBag.Prize prize);
    }

    /** @param prize what the bag holds; null only for {@link Status#ALREADY_OPENED} */
    public record Result(Status status, MysteryBag.Prize prize) {}

    private MysteryBagOpening() {}

    public static Result open(MysteryBagBook book, UUID bag, IntUnaryOperator random, Payer payer) {
        if (book.isOpened(bag)) return new Result(Status.ALREADY_OPENED, null);
        MysteryBag.Prize prize = book.rolledFor(bag).orElse(null);
        if (prize == null) {
            prize = MysteryBag.roll(random);
            book.recordRoll(bag, prize);
        }
        if (payer.pay(bag, prize) != Pay.PAID) return new Result(Status.NOT_PAID, prize);
        book.recordOpened(bag);
        return new Result(Status.OPENED, prize);
    }
}
