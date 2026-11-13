package com.cobbletowers.report;

import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.economy.AscensionLibTelemetry;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.TowerRuns;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The tower's gameplay telemetry, written into AscensionLib's log ({@link AscensionLibTelemetry}): the per-run events of
 * {@link RunTimeline}, plus {@code tower_alloc_refused} and {@code tower_cell_quarantined}. Each call builds a small map on the
 * server thread and nothing more. Never throws: telemetry must not disturb a run.
 */
public final class TowerTelemetry {
    private static final RunTimeline TIMELINE = new RunTimeline();

    static {
        com.cobbletowers.ServerState.onStop(TIMELINE::clear);
    }

    private TowerTelemetry() {}

    /** Called by {@link RunTelemetry#onTransition} for every move a run makes. */
    static void onTransition(PersistedRun run, RunState from, RunState to, RunEvent event) {
        try {
            var facts = new RunTimeline.RunFacts(run.runId(), run.towerId().toString(), run.floorIndex(), run.lastBankedFloor(),
                    run.options().entryThreat(), RunTelemetry.startKind(run),
                    run.participants().stream().map(PersistedParticipant::playerId).toList());
            for (var e : TIMELINE.onTransition(facts, from, to, event, System.currentTimeMillis())) AscensionLibTelemetry.event(e.type(), e.fields());
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }

    /** Why the run is about to end, said just before the transition that ends it: {@code left}, {@code disconnect}, {@code restart}, {@code start_failed}. */
    public static void endCause(UUID runId, String cause) {
        try {
            TIMELINE.noteEndCause(runId, cause);
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }

    /** The party is waiting on its next arena (the intermission's "The next arena is being prepared..."). */
    public static void arenaWaitStarted(UUID runId) {
        try {
            TIMELINE.arenaWaitStarted(runId, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }

    /** An allocation refused with {@code Reason.BUSY}: how many ended runs still held cells. */
    public static void allocationRefused(UUID runId, String reason, int pendingReleases) {
        try {
            TIMELINE.busyRefusal(runId);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("run", runId.toString());
            TowerRuns.get(runId).ifPresent(run -> {
                f.put("tower", run.towerId().toString());
                f.put("floor", run.floorIndex());
            });
            f.put("reason", reason);
            f.put("pendingReleases", pendingReleases);
            AscensionLibTelemetry.event("tower_alloc_refused", f);
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }

    /** A cell was taken out of service. */
    public static void cellQuarantined(int cell, String reason) {
        try {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("cell", cell);
            f.put("reason", reason == null ? "" : reason);
            AscensionLibTelemetry.event("tower_cell_quarantined", f);
        } catch (RuntimeException ex) {
            // Telemetry only.
        }
    }
}
