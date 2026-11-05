package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import com.cobbletowers.persistence.PendingLibSettlement;
import com.cobbletowers.persistence.TowerLibSettlementStore;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntPredicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Pays a boss victory in AscensionLib's wallet, reached by reflection since the library is optional. Each settlement
 * is written to disk ({@link TowerLibSettlementStore}) before the library is asked and removed once confirmed; the
 * library pays once per encounter, so retries every {@link #RETRY_TICKS} and at start are safe. A failure never
 * touches the run.
 */
public final class AscensionLibRewards {

    private static final String MOD_ID = "ascensionlib";
    private static final String REWARDS = "com.ascensionlib.AscensionRewards";

    /** How often unconfirmed settlements are tried again (30 seconds). */
    static final int RETRY_TICKS = 20 * 30;

    private static boolean resolved;
    private static Method settleBoss;
    private static Method settleScouters;
    private static Method settleTrial;
    private static Method settleCompletion;
    private static Method completionExtras;
    // Balance phase 2: AscensionLib's Threat calls, each optional (an older library pays through the calls above).
    private static Method settleBossThreat;
    private static Method settleCompletionThreat;
    private static Method completionExtrasThreat;
    private static int tickCounter;

    private AscensionLibRewards() {}

    /** Wires the retry: once at server start, then on a timer. Called once at startup. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> retryPending(server, System.currentTimeMillis()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter < RETRY_TICKS) return;
            tickCounter = 0;
            try {
                retryPending(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("Retrying AscensionLib settlements failed", ex);
            }
        });
    }

    /**
     * The first floor of the segment a milestone boss pays for: the floor after the previous milestone, or 1.
     * @param isMilestone whether a run floor is a milestone floor
     */
    public static int segmentStart(IntPredicate isMilestone, int bossFloor) {
        for (int floor = bossFloor - 1; floor >= 1; floor--) {
            if (isMilestone.test(floor)) return floor + 1;
        }
        return 1;
    }

    /** Pays a milestone boss victory from the library's bands to {@code players}, every member of the run. */
    public static void settleMilestone(MinecraftServer server, UUID encounterId, String outcome, int fromFloor,
                                       int bossFloor, Collection<UUID> players, long now) {
        settleMilestone(server, encounterId, outcome, fromFloor, bossFloor, players, now, 0);
    }

    /** As above for a fight at a Threat band (1-7; 0 for none). */
    public static void settleMilestone(MinecraftServer server, UUID encounterId, String outcome, int fromFloor,
                                       int bossFloor, Collection<UUID> players, long now, int threat) {
        // Flushed to disk before the attempt: this is real value, and a crash must not forget it is owed.
        submit(server, new PendingLibSettlement(encounterId, PendingLibSettlement.Kind.MILESTONE, outcome, fromFloor,
                bossFloor, false, List.copyOf(players), now, 0).withThreat(threat), true);
    }

    /**
     * Rolls the Scouter drop for a cleared floor, once per player. Not flushed first: a crash costs at most a small
     * roll.
     */
    public static void settleScouterDrops(MinecraftServer server, UUID encounterId, String outcome, boolean keenEyeFloor,
                                          Collection<UUID> players, long now) {
        submit(server, new PendingLibSettlement(encounterId, PendingLibSettlement.Kind.SCOUTER_DROPS, outcome, 0, 0,
                keenEyeFloor, List.copyOf(players), now, 0), false);
    }

    /**
     * Pays a won Trial at its rank from the library's trial bands. Flushed first because it is real value; the rank
     * rides in the boss-floor field.
     */
    public static void settleTrial(MinecraftServer server, UUID encounterId, String outcome, int rank,
                                   Collection<UUID> players, long now) {
        submit(server, new PendingLibSettlement(encounterId, PendingLibSettlement.Kind.TRIAL, outcome, 0, rank, false,
                List.copyOf(players), now, 0), true);
    }

    /** Whether the installed library can pay a completion chest (an older one pays the milestone formula instead). */
    public static boolean supportsCompletion() {
        return resolve(PendingLibSettlement.Kind.COMPLETION) != null;
    }

    /**
     * Pays a tower cycle's champion as a completion chest: a guaranteed pile, a quality tier from the run's challenge cards, Ascension cycle and
     * whether it was flawless, and a crown. Flushed first because it is real value. The armor pieces and booster packs the crown rolls are handed
     * over from the tower's own reward table once the library confirms the payment.
     */
    public static void settleCompletion(MinecraftServer server, UUID encounterId, String outcome, int bossFloor, int cycle,
                                        int challengeCards, boolean flawless, String towerId, Collection<UUID> players, long now) {
        settleCompletion(server, encounterId, outcome, bossFloor, cycle, challengeCards, flawless, towerId, players, now, 0);
    }

    /** As above for a champion fought at a Threat band (1-7; 0 for none): the library pays the band's chest when it has Threat on. */
    public static void settleCompletion(MinecraftServer server, UUID encounterId, String outcome, int bossFloor, int cycle,
                                        int challengeCards, boolean flawless, String towerId, Collection<UUID> players, long now, int threat) {
        submit(server, new PendingLibSettlement(encounterId, PendingLibSettlement.Kind.COMPLETION, outcome, challengeCards, bossFloor,
                flawless, List.copyOf(players), now, 0, cycle, towerId).withThreat(threat), true);
    }

    /** Tries every unconfirmed settlement again; gives up on, and says so for, any older than a week. */
    public static void retryPending(MinecraftServer server, long now) {
        TowerLibSettlementStore store = TowerLibSettlementStore.get(server);
        if (store.size() == 0 || resolve(PendingLibSettlement.Kind.MILESTONE) == null) return;
        for (PendingLibSettlement settlement : store.all()) {
            if (settlement.expired(now)) {
                store.remove(settlement);
                TowerLog.error("Giving up on AscensionLib {} for encounter {} after {} attempt(s); still owed: {}",
                        settlement.kind(), settlement.encounterId(), settlement.attempts(), settlement.players());
                continue;
            }
            attempt(server, store, settlement);
        }
    }

    private static void submit(MinecraftServer server, PendingLibSettlement settlement, boolean flush) {
        // Not installed (or a contract that does not match): there is nothing to pay and nothing to remember.
        if (resolve(PendingLibSettlement.Kind.MILESTONE) == null) return;
        TowerLibSettlementStore store = TowerLibSettlementStore.get(server);
        store.put(settlement);
        if (flush) store.checkpoint(server);
        attempt(server, store, settlement);
    }

    /** One call to the library, then the entry is trimmed to whoever is still owed (or removed). */
    private static void attempt(MinecraftServer server, TowerLibSettlementStore store, PendingLibSettlement submitted) {
        PendingLibSettlement settlement = submitted;
        Map<?, ?> statuses = invoke(submitted);
        // The completion's armor and packs, in this order so a crash anywhere repeats nothing and loses nothing: (1) queue them for each newly paid player and
        // write the queue; (2) write the settlement marked "extras paid" for those players; only then (3) hand the queue over. A crash before (2) leaves the items
        // queued and undelivered, so the retry finds them there (addIfAbsent); a crash after (2) is skipped by the mark.
        java.util.List<UUID> toDeliver = java.util.List.of();
        if (statuses != null && settlement.kind() == PendingLibSettlement.Kind.COMPLETION) {
            toDeliver = queueExtras(server, settlement, statuses);
            if (!toDeliver.isEmpty()) {
                settlement = settlement.withExtrasPaid(toDeliver);
                store.put(settlement);
                store.checkpoint(server);
                deliverQueued(server, toDeliver);
            }
        }
        final PendingLibSettlement settled = settlement;
        Optional<PendingLibSettlement> remaining = settled.afterAttempt(statuses);
        remaining.ifPresentOrElse(store::put, () -> store.remove(settled));
        if (statuses != null) {
            statuses.forEach((player, status) -> {
                // GRANTED and ALREADY_GRANTED are the normal answers; anything else is worth one line.
                // A retry that fails the same way again is not said again, or a refused wallet fills the log.
                boolean fresh = settled.attempts() == 0 || remaining.isEmpty();
                if (fresh && !"GRANTED".equals(String.valueOf(status)) && !"ALREADY_GRANTED".equals(String.valueOf(status))) {
                    TowerLog.warn("AscensionLib {} for encounter {}: player {} -> {}{}", settled.kind(),
                            settled.encounterId(), player, status,
                            remaining.isPresent() ? " (will retry)" : "");
                }
            });
        }
    }

    /**
     * Queues each paid player's armor pieces and booster packs from the tower's reward table (skipping anyone already marked in {@code extrasPaid}) and writes the queue
     * to disk. Returns the players it queued for; delivery is a separate step, after the settlement records them.
     */
    private static java.util.List<UUID> queueExtras(MinecraftServer server, PendingLibSettlement settlement, Map<?, ?> statuses) {
        var queued = new java.util.ArrayList<UUID>();
        try {
            var content = com.cobbletowers.definition.TowerDefinitionRegistry.content();
            var towerId = net.minecraft.resources.ResourceLocation.tryParse(settlement.towerId());
            var tower = towerId == null ? null : content.towers().get(towerId);
            var table = tower == null ? null : content.rewardTables().get(tower.rewardTableId());
            if (table == null) {
                TowerLog.warn("Completion {}: tower {} or its reward table is gone; no armor or packs handed over", settlement.encounterId(), settlement.towerId());
                return queued;
            }
            var queue = com.cobbletowers.persistence.TowerPendingRewardStore.get(server);
            long now = System.currentTimeMillis();
            for (UUID player : settlement.players()) {
                if (settlement.extrasPaid().contains(player)) continue;
                String status = String.valueOf(statuses.get(player));
                if (!"GRANTED".equals(status) && !"ALREADY_GRANTED".equals(status)) continue;
                Map<?, ?> extras = extras(settlement, player);
                if (extras == null) continue;
                int armor = extras.get("armor") instanceof Number n ? n.intValue() : 0;
                int tickets = extras.get("tickets") instanceof Number n ? n.intValue() : 0;
                for (var item : CompletionExtras.pick(table, settlement.encounterId(), player, armor, tickets)) {
                    queue.addIfAbsent(player, new com.cobbletowers.persistence.PendingTowerReward(settlement.encounterId(),
                            Math.max(1, settlement.bossFloor()), item, 1, now));
                }
                queued.add(player);
            }
            queue.checkpoint(server);
        } catch (RuntimeException ex) {
            TowerLog.error("Completion {}: queueing armor and packs failed; will retry", settlement.encounterId(), ex);
            return java.util.List.of();
        }
        return queued;
    }

    private static void deliverQueued(MinecraftServer server, java.util.List<UUID> players) {
        for (UUID player : players) {
            var online = server.getPlayerList().getPlayer(player);
            if (online == null) continue;
            try {
                com.cobbletowers.reward.RewardDelivery.deliver(server, online);
            } catch (RuntimeException ex) {
                TowerLog.error("Delivering completion rewards to {} failed; they stay queued for the next login", player, ex);
            }
        }
    }

    private static Map<?, ?> extras(PendingLibSettlement settlement, UUID player) {
        if (resolve(PendingLibSettlement.Kind.COMPLETION) == null || completionExtras == null) return null;
        try {
            // The same form as the settlement, so the armor and tickets match the chest that was paid.
            if (settlement.threat() > 0 && completionExtrasThreat != null && settleCompletionThreat != null)
                return completionExtrasThreat.invoke(null, settlement.encounterId(), player, settlement.cycle(), settlement.fromFloor(),
                        settlement.keenEyeFloor(), settlement.threat()) instanceof Map<?, ?> map ? map : null;
            return completionExtras.invoke(null, settlement.encounterId(), player, settlement.cycle(), settlement.fromFloor(),
                    settlement.keenEyeFloor()) instanceof Map<?, ?> map ? map : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("AscensionLib completion extras failed for {}", player, ex);
            return null;
        }
    }

    /** The library's per-player statuses, or null when the call could not be made or threw (nothing confirmed). */
    private static Map<?, ?> invoke(PendingLibSettlement settlement) {
        Method method = resolve(settlement.kind());
        if (method == null) return null;
        try {
            boolean threat = settlement.threat() > 0;
            Object result = switch (settlement.kind()) {
                case MILESTONE -> threat && settleBossThreat != null
                        ? settleBossThreat.invoke(null, settlement.encounterId(), settlement.outcome(), settlement.fromFloor(),
                                settlement.bossFloor(), settlement.players(), settlement.threat())
                        : method.invoke(null, settlement.encounterId(), settlement.outcome(), settlement.fromFloor(),
                                settlement.bossFloor(), settlement.players());
                case COMPLETION -> threat && settleCompletionThreat != null && completionExtrasThreat != null
                        ? settleCompletionThreat.invoke(null, settlement.encounterId(), settlement.outcome(), settlement.bossFloor(),
                                settlement.cycle(), settlement.fromFloor(), settlement.keenEyeFloor(), settlement.players(), settlement.threat())
                        : method.invoke(null, settlement.encounterId(), settlement.outcome(), settlement.bossFloor(),
                                settlement.cycle(), settlement.fromFloor(), settlement.keenEyeFloor(), settlement.players());
                case TRIAL -> method.invoke(null, settlement.encounterId(), settlement.outcome(), settlement.bossFloor(),
                        settlement.players());
                case SCOUTER_DROPS -> method.invoke(null, settlement.encounterId(), settlement.outcome(),
                        settlement.keenEyeFloor(), settlement.players());
            };
            return result instanceof Map<?, ?> statuses ? statuses : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("AscensionLib {} for encounter {} could not be settled; will retry",
                    settlement.kind(), settlement.encounterId(), ex);
            return null;
        }
    }

    /** Resolved once; null when the mod is absent or its contract is not what this expects (said in the log). */
    private static Method resolve(PendingLibSettlement.Kind kind) {
        if (!resolved) {
            resolved = true;
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
            try {
                Class<?> rewards = Class.forName(REWARDS);
                settleBoss = rewards.getMethod("settleTowerBoss", UUID.class, String.class, int.class, int.class,
                        Collection.class);
                settleScouters = rewards.getMethod("settleScouterDrops", UUID.class, String.class, boolean.class,
                        Collection.class);
                try {
                    // Newer than the others: an older library still pays bosses and Scouters, and trials simply pay
                    // nothing.
                    settleTrial = rewards.getMethod("settleTrial", UUID.class, String.class, int.class, Collection.class);
                } catch (NoSuchMethodException missing) {
                    settleTrial = null;
                }
                try {
                    // Newer still: without it the champion pays the milestone formula.
                    settleCompletion = rewards.getMethod("settleTowerCompletion", UUID.class, String.class, int.class, int.class,
                            int.class, boolean.class, Collection.class);
                    completionExtras = rewards.getMethod("towerCompletionExtras", UUID.class, UUID.class, int.class, int.class, boolean.class);
                } catch (NoSuchMethodException missing) {
                    settleCompletion = null;
                    completionExtras = null;
                }
                try {
                    // Balance phase 2 (AscensionLib balance phase 1): pay by Threat band. Missing, a Threat run pays through the calls above.
                    settleBossThreat = rewards.getMethod("settleTowerBoss", UUID.class, String.class, int.class, int.class, Collection.class, int.class);
                    settleCompletionThreat = rewards.getMethod("settleTowerCompletion", UUID.class, String.class, int.class, int.class,
                            int.class, boolean.class, Collection.class, int.class);
                    completionExtrasThreat = rewards.getMethod("towerCompletionExtras", UUID.class, UUID.class, int.class, int.class, boolean.class, int.class);
                } catch (NoSuchMethodException missing) {
                    settleBossThreat = null;
                    settleCompletionThreat = null;
                    completionExtrasThreat = null;
                }
            } catch (ReflectiveOperationException | LinkageError ex) {
                settleBoss = null;
                settleScouters = null;
                settleTrial = null;
                settleCompletion = null;
                completionExtras = null;
                settleBossThreat = null;
                settleCompletionThreat = null;
                completionExtrasThreat = null;
                TowerLog.error("AscensionLib is installed but " + REWARDS + " does not match what CobbleTowers expects",
                        ex);
            }
        }
        return switch (kind) {
            case MILESTONE -> settleBoss;
            case TRIAL -> settleTrial;
            case SCOUTER_DROPS -> settleScouters;
            case COMPLETION -> settleCompletion;
        };
    }
}
