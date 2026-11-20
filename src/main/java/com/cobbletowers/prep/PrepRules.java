package com.cobbletowers.prep;

/**
 * The preparation room's rules as plain functions (P59), so they are tested without a server. The party has {@link #TIMEOUT_MILLIS} from the
 * moment it arrives; the key was taken on the way in, so a timeout ends the run and gives nothing back.
 */
public final class PrepRules {

    /** How long a party may stay in the preparation room (owner, 2026-11-13): five minutes, counted from arrival. */
    public static final long TIMEOUT_MILLIS = 5 * 60_000L;
    /** The bar turns to a warning colour, and the team is told, with this long left. */
    public static final long WARNING_MILLIS = 60_000L;

    private PrepRules() {}

    /** Time left, never below zero. */
    public static long remaining(long enteredAt, long now) {
        return Math.max(0L, enteredAt + TIMEOUT_MILLIS - now);
    }

    public static boolean timedOut(long enteredAt, long now) {
        return remaining(enteredAt, now) == 0L;
    }

    /** The boss bar's fill, from 1 at arrival down to 0. */
    public static float progress(long enteredAt, long now) {
        return (float) remaining(enteredAt, now) / TIMEOUT_MILLIS;
    }

    /** "4:59": minutes and seconds left, rounded up so the bar never shows 0:00 while time remains. */
    public static String clock(long remainingMillis) {
        long seconds = (remainingMillis + 999) / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** Where the party is in the room's short sequence. */
    public enum Step { GATHERING, DOOR_OPEN, FADING }

    /**
     * What the room does next, from where everyone stands. Nobody online counts as not ready: the timeout decides.
     * @param fighters how many fighters are online in the room
     * @param onDais how many of them stand in the ready ring
     * @param onThreshold how many of them stand on the threshold beyond the door
     */
    public static Step next(Step step, int fighters, int onDais, int onThreshold) {
        if (fighters <= 0) return step;
        return switch (step) {
            case GATHERING -> onDais == fighters ? Step.DOOR_OPEN : Step.GATHERING;
            case DOOR_OPEN -> onThreshold == fighters ? Step.FADING : Step.DOOR_OPEN;
            case FADING -> Step.FADING;
        };
    }
}
