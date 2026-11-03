package com.cobbletowers.battle.cobbleraids;

import com.cobbletowers.encounter.TowerLevelPolicy;
import com.cobbletowers.CobbleTowers;
import com.cobbleraids.api.encounter.CobbleRaidsEncounters;
import com.cobbleraids.api.encounter.EncounterListener;
import com.cobbleraids.api.encounter.EncounterPolicy;
import com.cobbleraids.api.encounter.EncounterRules;
import com.cobbleraids.api.encounter.EncounterRequest;
import com.cobbleraids.api.encounter.EncounterOutcome;
import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbleraids.api.encounter.LeaveReason;
import com.cobbleraids.api.encounter.StartResult;
import com.cobbletowers.TowerLog;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.encounter.BossDraw;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The boss half of a floor, run by CobbleRaids (TDS #52, #53). The only place that names the CobbleRaids API; the
 * shared fight is the boss.
 */
public final class TowerBossAdapter {

    /** Who owns these encounters, so CobbleRaids can tell a tower boss from a wild raid. */
    private static final ResourceLocation OWNER = CobbleTowers.id("floor");

    /** No catching, no raid rewards, no raid progression; damage and PP carry back to the party (TDS #16). */
    static final EncounterPolicy POLICY = EncounterPolicy.none().withCarryover(true, true);

    /** What a running boss belongs to. Ids only; a live reference here would pin its level. */
    public record Binding(UUID runId, int floorIndex, ResourceLocation definition, int level) {}

    private static final Map<UUID, Binding> BY_ENCOUNTER = new HashMap<>();
    private static final Map<UUID, UUID> ENCOUNTER_BY_RUN = new HashMap<>();

    private TowerBossAdapter() {}

    /** What happens when a boss ends. Implemented by the floor, called by this. */
    public interface Listener {
        void onBossEnded(MinecraftServer server, Binding binding, EncounterResult result);

        default void onParticipantLeft(MinecraftServer server, Binding binding, UUID playerId, LeaveReason reason) {}
    }

    private static Listener listener = new Listener() {
        @Override
        public void onBossEnded(MinecraftServer server, Binding binding, EncounterResult result) {}
    };

    public static void install(Listener floorListener) {
        listener = floorListener;
        com.cobbletowers.battle.cobblemon.CobblemonBattleAdapter.setSharedBattleDeparture(TowerBossAdapter::withdrawParticipant);
    }

    /** Handles an individual exit without terminating the teammates' shared Cobblemon battle. */
    private static boolean withdrawParticipant(ServerPlayer player) {
        for (UUID encounterId : List.copyOf(BY_ENCOUNTER.keySet())) {
            if (CobbleRaidsEncounters.withdraw(encounterId, player)) return true;
        }
        return false;
    }

    /** The withdrawn actor remains registered, but its frozen clone team can no longer fight. */
    public static boolean hasWithdrawnParticipant(ServerPlayer player) {
        for (UUID encounterId : List.copyOf(BY_ENCOUNTER.keySet())) {
            if (CobbleRaidsEncounters.hasWithdrawnParticipant(encounterId, player)) return true;
        }
        return false;
    }

    /**
     * Starts this floor's boss against everybody still standing.
     * @return the encounter id, empty if CobbleRaids refused (a technical fault, never a loss)
     */
    public static Optional<UUID> start(MinecraftServer server, ServerLevel level, List<ServerPlayer> players,
                                       BossDraw.Boss boss, BlockPos where, UUID runId, int floorIndex) {
        return start(server, level, players, boss, where, runId, floorIndex, ModifierEffects.NONE);
    }

    /** The same start under the rules a run has drafted (TDS #2). Only the boss is fought under them. */
    public static Optional<UUID> start(MinecraftServer server, ServerLevel level, List<ServerPlayer> players,
                                       BossDraw.Boss boss, BlockPos where, UUID runId, int floorIndex,
                                       ModifierEffects effects) {
        return start(server, level, players, boss, where, runId, floorIndex, effects, id -> {});
    }

    /**
     * The same start, calling {@code beforeStart} with the encounter id first so the caller can declare the boss to
     * AscensionLib.
     */
    public static Optional<UUID> start(MinecraftServer server, ServerLevel level, List<ServerPlayer> players,
                                       BossDraw.Boss boss, BlockPos where, UUID runId, int floorIndex,
                                       ModifierEffects effects, java.util.function.Consumer<UUID> beforeStart) {
        return start(server, level, players, boss, where, runId, floorIndex, effects, beforeStart, new com.google.gson.JsonArray());
    }

    /** The same start, also arming the boss's own phases ({@code bossOps}, logical operations: side foe is the boss) for the first player only. */
    public static Optional<UUID> start(MinecraftServer server, ServerLevel level, List<ServerPlayer> players,
                                       BossDraw.Boss boss, BlockPos where, UUID runId, int floorIndex,
                                       ModifierEffects effects, java.util.function.Consumer<UUID> beforeStart,
                                       com.google.gson.JsonArray bossOps) {
        if (players.isEmpty()) return Optional.empty();
        if (players.size() > EncounterRequest.MAX_PLAYERS) {
            TowerLog.error("Floor {} of run {} has {} players; CobbleRaids takes at most {}",
                    floorIndex, runId, players.size(), EncounterRequest.MAX_PLAYERS);
            return Optional.empty();
        }

        UUID encounterId = UUID.randomUUID();
        try {
            beforeStart.accept(encounterId);
        } catch (RuntimeException ex) {
            TowerLog.error("Declaring the boss of floor {} of run {} threw; it fights without scouting", floorIndex, runId, ex);
        }
        EncounterRules rules = rulesFor(effects, floorIndex);
        // Said from this side as well as CobbleRaids', so a rule that goes missing can be pinned to
        // the boundary it went missing at rather than argued about from one end (TDS #60).
        TowerLog.info("Floor {} of run {} starts its boss {}", floorIndex, runId,
                rules.restrictsAnything() ? "under " + rules : "under ordinary rules");
        EncounterRequest request = new EncounterRequest(OWNER, encounterId, players, boss.definition(), level,
                Vec3.atBottomCenterOf(where), boss.level(),
                // No health override: CobbleRaids scales the pool from its own definition and the
                // party size, which is the behaviour the spike measured.
                OptionalLong.empty(), POLICY, rules);

        List<java.util.UUID> playerIds = players.stream().map(ServerPlayer::getUUID).toList();
        StartResult result;
        // Armed only around the start: the effects ride this boss battle's >start and no other's.
        com.cobbletowers.showdown.TowerBattleFx.armBossBattle(playerIds, id -> players.stream().filter(p -> p.getUUID().equals(id)).findFirst()
                    .map(p -> {
                        boolean first = p.getUUID().equals(playerIds.get(0));
                        com.google.gson.JsonArray ops = com.cobbletowers.armor.ArmorBonusEffects.battleEffects(p, runId, first);
                        if (first) bossOps.forEach(ops::add);   // once per battle, whatever the party size
                        return ops;
                    }).orElseGet(com.google.gson.JsonArray::new));
        com.cobbletowers.economy.AscensionLibScouting.arm(playerIds, encounterId.toString());
        try {
            result = CobbleRaidsEncounters.start(request, new FloorListener(server));
        } catch (RuntimeException ex) {
            TowerLog.error("Starting the boss for floor {} of run {} threw", floorIndex, runId, ex);
            com.cobbletowers.economy.AscensionLibScouting.end(encounterId.toString());
            return Optional.empty();
        } finally {
            com.cobbletowers.showdown.TowerBattleFx.disarmAll(playerIds);
            com.cobbletowers.economy.AscensionLibScouting.disarm(playerIds);
        }
        if (result instanceof StartResult.Refused refused) {
            TowerLog.error("CobbleRaids refused the boss for floor {} of run {}: {}",
                    floorIndex, runId, refused.reason());
            com.cobbletowers.economy.AscensionLibScouting.end(encounterId.toString());
            return Optional.empty();
        }

        UUID started = ((StartResult.Started) result).encounterId();
        Binding binding = new Binding(runId, floorIndex, boss.definition(), boss.level());
        BY_ENCOUNTER.put(started, binding);
        ENCOUNTER_BY_RUN.put(runId, started);
        TowerLog.info("Floor {} boss {} started at level {} for {} player(s), encounter {}",
                floorIndex, boss.definition(), boss.level(), players.size(), started);
        return Optional.of(started);
    }

    /** A run's drafted effects as CobbleRaids rules, with the floor's own softening of the boss's health folded in. */
    private static EncounterRules rulesFor(ModifierEffects effects, int floorIndex) {
        int health = Math.max(1, effects.bossHealthPercent() * TowerLevelPolicy.bossHealthPercent(floorIndex) / 100);
        if (!effects.changesBattleRules() && health == 100) return EncounterRules.none();
        return EncounterRules.none()
                .withBannedMoves(effects.bannedMoves())
                .withSwitching(effects.switchingAllowed())
                .withItems(effects.itemsAllowed())
                .withWeather(effects.weather())
                .withTerrain(effects.terrain())
                .withHealthPercent(health);
    }

    /** Ends a run's boss without it being fought out: abandoning, parking, shutting down. */
    public static boolean abort(UUID runId) {
        UUID encounterId = ENCOUNTER_BY_RUN.remove(runId);
        if (encounterId == null) return false;
        BY_ENCOUNTER.remove(encounterId);
        com.cobbletowers.economy.AscensionLibScouting.end(encounterId.toString());
        try {
            // CobbleRaids removes the boss itself on abort, which is what keeps the cell clean enough
            // for P3's sweep to release it rather than quarantine it.
            return CobbleRaidsEncounters.abort(encounterId);
        } catch (RuntimeException ex) {
            TowerLog.error("Aborting the boss of run {} threw", runId, ex);
            return false;
        }
    }

    /** Operator tool: removes a run's boss and tells the floor it was beaten, as if the shared pool had reached zero. */
    public static boolean forceVictory(MinecraftServer server, UUID runId) {
        UUID encounterId = ENCOUNTER_BY_RUN.remove(runId);
        if (encounterId == null) return false;
        Binding binding = BY_ENCOUNTER.remove(encounterId);
        com.cobbletowers.economy.AscensionLibScouting.end(encounterId.toString());
        try {
            // Removed from the maps first, so the abort's own ended callback finds nothing to settle twice.
            CobbleRaidsEncounters.abort(encounterId);
        } catch (RuntimeException ex) {
            TowerLog.error("Removing the boss of run {} threw", runId, ex);
        }
        if (binding == null) return false;
        listener.onBossEnded(server, binding,
                new EncounterResult(encounterId, EncounterOutcome.VICTORY, Map.of(), java.util.Set.of(), 0));
        return true;
    }

    public static boolean isFighting(UUID runId) {
        UUID encounterId = ENCOUNTER_BY_RUN.get(runId);
        return encounterId != null && CobbleRaidsEncounters.isActive(encounterId);
    }

    public static Optional<Binding> of(UUID runId) {
        UUID encounterId = ENCOUNTER_BY_RUN.get(runId);
        return encounterId == null ? Optional.empty() : Optional.ofNullable(BY_ENCOUNTER.get(encounterId));
    }

    public static int active() {
        return BY_ENCOUNTER.size();
    }

    public static int onServerStopped() {
        int held = BY_ENCOUNTER.size();
        BY_ENCOUNTER.clear();
        ENCOUNTER_BY_RUN.clear();
        return held;
    }

    /** Routes CobbleRaids' callbacks back to the floor that asked for the boss. */
    private record FloorListener(MinecraftServer server) implements EncounterListener {

        @Override
        public void onParticipantLeft(UUID encounterId, UUID playerId, LeaveReason reason) {
            Binding binding = BY_ENCOUNTER.get(encounterId);
            if (binding == null) return;
            try {
                listener.onParticipantLeft(server, binding, playerId, reason);
            } catch (RuntimeException ex) {
                // Contained for the same reason the Cobblemon adapter's handler is: this is called
                // from the other mod's battle path, and throwing back into it costs more than a floor.
                TowerLog.error("Handling a player leaving the boss of run {} threw", binding.runId(), ex);
            }
        }

        @Override
        public void onEnded(EncounterResult result) {
            Binding binding = BY_ENCOUNTER.remove(result.encounterId());
            if (binding == null) return;
            com.cobbletowers.economy.AscensionLibScouting.end(result.encounterId().toString());
            ENCOUNTER_BY_RUN.remove(binding.runId(), result.encounterId());
            try {
                listener.onBossEnded(server, binding, result);
            } catch (RuntimeException ex) {
                TowerLog.error("Handling the end of the boss of run {} threw", binding.runId(), ex);
            }
        }
    }
}
