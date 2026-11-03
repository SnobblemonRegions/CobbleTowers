package com.cobbletowers.trick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.reward.RewardValuation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TrickRulesTest {

    private static final ResourceLocation VAULT = ResourceLocation.fromNamespaceAndPath("cobbletowers", "halloween/jack_o_lantern_vault");
    private static final ResourceLocation ITEM = ResourceLocation.fromNamespaceAndPath("cobblemon", "rare_candy");

    @Test
    void theCoinIsEvenAndStable() {
        int tricks = 0;
        for (int seed = 0; seed < 4000; seed++) {
            TrickRules.Result a = TrickRules.roll(seed * 7919L, 3);
            assertEquals(a, TrickRules.roll(seed * 7919L, 3), "the same run and floor read the same coin every time");
            if (a == TrickRules.Result.TRICK) tricks++;
        }
        assertTrue(Math.abs(tricks - 2000) < 150, "about half are tricks, got " + tricks);
    }

    @Test
    void aTreatCarriesARelicNowAndThen() {
        int relics = 0;
        for (int seed = 0; seed < 4000; seed++) if (TrickRules.treatHasRelic(seed * 104_729L, 5)) relics++;
        assertTrue(Math.abs(relics - 800) < 120, "about a fifth, got " + relics);
    }

    @Test
    void aLostMimicTakesOneHeldRelicAndNothingWhenThereIsNone() {
        assertTrue(TrickRules.relicLost(List.of(), 1L, 4).isEmpty());
        ResourceLocation a = ResourceLocation.fromNamespaceAndPath("cobbletowers", "relic/a");
        ResourceLocation b = ResourceLocation.fromNamespaceAndPath("cobbletowers", "relic/b");
        var taken = TrickRules.relicLost(List.of(a, b), 99L, 4);
        assertTrue(taken.isPresent() && List.of(a, b).contains(taken.get()));
        assertEquals(taken, TrickRules.relicLost(List.of(a, b), 99L, 4));
    }

    @Test
    void theCutIsFivePercentPerLossAndAWinUndoesIt() {
        List<LedgerEntry> ledger = new ArrayList<>();
        assertEquals(0, TrickRules.cutPercent(ledger));
        ledger.add(LedgerEntry.trickLost(2, VAULT, 1));
        assertEquals(5, TrickRules.cutPercent(ledger));
        ledger.add(LedgerEntry.trickLost(6, VAULT, 2));
        assertEquals(10, TrickRules.cutPercent(ledger));
        ledger.add(LedgerEntry.trickWonBack(6, VAULT, 3));
        assertEquals(5, TrickRules.cutPercent(ledger));
        ledger.add(LedgerEntry.trickWonBack(2, VAULT, 4));
        ledger.add(LedgerEntry.trickWonBack(2, VAULT, 5));
        assertEquals(0, TrickRules.cutPercent(ledger), "never below nothing");
    }

    @Test
    void theCutTakesExactlyItsShareOnAverageAndIsRepeatable() {
        long total = 0;
        int runs = 20_000;
        for (int seed = 0; seed < runs; seed++) {
            int once = TrickRules.cut(1, 5, seed);
            assertEquals(once, TrickRules.cut(1, 5, seed), "a retry of the same payout comes out the same");
            total += once;
        }
        assertTrue(Math.abs(total - runs * 95 / 100) < 300, "a one-item grant survives 95 times in 100, got " + total + " of " + runs);
        assertEquals(19, TrickRules.cut(20, 5, 1L), "20 less 5% is exactly 19");
        assertEquals(100, TrickRules.cut(100, 0, 1L));
        assertEquals(0, TrickRules.cut(0, 5, 1L));
    }

    @Test
    void theCutReachesEveryGrantIncludingTheGuaranteedOnes() {
        var grants = List.of(new RewardValuation.Grant(ITEM, 100), new RewardValuation.Grant(ITEM, 40, true));
        var cut = TrickRules.apply(grants, 10, 7L);
        assertEquals(90, cut.get(0).amount());
        assertEquals(36, cut.get(1).amount());
        assertTrue(cut.get(1).perPlayer(), "a guaranteed item stays a guaranteed item");
        assertEquals(grants, TrickRules.apply(grants, 0, 7L));
    }

    @Test
    void theMimicIsCostumedMimikyuAtTheDrawnLevel() {
        var drawn = new com.cobbletowers.encounter.EncounterSnapshot(3, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", "eevee"),
                java.util.List.of("regional"), 37);
        var mimic = TrickRules.mimicOf(drawn);
        org.junit.jupiter.api.Assertions.assertEquals(TrickRules.MIMIC_SPECIES, mimic.species());
        org.junit.jupiter.api.Assertions.assertEquals(37, mimic.level());
        org.junit.jupiter.api.Assertions.assertEquals(3, mimic.ordinal());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(TrickRules.MIMIC_ASPECT), mimic.aspects());
    }
}
