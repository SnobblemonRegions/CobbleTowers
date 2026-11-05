package com.cobbletowers.economy;

/**
 * Which AscensionLib enemy tier (enemy-tiers.json) a tower enemy is generated from (decided 2026-10-05): floors 1-4
 * {@code trial_rank_1}, 5-9 {@code trial_rank_2}, 10 on {@code trial_rank_3}; a milestone boss uses {@code boss}. The
 * run's floor keeps counting, so deeper cycles never fall to an easier tier.
 */
public final class ScoutingTiers {

    private ScoutingTiers() {}

    /**
     * The tier for a run at a Threat band (balance phase 2): {@code threat_N} from {@link com.cobbletowers.ascension.ThreatPolicy#bandFor} when the run
     * picked a band and AscensionLib has Threat on; otherwise the floor tiers below, exactly as before.
     */
    public static String tierFor(int entryThreat, int ascension, int runFloor, boolean milestoneBoss, boolean champion, boolean threatOn) {
        int band = com.cobbletowers.ascension.ThreatPolicy.bandFor(entryThreat, ascension, champion);
        if (threatOn && band > 0) return com.cobbletowers.ascension.ThreatPolicy.tierId(band);
        return tierFor(runFloor, milestoneBoss);
    }

    public static String tierFor(int runFloor, boolean milestoneBoss) {
        if (milestoneBoss) return "boss";
        if (runFloor >= 10) return "trial_rank_3";
        if (runFloor >= 5) return "trial_rank_2";
        return "trial_rank_1";
    }
}
