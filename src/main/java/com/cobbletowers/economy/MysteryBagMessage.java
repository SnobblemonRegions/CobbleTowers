package com.cobbletowers.economy;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * What a player is told when a Mystery Bag opens: a framed banner in chat with the prize in bold and in its rarity's colour (the library's own rarity
 * colours), and, for an epic or legendary prize, a title across the screen too, so a good one is never missed in a busy chat. Pure text; the sounds and the
 * sending are {@link MysteryBagService}'s.
 */
public final class MysteryBagMessage {

    /** The library's rarity ladder and its colours, weakest first. */
    public enum Rarity {
        COMMON("COMMON", 0xC1C5CF, false),
        UNCOMMON("UNCOMMON", 0x1BD7E8, false),
        RARE("RARE", 0x38CCEF, false),
        EPIC("EPIC", 0xAF69FF, true),
        LEGENDARY("LEGENDARY", 0xFFD437, true);

        private final String word;
        private final int rgb;
        private final boolean titled;

        Rarity(String word, int rgb, boolean titled) {
            this.word = word;
            this.rgb = rgb;
            this.titled = titled;
        }

        public String word() {
            return word;
        }

        public int rgb() {
            return rgb;
        }

        /** Whether a prize of this rarity also takes over the screen with a title. */
        public boolean titled() {
            return titled;
        }
    }

    private static final int FRAME = 0xE8A33D;
    private static final int PLAIN = 0xC1C5CF;
    private static final int DIM = 0x8A8F9C;

    private MysteryBagMessage() {}

    /** The rarity AscensionLib prints on this currency's item. */
    public static Rarity rarityOf(String material) {
        return switch (material) {
            case "resonance_dust" -> Rarity.COMMON;
            case "facet", "scouter" -> Rarity.UNCOMMON;
            case "tower_mark", "unique_fragment" -> Rarity.RARE;
            case "ascension_core", "ascension_sigil", "apex_shard", "spooky_essence", "rank_respec" -> Rarity.EPIC;
            case "unique_respec", "unique_catalyst" -> Rarity.LEGENDARY;
            default -> Rarity.COMMON;
        };
    }

    private static Style style(int rgb, boolean bold) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold).withItalic(false);
    }

    /** The chat banner: a frame, the prize in bold and in its rarity's colour, and how to deposit it. */
    public static List<Component> banner(MysteryBag.Prize prize, String label) {
        Rarity rarity = rarityOf(prize.material());
        String thing = prize.amount() > 1 ? prize.amount() + "× " + label : label;
        MutableComponent top = Component.literal("✦ ━━━━━━  MYSTERY BAG  ━━━━━━ ✦").withStyle(style(FRAME, true));
        MutableComponent found = Component.literal("   You found  ").withStyle(style(PLAIN, false))
                .append(Component.literal(thing).withStyle(style(rarity.rgb(), true)))
                .append(Component.literal("  [" + rarity.word() + "]").withStyle(style(rarity.rgb(), true)));
        MutableComponent how = Component.literal("   Right-click the voucher items to deposit them.").withStyle(style(DIM, false));
        MutableComponent bottom = Component.literal("✦ ━━━━━━━━━━━━━━━━━━━━━━━━ ✦").withStyle(style(FRAME, true));
        return List.of(Component.empty(), top, found, how, bottom);
    }

    /** The big title for an epic or legendary prize: the prize itself, in bold and its colour. */
    public static Component title(MysteryBag.Prize prize, String label) {
        Rarity rarity = rarityOf(prize.material());
        return Component.literal(label).withStyle(style(rarity.rgb(), true));
    }

    /** The line under the title: which bag it came from and how rare the prize is. */
    public static Component subtitle(MysteryBag.Prize prize) {
        Rarity rarity = rarityOf(prize.material());
        return Component.literal("Mystery Bag  •  " + rarity.word()).withStyle(style(FRAME, true));
    }
}
