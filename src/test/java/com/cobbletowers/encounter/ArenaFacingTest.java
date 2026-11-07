package com.cobbletowers.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Turning each fighter to face the boss before the battle starts (TODO #2), so the Pokemon are sent out facing each other. */
class ArenaFacingTest {

    private static final float KEEP = 123.0f;

    @Test
    @DisplayName("a boss to the south is yaw 0, to the west 90, to the north 180, to the east -90 (Minecraft's convention)")
    void compass() {
        assertEquals(0f, ArenaFacing.yawToward(0, 0, 0, 10, KEEP), 0.01f);
        assertEquals(90f, ArenaFacing.yawToward(0, 0, -10, 0, KEEP), 0.01f);
        assertEquals(180f, Math.abs(ArenaFacing.yawToward(0, 0, 0, -10, KEEP)), 0.01f);
        assertEquals(-90f, ArenaFacing.yawToward(0, 0, 10, 0, KEEP), 0.01f);
    }

    @Test
    @DisplayName("the yaw depends only on the direction, not on how far the boss is")
    void distanceDoesNotMatter() {
        assertEquals(ArenaFacing.yawToward(5, 5, 6, 8, KEEP), ArenaFacing.yawToward(5, 5, 11, 23, KEEP), 0.01f);
    }

    @Test
    @DisplayName("standing on the boss's spot has no direction, so the player keeps the way they are facing")
    void onTheSpot() {
        assertEquals(KEEP, ArenaFacing.yawToward(4, 4, 4.2, 4.2, KEEP));
    }

    @Test
    @DisplayName("a diagonal boss is turned toward, halfway between the two compass points")
    void diagonal() {
        assertEquals(-45f, ArenaFacing.yawToward(0, 0, 10, 10, KEEP), 0.01f);
        assertEquals(45f, ArenaFacing.yawToward(0, 0, -10, 10, KEEP), 0.01f);
    }
}
