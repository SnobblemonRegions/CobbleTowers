package com.cobbletowers.encounter;

import com.cobbletowers.definition.RulesetDefinition;
import java.util.Collection;
import java.util.OptionalInt;

/**
 * The level a tower opponent fights at, and the only place that decides one (TDS #45). {@link TowerLevelSnapshot}
 * owns the top-two mean over registered parties; this turns it into the spawn level. Taken once per floor.
 */
public final class TowerLevelPolicy {

    /** Floor 1 fights this far below the party's level, and each floor closes 2 of the gap. */
    private static final int START_BELOW = 10;
    private static final int RAMP_STEP = 2;
    /** The floor that fights at the party's own level; above it each floor adds one. */
    private static final int LEVEL_FLOOR = 6;
    /** The boss's health on floor 1, as a percentage; it climbs 10 points a floor to full. */
    private static final int START_HEALTH = 50;

    /** What the floor adds to the party's level: negative at the bottom, zero at floor 6, then +1 a floor. */
    public static int floorOffset(int floorIndex) {
        return floorIndex <= LEVEL_FLOOR ? -START_BELOW + RAMP_STEP * (floorIndex - 1) : floorIndex - LEVEL_FLOOR;
    }

    /** The boss's shared health on this floor, as a percentage of its full pool (100 from floor 6). */
    public static int bossHealthPercent(int floorIndex) {
        return Math.max(1, Math.min(100, START_HEALTH + 10 * (floorIndex - 1)));
    }

    private TowerLevelPolicy() {}

    /**
     * The level for one opponent.
     * @param partyLevels every registered Pokemon of every participant, fainted included
     * @param floorIndex 1-based
     * @param entryOffset the pool entry's own offset
     * @param ruleset supplies the bounds
     */
    public static OptionalInt levelFor(Collection<Integer> partyLevels, int floorIndex, int entryOffset,
                                       RulesetDefinition ruleset) {
        OptionalInt mean = TowerLevelSnapshot.of(partyLevels);
        if (mean.isEmpty()) return OptionalInt.empty();

        int scaled = mean.getAsInt() + floorOffset(floorIndex) + entryOffset;
        return OptionalInt.of(clamp(scaled, ruleset));
    }

    /**
     * Balance phase 2: the levels a boss asked for but the level cap took away (0 when nothing was clamped). They come
     * back as boss health ({@link com.cobbletowers.modifier.ModifierEffects#withClampedLevels}), so a level-100 party
     * still feels a drafted "+3 levels".
     */
    public static int levelsClampedAway(Collection<Integer> partyLevels, int floorIndex, int offset, int levelGiven) {
        OptionalInt mean = TowerLevelSnapshot.of(partyLevels);
        if (mean.isEmpty()) return 0;
        return Math.max(0, mean.getAsInt() + floorOffset(floorIndex) + offset - levelGiven);
    }

    /** Held inside the ruleset's bounds, then Cobblemon's. */
    public static int clamp(int level, RulesetDefinition ruleset) {
        int low = Math.max(ruleset.minEnemyLevel(), TowerLevelSnapshot.MIN_LEVEL);
        int high = Math.min(ruleset.maxEnemyLevel(), TowerLevelSnapshot.MAX_LEVEL);
        if (low > high) {
            // Content says min above max. Refusing would take a floor down over a typo, so the
            // ruleset's own minimum wins and the definition validator is what complains about it.
            return Math.max(TowerLevelSnapshot.MIN_LEVEL, Math.min(low, TowerLevelSnapshot.MAX_LEVEL));
        }
        return Math.max(low, Math.min(high, level));
    }
}
