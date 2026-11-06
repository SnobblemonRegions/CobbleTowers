package com.cobbletowers.ascension;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * What each Threat band is, as the lobby's picker shows it: who the enemies are, what they spend, what a Silver completion pays. AscensionLib is the source of
 * truth ({@code AscensionEncounters.threatBands()}, which reads the enemy tiers in force and the payout's own methods); this only reads its answer, so a screen
 * cannot drift from the game, and reads it defensively: a library that is missing, older, or answers nonsense gives no bands, and the picker then shows none of the
 * detail. Pure.
 */
public final class ThreatBandInfo {

    /** One rarity in a band's enemy mix, as a whole percent. */
    public record Mix(String rarity, int percent) {}

    /**
     * One band the door offers.
     * @param credits the rank credits an ordinary enemy spends, {@code bossCredits} a boss
     * @param unique every enemy carries a Unique; {@code bossUnique} only the boss
     * @param dust what a Silver completion pays in dust, {@code facets}, a {@code corePercent} chance of a Core and the expected {@code apex} Shards
     */
    public record Band(int band, List<Mix> mix, int credits, int bossCredits, boolean unique, boolean bossUnique, int dust, int facets, int corePercent, double apex) {

        public static final StreamCodec<FriendlyByteBuf, Band> STREAM_CODEC = StreamCodec.of(Band::write, Band::read);

        private static void write(FriendlyByteBuf buf, Band b) {
            buf.writeVarInt(b.band());
            buf.writeVarInt(b.mix().size());
            for (Mix m : b.mix()) {
                buf.writeUtf(m.rarity(), 32);
                buf.writeVarInt(m.percent());
            }
            buf.writeVarInt(b.credits());
            buf.writeVarInt(b.bossCredits());
            buf.writeBoolean(b.unique());
            buf.writeBoolean(b.bossUnique());
            buf.writeVarInt(b.dust());
            buf.writeVarInt(b.facets());
            buf.writeVarInt(b.corePercent());
            buf.writeDouble(b.apex());
        }

        private static Band read(FriendlyByteBuf buf) {
            int band = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), MAX_MIX);
            List<Mix> mix = new ArrayList<>(n);
            for (int i = 0; i < n; i++) mix.add(new Mix(buf.readUtf(32), buf.readVarInt()));
            return new Band(band, List.copyOf(mix), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble());
        }
    }

    /** The most bands and the most rarities in a band that are read: the picker has seven rows, and no tier mixes more than six rarities. */
    public static final int MAX_BANDS = 7, MAX_MIX = 8;

    private ThreatBandInfo() {}

    /**
     * The bands in AscensionLib's answer, lowest first. Anything that is not the documented shape (bad JSON, a band outside 1-7, percents that do not add to
     * 100, a negative number) is skipped, never thrown on, so the lobby always opens.
     */
    public static List<Band> parse(String json) {
        List<Band> out = new ArrayList<>();
        if (json == null || json.isBlank()) return List.of();
        JsonArray array;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return List.of();
            array = root.getAsJsonArray();
        } catch (RuntimeException bad) {
            return List.of();
        }
        for (JsonElement e : array) {
            if (out.size() >= MAX_BANDS) break;
            try {
                Band band = one(e.getAsJsonObject());
                if (band != null) out.add(band);
            } catch (RuntimeException skipped) {
                // a band that is not the documented shape is left out
            }
        }
        out.sort(java.util.Comparator.comparingInt(Band::band));
        return List.copyOf(out);
    }

    private static Band one(JsonObject o) {
        int band = o.get("band").getAsInt();
        if (band < 1 || band > MAX_BANDS) return null;
        List<Mix> mix = new ArrayList<>();
        int sum = 0;
        for (JsonElement m : o.getAsJsonArray("mix")) {
            if (mix.size() >= MAX_MIX) return null;
            JsonObject mo = m.getAsJsonObject();
            String rarity = mo.get("rarity").getAsString();
            int percent = mo.get("percent").getAsInt();
            if (rarity.isBlank() || rarity.length() > 32 || percent < 0 || percent > 100) return null;
            mix.add(new Mix(rarity, percent));
            sum += percent;
        }
        if (mix.isEmpty() || sum != 100) return null;
        int credits = o.get("credits").getAsInt(), bossCredits = o.get("bossCredits").getAsInt();
        int dust = o.get("dust").getAsInt(), facets = o.get("facets").getAsInt(), core = o.get("corePercent").getAsInt();
        double apex = o.get("apex").getAsDouble();
        if (credits < 0 || bossCredits < 0 || dust < 0 || facets < 0 || core < 0 || core > 100 || apex < 0 || Double.isNaN(apex)) return null;
        return new Band(band, List.copyOf(mix), credits, bossCredits, o.get("unique").getAsBoolean(), o.get("bossUnique").getAsBoolean(), dust, facets, core, apex);
    }

    private static String lastJson = "";
    private static List<Band> lastBands = List.of();

    /** {@link #parse} with the last answer remembered: the lobby asks on every change, and the answer almost never changes. */
    public static synchronized List<Band> cached(String json) {
        if (json == null) return List.of();
        if (!json.equals(lastJson)) {
            lastBands = parse(json);
            lastJson = json;
        }
        return lastBands;
    }
}
