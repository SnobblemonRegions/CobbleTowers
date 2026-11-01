package com.cobbletowers.encounter;

import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbletowers.battle.cobbleraids.TowerBossAdapter;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.economy.AscensionLibRewards;
import com.cobbletowers.reward.PracticeTrialReward;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.TowerRuns;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** What a cleared floor pays in AscensionLib's wallet; retried by {@link AscensionLibRewards}. */
final class FloorPayout {

    private FloorPayout() {}

    /**
     * Pays a cleared floor: a Scouter roll per floor, milestone bands on milestone floors, a small band for a Trial's
     * last floor. Paid to every run member.
     */
    static void pay(MinecraftServer server, TowerBossAdapter.Binding binding, EncounterResult result, long now) {
        Optional<PersistedRun> found = TowerRuns.get(binding.runId());
        if (found.isEmpty()) return;
        PersistedRun run = found.get();
        TowerContent content = TowerDefinitionRegistry.content();
        String outcome = result.outcome().name();
        List<UUID> members = run.participants().stream()
                .filter(participant -> participant.state().isInRun())
                .map(PersistedParticipant::playerId)
                .toList();
        if (members.isEmpty()) return;

        int floorLimit = run.options().floorLimit();
        if (floorLimit > 0) {
            // A Trial pays once, on its last floor, at the rank its length implies: repeatable practice must not
            // out-earn or bypass the tower.
            if (binding.floorIndex() >= floorLimit) {
                if (PracticeTrialReward.isPractice(run.options())) {
                    // The one scored attempt is the only one that pays loot; a practice run can only turn up a rental key.
                    PracticeTrialReward.grantKeys(server, result.encounterId(), members, now);
                } else {
                    AscensionLibRewards.settleTrial(server, result.encounterId(), outcome,
                            trialRank(floorLimit), members, now);
                }
            }
            return;
        }

        boolean keenEye = DraftService.effects(run).scoutingBonus() > 0;
        AscensionLibRewards.settleScouterDrops(server, result.encounterId(), outcome, keenEye, members, now);

        if (content.milestoneAt(run.towerId(), binding.floorIndex()).isEmpty()) return;
        int from = AscensionLibRewards.segmentStart(
                floor -> content.milestoneAt(run.towerId(), floor).isPresent(), binding.floorIndex());
        var tower = content.towers().get(run.towerId());
        if (tower != null && tower.contentFloor(binding.floorIndex()) == tower.floorCount() && AscensionLibRewards.supportsCompletion()) {
            // A cycle's champion is the hour-long completion chest, scored by the run's challenge cards, its Ascension cycle and whether no player
            // Pokemon fainted during the cycle (the mastery stats reset at the cycle's first floor).
            var stats = com.cobbletowers.persistence.TowerRunStatsStore.get(server).peek(run.runId());
            boolean flawless = stats != null && stats.faints == 0;
            int cycle = tower.ascensionOf(binding.floorIndex());
            AscensionLibRewards.settleCompletion(server, result.encounterId(), outcome, binding.floorIndex(),
                    cycle, run.modifiers().accumulated().size(), flawless, run.towerId().toString(), members, now,
                    com.cobbletowers.ascension.ThreatPolicy.bandFor(run.options().entryThreat(), cycle, true));
            return;
        }
        int cycle = tower == null ? 0 : tower.ascensionOf(binding.floorIndex());
        AscensionLibRewards.settleMilestone(server, result.encounterId(), outcome, from, binding.floorIndex(),
                members, now, com.cobbletowers.ascension.ThreatPolicy.bandFor(run.options().entryThreat(), cycle, false));
    }

    /** A trial's rank (1-3) follows its length like the scouting tiers: 1-4 floors rank 1, 5-9 rank 2, 10+ rank 3. */
    static int trialRank(int floorLimit) {
        return floorLimit < 5 ? 1 : floorLimit < 10 ? 2 : 3;
    }
}
