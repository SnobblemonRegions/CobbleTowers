package com.cobbletowers.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

/**
 * The Records Hall trophy cases' item models (owner, 23 Nov 2026) and where each part sits ({@code data/cobbletowers/cobbletowers/hub/trophy.json}),
 * both written by {@code tools/tower_gen/trophy_export.py}. Positions are in blocks in a case's own frame: the origin is the alcove panel's
 * face at floor level, +z out into the hall.
 */
public final class TrophyModels {

    public static final String[] TOWERS = AltarModels.TOWERS;
    public static final List<String> FAMILIES = Trophies.FAMILIES;
    public static final List<String> TIERS = Trophies.TIERS;
    public static final List<String> MEDALS = Trophies.MEDALS;
    private static final Map<String, PolymerModelData> MODELS = new HashMap<>();

    /** Where the parts sit: the case's chunks per tower, each ladder trophy's foot, each medal's centre, how far over its foot a trophy's display stands. */
    public record Layout(Map<String, List<AltarModels.Chunk>> cases, Map<String, Vec3> ladders, Map<String, Vec3> medals, double footUp,
                         Map<String, Set<String>> glow) {}

    private static final Layout LAYOUT = readLayout();

    static {
        for (List<AltarModels.Chunk> chunks : LAYOUT.cases().values()) {
            for (AltarModels.Chunk c : chunks) MODELS.put(c.model(), request("trophy/" + c.model()));
        }
        for (String tower : TOWERS) {
            for (String family : FAMILIES) {
                for (String tier : TIERS) {
                    String key = tower + "/" + family + "_" + tier;
                    MODELS.put(key, request("trophy/" + key));
                    if (LAYOUT.glow().getOrDefault(tower, Set.of()).contains(family + "_" + tier)) {
                        MODELS.put(key + "_glow", request("trophy/" + key + "_glow"));
                    }
                }
            }
        }
        for (String part : new String[] {"medal_a", "medal_b", "medal_dark", "pool"}) MODELS.put(part, request("trophy/common/" + part));
    }

    private TrophyModels() {}

    /** Forces the class to load, so every model is requested before Polymer builds the pack. */
    public static void bootstrap() {}

    private static PolymerModelData request(String path) {
        return PolymerResourcePackUtils.requestModel(Items.PAPER, ResourceLocation.fromNamespaceAndPath("cobbletowers", path));
    }

    public static Layout layout() {
        return LAYOUT;
    }

    /** A case chunk's model (keyed "<tower>/<chunk>"). */
    public static ItemStack chunk(String key) {
        return paper(MODELS.get(key));
    }

    /** A ladder trophy in a tower's case; a tower without its own models uses Neutral's. */
    public static ItemStack trophy(String tower, String family, String tier) {
        PolymerModelData m = MODELS.get(tower + "/" + family + "_" + tier);
        return paper(m != null ? m : MODELS.get("neutral/" + family + "_" + tier));
    }

    /** The full-bright part of a ladder trophy (the ascended tip or gem), or empty when it has none. */
    public static ItemStack trophyGlow(String tower, String family, String tier) {
        PolymerModelData m = MODELS.get(tower + "/" + family + "_" + tier + "_glow");
        if (m == null) m = MODELS.get("neutral/" + family + "_" + tier + "_glow");
        return m == null ? ItemStack.EMPTY : paper(m);
    }

    /** A shared part: medal_a, medal_b, medal_dark, pool. */
    public static ItemStack common(String part) {
        return paper(MODELS.get(part));
    }

    private static ItemStack paper(PolymerModelData model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model.value()));
        return stack;
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static Map<String, Vec3> points(JsonObject o) {
        Map<String, Vec3> out = new LinkedHashMap<>();
        for (var e : o.entrySet()) out.put(e.getKey(), vec(e.getValue().getAsJsonArray()));
        return Map.copyOf(out);
    }

    private static Layout readLayout() {
        try (InputStream in = TrophyModels.class.getResourceAsStream("/data/cobbletowers/cobbletowers/hub/trophy.json")) {
            if (in == null) throw new IllegalStateException("trophy.json is missing from the jar");
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, List<AltarModels.Chunk>> cases = new HashMap<>();
            Map<String, Set<String>> glow = new HashMap<>();
            for (String tower : TOWERS) {
                List<AltarModels.Chunk> chunks = new ArrayList<>();
                for (JsonElement e : o.getAsJsonObject("case").getAsJsonArray(tower)) {
                    JsonArray a = e.getAsJsonArray();
                    chunks.add(new AltarModels.Chunk(tower + "/" + a.get(0).getAsString(),
                            new Vec3(a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()), a.get(4).getAsBoolean()));
                }
                cases.put(tower, List.copyOf(chunks));
                Set<String> g = new HashSet<>();
                for (JsonElement e : o.getAsJsonObject("glow").getAsJsonArray(tower)) g.add(e.getAsString());
                glow.put(tower, Set.copyOf(g));
            }
            return new Layout(Map.copyOf(cases), points(o.getAsJsonObject("ladders")), points(o.getAsJsonObject("medals")),
                    o.get("foot_up").getAsDouble(), Map.copyOf(glow));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read trophy.json", ex);
        }
    }
}
