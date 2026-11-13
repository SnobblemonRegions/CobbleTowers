package com.cobbletowers.runtime;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.persistence.PersistedRun;
import java.util.OptionalInt;
import net.minecraft.server.MinecraftServer;

/**
 * What happens to runs in flight when the server stopped: they have no instance any more, so they are parked in
 * RECOVERY_REQUIRED keeping their last checkpoint (TDS #28). That is waiting, not a loss. Parking goes through the
 * transition service; terminal and already-parked runs are left alone.
 */
public final class RunRecovery {

    private RunRecovery() {}

    /** Parks every live run. Returns how many were parked. */
    public static int recoverInterruptedRuns(MinecraftServer server, long now) {
        int parked = 0;
        for (PersistedRun run : TowerRuns.all()) {
            if (!run.state().isLive()) continue;
            OptionalInt cell = run.cell();
            com.cobbletowers.report.TowerTelemetry.parkedByRestart(run.runId());
            RunTransitionService.Outcome outcome =
                    RunTransitionService.apply(server, run.runId(), RunEvent.TECHNICAL_FAILURE, now);
            if (outcome instanceof RunTransitionService.Move) {
                parked++;
                // Whatever was fighting is still standing in the cell; sweep it now or the cell is quarantined at
                // release.
                if (cell.isPresent()) RecoverySweep.schedule(server, run.runId(), cell.getAsInt());
            } else if (outcome instanceof RunTransitionService.Refusal refusal) {
                // Reported rather than retried: a run the machine will not park is one a person
                // needs to look at, and silently leaving it live would let P3 try to resume it.
                TowerLog.error("Could not park interrupted tower run {} ({}): {}",
                        run.runId(), refusal.reason(), refusal.detail());
            }
        }
        if (parked > 0) {
            TowerLog.warn("{} tower run(s) were interrupted by a restart and are awaiting recovery.", parked);
        }
        return parked;
    }
}
