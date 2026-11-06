package com.cobbletowers.route;

import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorLayout;
import java.util.Optional;

/**
 * The pure half of walking between floors (P55): when a floor is left on foot, how close counts as having arrived, and who is stranded
 * in the gallery. No Minecraft types, so every rule is a plain test.
 */
public final class WalkRules {

    /** How far from the next floor's entry anchor, across the floor, a player has arrived. The anchor is just inside the arena door. */
    public static final double ARRIVE_RADIUS = 7.0;
    /** How far above or below the anchor a player may be and still be on that floor. A floor is ten blocks high. */
    public static final double ARRIVE_HEIGHT = 3.0;
    /** The longest the team gets to climb; stragglers are then taken to the arena. */
    public static final long WALK_MILLIS = 90_000;
    /** The gate opens on its own once the modifier vote is settled, so the party may shop first: stragglers are taken after this. */
    public static final long AUTO_WALK_MILLIS = 300_000;
    /** The gallery is behind glass: someone standing this close to the spectator anchor cannot walk out. */
    public static final double GALLERY_RADIUS = 14.0;

    private WalkRules() {}

    /**
     * Whether leaving this floor is on foot: it has a stair gate, and the next floor is in the very same building. A floor without a gate (the
     * last of a cycle) and a detour to another building are left by teleport, as every floor was before.
     */
    public static boolean onFoot(FloorLayout current, Optional<FloorLayout> next) {
        return current.walkable() && next.isPresent() && next.get().structure().equals(current.structure());
    }

    /** Whether a player at (x, y, z) has arrived at an anchor placed at (ax, ay, az). */
    public static boolean arrived(double x, double y, double z, double ax, double ay, double az) {
        double dx = x - ax;
        double dz = z - az;
        return dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS && Math.abs(y - ay) <= ARRIVE_HEIGHT;
    }

    /** Whether a player is on the floor's spectator ledge, which has no way out on foot. */
    public static boolean strandedAt(double x, double y, double z, FloorAnchor spectator, double originX, double originY, double originZ) {
        double dx = x - (originX + spectator.x() + 0.5);
        double dz = z - (originZ + spectator.z() + 0.5);
        return dx * dx + dz * dz <= GALLERY_RADIUS * GALLERY_RADIUS && Math.abs(y - (originY + spectator.y())) <= ARRIVE_HEIGHT;
    }
}
