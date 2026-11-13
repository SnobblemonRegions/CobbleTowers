package com.cobbletowers.reward;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.persistence.PendingTowerReward;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerDailyCompletionStore;
import com.cobbletowers.persistence.TowerPendingRewardStore;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.trial.TrialClock;
import com.cobbletowers.trial.TrialService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Turns a run's ledger into a real grant (TDS #24, #30). Decisions are pure; only {@link #bank} and {@link
 * #sweepUnbanked} write.
 */
public final class RewardBankService {

    private RewardBankService() {}

    // ---------------------------------------------------------------- pure

    /** The commit key one grant is made under, distinct from any transition's checkpoint key (TDS #30). */
    static String grantKey(UUID runId, int throughFloor) {
        return "run:" + runId + ":floor:" + throughFloor + ":granted";
    }

    /**
     * Whether this arrival is a real payout point, and the table to price it from. At INTERMISSION only a milestone
     * floor pays; COMPLETED and CASHED_OUT always pay what remains.
     */
    static Optional<RewardTableDefinition> bankPoint(TowerContent content, PersistedRun run) {
        TowerDefinition tower = content.towers().get(run.towerId());
        if (tower == null) return Optional.empty();
        if (run.state() == RunState.RECOVERY_REQUIRED) return Optional.empty();   // only through bankParked
        if (run.state() == RunState.INTERMISSION) {
            boolean banks = content.milestoneAt(run.towerId(), run.floorIndex())
                    .map(MilestoneDefinition::banksRewards)
                    .orElse(false)
                    // The end of an Ascension cycle always banks (P30): it is where the team may cash out.
                    || tower.ascension() && com.cobbletowers.ascension.AscensionPolicy.isCycleEnd(
                            run.floorIndex(), tower.floorCount());
            if (!banks) return Optional.empty();
        }
        return content.rewardTable(tower.rewardTableId());
    }

    /** The table a parked run's cleared floors are priced from; empty for any run that is not parked. */
    static Optional<RewardTableDefinition> parkedTable(TowerContent content, PersistedRun run) {
        if (run.state() != RunState.RECOVERY_REQUIRED) return Optional.empty();
        TowerDefinition tower = content.towers().get(run.towerId());
        return tower == null ? Optional.empty() : content.rewardTable(tower.rewardTableId());
    }

    /** Whether one granted item reaches this player at their share of the daily budget. Seeded, so a replay keeps the same items. */
    private static boolean kept(PersistedRun run, int position, UUID playerId, Map<UUID, Integer> percent) {
        int share = percent.getOrDefault(playerId, 100);
        if (share >= 100) return true;
        return DailyCompletionBudget.keeps(lootSeed(run) * 31 + position * 7919L + playerId.getLeastSignificantBits(), share);
    }

    /** The ledger newly priceable: earned since the run last banked, through the floor it is on now. */
    static List<LedgerEntry> unbanked(PersistedRun run) {
        List<LedgerEntry> priced = new ArrayList<>();
        for (LedgerEntry entry : run.ledger()) {
            if (entry.floorIndex() > run.lastBankedFloor() && entry.floorIndex() <= run.floorIndex()) {
                priced.add(entry);
            }
        }
        return List.copyOf(priced);
    }

    /**
     * The final-payout bonus for the modifiers the run holds, a locked-in one counted twice (as {@code
     * DifficultyScore} does).
     */
    public static int riskBonusPercent(TowerContent content, PersistedRun run) {
        List<com.cobbletowers.api.modifier.RiskTier> risks = new ArrayList<>();
        for (var held : DraftService.held(content, run.modifiers())) risks.add(held.risk());
        for (var locked : run.modifiers().lockedIn()) content.modifier(locked).ifPresent(m -> risks.add(m.risk()));
        int bonus = RiskReward.bonusPercent(risks);
        // the weekly rule doubles it (P46)
        return com.cobbletowers.trial.TrialRules.hasRule(run, "double_risk") ? bonus * 2 : bonus;
    }

    /** Who a grant is split across: everybody still a member, not only those who can fight right now. */
    static List<UUID> currentParticipants(PersistedRun run) {
        List<UUID> ids = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (participant.state().isInRun()) ids.add(participant.playerId());
        }
        return List.copyOf(ids);
    }

    /** {@code amount} split as evenly as possible; the remainder goes to the first participants in order. */
    static Map<UUID, Integer> evenSplit(int amount, List<UUID> participants) {
        return evenSplit(amount, participants, 0);
    }

    /** As above, but the remainder starts at participant {@code rotation} so lone items are spread out. */
    static Map<UUID, Integer> evenSplit(int amount, List<UUID> participants, int rotation) {
        Map<UUID, Integer> shares = new LinkedHashMap<>();
        if (participants.isEmpty() || amount <= 0) return shares;
        int count = participants.size();
        int base = amount / count;
        int remainder = amount % count;
        int start = Math.floorMod(rotation, count);
        for (int i = 0; i < count; i++) {
            int position = Math.floorMod(i - start, count);   // 0 = first in line for the remainder
            int share = base + (position < remainder ? 1 : 0);
            if (share > 0) shares.put(participants.get(i), share);
        }
        return shares;
    }

    // -------------------------------------------------------------- writing

    /**
     * The seed loot is rolled from: the run seed mixed with the run id, so a shared run-code seed cannot be hunted
     * for loot.
     */
    static long lootSeed(PersistedRun run) {
        UUID id = run.runId();
        return run.seed() ^ id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
    }

    /**
     * Banks what the run earned since it last banked, if this is a payout point. Idempotent via {@link #grantKey}.
     * The run record is written before the pending-reward store: a crash then loses a grant rather than duplicating
     * it.
     */
    public static void bank(MinecraftServer server, UUID runId, long now) {
        bank(server, runId, now, false);
    }

    /**
     * Banks a parked run's floors cleared since its last payout, as the run is closed: a restart or a fault is not
     * the team's loss, so what they cleared is kept, at the table's normal value and without the risk bonus (that is
     * for finishing or cashing out). Only for a run in RECOVERY_REQUIRED; idempotent like {@link #bank}.
     */
    public static void bankParked(MinecraftServer server, UUID runId, long now) {
        bank(server, runId, now, true);
    }

    private static void bank(MinecraftServer server, UUID runId, long now, boolean parked) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return;
        PersistedRun run = found.get();
        if (run.floorIndex() <= run.lastBankedFloor()) return;

        TowerContent content = TowerDefinitionRegistry.content();
        Optional<RewardTableDefinition> table = parked ? parkedTable(content, run) : bankPoint(content, run);
        if (table.isEmpty()) return;

        String key = grantKey(runId, run.floorIndex());
        if (run.hasCommitted(key)) return;

        List<LedgerEntry> priced = unbanked(run);
        ModifierEffects effects = DraftService.effects(run);
        List<RewardValuation.Grant> grants = RewardValuation.value(lootSeed(run), priced, table.get(), effects,
                DraftService.customs(run), content.towers().get(run.towerId()).ascension() ? content.towers().get(run.towerId()).floorCount() : 0,
                id -> content.milestoneKindOf(id), com.cobbletowers.season.SeasonSpotlight.weights(run.towerId()));
        // A daily or weekly trial pays its loot to the one scored attempt only; a practice run banks nothing from the tower's table.
        if (PracticeTrialReward.isPractice(run.options())) grants = List.of();
        // The risk bonus is paid once, with the final payout of the tower: the run is over (completed or cashed out).
        if (run.state() == RunState.COMPLETED || run.state() == RunState.CASHED_OUT) {
            int bonus = riskBonusPercent(content, run);
            com.cobbletowers.mastery.TuningCounters.bump(server, "risk_bonus.final_payouts");
            if (bonus > 0) {
                com.cobbletowers.mastery.TuningCounters.bump(server, "risk_bonus.payouts_with_bonus");
                com.cobbletowers.mastery.TuningCounters.add(server, "risk_bonus.percent_sum", bonus);
                grants = RiskReward.apply(grants, bonus);
                TowerLog.info("Run {} final payout carries a +{}% risk bonus", runId, bonus);
            }
        }
        List<UUID> participants = currentParticipants(run);
        // A mimic lost in a Trick or Treat vault takes its share of everything banked from then on (P55).
        int trickCut = com.cobbletowers.trick.TrickRules.cutPercent(run.ledger());
        if (trickCut > 0 && !grants.isEmpty()) {
            grants = com.cobbletowers.trick.TrickRules.apply(grants, trickCut, lootSeed(run));
            TowerLog.info("Run {} payout is cut {}% by lost mimic(s)", runId, trickCut);
            for (UUID playerId : participants) {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null) online.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "The mimic's grin costs you: this payout is " + trickCut + "% smaller."));
            }
        }

        TowerRuns.save(server, run.banked(run.floorIndex(), key, now), true);
        TowerLog.info("Run {} banked its rewards through floor {}: {}", runId, run.floorIndex(), grants);

        if (grants.isEmpty() || participants.isEmpty()) return;

        TowerPendingRewardStore store = TowerPendingRewardStore.get(server);
        // Repeat completions in a trial day pay less of the tower's own loot (a trial is already limited to one scored attempt).
        TowerDailyCompletionStore budget = TowerDailyCompletionStore.get(server);
        String day = TrialClock.dayKey(TrialService.today());
        boolean budgeted = !run.options().isTrial();
        Map<UUID, Integer> percent = new HashMap<>();
        for (UUID playerId : participants) {
            percent.put(playerId, budgeted ? DailyCompletionBudget.percentFor(budget.countOn(playerId, day)) : 100);
        }
        int position = 0;
        for (RewardValuation.Grant grant : grants) {
            if (grant.perPlayer()) {
                // A milestone's guaranteed item: everybody gets the whole amount, not a share of it.
                for (UUID playerId : participants) {
                    if (!kept(run, position, playerId, percent)) continue;
                    store.add(playerId, new PendingTowerReward(runId, run.floorIndex(), grant.item(), grant.amount(), now));
                }
            } else {
                // Rotated by floor and position so a one-item grant does not always reach the first player.
                for (Map.Entry<UUID, Integer> share
                        : evenSplit(grant.amount(), participants, run.floorIndex() + position).entrySet()) {
                    if (!kept(run, position, share.getKey(), percent)) continue;
                    store.add(share.getKey(),
                            new PendingTowerReward(runId, run.floorIndex(), grant.item(), share.getValue(), now));
                }
            }
            position++;
        }
        store.checkpoint(server);
        if (budgeted) {
            var tower = content.towers().get(run.towerId());
            int highestPriced = tower == null ? 0
                    : priced.stream().mapToInt(entry -> tower.contentFloor(entry.floorIndex())).max().orElse(0);
            boolean completes = tower != null
                    && DailyCompletionBudget.countsAsCompletion(run.state(), highestPriced, tower.floorCount());
            for (UUID playerId : participants) {
                int share = percent.get(playerId);
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null && share < 100 && !grants.isEmpty()) {
                    online.sendSystemMessage(net.minecraft.network.chat.Component.literal("You have completed " + budget.countOn(playerId, day)
                            + " tower(s) today, so this payout is reduced to about " + share + "% (it resets with the trial day)."));
                }
                if (completes) budget.record(playerId, day);
            }
            if (completes) budget.checkpoint(server);
        }

        for (UUID playerId : participants) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) RewardDelivery.deliver(server, player);
        }
    }

    /**
     * Catches the crash window the live path cannot: COMPLETED and CASHED_OUT are terminal, so recovery never parks
     * them. INTERMISSION needs no sweep because resuming re-enters the arrival block that calls {@link #bank}.
     */
    public static int sweepUnbanked(MinecraftServer server, long now) {
        int banked = 0;
        for (PersistedRun run : TowerRuns.all()) {
            if (run.state() != RunState.COMPLETED && run.state() != RunState.CASHED_OUT) continue;
            if (run.lastBankedFloor() >= run.floorIndex()) continue;
            bank(server, run.runId(), now);
            banked++;
        }
        return banked;
    }
}
