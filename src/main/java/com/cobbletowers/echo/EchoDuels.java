package com.cobbletowers.echo;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.battle.cobblemon.CobblemonBattleAdapter;
import com.cobbletowers.persistence.TowerEchoStore;
import com.cobbletowers.persistence.TowerWalletStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Echo Duels in progress (P35): which players of which run are in an exhibition against which Echo. In memory only,
 * since a restart ends the battle anyway. A run cannot ready up, cash out or move on while a player duels; a
 * disconnected or finished player is dropped on the next look.
 */
public final class EchoDuels {

    /** Stands in for the Echo when the opponent is a Trick or Treat mimic: its result goes to the vault, not to an Echo's record. */
    public static final UUID MIMIC = new UUID(0x7121CC0FFEEL, 0x7121CC0FFEEL);

    private static final Map<UUID, Map<UUID, UUID>> PENDING = new HashMap<>();

    static {
        ServerState.onStop(PENDING::clear);
    }

    private EchoDuels() {}

    /** Records that {@code player} is duelling {@code echo}. */
    public static void begin(UUID runId, UUID player, UUID echo) {
        PENDING.computeIfAbsent(runId, id -> new LinkedHashMap<>()).put(player, echo);
    }

    /**
     * Whether anyone in the run is still in a duel; drops offline or finished players first, so a vanished battle
     * cannot block the intermission.
     */
    public static boolean active(MinecraftServer server, UUID runId) {
        Map<UUID, UUID> pending = PENDING.get(runId);
        if (pending == null) return false;
        pending.keySet().removeIf(id -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player == null || !CobblemonBattleAdapter.inBattle(player);
        });
        if (pending.isEmpty()) PENDING.remove(runId);
        return PENDING.containsKey(runId);
    }

    /** A duel battle ended (called by the encounter for an exhibition binding). */
    public static void onResolved(MinecraftServer server, CobblemonBattleAdapter.Binding binding, boolean playerWon) {
        Map<UUID, UUID> pending = PENDING.get(binding.runId());
        UUID echoId = pending == null ? null : pending.remove(binding.playerId());
        if (pending != null && pending.isEmpty()) PENDING.remove(binding.runId());
        if (MIMIC.equals(echoId)) {
            com.cobbletowers.trick.TrickOrTreatService.onMimicResolved(server, binding, playerWon);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(binding.playerId());
        TowerEchoStore echoes = TowerEchoStore.get(server);
        if (echoId != null) echoes.recordResult(echoId, !playerWon);
        if (playerWon) {
            TowerWalletStore.get(server).credit(binding.playerId(), EchoPolicy.DUEL_REWARD);
            com.cobbletowers.season.SeasonProgressService.award(server, binding.playerId(),
                    com.cobbletowers.season.SeasonPoints.Source.ECHO_DUEL, 0, false);
            if (player != null) {
                player.sendSystemMessage(Component.literal("You won the Echo Duel: +" + EchoPolicy.DUEL_REWARD
                        + " Tower Tokens. Your party was not touched."));
            }
        } else if (player != null) {
            player.sendSystemMessage(Component.literal("The Echo won this time. Nothing was lost: your party was not touched."));
        }
        TowerLog.info("Echo duel of run {} ended: {} {}", binding.runId(), binding.playerId(), playerWon ? "won" : "lost");
    }

    /** Ends every duel of a run (it left the intermission, was abandoned or cashed out). */
    public static void cancel(MinecraftServer server, UUID runId) {
        Map<UUID, UUID> pending = PENDING.remove(runId);
        if (pending == null) return;
        for (UUID id : pending.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) CobblemonBattleAdapter.endBattleOf(player);
        }
        TowerLog.info("Cancelled the Echo duel(s) of run {}", runId);
    }

    /** Forgets everything (server stop, tests). */
    public static void clear() {
        PENDING.clear();
    }
}
