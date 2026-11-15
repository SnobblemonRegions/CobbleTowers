package com.cobbletowers.instance;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps a cell's chunks loaded while something uses it (TDS #27). A region ticket, never a forceload (which survives
 * a restart). Covers the arena (49 chunks), not the whole cell.
 */
public final class CellTickets {

    private static final TicketType<ChunkPos> CELL =
            TicketType.create("cobbletowers_cell", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Chunks either side of the arena centre: 5 gives an 11x11 area (176 blocks, 80 north or west of the centre to 95
     * south or east), which holds the 150 x 160 Tideforge cathedral (P59) and stays inside a cell's 256-block interior.
     * Was 3 (7x7) for Neutral's 66 x 126 acts; every cell now holds 121 chunks, almost all of them empty void.
     */
    public static final int RADIUS_CHUNKS = 5;

    // The normal run/warm-pool hold and each recovery hold are independent claims on one physical ticket.
    private static final Map<Integer, Set<Object>> HELD = new LinkedHashMap<>();
    private enum Owner { ACTIVE }

    static {
        ServerState.onStop(CellTickets::onServerStopped);
    }

    private CellTickets() {}

    private static ChunkPos centreChunk(int cell) {
        return new ChunkPos(CellGrid.centerOf(cell));
    }

    /** Loads the cell's arena and keeps it loaded. Repeat calls are harmless. */
    public static void hold(MinecraftServer server, int cell) {
        hold(server, cell, null);
    }

    public static void holdRecovery(MinecraftServer server, int cell, UUID runId) {
        hold(server, cell, java.util.Objects.requireNonNull(runId));
    }

    private static void hold(MinecraftServer server, int cell, UUID recovery) {
        CellGrid.requireValid(cell);
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return;
        if (!claim(cell, recovery)) return;
        level.getChunkSource().addRegionTicket(CELL, centreChunk(cell), RADIUS_CHUNKS, centreChunk(cell));
        TowerLog.info("Cell {} chunks held", cell);
    }

    /** Lets the cell's chunks go. Safe to call for a cell nothing was holding. */
    public static void drop(MinecraftServer server, int cell) {
        drop(server, cell, null);
    }

    public static void dropRecovery(MinecraftServer server, int cell, UUID runId) {
        drop(server, cell, java.util.Objects.requireNonNull(runId));
    }

    private static void drop(MinecraftServer server, int cell, UUID recovery) {
        CellGrid.requireValid(cell);
        if (!releaseClaim(cell, recovery)) return;
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return;
        level.getChunkSource().removeRegionTicket(CELL, centreChunk(cell), RADIUS_CHUNKS, centreChunk(cell));
        TowerLog.info("Cell {} chunks released", cell);
    }

    /** Whether this mod still holds the cell's chunks; the third cleanup stage (TDS section 11). */
    public static boolean isHeld(int cell) {
        return HELD.containsKey(cell);
    }

    /** Returns true only when a physical ticket must be added. Null is the normal run/pool claim. */
    static boolean claim(int cell, UUID recovery) {
        boolean first = !HELD.containsKey(cell);
        HELD.computeIfAbsent(cell, ignored -> new LinkedHashSet<>()).add(recovery == null ? Owner.ACTIVE : recovery);
        return first;
    }

    /** Returns true only when the last claim was released and the physical ticket must be removed. */
    static boolean releaseClaim(int cell, UUID recovery) {
        Set<Object> claims = HELD.get(cell);
        if (claims == null || !claims.remove(recovery == null ? Owner.ACTIVE : recovery)) return false;
        if (!claims.isEmpty()) return false;
        HELD.remove(cell);
        return true;
    }

    /** How many cells are being held loaded, for the diagnostics the TDS asks to track. */
    public static int heldCount() {
        return HELD.size();
    }

    /** The chunks one held cell covers, for reporting a real number rather than an estimate. */
    public static int chunksPerCell() {
        int span = RADIUS_CHUNKS * 2 + 1;
        return span * span;
    }

    /** The corner a floor is pasted at, so an arena of this size sits centred in the cell. */
    public static BlockPos pasteOrigin(int cell, int width, int length) {
        BlockPos centre = CellGrid.centerOf(cell);
        return new BlockPos(centre.getX() - width / 2, CellGrid.FLOOR_Y, centre.getZ() - length / 2);
    }

    /** Drops every ticket at shutdown so an integrated client's next world does not think it holds chunks. */
    public static int onServerStopped() {
        int held = HELD.size();
        HELD.clear();
        return held;
    }

    /** Test seam: hold a cell without a server, for the cleanup stage's own tests. */
    static void holdForTests(int cell) {
        claim(cell, null);
    }

    static void resetForTests() {
        HELD.clear();
    }
}
