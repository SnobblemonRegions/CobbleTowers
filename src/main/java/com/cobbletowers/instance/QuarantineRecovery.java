package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import com.cobbletowers.persistence.CellQuarantine;
import com.cobbletowers.persistence.CellStateStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Returns cells the release check quarantined to service once whatever kept them out has gone. Before this a quarantine lasted until an operator
 * cleared it by hand, and nobody did: live data from 12 Nov 2026 had five cells out of service, four since early October for players who had long
 * since left and one since 8 Nov for a stray Pokemon.
 *
 * <p>Clearing only takes the cell out of the quarantine list and marks it dirty; nothing in it is touched here. Its next build
 * ({@link CellPreparer#prepare}, or the warm pool's sliced build) resets its blocks and sweeps every entity but players first, as it does for any cell
 * not known to be clean. So a cell is returned only when no player is inside, no run holds it, and the quarantine is old enough that a party walking
 * out is not mistaken for an empty cell. A cell an operator quarantined by hand is never returned here.
 */
public final class QuarantineRecovery {

    /** How long a quarantine stands before it is retried: long enough for anyone still in the cell to leave or be moved. */
    static final long GRACE_MILLIS = 5 * 60 * 1000L;
    /** One check every five minutes, and one shortly after start (once the run index is rebuilt). */
    private static final int CHECK_EVERY_TICKS = 5 * 60 * 20;
    private static final int FIRST_CHECK_TICKS = 30 * 20;

    private static int ticks;

    private QuarantineRecovery() {}

    /** Whether a quarantined cell can go back into service now. Pure. */
    static boolean recoverable(CellQuarantine entry, boolean leased, int playersInside, long now) {
        return !entry.manual() && !leased && playersInside == 0 && now - entry.since() >= GRACE_MILLIS;
    }

    /** Retries every quarantine; returns how many cells went back into service. */
    public static int recover(MinecraftServer server, long now) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return 0; // cannot see who is inside, so nothing is returned
        CellStateStore cells = CellStateStore.get(server);
        int returned = 0;
        for (CellQuarantine entry : cells.quarantined().values()) {
            if (entry.cell() < 0 || entry.cell() >= CellGrid.MAX_CELLS) continue;
            AABB bounds = CellGrid.sweepBoundsOf(entry.cell());
            int players = level.getPlayers(player -> bounds.contains(player.position())).size();
            boolean leased = InstanceAllocator.runIn(entry.cell()).isPresent();
            if (!recoverable(entry, leased, players, now)) continue;
            if (InstanceAllocator.clearQuarantine(server, entry.cell())) {
                returned++;
                TowerLog.info("Cell {} is back in service after {} min in quarantine ({}); it is reset and swept before its next build",
                        entry.cell(), (now - entry.since()) / 60_000, entry.reason());
            }
        }
        return returned;
    }

    public static void install() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> ticks = 0);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = ++ticks;
            if (now != FIRST_CHECK_TICKS && now % CHECK_EVERY_TICKS != 0) return;
            try {
                recover(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("The cell quarantine check failed", ex);
            }
        });
    }
}
