package com.cobbletowers.modifier;

import com.cobbletowers.definition.ModifierDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a run's modifiers add up to (TDS #56). Summed once here; nothing downstream asks which modifiers a run
 * carries. No level arithmetic lives here: offsets go to {@code TowerLevelPolicy} (TDS #45).
 */
public record ModifierEffects(
        int bossLevelOffset,
        int bossHealthPercent,
        int rewardPercent,
        List<String> bannedMoves,
        boolean switchingAllowed,
        boolean itemsAllowed,
        Optional<String> weather,
        Optional<String> terrain,
        int scoutingBonus) {

    /** A run carrying nothing: every field is the value that changes nothing. */
    public static final ModifierEffects NONE = new ModifierEffects(0, 100, 100,
            List.of(), true, true, Optional.empty(), Optional.empty(), 0);

    /** Balance phase 2: the most a run's drafted reward percentages add up to (x3). Ascension's factor is on top. */
    public static final int MAX_REWARD_PERCENT = 300;

    public ModifierEffects {
        bannedMoves = List.copyOf(bannedMoves);
    }

    /**
     * Sums a run's modifiers. Offsets add; boss health compounds; reward percentages ADD (balance phase 2: +20% and
     * +50% is +70%, not +80%) and the total is capped at {@link #MAX_REWARD_PERCENT}. Integer arithmetic, and the caller
     * passes the list in draft order because the boss-health rounding is order-sensitive.
     */
    public static ModifierEffects of(List<ModifierDefinition> modifiers) {
        int bossLevel = 0;
        int bossHealth = 100;
        int reward = 100;
        List<String> banned = new ArrayList<>();
        boolean switching = true;
        boolean items = true;
        Optional<String> weather = Optional.empty();
        Optional<String> terrain = Optional.empty();
        int scoutingBonus = 0;
        for (ModifierDefinition modifier : modifiers) {
            ModifierDefinition.Effect effect = modifier.effect();
            bossLevel += effect.bossLevelOffset();
            bossHealth = bossHealth * effect.bossHealthPercent() / 100;
            reward += effect.rewardPercent() - 100;
            scoutingBonus += effect.scoutingBonus();

            for (String move : effect.bannedMoves()) {
                if (!banned.contains(move)) banned.add(move);
            }
            // A withdrawn permission stays withdrawn.
            switching = switching && effect.allowSwitching();
            items = items && effect.allowItems();
            // A field is one condition, so the last drafted wins.
            if (effect.weather().isPresent()) weather = effect.weather();
            if (effect.terrain().isPresent()) terrain = effect.terrain();
        }
        // A pool of zero is not a boss, it is a corpse: compounding reductions could reach it, and a
        // boss that dies to the first hit would read as a bug rather than as a drafted advantage.
        return new ModifierEffects(bossLevel, Math.max(bossHealth, 1), Math.min(MAX_REWARD_PERCENT, Math.max(reward, 0)),
                banned, switching, items, weather, terrain, scoutingBonus);
    }

    /**
     * These effects with Ascension {@code ascension}'s growth on top (P30): extra opponents, a bigger boss pool and a
     * better reward factor from {@link com.cobbletowers.ascension.AscensionPolicy}.
     */
    public ModifierEffects withAscension(int ascension) {
        return withAscension(ascension, ascension);
    }

    /**
     * As above, with the growth and the reward factor counted separately (balance phase 2): a run at a Threat band grows only for the cycles past
     * Threat VII ({@link com.cobbletowers.ascension.ThreatPolicy#rampAscension}), while its loot still improves every cycle.
     */
    public ModifierEffects withAscension(int growthAscension, int rewardAscension) {
        if (growthAscension <= 0 && rewardAscension <= 0) return this;
        return new ModifierEffects(bossLevelOffset, bossHealthPercent * com.cobbletowers.ascension.AscensionPolicy.bossHealthPercent(growthAscension) / 100,
                rewardPercent * com.cobbletowers.ascension.AscensionPolicy.rewardPercent(rewardAscension) / 100,
                bannedMoves, switchingAllowed, itemsAllowed, weather, terrain, scoutingBonus);
    }

    /** Balance phase 2: boss health added per level the cap took away. */
    public static final int BOSS_HEALTH_PERCENT_PER_CLAMPED_LEVEL = 3;

    /** These effects with {@code levels} clamped-away boss levels returned as health (+3% each). */
    public ModifierEffects withClampedLevels(int levels) {
        if (levels <= 0) return this;
        int health = bossHealthPercent * (100 + BOSS_HEALTH_PERCENT_PER_CLAMPED_LEVEL * levels) / 100;
        return new ModifierEffects(bossLevelOffset, health, rewardPercent, bannedMoves,
                switchingAllowed, itemsAllowed, weather, terrain, scoutingBonus);
    }

    /** Whether anything here changes how the boss battle itself is fought. */
    public boolean changesBattleRules() {
        return !bannedMoves.isEmpty() || !switchingAllowed || !itemsAllowed
                || weather.isPresent() || terrain.isPresent() || bossHealthPercent != 100;
    }

    /** Applies the boss health percentage to a pool the adapter would otherwise have used. */
    public long applyBossHealth(long baseline) {
        return Math.max(1L, baseline * bossHealthPercent / 100);
    }

    /**
     * Applies the reward percentage to an amount {@link com.cobbletowers.reward.RewardValuation} rolled. Raid Points
     * are not scaled (balance phase 2): they buy raid-shop items, and drafted loot multipliers would let towers
     * out-earn raids at the raids' own currency. {@code RewardValuation} skips this call for them.
     */
    public int applyReward(int amount) {
        return Math.max(0, amount * rewardPercent / 100);
    }
}
