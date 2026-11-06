package com.cobbletowers.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The goblin's mystery bag: the odds table, the way a roll lands on a currency, and that every currency it can pay is one the tower can hand over. */
class MysteryBagTest {

    private static MysteryBag.Odds line(String material) {
        return MysteryBag.ODDS.stream().filter(odds -> odds.material().equals(material)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("tickets are handed out in table order, a weight at a time")
    void ticketsFollowTheTable() {
        List<MysteryBag.Odds> odds = List.of(MysteryBag.Odds.one("facet", 2), MysteryBag.Odds.one("scouter", 3));

        assertEquals(5, MysteryBag.total(odds));
        assertEquals("facet", MysteryBag.pick(odds, 0).material());
        assertEquals("facet", MysteryBag.pick(odds, 1).material());
        assertEquals("scouter", MysteryBag.pick(odds, 2).material());
        assertEquals("scouter", MysteryBag.pick(odds, 4).material());
    }

    @Test
    @DisplayName("a ticket outside the table is refused, never mapped to a free prize")
    void outsideTheTableIsRefused() {
        List<MysteryBag.Odds> odds = List.of(MysteryBag.Odds.one("facet", 1));

        assertThrows(IllegalArgumentException.class, () -> MysteryBag.pick(odds, 1));
        assertThrows(IllegalArgumentException.class, () -> MysteryBag.pick(odds, -1));
    }

    @Test
    @DisplayName("a weight below one, or a quantity that is not a positive range, is refused")
    void linesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> MysteryBag.Odds.one("facet", 0));
        assertThrows(IllegalArgumentException.class, () -> new MysteryBag.Odds("facet", 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MysteryBag.Odds("facet", 1, 5, 4));
    }

    @Test
    @DisplayName("the table is out of 1000, lists no currency twice, and every one is a material the tower knows how to hand over")
    void theTableIsSound() {
        assertEquals(1000, MysteryBag.total(MysteryBag.ODDS));
        Set<String> seen = new HashSet<>();
        for (MysteryBag.Odds line : MysteryBag.ODDS) {
            assertTrue(seen.add(line.material()), line.material() + " is listed twice");
            assertTrue(AscensionMaterials.of(ResourceLocation.fromNamespaceAndPath("ascensionlib", line.material())).isPresent(),
                    line.material() + " is not an AscensionMaterials id");
        }
        assertEquals(12, seen.size());
    }

    @Test
    @DisplayName("Resonance Dust is most of the bags and pays about 50; every other currency pays exactly one")
    void dustIsTheCommonPrize() {
        MysteryBag.Odds dust = line("resonance_dust");
        assertEquals(676, dust.weight());
        assertTrue(dust.weight() * 2 > MysteryBag.total(MysteryBag.ODDS), "dust must be a strict majority of the bags");
        assertEquals(40, dust.min());
        assertEquals(60, dust.max());
        for (MysteryBag.Odds other : MysteryBag.ODDS) {
            if (other == dust) continue;
            assertTrue(other.weight() < dust.weight(), other.material() + " is as common as dust");
            assertEquals(1, other.min(), other.material());
            assertEquals(1, other.max(), other.material());
        }
    }

    @Test
    @DisplayName("the Unique Catalyst is the rarest prize in the bag, one in a thousand")
    void theCatalystIsTheJackpot() {
        MysteryBag.Odds catalyst = line("unique_catalyst");
        assertEquals(1, catalyst.weight());
        for (MysteryBag.Odds other : MysteryBag.ODDS) {
            if (other != catalyst) assertTrue(other.weight() > catalyst.weight(), other.material() + " is as rare as the catalyst");
        }
    }

    @Test
    @DisplayName("each step up the library's rarity ladder is rarer than the one below it")
    void theLadderRunsDownhill() {
        int common = line("resonance_dust").weight();
        int uncommon = line("facet").weight() + line("scouter").weight();
        int rare = line("tower_mark").weight() + line("unique_fragment").weight();
        int epic = line("ascension_core").weight() + line("ascension_sigil").weight() + line("rank_respec").weight()
                + line("apex_shard").weight() + line("spooky_essence").weight();
        int legendary = line("unique_respec").weight() + line("unique_catalyst").weight();

        assertTrue(common > uncommon && uncommon > rare && rare > epic && epic > legendary,
                common + " > " + uncommon + " > " + rare + " > " + epic + " > " + legendary);
    }

    @Test
    @DisplayName("bags can be traded, so the good materials (epic and legendary together) stay under one bag in ten")
    void theGoodMaterialsAreVeryRare() {
        int good = line("ascension_core").weight() + line("ascension_sigil").weight() + line("rank_respec").weight()
                + line("apex_shard").weight() + line("spooky_essence").weight() + line("unique_respec").weight() + line("unique_catalyst").weight();
        assertTrue(good * 10 < MysteryBag.total(MysteryBag.ODDS), "epic and legendary are " + good + " of " + MysteryBag.total(MysteryBag.ODDS));
    }

    @Test
    @DisplayName("roll takes a ticket from the whole table, then a quantity from the line's range only when it has one")
    void rollAsksForTheTicketThenTheQuantity() {
        // Ticket 0 is dust; the quantity draw is from 0..20 (a 40..60 range has 21 values), so a draw of 10 gives 50.
        int[] calls = {0};
        MysteryBag.Prize dust = MysteryBag.roll(bound -> {
            calls[0]++;
            return calls[0] == 1 ? 0 : 10;
        });
        assertEquals(new MysteryBag.Prize("resonance_dust", 50), dust);
        assertEquals(2, calls[0]);

        // The last ticket is the catalyst: one of it, and no second draw.
        int[] asked = {0};
        MysteryBag.Prize catalyst = MysteryBag.roll(bound -> {
            asked[0]++;
            return MysteryBag.total(MysteryBag.ODDS) - 1;
        });
        assertEquals(new MysteryBag.Prize("unique_catalyst", 1), catalyst);
        assertEquals(1, asked[0]);
    }

    @Test
    @DisplayName("the quantity of dust always lands from 40 to 60")
    void dustQuantityStaysInRange() {
        for (int draw = 0; draw <= 20; draw++) {
            int fixed = draw;
            int[] calls = {0};
            MysteryBag.Prize prize = MysteryBag.roll(bound -> ++calls[0] == 1 ? 0 : fixed);
            assertTrue(prize.amount() >= 40 && prize.amount() <= 60, "draw " + draw + " gave " + prize.amount());
        }
    }

    @Test
    @DisplayName("every currency the bag can pay stacks as a real item with the library's own name")
    void everyPrizeHasAStack() {
        for (MysteryBag.Odds line : MysteryBag.ODDS) {
            var material = AscensionMaterials.of(ResourceLocation.fromNamespaceAndPath("ascensionlib", line.material())).orElseThrow();
            assertFalse(material.label().isBlank(), line.material());
            assertTrue(material.stack().contains("material:\"" + line.material() + "\""), line.material() + " stack carries the wrong material tag");
        }
    }
}
