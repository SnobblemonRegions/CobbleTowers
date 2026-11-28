package com.cobbletowers.echo;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.battle.cobblemon.RentalStorage;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerEchoStore;
import com.cobbletowers.persistence.TowerLeaderboardStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Echoes at run time (P35): records a top-ten regional team as an Echo, drops Echoes that leave the top ten and fills
 * an opponent slot with an Echo's Pokemon. Rules are in {@link EchoPolicy}.
 */
public final class EchoService {

    private EchoService() {}

    /** A regional tower is one with a regional theme; Neutral and the Test tower never record or serve Echoes. */
    static boolean regional(ResourceLocation towerId) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(towerId);
        return tower != null && tower.regionalTheme().isPresent();
    }

    /**
     * Called after a run's boards change: records an Echo for each online, non-opted-out player of a top-ten run,
     * then drops Echoes whose run is gone.
     */
    public static void refresh(MinecraftServer server, PersistedRun run) {
        boolean trial = run.options().trial().isPresent();
        if (!EchoPolicy.applies(regional(run.towerId()), trial)) return;
        TowerEchoStore echoes = TowerEchoStore.get(server);
        // The season on view (P36c): the running one, or the one just ended in an off-season; 0 with seasons off or
        // not yet begun.
        int season = com.cobbletowers.season.Seasons.viewNumber().orElse(0);
        Set<UUID> top = EchoPolicy.topRuns(TowerLeaderboardStore.get(server).all(), run.towerId(),
                season > 0 ? com.cobbletowers.season.SeasonSchedule.idOf(season) : "");
        if (top.contains(run.runId())) {
            for (PersistedParticipant participant : run.participants()) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player == null || echoes.isOptedOut(player.getUUID())) continue;
                List<String> team = teamOf(player);
                if (team.isEmpty()) continue;
                echoes.add(new Echo(UUID.randomUUID(), player.getUUID(), player.getGameProfile().getName(), run.towerId(),
                        run.runId(), team, System.currentTimeMillis(), 0, 0, season, routeLine(run)));
                player.sendSystemMessage(Component.literal("Your team made the top " + EchoPolicy.TOP_N + " of "
                        + run.towerId().getPath() + ": it is now an Echo other challengers may meet. "
                        + "Opt out any time in the Hall."));
                TowerLog.info("Recorded an Echo of {} ({} Pokemon) for run {}", player.getGameProfile().getName(),
                        team.size(), run.runId());
            }
        }
        int dropped = echoes.prune(run.towerId(), season, top);
        if (dropped > 0) TowerLog.info("{} Echo(es) of {} left the pool (no longer in the top {})", dropped,
                run.towerId(), EchoPolicy.TOP_N);
        echoes.checkpoint(server);
    }

    /** The stops the run took as one line, for the Echo to carry (P50); empty with no route. */
    static String routeLine(PersistedRun run) {
        var content = TowerDefinitionRegistry.content();
        return com.cobbletowers.route.RouteText.line(com.cobbletowers.route.RouteText.stops(run.modifiers(), place -> {
            TowerDefinition other = content.towers().get(place);
            return other == null ? place.getPath() : other.displayName().replaceFirst(" Tower$", "");
        }));
    }

    /** The player's live party as property strings, at most {@value EchoPolicy#MAX_TEAM}. */
    public static List<String> teamOf(ServerPlayer player) {
        List<String> team = new ArrayList<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (team.size() >= EchoPolicy.MAX_TEAM) break;
            team.add(RentalStorage.describe(pokemon));
        }
        return team;
    }

    /** The Echo and Pokemon fighter number {@code ordinal} of this run would face at {@code floorIndex}, if any. */
    public static Optional<EchoPolicy.Pick> duelOpponent(MinecraftServer server, PersistedRun run, int floorIndex, int ordinal) {
        if (!EchoPolicy.applies(regional(run.towerId()), run.options().trial().isPresent())) return Optional.empty();
        Set<UUID> present = new HashSet<>();
        for (PersistedParticipant participant : run.participants()) present.add(participant.playerId());
        int season = com.cobbletowers.season.Seasons.viewNumber().orElse(0);
        return EchoPolicy.pick(EchoPolicy.pool(TowerEchoStore.get(server).forTower(run.towerId()), season), present, run.seed(),
                floorIndex, ordinal);
    }

    /**
     * Whether an Echo Duel room can be offered now: a regional run outside a trial, with someone else's Echo to meet.
     */
    public static boolean duelAvailable(MinecraftServer server, PersistedRun run, int floorIndex) {
        if (com.cobbletowers.tutorial.Tutorial.isTutorial(run.towerId())) return false;    // no Echo Duel (it pays) in the replayable tutorial
        return duelOpponent(server, run, floorIndex, 0).isPresent();
    }

    /** Starts the duel: each fighter faces one Pokemon of an Echo, as an exhibition. Returns a line for the team. */
    public static String beginDuel(MinecraftServer server, PersistedRun run, int floorIndex) {
        int started = 0;
        int ordinal = 0;
        TowerEchoStore echoes = TowerEchoStore.get(server);
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            Optional<EchoPolicy.Pick> pick = duelOpponent(server, run, floorIndex, ordinal++);
            if (pick.isEmpty()) continue;
            if (com.cobbletowers.encounter.TowerEncounters.startEchoDuel(server, run, player, ordinal - 1, pick.get())) {
                EchoDuels.begin(run.runId(), player.getUUID(), pick.get().echo().id());
                echoes.recordFaced(pick.get().echo().id());
                player.sendSystemMessage(Component.literal("Echo Duel: " + pick.get().echo().name() + "'s "
                        + EchoPolicy.speciesOf(pick.get().properties()) + " steps up. A safe exhibition: your party is not touched."));
                started++;
            }
        }
        TowerLog.info("Run {} began an Echo duel at floor {} for {} player(s)", run.runId(), floorIndex, started);
        return started > 0 ? "The Echo Duel begins." : "No Echo could be found for the duel.";
    }
}
