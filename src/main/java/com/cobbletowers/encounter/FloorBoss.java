package com.cobbletowers.encounter;

import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.battle.cobbleraids.TowerBossAdapter;
import com.cobbletowers.definition.BossPoolDefinition;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.runtime.ParticipantService;
import com.cobbletowers.runtime.RunTransitionService;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The boss half of a floor: starting it against everyone still standing, and settling how it ended. */
final class FloorBoss {

    private FloorBoss() {}

    /** Puts the boss up against everyone still standing, not just those who cleared. */
    static boolean start(MinecraftServer server, TowerEncounters.Round round, long now) {
        Optional<PersistedRun> found = TowerRuns.get(round.runId());
        if (found.isEmpty()) return false;
        Optional<FloorSetup> resolved = FloorSetup.resolve(server, found.get(), false);
        if (resolved.isEmpty()) return false;
        FloorSetup setup = resolved.get();
        PersistedRun run = setup.run();

        List<ServerPlayer> standing = new ArrayList<>();
        for (UUID playerId : standingParticipants(run.participants(), round.byPlayer())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == setup.level()) standing.add(player);
        }
        if (standing.isEmpty()) return false;

        // A milestone floor takes the boss the milestone names; every other floor draws one.
        Optional<BossPoolDefinition> pool = setup.floor().bossPoolId()
                .map(id -> setup.content().bossPools().get(id))
                .filter(Objects::nonNull);
        Optional<BossDraw.Boss> boss = BossDraw.draw(pool, handpickedBoss(setup.content(), setup.tower(), setup.floor()),
                run.seed(), run.floorIndex(), TowerEncounters.levelsOf(run, standing), setup.ruleset(),
                DraftService.effects(run).bossLevelOffset(),
                RunDraws.seen(server, run.runId(), run.floorIndex(), BossDraw.LOG_ORDINAL));
        boss.ifPresent(b -> RunDraws.record(server, run.runId(), run.floorIndex(), BossDraw.LOG_ORDINAL,
                b.definition().getPath()));
        if (boss.isEmpty()) {
            TowerLog.error("Floor {} names neither a boss pool nor a milestone boss", setup.floor().id());
            return false;
        }

        // The boss fight sends out CLONES of each party (CobbleRaids), so a lead left standing from the
        // opponent fight would be there twice. Put the real ones away first.
        for (ServerPlayer player : standing) TowerEncounters.recallParty(player);

        // Balance phase 2: drafted boss levels the level cap swallowed come back as boss health.
        var effects = DraftService.effects(run);
        int clamped = TowerLevelPolicy.levelsClampedAway(TowerEncounters.levelsOf(run, standing), run.floorIndex(),
                effects.bossLevelOffset(), boss.get().level());
        if (effects.bossLevelOffset() > 0) effects = effects.withClampedLevels(Math.min(clamped, effects.bossLevelOffset()));
        Optional<UUID> started = TowerBossAdapter.start(
                server, setup.level(), standing, boss.get(), setup.presentation(0), round.runId(), round.floorIndex(),
                effects,
                // Declared inside the adapter, once the encounter id exists and before the boss battle starts.
                encounterId -> FloorScouting.declareBoss(server, setup, boss.get(), standing, encounterId));
        if (started.isEmpty()) return false;

        TowerEncounters.putRound(
                new TowerEncounters.Round(round.runId(), round.floorIndex(), TowerEncounters.Phase.BOSS, round.byPlayer(),
                        round.startedAt()));
        return true;
    }

    static List<UUID> standingParticipants(List<PersistedParticipant> participants,
                                           Map<UUID, TowerEncounters.Status> outcomes) {
        // Round outcomes outlive individual departures. A CLEARED result does not keep a player in the run.
        return participants.stream()
                .filter(participant -> participant.state().canFight())
                .filter(participant -> outcomes.containsKey(participant.playerId())
                        && outcomes.get(participant.playerId()) != TowerEncounters.Status.OUT)
                .map(PersistedParticipant::playerId)
                .toList();
    }

    /** The definition a milestone floor is meant to finish with, if this is one. */
    static Optional<ResourceLocation> handpickedBoss(TowerContent content, TowerDefinition tower,
                                                            FloorDefinition floor) {
        if (floor.milestone().isEmpty()) return Optional.empty();
        return content.milestoneAt(tower.id(), floor.index()).flatMap(MilestoneDefinition::raidDefinitionId);
    }

    /** Whether {@code floorIndex} is the last floor of the run's tower. */
    private static boolean isFinalFloor(UUID runId, int floorIndex) {
        // A trial (P32) ends on its own floor limit, whatever the tower's length.
        Optional<PersistedRun> trial = TowerRuns.get(runId).filter(run -> run.options().floorLimit() > 0);
        if (trial.isPresent()) return floorIndex >= trial.get().options().floorLimit();
        return TowerRuns.get(runId)
                .map(run -> TowerDefinitionRegistry.content().towers().get(run.towerId()))
                // A tower that ascends (P30) has no last floor: a cycle's end is an intermission where the team
                // chooses to cash out or go on, not the end of the run.
                .map(tower -> !tower.ascension() && floorIndex >= tower.floorCount())
                .orElse(false);
    }

    /** CobbleRaids has finished with the floor's boss, one way or another. */
    static void onBossEnded(MinecraftServer server, TowerBossAdapter.Binding binding,
                                    EncounterResult result) {
        TowerEncounters.Round round = TowerEncounters.removeRound(binding.runId());
        long now = System.currentTimeMillis();
        TowerLog.info("Floor {} boss of run {} ended {} after {} combat tick(s)",
                binding.floorIndex(), binding.runId(), result.outcome(), result.elapsedCombatTicks());

        switch (result.outcome()) {
            case VICTORY -> {
                TowerEncounters.earn(server, binding.runId(),
                        LedgerEntry.bossDefeated(binding.floorIndex(), binding.definition(), now));
                TowerRuns.get(binding.runId())
                        .flatMap(run -> TowerDefinitionRegistry.content().floorAt(run.towerId(), run.floorIndex()))
                        .ifPresent(floor -> TowerEncounters.earn(server, binding.runId(),
                                LedgerEntry.floorCleared(binding.floorIndex(), floor.id(), now)));
                // A milestone floor also pays its milestone reward (P21), banked with everything else.
                TowerRuns.get(binding.runId())
                        .flatMap(run -> TowerDefinitionRegistry.content().milestoneAt(run.towerId(), binding.floorIndex()))
                        .ifPresent(milestone -> TowerEncounters.earn(server, binding.runId(),
                                LedgerEntry.milestoneCleared(binding.floorIndex(), milestone.id(), now)));
                // Logged so an operator can see floors completing.
                TowerLog.info("Floor {} of run {} cleared", binding.floorIndex(), binding.runId());
                if (round != null) {
                    com.cobbletowers.mastery.TuningCounters.bump(server, "floor_time.floors");
                    com.cobbletowers.mastery.TuningCounters.add(server, "floor_time.millis_sum", Math.max(0, now - round.startedAt()));
                }
                FloorPayout.pay(server, binding, result, now);
                // Everyone watching is now owed the intermission, which is where they come back.
                ParticipantService.markRevivePending(server, binding.runId(), now);
                // And the party's Pokemon go back in their balls: the floor is over, and a lead left
                // standing is what the cell's cleanup sweep quarantines the cell for.
                TowerEncounters.recallParties(server, binding.runId());
                RunTransitionService.Outcome resolved =
                        RunTransitionService.apply(server, binding.runId(), RunEvent.ENCOUNTER_RESOLVED_CLEARED, now);
                // Only picks the transition; RewardBankService decides whether the clear pays out.
                if (resolved instanceof RunTransitionService.Move) {
                    RunEvent next = isFinalFloor(binding.runId(), binding.floorIndex())
                            ? RunEvent.FINAL_FLOOR_CLEARED : RunEvent.REWARDS_BANKED;
                    RunTransitionService.apply(server, binding.runId(), next, now);
                } else {
                    TowerLog.error("Run {} could not resolve floor {} into FLOOR_RESOLVING; rewards were not banked",
                            binding.runId(), binding.floorIndex());
                }
            }
            case DEFEAT -> TowerEncounters.lose(server, binding.runId(), binding.floorIndex(), now);
            // Aborted is neither a win nor a loss -- an operator or a shutdown ended it -- so the run
            // parks rather than being scored. Losing a pool to a server restart would be indefensible.
            case ABORTED -> {
                if (round != null) {
                    RunTransitionService.apply(server, binding.runId(), RunEvent.TECHNICAL_FAILURE, now);
                }
            }
        }
    }
}
