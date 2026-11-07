package com.cobbletowers.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a player is told when a Mystery Bag opens: bold, in the prize's rarity colour, and impossible to miss when it is a good one. */
class MysteryBagMessageTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static String plain(List<Component> lines) {
        StringBuilder text = new StringBuilder();
        for (Component line : lines) text.append(line.getString()).append('\n');
        return text.toString();
    }

    @Test
    @DisplayName("every currency a bag can hold has a rarity, and the rarities follow the library's ladder")
    void everyPrizeHasARarity() {
        for (MysteryBag.Odds line : MysteryBag.ODDS) assertNotNull(MysteryBagMessage.rarityOf(line.material()), line.material());
        assertEquals(MysteryBagMessage.Rarity.COMMON, MysteryBagMessage.rarityOf("resonance_dust"));
        assertEquals(MysteryBagMessage.Rarity.UNCOMMON, MysteryBagMessage.rarityOf("facet"));
        assertEquals(MysteryBagMessage.Rarity.RARE, MysteryBagMessage.rarityOf("unique_fragment"));
        assertEquals(MysteryBagMessage.Rarity.EPIC, MysteryBagMessage.rarityOf("ascension_core"));
        assertEquals(MysteryBagMessage.Rarity.LEGENDARY, MysteryBagMessage.rarityOf("unique_catalyst"));
    }

    @Test
    @DisplayName("the rarer the prize, the lower its weight in the table, so a colour always means what the odds say")
    void rarityMatchesTheOdds() {
        for (MysteryBag.Odds a : MysteryBag.ODDS) {
            for (MysteryBag.Odds b : MysteryBag.ODDS) {
                int ra = MysteryBagMessage.rarityOf(a.material()).ordinal();
                int rb = MysteryBagMessage.rarityOf(b.material()).ordinal();
                if (ra > rb) assertTrue(a.weight() <= b.weight(), a.material() + " (" + ra + ") is rarer than " + b.material() + " (" + rb + ")");
            }
        }
    }

    @Test
    @DisplayName("the banner is framed, names the bag, the amount, the currency and its rarity, and says how to deposit")
    void bannerSaysEverything() {
        String text = plain(MysteryBagMessage.banner(new MysteryBag.Prize("resonance_dust", 44), "Resonance Dust"));
        assertTrue(text.contains("MYSTERY BAG"), text);
        assertTrue(text.contains("44"), text);
        assertTrue(text.contains("Resonance Dust"), text);
        assertTrue(text.contains("COMMON"), text);
        assertTrue(text.contains("Right-click"), text);
    }

    @Test
    @DisplayName("a single item is not written as 1x, and several are")
    void amounts() {
        assertFalse(plain(MysteryBagMessage.banner(new MysteryBag.Prize("facet", 1), "Facet")).contains("1×"));
        assertTrue(plain(MysteryBagMessage.banner(new MysteryBag.Prize("resonance_dust", 50), "Resonance Dust")).contains("50×"));
    }

    @Test
    @DisplayName("the prize itself is bold and in its rarity's colour, so it stands out from the rest of the banner")
    void thePrizeIsBoldAndColoured() {
        for (MysteryBagMessage.Rarity rarity : MysteryBagMessage.Rarity.values()) {
            String material = switch (rarity) {
                case COMMON -> "resonance_dust";
                case UNCOMMON -> "facet";
                case RARE -> "tower_mark";
                case EPIC -> "apex_shard";
                case LEGENDARY -> "unique_catalyst";
            };
            boolean found = false;
            for (Component line : MysteryBagMessage.banner(new MysteryBag.Prize(material, 1), "PRIZE-NAME")) {
                for (Component part : line.toFlatList(line.getStyle())) {
                    if (part.getString().contains("PRIZE-NAME")) {
                        found = true;
                        assertTrue(part.getStyle().isBold(), rarity + " prize must be bold");
                        assertEquals(rarity.rgb(), part.getStyle().getColor().getValue(), rarity + " colour");
                    }
                }
            }
            assertTrue(found, rarity + ": the prize text was not in the banner");
        }
    }

    @Test
    @DisplayName("only an epic or legendary prize takes over the screen with a title; the common ones stay in chat")
    void onlyTheGoodOnesGetATitle() {
        assertFalse(MysteryBagMessage.Rarity.COMMON.titled());
        assertFalse(MysteryBagMessage.Rarity.UNCOMMON.titled());
        assertFalse(MysteryBagMessage.Rarity.RARE.titled());
        assertTrue(MysteryBagMessage.Rarity.EPIC.titled());
        assertTrue(MysteryBagMessage.Rarity.LEGENDARY.titled());
    }

    @Test
    @DisplayName("the on-screen title is the prize, and its subtitle names the bag and the rarity")
    void titleAndSubtitle() {
        MysteryBag.Prize prize = new MysteryBag.Prize("unique_catalyst", 1);
        assertEquals("Unique Catalyst", MysteryBagMessage.title(prize, "Unique Catalyst").getString());
        String subtitle = MysteryBagMessage.subtitle(prize).getString();
        assertTrue(subtitle.contains("Mystery Bag") && subtitle.contains("LEGENDARY"), subtitle);
    }
}
