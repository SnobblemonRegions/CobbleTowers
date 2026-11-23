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
public record HubLayout(ResourceLocation structure, int sizeX, int sizeY, int sizeZ, Spot pad, List<Portal> portals, Ring ring,
                        List<Shrine> shrines, java.util.Optional<Records> records) {

    /** A point in the structure's coordinates (blocks, centred where the generator says). */
    public record Point(double x, double y, double z) {
        static Point of(com.google.gson.JsonArray a) {
            return new Point(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
    }

    /** One tower's mastery alcove in the Records Hall: where its trophy case stands (the panel's face, middle, floor level), its plate, and
     *  the yaw the case faces (out into the hall). */
    public record Shelf(ResourceLocation tower, Point at, Point plate, float facing) {}

    /**
     * The Records Hall (owner, 20 Nov 2026): its middle and radius, the leaderboard wall's three columns (their heading points) and
     * the yaw the wall faces, the three statue plinths' tops (first, second, third), the season track's tier marks in the floor ring (their
     * top face) in walking order, each tower's shelf, the door's rank plate, and the Season Chest's foot and the yaw its front faces.
     */
    public record Records(Point centre, double radius, List<Point> board, float boardFacing, List<Point> statues, List<Point> track,
                          List<Shelf> shelves, Point rank, Point chest, float chestFacing) {
        /** Whether a position (structure coordinates) is inside the hall. */
        public boolean inside(double px, double py, double pz) {
            double dx = px - centre.x(), dz = pz - centre.z();
            return dx * dx + dz * dz <= radius * radius && py >= centre.y() - 2 && py <= centre.y() + 16;
        }
    }

    /**
     * A trial shrine in the Trials Pavilion (owner, 20 Nov 2026): its portal (a gate-like box; its tower id is {@code trial_daily} or
     * {@code trial_weekly}, not a real tower), its ready ring, and where its plate hangs.
     */
    public record Shrine(String kind, Portal portal, double readyX, double readyZ, double readyR, Cell plate) {
        /** Whether a position (structure coordinates, blocks) is on the shrine's ready ring. */
        public boolean ready(double px, double py, double pz) {
            double dx = px - readyX, dz = pz - readyZ;
            return dx * dx + dz * dz <= readyR * readyR && Math.abs(py - portal.minY()) < 2.5;
        }

        public boolean daily() {
            return kind.equals("daily");
        }
    }

    /** A block position in the structure's coordinates. */
    public record Cell(int x, int y, int z) {}

    /**
     * The party ring on the warp pad's rim (owner, 20 Nov 2026): its cells and which of the four arcs each belongs to; an arc lights per
     * player standing on the pad. {@code stoneLit}/{@code stoneUnlit} are the party's lit and unlit blocks.
     */
    public record Ring(int y, double r, String lit, String unlit, String stoneLit, String stoneUnlit, List<int[]> cells) {
        static final Ring NONE = new Ring(0, 0, "", "", "", "", List.of());

        public boolean present() {
            return !cells.isEmpty();
        }
    }

    /**
     * A gate's Gate Altar (owner, 20 Nov 2026): the floor cell at its centre, the way its front faces (the model's +z turned by {@code yaw}
     * degrees about y: toward the plaza), and the radius of its ready ring, in blocks.
     */
    public record Altar(int x, int y, int z, float yaw, double ready) {
        /** Whether a block (structure coordinates) is under the altar: its 3 x 3 footprint, two high (its invisible collision). */
        public boolean holds(int bx, int by, int bz) {
            return Math.abs(bx - x) <= 1 && Math.abs(bz - z) <= 1 && by >= y && by <= y + 1;
        }
    }

    /**
     * A gate's party fixtures: the portal's mouth (a party standing there is at its gate) and the Gate Altar, drawn by the mod
     * ({@link GateAltar}).
     */
    public record Gate(Cell gatherMin, Cell gatherMax, Altar altar) {
        /** Whether a block is in the portal's mouth. */
        public boolean mouth(int x, int y, int z) {
            return x >= gatherMin.x() && x <= gatherMax.x() && y >= gatherMin.y() && y <= gatherMax.y() && z >= gatherMin.z() && z <= gatherMax.z();
        }

        /** Whether a position (structure coordinates, blocks) is on the altar's ready ring: within its radius, beside the altar. */
        public boolean ready(double px, double py, double pz) {
            double dx = px - (altar.x() + 0.5), dz = pz - (altar.z() + 0.5);
            return dx * dx + dz * dz <= altar.ready() * altar.ready() && Math.abs(py - altar.y()) < 2.5;
        }
    }

    static final String RESOURCE = "/data/cobbletowers/cobbletowers/hub/tower_hub.json";

    /** A place to stand, facing {@code yaw} (0 south, 90 west, 180 north, 270 east). */
    public record Spot(int x, int y, int z, float yaw) {
        static Spot of(JsonObject o) {
            return new Spot(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("yaw").getAsFloat());
        }
    }

    /** One tower's portal: the box a player walks into, the spot outside it, the host's dais, the veil's colour (0xRRGGBB). */
    public record Portal(ResourceLocation tower, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Spot back, Spot host, int color,
                         java.util.Optional<Gate> gate) {

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
                    Spot.of(p.getAsJsonObject("back")), Spot.of(p.getAsJsonObject("host")), p.get("color").getAsInt(), gateOf(p)));
        }
        return new HubLayout(ResourceLocation.parse(root.get("structure").getAsString()),
                sizeArray.get(0).getAsInt(), sizeArray.get(1).getAsInt(), sizeArray.get(2).getAsInt(),
                Spot.of(root.getAsJsonObject("pad")), List.copyOf(portals), ringOf(root), shrinesOf(root), recordsOf(root));
    }

    private static List<Shrine> shrinesOf(JsonObject root) {
        if (!root.has("shrines")) return List.of();
        List<Shrine> out = new ArrayList<>();
        for (var e : root.getAsJsonArray("shrines")) {
            JsonObject s = e.getAsJsonObject();
            var min = s.getAsJsonArray("min");
            var max = s.getAsJsonArray("max");
            Spot back = Spot.of(s.getAsJsonObject("back"));
            String kind = s.get("kind").getAsString();
            Portal portal = new Portal(ResourceLocation.fromNamespaceAndPath("cobbletowers", "trial_" + kind),
                    min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt(), max.get(0).getAsInt(), max.get(1).getAsInt(),
                    max.get(2).getAsInt(), back, back, s.get("color").getAsInt(), java.util.Optional.empty());
            JsonObject r = s.getAsJsonObject("ready");
            out.add(new Shrine(kind, portal, r.get("x").getAsDouble(), r.get("z").getAsDouble(), r.get("r").getAsDouble(),
                    cell(s.getAsJsonArray("plate"))));
        }
        return List.copyOf(out);
    }

    private static java.util.Optional<Records> recordsOf(JsonObject root) {
        if (!root.has("records")) return java.util.Optional.empty();
        JsonObject r = root.getAsJsonObject("records");
        List<Shelf> shelves = new ArrayList<>();
        for (var e : r.getAsJsonObject("shelves").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            shelves.add(new Shelf(ResourceLocation.fromNamespaceAndPath("cobbletowers", e.getKey()), Point.of(s.getAsJsonArray("case")),
                    Point.of(s.getAsJsonArray("plate")), s.get("facing").getAsFloat()));
        }
        return java.util.Optional.of(new Records(Point.of(r.getAsJsonArray("centre")), r.get("radius").getAsDouble(),
                points(r.getAsJsonArray("board")), r.get("board_facing").getAsFloat(), points(r.getAsJsonArray("statues")),
                points(r.getAsJsonArray("track")), List.copyOf(shelves), Point.of(r.getAsJsonArray("rank")),
                Point.of(r.getAsJsonArray("chest")), r.get("chest_facing").getAsFloat()));
    }

    private static List<Point> points(com.google.gson.JsonArray a) {
        List<Point> out = new ArrayList<>();
        for (var e : a) out.add(Point.of(e.getAsJsonArray()));
        return List.copyOf(out);
    }

    private static Cell cell(com.google.gson.JsonArray a) {
        return new Cell(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    /** A layout written before the party system (20 Nov 2026) has no gate fixtures; the portals still work without them. */
    private static java.util.Optional<Gate> gateOf(JsonObject p) {
        if (!p.has("gather") || !p.has("altar")) return java.util.Optional.empty();
        JsonObject gather = p.getAsJsonObject("gather");
        JsonObject a = p.getAsJsonObject("altar");
        Altar altar = new Altar(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt(), a.get("yaw").getAsFloat(), a.get("ready").getAsDouble());
        return java.util.Optional.of(new Gate(cell(gather.getAsJsonArray("min")), cell(gather.getAsJsonArray("max")), altar));
    }

    private static Ring ringOf(JsonObject root) {
        if (!root.has("ring")) return Ring.NONE;
        JsonObject r = root.getAsJsonObject("ring");
        List<int[]> cells = new ArrayList<>();
        for (var c : r.getAsJsonArray("cells")) {
            var a = c.getAsJsonArray();
            cells.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        JsonObject stone = r.getAsJsonObject("stone");
        return new Ring(r.get("y").getAsInt(), r.get("r").getAsDouble(), r.get("lit").getAsString(), r.get("unlit").getAsString(),
                stone.get("lit").getAsString(), stone.get("unlit").getAsString(), List.copyOf(cells));
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
