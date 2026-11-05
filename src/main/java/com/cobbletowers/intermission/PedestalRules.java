package com.cobbletowers.intermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The pure half of the modifier pedestals (P55): tags, where each stands, and which one a player's gaze falls on. */
public final class PedestalRules {

    public static final String TAG = "cobbletowers_pedestal";
    public static final String PART_STAND = "cobbletowers_pedestal_stand";
    public static final String PART_TEXT = "cobbletowers_pedestal_text";
    public static final String PART_HITBOX = "cobbletowers_pedestal_hitbox";
    public static final String PART_GEM = "cobbletowers_pedestal_gem";

    private static final String RUN_PREFIX = "cobbletowers_pedestal_run:";
    private static final String CARD_PREFIX = "cobbletowers_pedestal_card_";
    private static final String LINE_PREFIX = "cobbletowers_pedestal_line_";

    /** How far from the vendor the row stands, and the gap between pedestal centres. */
    static final double ROW_DISTANCE = 4.0;
    static final double SPACING = 3.6;

    /** The click and gaze box: wide enough to catch the hologram, short of the next pedestal. */
    public static final double HITBOX_WIDTH = 2.4;
    public static final double HITBOX_HEIGHT = 3.8;
    public static final double GAZE_RANGE = 6.0;

    /** The three stacked text pieces of one card, top to bottom: the name, the big payout, the detail block. */
    public static final int LINE_NAME = 0;
    public static final int LINE_BIG = 1;
    public static final int LINE_BODY = 2;
    public static final int BODY_LINES = 5;

    /** Text scales and metrics, in blocks. A text row is 10 px at scale 1 and a block is 40 px; calibrated live. */
    public static final double NAME_SCALE = 0.8;
    public static final double BIG_SCALE = 1.6;
    public static final double BODY_SCALE = 0.68;
    static final double LINE_HEIGHT = 0.25;
    static final double TEXT_PAD = 0.05;
    static final double TEXT_GAP = 0.03;
    /** Where the bottom of the lowest text sits above the floor: over the stand and the gem. */
    public static final double TEXT_BASE = 2.05;
    public static final double GEM_HEIGHT = 1.55;
    public static final double GEM_SIZE = 0.42;

    private PedestalRules() {}

    /** Floor-level centre of one pedestal. */
    public record Slot(double x, double y, double z) {}

    /** How far each of a door's two event cards stands from the door's own pedestal, sideways. */
    public static final double BESIDE_DOOR = 1.6;

    /**
     * Where an event round's cards stand: beside the door the party chose, so nobody walks to the far end of the hall and back (TODO #5). Card 0 (the
     * accept) stands on the player's right and card 1 (the refusal) on their left, facing the door, so the sides are the same in every room. One card
     * stands on the door's own pedestal. Any other count is left to the usual layout.
     * @param door the door's pedestal
     * @param facingX the direction the player faces, toward the door (east is +x), any length
     * @param facingZ as above (south is +z); a direction of no length counts as south
     */
    public static Optional<List<Slot>> beside(Slot door, double facingX, double facingZ, int count) {
        if (count == 1) return Optional.of(List.of(door));
        if (count != 2) return Optional.empty();
        double length = Math.hypot(facingX, facingZ);
        double fx = length < 1e-6 ? 0 : facingX / length;
        double fz = length < 1e-6 ? 1 : facingZ / length;
        double rightX = -fz;
        double rightZ = fx;
        return Optional.of(List.of(
                new Slot(door.x() + rightX * BESIDE_DOOR, door.y(), door.z() + rightZ * BESIDE_DOOR),
                new Slot(door.x() - rightX * BESIDE_DOOR, door.y(), door.z() - rightZ * BESIDE_DOOR)));
    }

    public static String runTag(UUID runId) {
        return RUN_PREFIX + runId;
    }

    public static String cardTag(int index) {
        return CARD_PREFIX + index;
    }

    public static Optional<UUID> runOf(String tag) {
        if (!tag.startsWith(RUN_PREFIX)) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(tag.substring(RUN_PREFIX.length())));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static String lineTag(int line) {
        return LINE_PREFIX + line;
    }

    public static Optional<Integer> lineOf(String tag) {
        if (!tag.startsWith(LINE_PREFIX)) return Optional.empty();
        try {
            int line = Integer.parseInt(tag.substring(LINE_PREFIX.length()));
            return line < 0 ? Optional.empty() : Optional.of(line);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** Height of {@code lines} rows of text at {@code scale}, in blocks. */
    public static double textHeight(int lines, double scale) {
        return (lines * LINE_HEIGHT + TEXT_PAD) * scale;
    }

    /** Heights above the floor at which the bottom of each text piece sits (text grows upward from its anchor). */
    public record Stack(double bodyY, double bigY, double nameY, double top) {}

    public static Stack stack(int bodyLines) {
        double body = TEXT_BASE;
        double big = body + textHeight(bodyLines, BODY_SCALE) + TEXT_GAP;
        double name = big + textHeight(1, BIG_SCALE) + TEXT_GAP;
        return new Stack(body, big, name, name + textHeight(1, NAME_SCALE));
    }

    /**
     * A point on a circle of {@code radius} round (cx, cy, cz) at {@code angle}, in a plane tilted {@code tilt} radians
     * about the X axis: the orbit of a rune round a gem.
     */
    public static double[] orbit(double cx, double cy, double cz, double radius, double angle, double tilt) {
        double flat = Math.cos(angle) * radius;
        double depth = Math.sin(angle) * radius;
        return new double[] {cx + flat, cy + depth * Math.sin(tilt), cz + depth * Math.cos(tilt)};
    }

    /** A point on a ring of {@code radius} on the floor round (cx, cz), for a shockwave. */
    public static double[] ring(double cx, double cz, double radius, double angle) {
        return new double[] {cx + Math.cos(angle) * radius, cz + Math.sin(angle) * radius};
    }

    /** The gem's orientation after {@code spin} radians: tilted 45 degrees on Z (a diamond), then turned about Y. x, y, z, w. */
    public static double[] gemRotation(double spin) {
        double[] tilt = {0, 0, Math.sin(Math.PI / 8), Math.cos(Math.PI / 8)};
        double[] turn = {0, Math.sin(spin / 2), 0, Math.cos(spin / 2)};
        return multiply(turn, tilt);
    }

    /** Offset that makes a cube of {@code size} turn about its centre rather than its corner, for a display's translation. */
    public static double[] centreOffset(double[] q, double size) {
        double[] v = rotate(q, size / 2, size / 2, size / 2);
        return new double[] {-v[0], -v[1], -v[2]};
    }

    static double[] multiply(double[] a, double[] b) {
        return new double[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    static double[] rotate(double[] q, double x, double y, double z) {
        double[] conj = {-q[0], -q[1], -q[2], q[3]};
        double[] r = multiply(multiply(q, new double[] {x, y, z, 0}), conj);
        return new double[] {r[0], r[1], r[2]};
    }

    public static Optional<Integer> cardOf(String tag) {
        if (!tag.startsWith(CARD_PREFIX)) return Optional.empty();
        try {
            int index = Integer.parseInt(tag.substring(CARD_PREFIX.length()));
            return index < 0 ? Optional.empty() : Optional.of(index);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * {@code count} pedestals in a row {@link #ROW_DISTANCE} from the vendor toward the point it looks at, spread
     * across that line and centred on it. Order runs left to right as seen from the vendor.
     */
    public static List<Slot> layout(double vendorX, double vendorZ, double towardX, double towardZ, double floorY, int count) {
        double dx = towardX - vendorX;
        double dz = towardZ - vendorZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-6) {
            dx = -1;
            dz = 0;
            length = 1;
        }
        dx /= length;
        dz /= length;
        double px = dz;
        double pz = -dx;
        List<Slot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double offset = (i - (count - 1) / 2.0) * SPACING;
            slots.add(new Slot(vendorX + dx * ROW_DISTANCE + px * offset, floorY, vendorZ + dz * ROW_DISTANCE + pz * offset));
        }
        return slots;
    }

    /**
     * Pedestals in front of the doors of a walkable tower. A route draft puts card {@code i} in front of door {@code lanes[i]}; any other draft
     * (a relic, an event room) spreads its cards over the doors, the middle one for a single card and the two outer ones for a pair.
     * @param lanes the lane each card rides on, empty for a draft with no lanes
     * @param doors the places in front of the doors, left to right
     * @return empty when the doors cannot take that many cards
     */
    public static Optional<List<Slot>> atDoors(List<Integer> lanes, List<Slot> doors, int count) {
        if (count < 1 || count > doors.size()) return Optional.empty();
        List<Slot> slots = new ArrayList<>(count);
        if (!lanes.isEmpty()) {
            if (lanes.size() != count) return Optional.empty();
            for (int lane : lanes) {
                if (lane < 0 || lane >= doors.size() || slots.contains(doors.get(lane))) return Optional.empty();
                slots.add(doors.get(lane));
            }
            return Optional.of(slots);
        }
        int[] pick = count == 1 ? new int[] {doors.size() / 2} : count == 2 ? new int[] {0, doors.size() - 1} : null;
        for (int i = 0; i < count; i++) slots.add(doors.get(pick == null ? i : pick[i]));
        return Optional.of(slots);
    }

    /**
     * The pedestal the ray from {@code eye} along {@code look} meets first inside {@link #GAZE_RANGE}, or empty. Slab
     * test against each pedestal's upright box.
     */
    public static Optional<Integer> gazedAt(double eyeX, double eyeY, double eyeZ, double lookX, double lookY, double lookZ,
                                            List<Slot> slots) {
        int best = -1;
        double bestDistance = GAZE_RANGE;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            double half = HITBOX_WIDTH / 2;
            double tMin = 0;
            double tMax = bestDistance;
            double[] origin = {eyeX, eyeY, eyeZ};
            double[] dir = {lookX, lookY, lookZ};
            double[] low = {s.x() - half, s.y(), s.z() - half};
            double[] high = {s.x() + half, s.y() + HITBOX_HEIGHT, s.z() + half};
            boolean hit = true;
            for (int axis = 0; axis < 3 && hit; axis++) {
                if (Math.abs(dir[axis]) < 1.0e-9) {
                    hit = origin[axis] >= low[axis] && origin[axis] <= high[axis];
                    continue;
                }
                double t1 = (low[axis] - origin[axis]) / dir[axis];
                double t2 = (high[axis] - origin[axis]) / dir[axis];
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
                hit = tMin <= tMax;
            }
            if (hit && tMin < bestDistance) {
                best = i;
                bestDistance = tMin;
            }
        }
        return best < 0 ? Optional.empty() : Optional.of(best);
    }
}
