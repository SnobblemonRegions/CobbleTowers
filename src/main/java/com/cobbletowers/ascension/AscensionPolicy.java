package com.cobbletowers.ascension;

/**
 * Everything Ascension (P30) decides, in one pure place. A run's {@code floorIndex} keeps counting past the tower's
 * last floor: the Ascension is the number of full cycles behind it and the tower floor is where in the cycle it is.
 * Growth constants are first guesses; reward growth is bounded (TDS #9).
 */
public final class AscensionPolicy {

    /** Added to every boss's health, in percent, per Ascension. */
    public static final int BOSS_HEALTH_PERCENT_PER_ASCENSION = 12;
    /** One extra opponent per this many Ascensions... */
    public static final int ASCENSIONS_PER_EXTRA_OPPONENT = 3;
    /** ...up to this many. */
    public static final int MAX_EXTRA_OPPONENTS = 3;
    /** The reward multiplier approaches 100 + this percent and never reaches it. */
    public static final int REWARD_BONUS_CEILING_PERCENT = 150;
    /** Each Ascension closes this much less of the remaining gap to the ceiling, in percent (diminishing returns). */
    public static final int REWARD_RETAINED_GAP_PERCENT = 90;
    /** EVs added to every stat of every enemy, per Ascension (applied in-battle; see tower-fx.js). */
    public static final int ENEMY_EVS_PER_ASCENSION = 50;
    /**
     * One {@code evs} operation never carries more than this (the operation's own limit); more is sent as several.
     */
    public static final int MAX_EVS_PER_OPERATION = 2000;
    /** The most EVs a stat can hold in a battle, however they arrive (the extension's own limit). */
    public static final int MAX_EVS_TOTAL = 4000;
    /** The matching player boon, as a percentage of the enemy's. */
    public static final int BOON_PERCENT_OF_ENEMY = 50;
    /** Past this the arithmetic stops changing anything; it also keeps every loop and product small. */
    private static final int MAX_ASCENSION = 1000;

    private AscensionPolicy() {}

    /** Full cycles completed before this floor; 0 is the base cycle. {@code floorIndex} is 1-based. */
    public static int ascensionOf(int floorIndex, int floorCount) {
        if (floorCount < 1 || floorIndex < 1) return 0;
        return Math.min(MAX_ASCENSION, (floorIndex - 1) / floorCount);
    }

    /** Where in its cycle a floor is, 1-based; the number content is looked up by. */
    public static int towerFloorOf(int floorIndex, int floorCount) {
        if (floorCount < 1 || floorIndex < 1) return floorIndex;
        return (floorIndex - 1) % floorCount + 1;
    }

    /** Whether clearing this floor ends a cycle. */
    public static boolean isCycleEnd(int floorIndex, int floorCount) {
        return floorCount >= 1 && floorIndex >= 1 && floorIndex % floorCount == 0;
    }

    /** The run floor an Ascension begins on. */
    public static int firstFloorOf(int ascension, int floorCount) {
        return Math.max(0, ascension) * Math.max(1, floorCount) + 1;
    }

    /** The boss-health factor, in percent of the base (100 = unchanged). */
    public static int bossHealthPercent(int ascension) {
        return 100 + BOSS_HEALTH_PERCENT_PER_ASCENSION * clamp(ascension);
    }

    /**
     * Boss-rush towers (balance phase 2): the turns between enrages that stand in for {@link #extraOpponents}, 0 for
     * none. One extra opponent is an enrage every 5 turns, two every 4, three every 3.
     */
    public static int enrageEvery(int ascension) {
        int extra = extraOpponents(ascension);
        return extra <= 0 ? 0 : 6 - extra;
    }

    public static int extraOpponents(int ascension) {
        return Math.min(MAX_EXTRA_OPPONENTS, clamp(ascension) / ASCENSIONS_PER_EXTRA_OPPONENT);
    }

    /**
     * The reward factor in percent: 100 at the base cycle, rising with shrinking steps towards {@code 100 +
     * REWARD_BONUS_CEILING_PERCENT}. Integer arithmetic.
     */
    public static int rewardPercent(int ascension) {
        long gap = 10_000;   // what is left of the distance to the ceiling, in hundredths of a percent
        for (int i = 0; i < clamp(ascension) && gap > 0; i++) gap = gap * REWARD_RETAINED_GAP_PERCENT / 100;
        return 100 + (int) (REWARD_BONUS_CEILING_PERCENT * (10_000 - gap) / 10_000);
    }

    /** EVs the enemy gets in each stat in total, at most what a stat can hold. */
    public static int enemyEvs(int ascension) {
        return Math.min(MAX_EVS_TOTAL, ENEMY_EVS_PER_ASCENSION * clamp(ascension));
    }

    /** The matching player boon: a fixed share of the enemy's. */
    public static int boonEvs(int ascension) {
        return enemyEvs(ascension) * BOON_PERCENT_OF_ENEMY / 100;
    }

    private static int clamp(int ascension) {
        return Math.max(0, Math.min(MAX_ASCENSION, ascension));
    }
}
