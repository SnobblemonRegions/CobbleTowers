package com.cobbletowers.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

/**
 * The Gate Altar's item models (owner, 20 Nov 2026), allocated through Polymer on paper like the gate doors, and where each moving part sits
 * ({@code data/cobbletowers/cobbletowers/hub/altar.json}). Both are written by {@code tools/tower_gen/altar_export.py} from the same model
 * data the concept renders came from. Positions are in blocks from the altar's floor centre, front = +z.
 */
public final class AltarModels {

    public static final String[] TOWERS = {"neutral", "tideforge", "duskvale", "rootvale"};
    private static final String[] OWN = {"body", "body_glow", "focus", "focus_glow"};
    private static final String[] COMMON = {"rune_off", "rune_0", "rune_1", "rune_2", "rune_3", "rune_4", "rune_5", "rune_6", "ready_off", "ready_on",
            "crystal_first", "crystal_best", "mote_first", "mote_best", "tile_lit"};
    private static final Map<String, PolymerModelData> MODELS = new HashMap<>();

    /** Where the parts sit, read from altar.json. */
    public record Layout(Map<String, Vec3> focus, Vec3 first, Vec3 best, List<Vec3> runes, Vec3 ready, double bodyUp,
                         Map<String, List<Chunk>> boards, Tiles tiles) {}

    /** One piece of a mode board: its model, where its middle sits (blocks, the altar's frame), whether it glows. */
    public record Chunk(String model, Vec3 at, boolean glow) {}

    /** The mode tiles: their modes in order, their face's x, how far the chosen one stands proud, their height, their z along the board. */
    public record Tiles(List<String> modes, double x, double proud, double y, List<Double> z) {}

    private static final Layout LAYOUT;

    static {
        for (String tower : TOWERS) {
            for (String part : OWN) MODELS.put(tower + "/" + part, request("altar/" + tower + "/" + part));
        }
        for (String part : COMMON) MODELS.put(part, request("altar/common/" + part));
        LAYOUT = readLayout();
        for (List<Chunk> chunks : LAYOUT.boards().values()) {
            for (Chunk c : chunks) MODELS.computeIfAbsent(c.model(), k -> request("altar/" + k));
        }
        for (String mode : LAYOUT.tiles().modes()) MODELS.put("tile_" + mode, request("altar/common/tile_" + mode));
    }

    private AltarModels() {}

    /** Forces the class to load, so every model is requested before Polymer builds the pack. */
    public static void bootstrap() {}

    private static PolymerModelData request(String path) {
        return PolymerResourcePackUtils.requestModel(Items.PAPER, ResourceLocation.fromNamespaceAndPath("cobbletowers", path));
    }

    /** A tower's own part (body, body_glow, focus, focus_glow); a tower without an altar uses Neutral's. */
    public static ItemStack own(String tower, String part) {
        PolymerModelData m = MODELS.get(tower + "/" + part);
        return paper(m != null ? m : MODELS.get("neutral/" + part));
    }

    /** A shared part (rune_off, rune_0..6, ready_off/on, crystal_first/best, mote_first/best). */
    public static ItemStack common(String part) {
        return paper(MODELS.get(part));
    }

    public static Layout layout() {
        return LAYOUT;
    }

    /** A board chunk's model (keyed "<tower>/<chunk>"). */
    public static ItemStack chunk(String key) {
        return paper(MODELS.get(key));
    }

    private static ItemStack paper(PolymerModelData model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model.value()));
        return stack;
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static Layout readLayout() {
        try (InputStream in = AltarModels.class.getResourceAsStream("/data/cobbletowers/cobbletowers/hub/altar.json")) {
            if (in == null) throw new IllegalStateException("altar.json is missing from the jar");
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Vec3> focus = new HashMap<>();
            for (String tower : TOWERS) focus.put(tower, vec(o.getAsJsonObject(tower).getAsJsonArray("focus")));
            List<Vec3> runes = new ArrayList<>();
            for (var r : o.getAsJsonArray("runes")) runes.add(vec(r.getAsJsonArray()));
            Map<String, List<Chunk>> boards = new HashMap<>();
            for (String tower : TOWERS) {
                List<Chunk> chunks = new ArrayList<>();
                for (String side : new String[] {"board_left", "board_right"}) {
                    for (var e : o.getAsJsonObject(tower).getAsJsonArray(side)) {
                        JsonArray a = e.getAsJsonArray();
                        chunks.add(new Chunk(tower + "/" + a.get(0).getAsString(),
                                new Vec3(a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()), a.get(4).getAsBoolean()));
                    }
                }
                boards.put(tower, List.copyOf(chunks));
            }
            JsonObject t = o.getAsJsonObject("tiles");
            List<String> modes = new ArrayList<>();
            for (var m : t.getAsJsonArray("modes")) modes.add(m.getAsString());
            List<Double> zs = new ArrayList<>();
            for (var z : t.getAsJsonArray("z")) zs.add(z.getAsDouble());
            Tiles tiles = new Tiles(List.copyOf(modes), t.get("x").getAsDouble(), t.get("proud").getAsDouble(), t.get("y").getAsDouble(), List.copyOf(zs));
            return new Layout(focus, vec(o.getAsJsonArray("crystal_first")), vec(o.getAsJsonArray("crystal_best")), List.copyOf(runes),
                    vec(o.getAsJsonArray("ready")), o.get("body_up").getAsDouble(), Map.copyOf(boards), tiles);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read altar.json", ex);
        }
    }
}
