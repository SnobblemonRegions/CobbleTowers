package com.cobbletowers.definition;

/**
 * What a vendor purchase does (TDS #20 C): direct recovery of a target's live party, never a handed-over item. Closed on purpose; a new kind of
 * service is a reviewed decision. The one reviewed exception is the mystery bag.
 */
public enum VendorEffect {
    /** Every Pokemon in the target's party is set to full current health. */
    FULL_HEAL,
    /** Every persistent status condition in the target's party is cleared. */
    CURE_STATUS,
    /**
     * The goblin's mystery bag: sells a physical, tradable bag item ({@code MysteryBagItem}); opening it (a right-click) rolls a random AscensionLib currency
     * on the server ({@code MysteryBag}) and AscensionLib pays it as voucher items. Approved by the owner on 2026-11-05 as the one service that hands over items.
     */
    MYSTERY_BAG
}
