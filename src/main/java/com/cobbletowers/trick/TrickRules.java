package com.cobbletowers.trick;

import com.cobbletowers.encounter.EncounterSeed;
import com.cobbletowers.encounter.EncounterSnapshot;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.reward.RewardValuation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.resources.ResourceLocation;

/**
 * The rules of the Trick or Treat vault (P54, P55): an even-odds coin from the run seed, what a treat pays, and what a lost mimic costs, which is one
 * relic (if the run holds any) and {@value #LOSS_CUT_PERCENT} percent of the loot banked from then on, for each loss. Pure, so the odds and the cut are tested
 * without Minecraft.
 */
public final class TrickRules {

    /** The share of the final loot pool one lost mimic takes. */
    public static final int LOSS_CUT_PERCENT = 5;
    /** The chance, in percent, that opening the vault is a trick. */
    public static final int TRICK_PERCENT = 50;
    /** The chance, in percent, that a treat also carries a relic. */
    public static final int TREAT_RELIC_PERCENT = 20;

    /** Its own ordinal space, like every other draw. */
    static final int ORDINAL_BASE = 7_000_019;

    /** The vault's mimic is Mimikyu in its Halloween skin (the cosmetic aspect is ignored by Cobblemon where the skin pack is not installed). */
    public static final ResourceLocation MIMIC_SPECIES = ResourceLocation.fromNamespaceAndPath("cobblemon", "mimikyu");
    public static final String MIMIC_ASPECT = "snobble_halloween_mimikyu";

    /** SpookySurge's item, paid with a treat. */
    public static final ResourceLocation CANDY_CORN = ResourceLocation.fromNamespaceAndPath("spookysurge", "candy_corn");

    /** How many Candy Corn a treat on this floor pays each player: 5 on the first floor, 2 more for each floor after. */
    public static int candyCorn(int floorIndex) {
        return 5 + 2 * Math.max(0, floorIndex);
    }

    private TrickRules() {}

    /** The mimic that fights in place of the opponent drawn for this slot: the same level, so the difficulty is the floor's, but wearing the vault's costume. */
    public static EncounterSnapshot mimicOf(EncounterSnapshot drawn) {
        return new EncounterSnapshot(drawn.ordinal(), MIMIC_SPECIES, List.of(MIMIC_ASPECT), drawn.level());
    }

    public enum Result { TREAT, TRICK }

    /** What the vault of this floor holds. Seeded, so leaving and coming back, or a replay of the run code, finds the same thing. */
    public static Result roll(long runSeed, int floorIndex) {
        return Math.floorMod(EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE), 100) < TRICK_PERCENT ? Result.TRICK : Result.TREAT;
    }

    /** Whether this treat also hands over a relic. */
    public static boolean treatHasRelic(long runSeed, int floorIndex) {
        return Math.floorMod(EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE + 1), 100) < TREAT_RELIC_PERCENT;
    }

    /** The relic a lost mimic takes: one of those held, chosen by the seed; empty when the run holds none. */
    public static Optional<ResourceLocation> relicLost(List<ResourceLocation> relics, long runSeed, int floorIndex) {
        if (relics.isEmpty()) return Optional.empty();
        return Optional.of(relics.get((int) Math.floorMod(EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE + 2), relics.size())));
    }

    /** The cut on the loot banked from now on, in percent: {@value #LOSS_CUT_PERCENT} for each mimic lost and not won back. */
    public static int cutPercent(List<LedgerEntry> ledger) {
        int net = 0;
        for (LedgerEntry entry : ledger) {
            if (entry.kind() == LedgerEntry.Kind.TRICK_LOST) net++;
            else if (entry.kind() == LedgerEntry.Kind.TRICK_WON_BACK) net--;
        }
        return Math.min(100, Math.max(0, net) * LOSS_CUT_PERCENT);
    }

    /**
     * {@code amount} less {@code percent} percent. The fraction is settled by the seed (a one-item grant loses its item about {@code percent} times in a
     * hundred) so the expected loss is exactly the cut and a retry of the same payout comes out the same.
     */
    public static int cut(int amount, int percent, long seed) {
        if (amount <= 0 || percent <= 0) return amount;
        long scaled = (long) amount * (100 - Math.min(percent, 100));
        int whole = (int) (scaled / 100);
        int fraction = (int) (scaled % 100);
        return whole + (fraction > 0 && new Random(seed).nextInt(100) < fraction ? 1 : 0);
    }

    /** The grants with the cut taken from each, the milestone's guaranteed items included; a grant that is cut away entirely is dropped. */
    public static List<RewardValuation.Grant> apply(List<RewardValuation.Grant> grants, int percent, long lootSeed) {
        if (percent <= 0) return grants;
        List<RewardValuation.Grant> kept = new ArrayList<>();
        int position = 0;
        for (RewardValuation.Grant grant : grants) {
            int amount = cut(grant.amount(), percent, lootSeed * 31 + position++ * 104_729L);
            if (amount > 0) kept.add(new RewardValuation.Grant(grant.item(), amount, grant.perPlayer()));
        }
        return List.copyOf(kept);
    }
}
