package com.cobbletowers.vendor;

import java.util.Optional;
import java.util.UUID;

/** The pure half of the physical vendor (P28): how a vendor mob is marked with its run and who may use it. */
public final class VendorNpcRules {

    /** Every vendor mob carries this scoreboard tag. */
    public static final String VENDOR_TAG = "cobbletowers_vendor";

    private static final String RUN_PREFIX = "cobbletowers_vendor_run:";

    public enum Access { ALLOWED, NOT_IN_THIS_RUN, CLOSED }

    /** The goblin witch's name tag. */
    public static final String WITCH_NAME = "Grizelda the Goblin Merchant";

    /** Towers whose vendor is the goblin witch merchant instead of the cleric. */
    private static final java.util.Set<net.minecraft.resources.ResourceLocation> WITCH_TOWERS = java.util.Set.of(com.cobbletowers.CobbleTowers.id("halloween"));

    private VendorNpcRules() {}

    public static boolean isGoblinWitch(net.minecraft.resources.ResourceLocation tower) {
        return WITCH_TOWERS.contains(tower);
    }

    /** The deepest floor of the early band and of the middle band; the floors after them are the last stretch, and the last floor of all is the boss. */
    private static final int EARLY_LAST = 3;
    private static final int MIDDLE_LAST = 6;
    private static final int BOSS_FLOOR = 10;

    private static final String[] EARLY = {
            "Heh heh heh... fresh faces! What'll it be, dearie?",
            "Come in, come in, mind the cobwebs. Plenty for little trainers.",
            "Ooh, you still smell of the sunshine. Buy something before it wears off!"};
    private static final String[] MIDDLE = {
            "Still breathing, are we? Heh heh... the good stock is saved for the survivors.",
            "The ghosts have been talking about you, dearie. Spend your corn, quick, quick.",
            "Deeper and deeper... you'll be wanting something sharper than a smile."};
    private static final String[] LATE = {
            "Hush now, can you hear it? Something big is waiting upstairs. Best buy while you can.",
            "Heh heh... few come this far. My finest goods, for my finest customers.",
            "Every coin you carry is a coin the Hollow gets if you fall. Spend it!"};
    private static final String[] BOSS = {
            "The last door, dearie. Whatever you don't buy now, you'll wish you had.",
            "Grizelda has never seen anyone return from up there... so no refunds, heh heh!"};

    /**
     * What the goblin witch says when a party opens her stall on {@code floor} (1-based): one short line in her voice, chosen by how deep the party
     * is and varying from floor to floor within a stretch so it is never the same sentence twice in a row. A floor below the first is the first.
     */
    public static String witchGreeting(int floor) {
        int at = Math.max(1, floor);
        String[] lines = at >= BOSS_FLOOR ? BOSS : at > MIDDLE_LAST ? LATE : at > EARLY_LAST ? MIDDLE : EARLY;
        return lines[at % lines.length];
    }

    public static String runTag(UUID runId) {
        return RUN_PREFIX + runId;
    }

    /** The run a tag names, empty for any other tag or a malformed id. */
    public static Optional<UUID> runOf(String tag) {
        if (!tag.startsWith(RUN_PREFIX)) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(tag.substring(RUN_PREFIX.length())));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Whether a player may use a vendor.
     * @param vendorRun the run it was spawned for
     * @param playerRun the player's run, if any
     * @param intermission whether that run is at an intermission
     */
    public static Access access(UUID vendorRun, Optional<UUID> playerRun, boolean intermission) {
        if (playerRun.isEmpty() || !playerRun.get().equals(vendorRun)) return Access.NOT_IN_THIS_RUN;
        return intermission ? Access.ALLOWED : Access.CLOSED;
    }

    /** The sentence shown to a player who may not use the vendor. */
    public static String describe(Access access) {
        return switch (access) {
            case ALLOWED -> "";
            case NOT_IN_THIS_RUN -> "This vendor is serving another team.";
            case CLOSED -> "The vendor is only open between floors.";
        };
    }

    /** The yaw that makes something at (fromX, fromZ) face (toX, toZ), in Minecraft's convention (0 = south). */
    public static float yawToward(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }
}
