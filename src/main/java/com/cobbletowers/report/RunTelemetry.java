package com.cobbletowers.report;

import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.mastery.TuningCounters;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.TowerRuns;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the beta wants to know about runs (P48): a history line per transition for reports, and tallies in the tuning
 * counters (run starts and ends, floors entered, where runs were lost). Never throws: a tally must not disturb a run.
 */
public final class RunTelemetry {

    /**
     * When each live run started, for the run-length line (balance phase 0). In memory only: a run that spans a
     * restart has no start time and logs no length, which is fine for a sample.
     */
    private static final java.util.Map<UUID, Long> STARTED = new java.util.concurrent.ConcurrentHashMap<>();

    private RunTelemetry() {}

    /** Called by the transition service for every move a run makes. */
    public static void onTransition(MinecraftServer server, UUID runId, RunState from, RunState to, RunEvent event) {
        try {
            RunHistory.note(runId, java.time.LocalTime.now().withNano(0) + " " + from + " -> " + to + " on " + event);
            Optional<PersistedRun> found = TowerRuns.get(runId);
            if (found.isEmpty()) return;
            PersistedRun run = found.get();
            if (to == RunState.ALLOCATING_INSTANCE && from == RunState.VALIDATING_PARTY) {
                TuningCounters.bump(server, "run_start.total");
                TuningCounters.bump(server, "run_start." + startKind(run));
                STARTED.put(runId, System.currentTimeMillis());
            }
            if (to == RunState.ENCOUNTER_ACTIVE && from == RunState.FLOOR_READY) {
                TuningCounters.bump(server, "floor_entered." + floorKey(run.floorIndex()));
            }
            if (to.isTerminal()) {
                TuningCounters.bump(server, "run_end." + to.name().toLowerCase(Locale.ROOT));
                if (to == RunState.FAILED || to == RunState.ABANDONED) {
                    TuningCounters.bump(server, "run_lost_at." + floorKey(run.floorIndex()));
                }
                logRunLength(server, run, to);
                prompt(server, run);
            }
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }

    /**
     * One log line and two counters per finished run, so the balance model can use real run lengths instead of its
     * 30-60 minute assumption. {@code run_minutes.<state>} sums the minutes and {@code run_minutes_n.<state>} counts the
     * runs, so the average is one division in the counter report.
     */
    private static void logRunLength(MinecraftServer server, PersistedRun run, RunState end) {
        Long started = STARTED.remove(run.runId());
        if (started == null) return;
        long seconds = Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
        String state = end.name().toLowerCase(Locale.ROOT);
        TuningCounters.add(server, "run_minutes." + state, Math.round(seconds / 60.0));
        TuningCounters.bump(server, "run_minutes_n." + state);
        com.cobbletowers.TowerLog.info("Run {} on {} ended {} at floor {} after {} min {} s with {} player(s)",
                run.runId(), run.towerId(), state, run.floorIndex(), seconds / 60, seconds % 60, run.participants().size());
    }

    /** {@code trial_daily}, {@code trial_weekly}, {@code rental}, {@code playlist} or {@code plain}. */
    static String startKind(PersistedRun run) {
        Optional<String> trial = run.options().trial();
        if (trial.isPresent()) return trial.get().startsWith("weekly") ? "trial_weekly" : "trial_daily";
        if (run.options().playlist().isPresent()) {
            return run.options().playlist().get().getPath().equals("rental") ? "rental" : "playlist";
        }
        return "plain";
    }

    /** {@code f05}: floors sort in order in the counter report. */
    static String floorKey(int floor) {
        return String.format(Locale.ROOT, "f%02d", Math.max(0, Math.min(99, floor)));
    }

    private static void prompt(MinecraftServer server, PersistedRun run) {
        if (!BetaConfig.reportPrompt()) return;
        List<PersistedParticipant> participants = run.participants();
        for (PersistedParticipant participant : participants) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) {
                player.sendSystemMessage(Component.literal("Beta: found a problem or have an idea? /tower report <what happened>")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
