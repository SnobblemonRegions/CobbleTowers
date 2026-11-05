package com.cobbletowers.intermission;

/** How a pedestal looks per risk tier (P55, the "Arcade Cabinet" look): neon cap, gem, dust tint, gaze chime. Pure. */
public final class PedestalStyle {

    /**
     * @param accent text and trim colour, 0xRRGGBB
     * @param capBlock the neon cap and trim, drawn fullbright
     * @param gemBlock the spinning gem, drawn fullbright
     * @param dust particle tint, each channel 0..1
     * @param pitch the gaze chime's pitch
     * @param beamBlock the translucent light beam rising from the cap, drawn fullbright
     */
    public record Style(int accent, String capBlock, String gemBlock, float[] dust, float pitch, String beamBlock) {}

    private static final Style MINOR = new Style(0x55FF99, "minecraft:lime_concrete", "minecraft:lime_stained_glass",
            new float[] {0.33f, 1.0f, 0.6f}, 1.8f, "minecraft:lime_stained_glass");
    private static final Style MODERATE = new Style(0xFFD34D, "minecraft:yellow_concrete", "minecraft:gold_block",
            new float[] {1.0f, 0.83f, 0.3f}, 1.4f, "minecraft:yellow_stained_glass");
    private static final Style SEVERE = new Style(0xFF4D8D, "minecraft:magenta_concrete", "minecraft:crying_obsidian",
            new float[] {1.0f, 0.3f, 0.55f}, 1.0f, "minecraft:magenta_stained_glass");
    private static final Style EVENT = new Style(0x6AE4FF, "minecraft:cyan_concrete", "minecraft:sea_lantern",
            new float[] {0.4f, 0.9f, 1.0f}, 1.2f, "minecraft:cyan_stained_glass");

    /** The refusal card of an event room (TODO #4): grey, so it never looks like the offer beside it. */
    private static final Style REFUSAL = new Style(0xB4B8C2, "minecraft:gray_concrete", "minecraft:iron_block",
            new float[] {0.7f, 0.72f, 0.78f}, 0.8f, "minecraft:gray_stained_glass");

    private PedestalStyle() {}

    /** How the refusing card of an event room looks. */
    public static Style refusal() {
        return REFUSAL;
    }

    /** @param risk 0 minor, 1 moderate, 2 severe, -1 for a card that is not a modifier */
    public static Style styleFor(int risk) {
        return switch (risk) {
            case 0 -> MINOR;
            case 1 -> MODERATE;
            case 2 -> SEVERE;
            default -> EVENT;
        };
    }
}
