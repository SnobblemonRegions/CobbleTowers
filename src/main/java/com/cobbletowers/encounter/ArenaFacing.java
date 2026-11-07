package com.cobbletowers.encounter;

/**
 * Which way a fighter must face to look at the boss (TODO #2). A party that walked into the arena stands wherever it stopped, facing whichever way it was
 * walking, and the battle starts at once; Cobblemon sends the Pokemon out from the player, so a sideways player meant a sideways Pokemon with the foe
 * across the arena. The party is turned toward the boss as the floor begins. Pure.
 */
public final class ArenaFacing {

    /** Closer than this to the boss's spot there is no direction to speak of. */
    private static final double MIN_DISTANCE = 0.5;

    private ArenaFacing() {}

    /**
     * The yaw (Minecraft's convention: 0 is south, 90 west, 180 north, -90 east) that looks from {@code (fromX, fromZ)} toward {@code (toX, toZ)}.
     * @param current kept when the two are almost the same point
     */
    public static float yawToward(double fromX, double fromZ, double toX, double toZ, float current) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.hypot(dx, dz) < MIN_DISTANCE) return current;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
