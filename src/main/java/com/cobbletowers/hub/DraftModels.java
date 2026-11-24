package com.cobbletowers.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/**
 * The Rental Draft's cards and effects at a Tower Hub gate (owner, 23 Nov 2026), written by {@code tools/tower_gen/draft_cards_export.py}: a
 * card model per rental species and tier (front: type face, tier frame, the Pokemon's Cobblemon Cards sprite, gems, foil on rare and better;
 * back: the card back), and the
 * flat effect models. Sizes from {@code data/cobbletowers/cobbletowers/hub/draft.json}.
 */
public final class DraftModels {

    public static final List<String> FX = List.of("pack", "pack_top", "pack_body", "rays", "ring", "flash", "stamp_legendary", "stamp_mythic",
            "kept", "check", "glow", "cover");
    private static final Map<String, PolymerModelData> MODELS = new HashMap<>();
    /** The card's width and height in blocks, and how far below its middle the name line sits. */
    public static final double CARD_W, CARD_H, NAME_BELOW;
    public static final List<String> TIERS;
    /**
     * How far a card's text display draws the card's middle above the display's own place, in blocks: a text display stands its block of
     * lines (4 here, 9 units a line) on its place, and the card hangs from the first line's baseline (7 units under the block's top),
     * 52 units long, at 1/40 block a unit.
     */
    public static final double CARD_TEXT_MID = (4 * 9 - 7 - 52 / 2.0) / 40.0;
    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("cobbletowers", "draft_cards");
    /**
     * The board's words (the stats back, the name labels): Jersey 15, the draft screen's own font, as a TrueType font the game draws smooth
     * at any size (owner, 24 Nov 2026: the game's 8-pixel font was blurry this small). Characters it lacks fall back to the game's font.
     */
    private static final ResourceLocation TEXT_FONT = ResourceLocation.fromNamespaceAndPath("cobbletowers", "draft_text");

    /** A name label on a card, in the board's font. */
    public static net.minecraft.network.chat.MutableComponent label(String text, int colour) {
        return Component.literal(text).withStyle(s -> s.withFont(TEXT_FONT).withColor(colour));
    }
    private static final Map<String, String> GLYPHS = new HashMap<>();

    static {
        JsonObject o;
        try (InputStream in = DraftModels.class.getResourceAsStream("/data/cobbletowers/cobbletowers/hub/draft.json")) {
            if (in == null) throw new IllegalStateException("draft.json is missing from the jar");
            o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read draft.json", ex);
        }
        JsonArray card = o.getAsJsonArray("card");
        CARD_W = card.get(0).getAsDouble();
        CARD_H = card.get(1).getAsDouble();
        NAME_BELOW = -o.get("name_at").getAsDouble();
        List<String> tiers = new ArrayList<>();
        o.getAsJsonArray("tiers").forEach(e -> tiers.add(e.getAsString()));
        TIERS = List.copyOf(tiers);
        for (var e : o.getAsJsonObject("glyphs").entrySet()) GLYPHS.put(e.getKey(), e.getValue().getAsString());
        for (String fx : FX) MODELS.put("fx/" + fx, request("draft/fx/" + fx));
    }

    private DraftModels() {}

    /** Forces the class to load, so every model is requested before Polymer builds the pack. */
    public static void bootstrap() {}

    private static PolymerModelData request(String path) {
        return PolymerResourcePackUtils.requestModel(Items.PAPER, ResourceLocation.fromNamespaceAndPath("cobbletowers", path));
    }

    private static String glyph(String key) {
        return GLYPHS.getOrDefault(key, "");
    }

    /** The card behind (its type's face with its tier's frame), four lines like every card text so all stand alike. */
    public static Component cardBase(com.cobbletowers.definition.RentalSetDefinition set, String tier) {
        String t0 = set.types().isEmpty() ? "normal" : set.types().get(0);
        return Component.literal(glyph("card_" + t0 + "_" + tier) + "\n\n\n").withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF));
    }

    /** What stands just in front of the card: the Pokemon's Cobblemon Cards sprite on the second line, its type gems on the third and fourth. */
    public static Component cardFront(com.cobbletowers.definition.RentalSetDefinition set) {
        List<String> types = set.types();
        String t0 = types.isEmpty() ? "normal" : types.get(0);
        String text = "\n" + glyph("sprite_" + set.species()) + "\n"
                + glyph("gem_pad") + glyph("gem3_" + t0) + "\n"
                + (types.size() > 1 ? glyph("gem_pad") + glyph("gem4_" + types.get(1)) : "");
        return Component.literal(text).withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF));
    }

    /** A card's back, four lines like a face. */
    public static Component cardBack() {
        return Component.literal(glyph("back") + "\n\n\n").withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF));
    }

    /** The stats back: a dark card under the tier's frame, four lines like every card text. */
    public static Component cardStats(String tier) {
        return Component.literal(glyph("stats_" + tier) + "\n\n\n").withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF));
    }

    /** How many lines the stats text always has (blank ones pad it), so its middle is always at the same height. */
    public static final int STATS_LINES = 17;

    private static final Map<String, Integer> TYPE_COLOUR = Map.ofEntries(Map.entry("normal", 0xC8C8A0), Map.entry("fire", 0xFF8C42),
            Map.entry("water", 0x6F9BFF), Map.entry("grass", 0x78CF55), Map.entry("electric", 0xFFD940), Map.entry("ice", 0x9BE0E8),
            Map.entry("fighting", 0xE0453F), Map.entry("poison", 0xC070C8), Map.entry("ground", 0xE8C870), Map.entry("flying", 0xB0A0FF),
            Map.entry("psychic", 0xFF5E8C), Map.entry("bug", 0xB8D030), Map.entry("rock", 0xC8B050), Map.entry("ghost", 0x8A70B0),
            Map.entry("dark", 0x8A7A70), Map.entry("dragon", 0x7A50FF), Map.entry("steel", 0xB8B8D0), Map.entry("fairy", 0xFFA0C0));
    private static final int[] STAT_COLOUR = {0xFF4D4D, 0xFF9A3D, 0xFFD42E, 0x4DA0FF, 0x4DD64D, 0xE8A0E0};
    private static final String[] STAT = {"HP", "Atk", "Def", "SpA", "SpD", "Spe"};
    /**
     * The natures in the games' order: the n-th raises NATURE_STAT[n / 5] and lowers NATURE_STAT[n % 5] (the same for both is neutral). The
     * games list natures by Atk, Def, Spe, SpA, SpD, not by the stat screen's order.
     */
    private static final int[] NATURE_STAT = {1, 2, 5, 3, 4};
    private static final List<String> NATURES = List.of("hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile", "relaxed", "impish",
            "lax", "timid", "hasty", "serious", "jolly", "naive", "modest", "mild", "quiet", "bashful", "rash", "calm", "gentle", "sassy",
            "careful", "quirky");
    private static final int WHITE = 0xFFFFFF, GREY = 0xAAAAAA, DIM = 0x5A5A66, GOLD = 0xFFC83D, UP = 0x55FF55, DOWN = 0xFF5555;

    /**
     * What the stats back says, as the draft screen's hover card does: name and level, types, role, ability, nature with what it raises and
     * lowers, held item, the EVs this card rolled in the stat colours (untrained ones dim), and the moves in their types' colours.
     */
    public static Component statsText(com.cobbletowers.definition.RentalSetDefinition set, List<Integer> evs) {
        net.minecraft.network.chat.MutableComponent out = Component.empty().withStyle(s -> s.withFont(TEXT_FONT));
        List<net.minecraft.network.chat.MutableComponent> lines = new ArrayList<>();
        lines.add(Component.literal(set.displayName()).withStyle(s -> s.withColor(WHITE).withBold(true))
                .append(Component.literal("  Lv." + set.level()).withStyle(s -> s.withColor(GREY).withBold(false))));
        net.minecraft.network.chat.MutableComponent types = Component.empty();
        for (int k = 0; k < set.types().size(); k++) {
            if (k > 0) types.append(Component.literal(" · ").withColor(DIM));
            String t = set.types().get(k);
            types.append(Component.literal(t.toUpperCase(java.util.Locale.ROOT)).withColor(TYPE_COLOUR.getOrDefault(t, WHITE)));
        }
        lines.add(types);
        lines.add(Component.literal(set.role().isEmpty() ? "" : Character.toUpperCase(set.role().charAt(0)) + set.role().substring(1)).withColor(GREY));
        lines.add(Component.empty());
        net.minecraft.network.chat.MutableComponent ability = com.cobbletowers.battle.cobblemon.CobblemonMoves.abilityName(set.ability());
        lines.add(Component.literal("Ability ").withColor(GREY).append((ability != null ? ability : Component.literal(words(set.ability()))).withColor(WHITE)));
        net.minecraft.network.chat.MutableComponent nature = Component.literal(words(set.nature())).withColor(WHITE);   // no label: it fits one line
        int n = NATURES.indexOf(set.nature().toLowerCase(java.util.Locale.ROOT));
        if (n >= 0 && n / 5 != n % 5) {
            nature.append(Component.literal("  +" + STAT[NATURE_STAT[n / 5]]).withColor(UP))
                    .append(Component.literal(" -" + STAT[NATURE_STAT[n % 5]]).withColor(DOWN));
        }
        lines.add(nature);
        lines.add(Component.literal("Held ").withColor(GREY)
                .append(Component.literal(set.item().map(i -> words(i.getPath())).orElse("Nothing")).withColor(set.item().isPresent() ? WHITE : DIM)));
        lines.add(Component.empty());
        int total = 0;
        for (int v : evs) total += v;
        lines.add(Component.literal("EVs ").withColor(GREY).append(Component.literal(total + "/510").withColor(GOLD)));
        for (int row = 0; row < 2; row++) {
            net.minecraft.network.chat.MutableComponent line = Component.empty();
            for (int k = row * 3; k < row * 3 + 3; k++) {
                int v = k < evs.size() ? evs.get(k) : 0;
                if (k > row * 3) line.append(Component.literal("  "));
                line.append(Component.literal(STAT[k] + " ").withColor(v > 0 ? STAT_COLOUR[k] : DIM))
                        .append(Component.literal(String.valueOf(v)).withColor(v > 0 ? WHITE : DIM));
            }
            lines.add(line);
        }
        lines.add(Component.empty());
        lines.add(Component.literal("Moves").withColor(GREY));
        for (String move : set.moves()) {
            String type = com.cobbletowers.battle.cobblemon.CobblemonMoves.resolve(move).type();
            lines.add(com.cobbletowers.battle.cobblemon.CobblemonMoves.name(move).withColor(TYPE_COLOUR.getOrDefault(type, WHITE)));
        }
        while (lines.size() < STATS_LINES) lines.add(Component.empty());
        for (int k = 0; k < lines.size(); k++) {
            if (k > 0) out.append("\n");
            out.append(lines.get(k));
        }
        return out;
    }

    private static String words(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    public static ItemStack fx(String name) {
        return paper(MODELS.get("fx/" + name));
    }

    private static ItemStack paper(PolymerModelData model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model.value()));
        return stack;
    }

    /**
     * Cobblemon's model item showing a species (cobblemon:pokemon_model with its cobblemon:pokemon_item component), decoded from data so this
     * mod needs no compile-time hook into Cobblemon's item classes. Empty if Cobblemon is not there or refuses it.
     */
    public static ItemStack pokemon(HolderLookup.Provider registries, String species) {
        JsonObject item = new JsonObject();
        item.addProperty("id", "cobblemon:pokemon_model");
        item.addProperty("count", 1);
        JsonObject comp = new JsonObject();
        JsonObject poke = new JsonObject();
        poke.addProperty("species", "cobblemon:" + species);
        poke.add("aspects", new JsonArray());
        comp.add("cobblemon:pokemon_item", poke);
        item.add("components", comp);
        return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), item).result().orElse(ItemStack.EMPTY);
    }
}
