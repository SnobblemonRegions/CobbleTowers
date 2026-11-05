package com.cobbletowers.economy;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The pure half of the goblin's mystery bag: which AscensionLib currency a bag can hold and how likely each is. The bag is bought with the tower's
 * own CobbleDollars (earned only in the tower, and already trimmed by the daily completion budget) and pays out through the same pending-reward queue
 * as a floor reward, so a full inventory holds the prize for {@code /tower claim}. The roll is made on the server; the client never sees the table.
 *
 * <p>Owner decision (2026-11-05): unlike Full Heal and Cure Status, the bag hands over items, on purpose. It is the one exception to TDS #20 C.
 */
public final class MysteryBag {

    /**
     * One line of the odds table: a material (an {@link AscensionMaterials} id), its weight out of the table's total, and how many a hit gives
     * (from {@code min} to {@code max}, evenly).
     */
    public record Odds(String material, int weight, int min, int max) {
        public Odds {
            if (weight < 1) throw new IllegalArgumentException("a weight must be at least 1, got " + weight);
            if (min < 1 || max < min) throw new IllegalArgumentException("a quantity must be 1 or more and max at least min, got " + min + ".." + max);
        }

        static Odds one(String material, int weight) {
            return new Odds(material, weight, 1, 1);
        }
    }

    /** What one bag held. */
    public record Prize(String material, int amount) {}

    /**
     * The odds, out of 1000. Resonance Dust is by far the most common and pays a handful (40 to 60); every other currency pays one. The rest follow the
     * library's own rarity ladder, so each step up is rarer than the one below it:
     * <ul>
     *   <li>COMMON, 50%: Resonance Dust;</li>
     *   <li>UNCOMMON, 25%: Facet 14%, Scouter 11%;</li>
     *   <li>RARE, 14%: Tower Mark 7%, Unique Fragment 7% (a Catalyst takes 100 Fragments, so a Fragment is no shortcut to the jackpot);</li>
     *   <li>EPIC, 9.5%: Ascension Core 3%, Ascension Sigil 2%, Hourglass of Undoing 2%, Apex Shard 1.5%, Spooky Essence 1%;</li>
     *   <li>LEGENDARY, 1.5%: Unbinding Prism 1%, and the jackpot, the Unique Catalyst, at 0.5% (one bag in two hundred).</li>
     * </ul>
     * The Tower Mark, the boss-loot currencies and the respec items are kept thin on purpose: they are earned by clearing the tower and are not meant to
     * be bought in bulk.
     */
    static final List<Odds> ODDS = List.of(
            new Odds("resonance_dust", 500, 40, 60),
            Odds.one("facet", 140),
            Odds.one("scouter", 110),
            Odds.one("tower_mark", 70),
            Odds.one("unique_fragment", 70),
            Odds.one("ascension_core", 30),
            Odds.one("ascension_sigil", 20),
            Odds.one("rank_respec", 20),
            Odds.one("apex_shard", 15),
            Odds.one("spooky_essence", 10),
            Odds.one("unique_respec", 10),
            Odds.one("unique_catalyst", 5));

    private MysteryBag() {}

    /** The sum of the weights of {@code odds}. */
    static int total(List<Odds> odds) {
        int total = 0;
        for (Odds line : odds) total += line.weight();
        return total;
    }

    /** The line that holds ticket {@code ticket} (0 up to the total, exclusive): tickets are handed out in table order. */
    static Odds pick(List<Odds> odds, int ticket) {
        if (ticket < 0) throw new IllegalArgumentException("ticket " + ticket + " is outside the table of " + total(odds));
        int left = ticket;
        for (Odds line : odds) {
            if (left < line.weight()) return line;
            left -= line.weight();
        }
        throw new IllegalArgumentException("ticket " + ticket + " is outside the table of " + total(odds));
    }

    /**
     * Rolls the bag once: a ticket picks the currency, and a second number picks how many (only when the line pays a range).
     * @param nextInt a source of a number from 0 up to, and not including, its argument (the server's own random)
     */
    public static Prize roll(IntUnaryOperator nextInt) {
        Odds line = pick(ODDS, nextInt.applyAsInt(total(ODDS)));
        int amount = line.max() == line.min() ? line.min() : line.min() + nextInt.applyAsInt(line.max() - line.min() + 1);
        return new Prize(line.material(), amount);
    }
}
