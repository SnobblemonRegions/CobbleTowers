package com.cobbletowers.ascension;

/**
 * The Threat band a tower fight is at (balance phase 2, AscensionLib's docs/BALANCE-THREAT.md). A run picks its entry band (1-6) at the door; each
 * Ascension cycle adds one, the floor-10 champion adds one more, and nothing goes past VII. A run with no entry band (0: every run made before this, a
 * trial, or a library without Threat) keeps the old floor-based tiers. Pure.
 *
 * <p>Once a run is past Threat VII the old Ascension growth (boss pool, enemy EVs, extra opponents) takes over, counted only for the cycles beyond
 * VII, so the difficulty is never counted twice; the Ascension reward factor keeps counting every cycle.
 */
public final class ThreatPolicy {
    public static final int MAX_BAND = 7;
    /** The highest band a party may pick at the door; VII is reached only through Ascension cycles. */
    public static final int MAX_ENTRY = 6;

    private ThreatPolicy() {}

    /** The band of a fight: entry + Ascension cycle, +1 for a champion, clamped to 1-7. 0 for a run with no entry band. */
    public static int bandFor(int entryThreat, int ascension, boolean champion) {
        if (entryThreat <= 0) return 0;
        return Math.max(1, Math.min(MAX_BAND, entryThreat + Math.max(0, ascension) + (champion ? 1 : 0)));
    }

    /** AscensionLib's enemy tier ID for a band, such as {@code threat_4}. */
    public static String tierId(int band) {
        return "threat_" + Math.max(1, Math.min(MAX_BAND, band));
    }

    /**
     * The Ascension the old growth ramps (boss pool, EVs, extra opponents) are computed from: every cycle for a run with no entry band, only the cycles
     * past Threat VII for one with.
     */
    public static int rampAscension(int entryThreat, int ascension) {
        if (entryThreat <= 0) return Math.max(0, ascension);
        return Math.max(0, entryThreat + Math.max(0, ascension) - MAX_BAND);
    }

    /** A valid door choice: 0 (no Threat) or 1-6. */
    public static int clampEntry(int entryThreat) {
        return Math.max(0, Math.min(MAX_ENTRY, entryThreat));
    }
}
