package com.cobbletowers.reward;

import com.cobbletowers.api.reward.RewardKind;
import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.persistence.LedgerEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Turns a slice of a run's ledger into what it is worth. Pure, so tested without Minecraft. POOL_FORFEITED never
 * reaches here.
 */
public final class RewardValuation {

    private RewardValuation() {}

    /**
     * One item, at the amount it was worth once growth and the run's modifiers applied.
     * @param perPlayer true for a milestone's guaranteed item: every participant gets the whole amount
     */
    public record Grant(ResourceLocation item, int amount, boolean perPlayer) {
        public Grant(ResourceLocation item, int amount) {
            this(item, amount, false);
        }
    }

    /** Where bonus rolls draw their ordinals, disjoint from the ledger-position ordinals below it. */
    static final int BONUS_ORDINAL_BASE = 100_000;

    /** Where a table's extra rolls (P39) draw theirs; the first roll keeps the ledger position, so older results do not move. */
    static final int EXTRA_ROLL_ORDINAL_BASE = 300_000;

    /**
     * What {@code priced} is worth from {@code table}: linear growth per floor, then reward-percent applied once via
     * {@link ModifierEffects#applyReward}.
     * @param runSeed roll source, so a crash cannot reroll (TDS #29)
     */
    public static List<Grant> value(long runSeed, List<LedgerEntry> priced, RewardTableDefinition table,
                                    ModifierEffects effects) {
        return value(runSeed, priced, table, effects, id -> Optional.empty());
    }

    /**
     * As above, also pricing {@code MILESTONE_CLEARED} entries.
     * @param milestoneKinds resolves a milestone id to boss or champion
     */
    public static List<Grant> value(long runSeed, List<LedgerEntry> priced, RewardTableDefinition table,
                                    ModifierEffects effects,
                                    java.util.function.Function<ResourceLocation, Optional<MilestoneKind>> milestoneKinds) {
        return value(runSeed, priced, table, effects, com.cobbletowers.modifier.CustomEffects.NONE, milestoneKinds);
    }

    /** As above, with the run's CUSTOM modifiers (P29): the Wheel scales each rolled grant by its own spin. */
    public static List<Grant> value(long runSeed, List<LedgerEntry> priced, RewardTableDefinition table,
                                    ModifierEffects effects, com.cobbletowers.modifier.CustomEffects customs,
                                    java.util.function.Function<ResourceLocation, Optional<MilestoneKind>> milestoneKinds) {
        return value(runSeed, priced, table, effects, customs, 0, milestoneKinds);
    }

    /**
     * As above for a cycling tower (P30): growth counts within the floor's own cycle. Zero cycleLength means no
     * cycling.
     */
    public static List<Grant> value(long runSeed, List<LedgerEntry> priced, RewardTableDefinition table,
                                    ModifierEffects effects, com.cobbletowers.modifier.CustomEffects customs,
                                    int cycleLength,
                                    java.util.function.Function<ResourceLocation, Optional<MilestoneKind>> milestoneKinds) {
        return value(runSeed, priced, table, effects, customs, cycleLength, milestoneKinds, entry -> 100);
    }

    /** As above, scaling each rolled entry's weight by {@code weightPercent} (P36c); only item choice moves. */
    public static List<Grant> value(long runSeed, List<LedgerEntry> priced, RewardTableDefinition table,
                                    ModifierEffects effects, com.cobbletowers.modifier.CustomEffects customs,
                                    int cycleLength,
                                    java.util.function.Function<ResourceLocation, Optional<MilestoneKind>> milestoneKinds,
                                    java.util.function.ToIntFunction<RewardTableDefinition.Entry> weightPercent) {
        List<Grant> grants = new ArrayList<>();
        int n = 0;
        for (LedgerEntry entry : priced) {
            // a mimic's mark is not loot and must not move the ordinal of the entries after it
            if (entry.kind() == LedgerEntry.Kind.TRICK_LOST || entry.kind() == LedgerEntry.Kind.TRICK_WON_BACK) continue;
            n++;
            if (entry.kind() == LedgerEntry.Kind.MILESTONE_CLEARED) {
                addMilestone(grants, runSeed, entry, n, table, effects, customs, cycleLength, milestoneKinds, weightPercent);
                continue;
            }
            Optional<RewardKind> kind = toRewardKind(entry.kind());
            if (kind.isEmpty()) continue;
            int floor = growthFloor(entry.floorIndex(), cycleLength);
            // Only the items this floor's depth allows (P39); the whole pool if the bands leave nothing.
            List<RewardTableDefinition.Entry> pool = table.entriesFor(kind.get(), floor);
            if (pool.isEmpty()) continue;

            int rolls = table.rollsFor(kind.get());
            for (int roll = 0; roll < rolls; roll++) {
                int ordinal = roll == 0 ? n : EXTRA_ROLL_ORDINAL_BASE + n * 16 + roll;
                RewardTableDefinition.Entry rolled = RewardDraw.pickItem(runSeed, entry.floorIndex(), ordinal, pool, weightPercent);
                int amount = RewardDraw.rollAmount(runSeed, entry.floorIndex(), ordinal, rolled.minAmount(), rolled.maxAmount());
                int grown = amount + amount * table.growthPercentPerFloor() * floor / 100;
                int worth = scaled(rolled, grown, effects, customs, runSeed, entry.floorIndex(), ordinal);
                if (worth > 0) grants.add(new Grant(rolled.item(), worth));
            }
        }
        return List.copyOf(grants);
    }

    /** The guaranteed items, each to everybody in full, then the bonus rolls valued like any other grant. */
    private static void addMilestone(List<Grant> grants, long runSeed, LedgerEntry entry, int n,
                                     RewardTableDefinition table, ModifierEffects effects,
                                     com.cobbletowers.modifier.CustomEffects customs, int cycleLength,
                                     java.util.function.Function<ResourceLocation, Optional<MilestoneKind>> milestoneKinds,
                                     java.util.function.ToIntFunction<RewardTableDefinition.Entry> weightPercent) {
        Optional<RewardTableDefinition.MilestoneReward> reward =
                milestoneKinds.apply(entry.what()).flatMap(table::milestoneReward);
        if (reward.isEmpty()) return;

        for (RewardTableDefinition.Guaranteed fixed : reward.get().guaranteed()) {
            // Not grown and not scaled: "a guaranteed XL candy" must mean exactly that many.
            grants.add(new Grant(fixed.item(), fixed.amount(), true));
        }
        for (int roll = 0; roll < reward.get().bonusRolls(); roll++) {
            int ordinal = BONUS_ORDINAL_BASE + n * 16 + roll;
            RewardTableDefinition.Entry rolled = RewardDraw.pickItem(runSeed, entry.floorIndex(), ordinal,
                    reward.get().bonusPool(), weightPercent);
            int amount = RewardDraw.rollAmount(runSeed, entry.floorIndex(), ordinal, rolled.minAmount(), rolled.maxAmount());
            int grown = amount + amount * table.growthPercentPerFloor() * growthFloor(entry.floorIndex(), cycleLength) / 100;
            int worth = scaled(rolled, grown, effects, customs, runSeed, entry.floorIndex(), ordinal);
            if (worth > 0) grants.add(new Grant(rolled.item(), worth));
        }
    }

    /** A rolled amount after the drafted loot multipliers; Raid Points are never multiplied (balance phase 2). */
    private static int scaled(RewardTableDefinition.Entry rolled, int grown, ModifierEffects effects,
                              com.cobbletowers.modifier.CustomEffects customs, long runSeed, int floorIndex, int ordinal) {
        if (com.cobbletowers.economy.RaidPointsCurrency.ITEM_ID.equals(rolled.item())) return grown;
        return effects.applyReward(grown) * customs.rewardPercent(runSeed, floorIndex, ordinal) / 100;
    }

    /**
     * The floor a reward's growth is counted from: its place in its own cycle, or the floor itself when nothing
     * cycles.
     */
    private static int growthFloor(int floorIndex, int cycleLength) {
        return cycleLength > 0 ? com.cobbletowers.ascension.AscensionPolicy.towerFloorOf(floorIndex, cycleLength) : floorIndex;
    }

    private static Optional<RewardKind> toRewardKind(LedgerEntry.Kind kind) {
        return switch (kind) {
            case MILESTONE_CLEARED -> Optional.empty();
            case OPPONENT_DEFEATED -> Optional.of(RewardKind.OPPONENT_DEFEATED);
            case BOSS_DEFEATED -> Optional.of(RewardKind.BOSS_DEFEATED);
            case FLOOR_CLEARED -> Optional.of(RewardKind.FLOOR_CLEARED);
            case POOL_FORFEITED, TRICK_LOST, TRICK_WON_BACK -> Optional.empty();
        };
    }
}
