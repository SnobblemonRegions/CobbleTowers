package com.cobbletowers.encounter;

import com.cobbletowers.ServerState;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbletowers.battle.cobblemon.CobblemonBattleAdapter;
import com.cobbletowers.battle.cobblemon.PartyReader;
import com.cobbletowers.battle.cobbleraids.TowerBossAdapter;
import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.diagnostics.TowerMetrics;
import com.cobbletowers.economy.AscensionLibScouting;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.api.tower.participant.ParticipantState;
import com.cobbletowers.runtime.ParticipantService;
import com.cobbletowers.runtime.RunExitService;
import com.cobbletowers.runtime.PartyValidation;
import com.cobbletowers.runtime.RunTransitionService;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.spectator.SpectatorPresentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import com.cobbletowers.definition.BossPoolDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A floor's prerequisite round: one opponent per player, all at once. The last player to clear resolves the floor;
 * everyone out loses the run.
 */
public final class TowerEncounters {

    public enum Status { FIGHTING, CLEARED, OUT }

    /** Which half of the floor is being fought. Kept here, not in the run transition table. */
    public enum Phase { PREREQUISITE, BOSS }

    /** One floor's round for one run. */
    public record Round(UUID runId, int floorIndex, Phase phase, Map<UUID, Status> byPlayer,
                        long startedAt) {

        public Round {
            byPlayer = Map.copyOf(byPlayer);
        }

        public boolean everyoneCleared() {
            return byPlayer.values().stream().allMatch(status -> status == Status.CLEARED);
        }

        public boolean everyoneOut() {
            return byPlayer.values().stream().allMatch(status -> status == Status.OUT);
        }

        public boolean settled() {
            return byPlayer.values().stream().noneMatch(status -> status == Status.FIGHTING);
        }
    }

    private static final Map<UUID, Round> ROUNDS = new LinkedHashMap<>();

    static {
        ServerState.onStop(TowerEncounters::onServerStopped);
    }

    private TowerEncounters() {}

    /** Wires this to the adapter. Called once at startup. */
    public static void install() {
        CobblemonBattleAdapter.install(TowerEncounters::onResolved);
        ArrivalGuard.install();
        TowerBossAdapter.install(new TowerBossAdapter.Listener() {
            @Override
            public void onBossEnded(MinecraftServer server, TowerBossAdapter.Binding binding,
                                    EncounterResult result) {
                FloorBoss.onBossEnded(server, binding, result);
            }
        });
    }

    /**
     * Starts one opponent per player who can fight. Empty means the floor cannot run at all (a technical fault, not a
     * loss).
     */
    public static Optional<Round> begin(MinecraftServer server, UUID runId) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return Optional.empty();
        Optional<FloorSetup> resolved = FloorSetup.resolve(server, found.get(), true);
        if (resolved.isEmpty()) return Optional.empty();
        FloorSetup setup = resolved.get();
        PersistedRun run = setup.run();

        List<ServerPlayer> fighters = fightersOf(server, run);
        if (fighters.isEmpty()) {
            TowerLog.error("Run {} has nobody able to fight floor {}", runId, run.floorIndex());
            return Optional.empty();
        }
        // Taken once, from everybody, before a single battle starts (TDS #45): a party that faints or
        // disconnects during the floor cannot make the rest of it easier.
        List<Integer> partyLevels = levelsOf(run, fighters);
        if (partyLevels.isEmpty()) {
            // Logged because an empty draw otherwise returned empty with no explanation.
            TowerLog.error("Run {} cannot start floor {}: none of its {} fighter(s) has a Pokemon to"
                    + " fight with, so no opponent can be levelled", runId, run.floorIndex(), fighters.size());
            return Optional.empty();
        }

        arrive(server, setup, fighters);
        return beginBoss(server, setup, fighters);
    }

    /**
     * Turns every fighter to face the boss's spot as the floor begins (TODO #2). Everyone is placed by now (they walked to the arena door, or the straggler
     * rule took them there), but a party that walked in stands facing whichever way it was walking, and Cobblemon sends the Pokemon out from the player.
     * Same place, new yaw: nobody is moved.
     */
    private static void faceTheBoss(FloorSetup setup, List<ServerPlayer> fighters) {
        BlockPos boss = setup.presentation(0);
        for (ServerPlayer player : fighters) {
            if (player.serverLevel() != setup.level()) continue;
            float yaw = ArenaFacing.yawToward(player.getX(), player.getZ(), boss.getX() + 0.5, boss.getZ() + 0.5, player.getYRot());
            player.teleportTo(setup.level(), player.getX(), player.getY(), player.getZ(), yaw, 0.0f);
        }
    }

    /** Every floor is its boss alone: everyone who can fight goes straight to the floor's boss. */
    private static Optional<Round> beginBoss(MinecraftServer server, FloorSetup setup, List<ServerPlayer> fighters) {
        PersistedRun run = setup.run();
        Map<UUID, Status> byPlayer = new LinkedHashMap<>();
        for (ServerPlayer player : fighters) byPlayer.put(player.getUUID(), Status.CLEARED);
        Round round = new Round(run.runId(), run.floorIndex(), Phase.PREREQUISITE, byPlayer, System.currentTimeMillis());
        faceTheBoss(setup, fighters);
        if (!FloorBoss.start(server, round, System.currentTimeMillis())) {
            TowerLog.error("Floor {} of run {}: the boss could not be started", run.floorIndex(), run.runId());
            return Optional.empty();
        }
        Round started = ROUNDS.get(run.runId());
        TowerLog.info("Floor {} of run {} begun against its boss with {} fighter(s)", run.floorIndex(), run.runId(), fighters.size());
        return Optional.ofNullable(started);
    }

    /** The run's participants who can fight and are online. */
    private static List<ServerPlayer> fightersOf(MinecraftServer server, PersistedRun run) {
        List<ServerPlayer> fighters = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) fighters.add(player);
        }
        return fighters;
    }

    /** Puts the party in the arena before anything is fought. */
    private static void arrive(MinecraftServer server, FloorSetup setup, List<ServerPlayer> fighters) {
        FloorAnchor entry = setup.layout().entry();
        // A floor built from nothing can stall the server; the point is checked (and its chunk loaded) before anyone is sent, and a player is watched after.
        BlockPos arrival = ArrivalGuard.standingSpot(setup.level(), entry.in(setup.origin()));
        for (int ordinal = 0; ordinal < fighters.size(); ordinal++) {
            ServerPlayer player = fighters.get(ordinal);
            // Where they stand now is where they go home to (P20); skipped if they are already inside.
            RunExitService.remember(server, player);
            // Somebody who climbed here on foot (a walkable tower) is already at the door of the arena: nobody is moved who is there.
            if (player.serverLevel() == setup.level() && com.cobbletowers.route.WalkRules.arrived(player.getX(), player.getY(), player.getZ(),
                    arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5)) continue;
            double x = arrival.getX() + 0.5 + ordinal * 2;
            player.teleportTo(setup.level(), x, arrival.getY(), arrival.getZ() + 0.5, entry.yaw(), 0.0f);
            ArrivalGuard.watch(server, player, setup.level(), x, arrival.getY(), arrival.getZ() + 0.5, entry.yaw());
            com.cobbletowers.route.LaneStairs.fadeIn(player);   // a change of floor is never a cut (P58)
        }
    }

    /**
     * Levels of every fighter's registered Pokemon, fainted ones included (TDS #45); falls back to the live party if
     * none registered.
     */
    static List<Integer> levelsOf(PersistedRun run, List<ServerPlayer> fighters) {
        List<Integer> levels = new ArrayList<>();
        for (ServerPlayer player : fighters) {
            List<UUID> registered = run.participants().stream()
                    .filter(participant -> participant.playerId().equals(player.getUUID()))
                    .findFirst().map(PersistedParticipant::registeredPokemon).orElse(List.of());
            levels.addAll(PartyValidation.levels(PartyReader.members(player), registered));
        }
        return levels;
    }

    /** The adapter calls this when one player's battle ends. */
    private static void onResolved(MinecraftServer server, CobblemonBattleAdapter.Binding binding, boolean playerWon) {
        // An Echo Duel (P35) is a bonus exhibition at an intermission: it is not the floor's, and no round is waiting
        // on it.
        if (binding.exhibition()) {
            com.cobbletowers.echo.EchoDuels.onResolved(server, binding, playerWon);
            return;
        }
        // Floors are bosses, which end through FloorBoss; nothing else is fought outside an exhibition.
    }

    /** Runs once nobody is still fighting, whether the last one lost, won or was dropped. */
    private static void settle(MinecraftServer server, Round round, long now) {
        if (!round.settled()) return;

        if (round.everyoneOut()) {
            ROUNDS.remove(round.runId());
            TowerLog.info("Floor {} of run {} wiped the party", round.floorIndex(), round.runId());
            lose(server, round.runId(), round.floorIndex(), now);
            return;
        }

        // Anyone still standing has earned the right to the boss. The floor is not finished until
        // that is fought: the prerequisite is a prerequisite, not the floor.
        if (!FloorBoss.start(server, round, now)) {
            ROUNDS.remove(round.runId());
            TowerLog.error("Floor {} of run {} cleared its opponents but the boss could not be started",
                    round.floorIndex(), round.runId());
            RunTransitionService.apply(server, round.runId(), RunEvent.TECHNICAL_FAILURE, now);
        }
    }

    /**
     * Starts an Echo Duel: one Pokemon of an Echo against a cloned, healed party.
     * @return whether the battle started
     */
    public static boolean startEchoDuel(MinecraftServer server, PersistedRun run, ServerPlayer player, int ordinal,
                                        com.cobbletowers.echo.EchoPolicy.Pick pick) {
        Optional<FloorSetup> resolved = FloorSetup.resolve(server, run, false);
        if (resolved.isEmpty()) return false;
        FloorSetup setup = resolved.get();
        Optional<EncounterSnapshot> drawn = exhibitionOpponent(server, setup, run, player, ordinal);
        if (drawn.isEmpty()) return false;
        EncounterSnapshot opponent = drawn.get().withEcho(pick.properties(), pick.echo().name());
        BlockPos where = setup.presentation(ordinal);
        // An exhibition is explicitly native: no enemy effects, not even a wild rating.
        return AscensionLibScouting.armed(List.of(player.getUUID()), null,
                () -> CobblemonBattleAdapter.startExhibition(setup.level(), player, opponent, where, run.runId(),
                        run.floorIndex())).isPresent();
    }

    /**
     * The opponent of an exhibition (an Echo Duel or a vault mimic): the floor's own boss draw, at the boss's level, as one Pokemon. An Echo then
     * swaps in its own Pokemon and the mimic wears its costume, so what matters here is the level and a real species to stand on.
     */
    private static Optional<EncounterSnapshot> exhibitionOpponent(MinecraftServer server, FloorSetup setup, PersistedRun run,
                                                                  ServerPlayer player, int ordinal) {
        Optional<BossPoolDefinition> pool = setup.floor().bossPoolId().map(id -> setup.content().bossPools().get(id)).filter(Objects::nonNull);
        Optional<BossDraw.Boss> boss = BossDraw.draw(pool, FloorBoss.handpickedBoss(setup.content(), setup.tower(), setup.floor()),
                run.seed(), run.floorIndex(), levelsOf(run, List.of(player)), setup.ruleset(), DraftService.effects(run).bossLevelOffset());
        if (boss.isEmpty()) return Optional.empty();
        Optional<String> species = com.cobbletowers.battle.cobbleraids.RaidSpecies.of(server, boss.get().definition());
        ResourceLocation id = species.map(ResourceLocation::tryParse).orElse(null);
        if (id == null) return Optional.empty();
        return Optional.of(new EncounterSnapshot(ordinal, id, List.of(), boss.get().level()));
    }

    /**
     * Starts a Trick or Treat mimic: one opponent drawn from this floor's own pool, at this player's level, fought as an exhibition (a cloned, healed
     * party), so nothing in the party is lost, only what the vault staked.
     * @param where where the mimic stands, in front of the vault
     * @return whether the battle started
     */
    public static boolean startMimicDuel(MinecraftServer server, PersistedRun run, ServerPlayer player, int ordinal, BlockPos where) {
        Optional<FloorSetup> resolved = FloorSetup.resolve(server, run, false);
        if (resolved.isEmpty()) return false;
        FloorSetup setup = resolved.get();
        Optional<EncounterSnapshot> drawn = exhibitionOpponent(server, setup, run, player, 40 + ordinal);
        if (drawn.isEmpty()) return false;
        // the vault's mimic wears its costume: Mimikyu at the level the floor drew
        EncounterSnapshot mimic = com.cobbletowers.trick.TrickRules.mimicOf(drawn.get());
        return AscensionLibScouting.armed(List.of(player.getUUID()), null,
                () -> CobblemonBattleAdapter.startExhibition(setup.level(), player, mimic, where, run.runId(), run.floorIndex())).isPresent();
    }

    /**
     * Takes one player out of the floor and lets it carry on; settles the floor if they were the last fighter.
     * @return true when a floor had them
     */
    public static boolean dropPlayer(MinecraftServer server, UUID runId, UUID playerId, String why, long now) {
        CobblemonBattleAdapter.endPlayer(server, runId, playerId);
        Round round = ROUNDS.get(runId);
        if (round == null || !round.byPlayer().containsKey(playerId)) return false;
        if (round.byPlayer().get(playerId) != Status.FIGHTING) return false;

        Map<UUID, Status> byPlayer = new LinkedHashMap<>(round.byPlayer());
        byPlayer.put(playerId, Status.OUT);
        Round updated = new Round(round.runId(), round.floorIndex(), round.phase(), byPlayer,
                round.startedAt());
        ROUNDS.put(runId, updated);
        TowerLog.info("Player {} dropped from floor {} of run {}: {}", playerId, round.floorIndex(), runId, why);

        settle(server, updated, now);
        return true;
    }

    /**
     * Marks a player knocked out. KNOCKED_OUT is true at once; SPECTATING_TEAM only once they stand somewhere
     * watching.
     */
    private static void knockOut(MinecraftServer server, UUID runId, UUID playerId, long now) {
        ParticipantService.update(server, runId, playerId, ParticipantState::knockedOut, now);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        TowerRuns.get(runId).ifPresent(run -> {
            if (sendToSpectatorAnchor(server, run, player)) {
                ParticipantService.update(server, runId, playerId, ParticipantState::spectating, now)
                        .ifPresent(updated -> SpectatorPresentation.startSpectating(server, updated, player));
            }
        });
    }

    /** Every floor being fought right now, for the watchdog to judge. */
    public static List<Round> activeRounds() {
        return List.copyOf(ROUNDS.values());
    }

    /** Stands a player on the floor's spectator anchor. @return false when the floor has none */
    public static boolean sendToSpectatorAnchor(MinecraftServer server, PersistedRun run, ServerPlayer player) {
        Optional<FloorDefinition> floor = TowerDefinitionRegistry.content().floorAt(run.towerId(), run.floorIndex());
        ServerLevel level = TowerDimension.level(server);
        Optional<com.cobbletowers.definition.FloorLayout> layout = com.cobbletowers.route.FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (floor.isEmpty() || layout.isEmpty() || level == null || run.cell().isEmpty()) return false;

        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        if (origin.isEmpty()) return false;

        FloorAnchor anchor = layout.get().spectator();
        BlockPos where = anchor.in(origin.get());
        player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, anchor.yaw(), 0.0f);
        com.cobbletowers.route.LaneStairs.fadeIn(player);
        return true;
    }

    /**
     * Operator tool: wins the run's current floor. Each open opponent battle ends as a player win through the normal
     * result path, then the boss is ended as a victory, so rewards, banking and the intermission follow as usual.
     * @return what was done, or empty when the run has no floor under way
     */
    public static Optional<String> winFloor(MinecraftServer server, UUID runId) {
        if (!ROUNDS.containsKey(runId)) return Optional.empty();
        int opponents = 0;
        // Bounded: a multi-opponent floor sends the next one from inside onResolved.
        for (int pass = 0; pass < 64; pass++) {
            Round round = ROUNDS.get(runId);
            if (round == null || round.phase() != Phase.PREREQUISITE) break;
            List<CobblemonBattleAdapter.Binding> open = CobblemonBattleAdapter.battlesOf(runId).stream()
                    .filter(b -> !b.exhibition()).toList();
            if (open.isEmpty()) break;
            for (CobblemonBattleAdapter.Binding binding : open) {
                if (!CobblemonBattleAdapter.endPlayer(server, runId, binding.playerId())) continue;
                opponents++;
                onResolved(server, binding, true);
            }
        }
        Round round = ROUNDS.get(runId);
        boolean boss = round != null && round.phase() == Phase.BOSS && TowerBossAdapter.forceVictory(server, runId);
        if (boss) endParticipantBattles(server, runId);
        if (!boss && ROUNDS.containsKey(runId)) return Optional.of("Could not finish the floor (" + opponents + " opponent(s) won)");
        return Optional.of("Floor won: " + opponents + " opponent battle(s)" + (boss ? " and the boss" : ""));
    }

    /** The run is lost. The unclaimed pool is marked, not deleted, so it can still be read. */
    static void lose(MinecraftServer server, UUID runId, int floorIndex, long now) {
        TowerLog.info("Floor {} of run {} wiped the party; the unclaimed pool is forfeited",
                floorIndex, runId);
        TowerRuns.get(runId).ifPresent(run -> earn(server, runId,
                LedgerEntry.forfeited(floorIndex, run.towerId(), now)));
        // Seen on the rig: a party that all disconnected mid-floor is dropped from the boss fight by its reconnect grace and lands here as a
        // wipe, before the tower's own window would have said "disconnect". Nobody online means nobody lost the fight.
        TowerRuns.get(runId).filter(run -> run.participants().stream()
                        .noneMatch(p -> server.getPlayerList().getPlayer(p.playerId()) != null))
                .ifPresent(run -> com.cobbletowers.report.TowerTelemetry.endCause(runId, "disconnect"));
        RunTransitionService.apply(server, runId, RunEvent.ENCOUNTER_RESOLVED_WIPED, now);
    }


    /** Adds an entry to a run's unclaimed pool. Public for the operator {@code runs earn} command, a test seam. */
    public static void earn(MinecraftServer server, UUID runId, LedgerEntry entry) {
        TowerRuns.get(runId).ifPresent(run -> TowerRuns.save(server, run.withEarned(entry, entry.at()), false));
    }

    static void putRound(Round round) {
        ROUNDS.put(round.runId(), round);
    }

    static Round removeRound(UUID runId) {
        return ROUNDS.remove(runId);
    }

    public static Optional<Round> of(UUID runId) {
        return Optional.ofNullable(ROUNDS.get(runId));
    }

    public static int active() {
        return ROUNDS.size();
    }

    /** Ends a run's floor without resolving it: abandoning, parking, shutting down. */
    public static void abandon(MinecraftServer server, UUID runId) {
        ROUNDS.remove(runId);
        CobblemonBattleAdapter.endRun(server, runId);
        // The boss too, or it stands in the cell until the sweep quarantines it.
        TowerBossAdapter.abort(runId);
        // Aborting the boss does not end the player's battle with it; end it here so they can fight again.
        endParticipantBattles(server, runId);
        recallParties(server, runId);
    }

    /** Ends whatever Cobblemon battle each of a run's online participants is still in (see {@code abandon}). */
    private static void endParticipantBattles(MinecraftServer server, UUID runId) {
        TowerRuns.get(runId).ifPresent(run -> {
            for (PersistedParticipant participant : run.participants()) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player != null) CobblemonBattleAdapter.endBattleOf(player);
            }
        });
    }

    /** Recalls the party's Pokemon before the cell is handed back; a leftover entity would quarantine the cell. */
    static void recallParties(MinecraftServer server, UUID runId) {
        TowerRuns.get(runId).ifPresent(run -> {
            for (PersistedParticipant participant : run.participants()) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player != null) recallParty(player);
            }
        });
    }

    static void recallParty(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon.getEntity() != null) pokemon.recall();
        }
    }

    public static int onServerStopped(MinecraftServer server) {
        int held = ROUNDS.size();
        ROUNDS.clear();
        CobblemonBattleAdapter.onServerStopped(server);
        TowerBossAdapter.onServerStopped();
        return held;
    }
}
