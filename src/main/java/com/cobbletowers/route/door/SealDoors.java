package com.cobbletowers.route.door;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.halloween.door.DoorGeometry;
import com.cobbletowers.route.RouteMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Split Seal route door (P59), standing in place of a route doorway's iron bars: two leaves meeting on a seal that carries the lane's icon. The
 * doorway's jambs and lintel are invisible barrier (the frame is part of the model) and its 3 x 5 gap is barrier while shut, air while open, so
 * the blocks still decide who can pass. A doorway too small for the door keeps its bars. Server thread only.
 */
public final class SealDoors {

    static final BlockState SHUT = Blocks.BARRIER.defaultBlockState();
    static final BlockState OPEN = Blocks.AIR.defaultBlockState();
    static final BlockState BARS = Blocks.IRON_BARS.defaultBlockState();
    /** Quarter turns that put the door's front on the hall side, +Z (as the trick door). */
    static final int FRONT_TURNS = 2;

    private static final class Door {
        final UUID runId;
        final FloorBox box;
        final String trim;
        RouteMap.Stop stop;
        SealDoorDisplay display;
        boolean open, lit;

        Door(UUID runId, FloorBox box, String trim) {
            this.runId = runId;
            this.box = box;
            this.trim = trim;
        }
    }

    private static final Map<BlockPos, Door> DOORS = new HashMap<>();

    static {
        ServerState.onStop(SealDoors::clear);
    }

    private SealDoors() {}

    public static void bootstrap() {
        SealDoorModels.bootstrap();
        com.cobbletowers.instance.CellEvents.onWiped(SealDoors::forgetCell);
    }

    /** Forgets every door in a wiped cell: its displays went with the cell, and a later floor at the same spot must get a door of its own. */
    static int forgetCell(int cell) {
        var stale = DOORS.keySet().stream().filter(pos -> com.cobbletowers.instance.CellEvents.inCell(cell, pos.getX(), pos.getZ())).toList();
        stale.forEach(SealDoors::remove);
        if (!stale.isEmpty()) com.cobbletowers.TowerLog.info("Cell {} wiped: forgot {} seal door(s) it held", cell, stale.size());
        return stale.size();
    }

    /** Whether a doorway of this size takes the door; one that does not keeps its bars. */
    public static boolean fits(FloorBox opening) {
        return DoorGeometry.grand(opening);
    }

    /**
     * Stands a shut, dark door in the doorway showing {@code stop}'s icon. A doorway that already holds this run's door only changes its icon, keeping
     * whether it is open and lit. Done (true) also when the doorway is too small and keeps its bars; false while a player stands in the wall,
     * so the caller tries again.
     */
    public static boolean install(ServerLevel level, UUID runId, FloorDoor door, BlockPos origin, String trim, RouteMap.Stop stop) {
        FloorBox box = door.open();
        if (!fits(box)) return true;
        BlockPos min = box.minIn(origin);
        Door existing = DOORS.get(min);
        if (existing != null && existing.runId.equals(runId) && existing.trim.equals(trim)) {
            if (existing.stop != stop) {
                existing.stop = stop;
                rebuild(level, min, existing);
            }
            return true;
        }
        if (existing != null) remove(min);
        BlockPos max = box.maxIn(origin);
        if (!level.getEntitiesOfClass(ServerPlayer.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1)).isEmpty()) {
            return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.isLoaded(pos)) level.setBlock(pos, SHUT, Block.UPDATE_ALL);
        }
        Door made = new Door(runId, box, trim);
        made.stop = stop;
        rebuild(level, min, made);
        DOORS.put(min.immutable(), made);
        TowerLog.info("Run {}: seal door ({}, {}) installed at {}", runId, trim, stop, min.toShortString());
        return true;
    }

    private static void rebuild(ServerLevel level, BlockPos min, Door door) {
        if (door.display != null) door.display.destroy();
        door.display = new SealDoorDisplay(level, corner(min, door.box), FRONT_TURNS, door.trim, door.stop, door.open, door.lit);
    }

    private static Vec3 corner(BlockPos min, FloorBox box) {
        return new Vec3(min.getX() + DoorGeometry.gapX(box) - box.minX(), min.getY(), min.getZ());
    }

    public static boolean isSeal(FloorDoor door, BlockPos origin) {
        return DOORS.containsKey(door.open().minIn(origin));
    }

    public static boolean isOpen(FloorDoor door, BlockPos origin) {
        Door found = DOORS.get(door.open().minIn(origin));
        return found != null && found.open;
    }

    /** The vote settled on this door: the seam and seal light, the lintel slit glows, and it opens. */
    public static boolean choose(ServerLevel level, FloorDoor door, BlockPos origin) {
        BlockPos min = door.open().minIn(origin);
        Door found = DOORS.get(min);
        if (found == null) return false;
        if (!found.lit) {
            found.lit = true;
            found.display.setLit(true);
        }
        if (found.open) return true;
        paintGap(level, found.box, min, OPEN);
        found.open = true;
        found.display.setOpen(true);
        BlockPos centre = min.offset(DoorGeometry.gapX(found.box) - found.box.minX(), 2, 0);
        level.playSound(null, centre, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.2f, 0.7f);
        level.playSound(null, centre, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 1.6f);
        return true;
    }

    /** Shuts and darkens the door as a floor begins, unless a player stands in the gap. @return whether it is shut now */
    public static boolean reset(ServerLevel level, FloorDoor door, BlockPos origin) {
        BlockPos min = door.open().minIn(origin);
        Door found = DOORS.get(min);
        if (found == null) return false;
        if (found.lit) {
            found.lit = false;
            found.display.setLit(false);
        }
        if (!found.open) return true;
        BlockPos max = door.open().maxIn(origin);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1))) {
            if (isGap(found.box, min, player.blockPosition()) || isGap(found.box, min, player.blockPosition().above())) return false;
        }
        paintGap(level, found.box, min, SHUT);
        found.open = false;
        found.display.setOpen(false);
        level.playSound(null, min.offset(DoorGeometry.gapX(found.box) - found.box.minX(), 2, 0), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 0.8f);
        return true;
    }

    /** Takes the door's display away and forgets it, leaving the blocks for whoever stands a door there next (the trick door). */
    public static void remove(FloorDoor door, BlockPos origin) {
        remove(door.open().minIn(origin));
    }

    private static void remove(BlockPos min) {
        Door found = DOORS.remove(min);
        if (found != null && found.display != null) found.display.destroy();
    }

    /** Takes the door out and puts the bars back. */
    public static void uninstall(ServerLevel level, FloorDoor door, BlockPos origin) {
        BlockPos min = door.open().minIn(origin);
        if (!DOORS.containsKey(min)) return;
        remove(min);
        for (BlockPos pos : BlockPos.betweenClosed(min, door.open().maxIn(origin))) {
            if (level.isLoaded(pos)) level.setBlock(pos, BARS, Block.UPDATE_ALL);
        }
        com.cobbletowers.route.Joins.settle(level, min, door.open().maxIn(origin));
    }

    /** The run is over: its displays go (the blocks go with the cell). */
    public static void dropRun(UUID runId) {
        Iterator<Map.Entry<BlockPos, Door>> it = DOORS.entrySet().iterator();
        while (it.hasNext()) {
            Door door = it.next().getValue();
            if (!door.runId.equals(runId)) continue;
            if (door.display != null) door.display.destroy();
            it.remove();
        }
    }

    private static void clear() {
        for (Door door : DOORS.values()) if (door.display != null) door.display.destroy();
        DOORS.clear();
    }

    private static boolean isGap(FloorBox box, BlockPos min, BlockPos pos) {
        return DoorGeometry.isGap(box, pos.getX() - min.getX() + box.minX(), pos.getY() - min.getY() + box.minY(), pos.getZ() - min.getZ() + box.minZ());
    }

    private static void paintGap(ServerLevel level, FloorBox box, BlockPos min, BlockState state) {
        BlockPos max = box.maxIn(min.offset(-box.minX(), -box.minY(), -box.minZ()));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.isLoaded(pos) && isGap(box, min, pos) && level.getBlockState(pos) != state) level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }
}
