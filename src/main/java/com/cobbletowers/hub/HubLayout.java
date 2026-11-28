package com.cobbletowers.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Where things are in the Tower Hub structure (owner, 19 Nov 2026), in the structure's own coordinates: each tower's portal (its
 * opening and the step behind it), the spot in front of it a walker is set back on, the host's dais, the veil's colour, and the
 * warp pad. Written by {@code tools/tower_gen/hub.py} beside the structure it describes, so the two never disagree.
 */
public record HubLayout(ResourceLocation structure, int sizeX, int sizeY, int sizeZ, Spot pad, List<Portal> portals) {

    static final String RESOURCE = "/data/cobbletowers/cobbletowers/hub/tower_hub.json";

    /** A place to stand, facing {@code yaw} (0 south, 90 west, 180 north, 270 east). */
    public record Spot(int x, int y, int z, float yaw) {
        static Spot of(JsonObject o) {
            return new Spot(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("yaw").getAsFloat());
        }
    }

    /** One tower's portal: the box a player walks into, the spot outside it, the host's dais, the veil's colour (0xRRGGBB). */
    public record Portal(ResourceLocation tower, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Spot back, Spot host, int color) {

        /** Whether a block position, in the structure's coordinates, is inside the portal. */
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** True when the opening is wide along x (the portal faces north or south), false when it is wide along z. */
        public boolean wideAlongX() {
            return Math.abs(back.x() - (minX + maxX) / 2.0) < Math.abs(back.z() - (minZ + maxZ) / 2.0);
        }
    }

    public static HubLayout parse(JsonObject root) {
        var sizeArray = root.getAsJsonArray("size");
        List<Portal> portals = new ArrayList<>();
        for (var element : root.getAsJsonArray("portals")) {
            JsonObject p = element.getAsJsonObject();
            var min = p.getAsJsonArray("min");
            var max = p.getAsJsonArray("max");
            portals.add(new Portal(ResourceLocation.parse(p.get("tower").getAsString()),
                    min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt(),
                    max.get(0).getAsInt(), max.get(1).getAsInt(), max.get(2).getAsInt(),
                    Spot.of(p.getAsJsonObject("back")), Spot.of(p.getAsJsonObject("host")), p.get("color").getAsInt()));
        }
        return new HubLayout(ResourceLocation.parse(root.get("structure").getAsString()),
                sizeArray.get(0).getAsInt(), sizeArray.get(1).getAsInt(), sizeArray.get(2).getAsInt(),
                Spot.of(root.getAsJsonObject("pad")), List.copyOf(portals));
    }

    private static volatile HubLayout loaded;

    /** The layout shipped in the jar, read once. */
    public static HubLayout load() {
        HubLayout found = loaded;
        if (found != null) return found;
        try (InputStream in = HubLayout.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("The Tower Hub layout " + RESOURCE + " is missing from the jar");
            found = parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the Tower Hub layout", ex);
        }
        loaded = found;
        return found;
    }
}
