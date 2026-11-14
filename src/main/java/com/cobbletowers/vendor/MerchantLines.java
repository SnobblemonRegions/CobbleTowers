package com.cobbletowers.vendor;

import com.cobbletowers.definition.VendorPlace;

/**
 * What the Gilded Merchant says over his counter (P59). Pure: the shop's state in, one line out, so the screen never has to reason about the
 * party itself. The lines are his voice: short, a little sly, about the customer's party, never about game mechanics.
 */
public final class MerchantLines {

    private MerchantLines() {}

    /**
     * The shop as the merchant sees it when the screen opens or refreshes.
     * @param place where the counter stands
     * @param floor 1-based floor; 0 in the preparation room
     * @param fainted how many of the chosen customer's Pokemon have fainted
     * @param hurt how many are below full HP and not fainted
     * @param lowestName the most hurt Pokemon still standing (lowest HP percent), or empty when nobody is hurt
     * @param statused how many carry a status (burn, poison, sleep, freeze, paralysis)
     * @param balance the buyer's Tower Tokens
     * @param cheapest the lowest price of anything sold here, or 0 when nothing is
     */
    public record Context(VendorPlace place, int floor, int fainted, int hurt, String lowestName, int statused, long balance, int cheapest) {
        public boolean healthy() {
            return fainted == 0 && hurt == 0 && statused == 0;
        }
    }

    static final String PREP = "Supplies for the climb. My bags I only open upstairs.";
    static final String BROKE = "Your purse is light, traveller. Come back with tokens.";
    static final String HEALTHY = "Everyone standing tall. Browse, then; I do not bite.";
    static final String STATUS = "Something ails your party. A cure costs less than a loss.";
    static final String GREETING = "Welcome back. Take your time.";

    static String fainted(int n) {
        return n == 1 ? "One of yours is down. A Full Heal sets that right." : n + " of yours are down. A Full Heal sets them all right.";
    }

    static String hurt(String name) {
        // no price in the line: armour discounts make it differ per customer, and the tag on the counter already says it
        return name + " looks tired. A Full Heal puts the lot right.";
    }

    /**
     * The one line he says now. The most urgent thing he can point at wins: an empty purse first (whatever ails the party, he can sell nothing),
     * then a fainted Pokemon, a status, low HP; only a healthy party hears about the prep room or gets browsed at.
     */
    public static String pick(Context c) {
        if (c.cheapest() > 0 && c.balance() < c.cheapest()) return BROKE;
        if (c.fainted() > 0) return fainted(c.fainted());
        if (c.statused() > 0) return STATUS;
        if (c.hurt() > 0 && !c.lowestName().isEmpty()) return hurt(c.lowestName());
        if (c.place() == VendorPlace.PREP) return PREP;
        return c.healthy() ? HEALTHY : GREETING;
    }
}
