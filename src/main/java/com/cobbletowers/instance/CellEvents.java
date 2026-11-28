package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Tells what keeps per-position state in the tower dimension that a cell has been wiped: a new building is about to go
 * into it, or it was cleared. The doors (Split Seal, Trick or Treat) keep their state in memory by world position, and a
 * run that comes back to a cell it used before (P59: floors 1-2 in cell 0, 3-5 in cell 3, 6-7 in cell 0 again) found its
 * old floors' doors still on record at the very same positions: the new ones were taken as installed, so the doorways
 * kept their bars, and one carried the old floor's "open". Server thread only.
 */
public final class CellEvents {

    private static final List<IntConsumer> WIPED = new CopyOnWriteArrayList<>();

    private CellEvents() {}

    /** Called with the cell's index each time it is wiped. */
    public static void onWiped(IntConsumer listener) {
        WIPED.add(listener);
    }

    static void wiped(int cell) {
        for (IntConsumer listener : WIPED) {
            try {
                listener.accept(cell);
            } catch (RuntimeException ex) {
                TowerLog.error("Forgetting what cell {} held failed", cell, ex);
            }
        }
    }

    /** Whether a block position lies in a cell's interior (any height). Pure. */
    public static boolean inCell(int cell, int x, int z) {
        var origin = CellGrid.originOf(cell);
        return x >= origin.getX() && x < origin.getX() + CellGrid.INTERIOR && z >= origin.getZ() && z < origin.getZ() + CellGrid.INTERIOR;
    }
}
