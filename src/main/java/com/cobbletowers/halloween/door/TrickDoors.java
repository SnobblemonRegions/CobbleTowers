package com.cobbletowers.halloween.door;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
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
 * The trick-or-treat door of a Halloween route doorway (P57): the 5 x 6 opening is walled and a 3 x 5 gap in the middle holds the Hallowbound Grand door (a 1 x 2 gap and the standard door in a smaller opening),
 * drawn as Polymer displays. Shut, the gap is barrier (collision, invisible); open, it is air. The doorway keys the door, the blocks are
 * the state, and the display is rebuilt with the doorway. Server thread only.
 */
public final class TrickDoors {

    static final BlockState WALL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    static final BlockState GAP_SHUT = Blocks.BARRIER.defaultBlockState();
    static final BlockState GAP_OPEN = Blocks.AIR.defaultBlockState();
    static final BlockState BARS = Blocks.IRON_BARS.defaultBlockState();
    /** Quarter turns that put the authored front (the lit sign) on the hall side, +Z. */
    static final int FRONT_TURNS = 2;

    private static final class Door {
        final UUID runId;
        final DoorDisplay display;
        final FloorBox box;
        boolean open;

        Door(UUID runId, DoorDisplay display, FloorBox box, boolean open) {
            this.runId = runId;
            this.display = display;
            this.box = box;
            this.open = open;
        }
    }

    private static final Map<BlockPos, Door> DOORS = new HashMap<>();

    static {
        ServerState.onStop(TrickDoors::clear);
    }

    private TrickDoors() {}

    public static void bootstrap() {
        DoorModels.bootstrap();
        com.cobbletowers.instance.CellEvents.onWiped(TrickDoors::forgetCell);
    }

    /** Forgets every door in a wiped cell (see SealDoors.forgetCell). */
    static int forgetCell(int cell) {
        var stale = DOORS.keySet().stream().filter(pos -> com.cobbletowers.instance.CellEvents.inCell(cell, pos.getX(), pos.getZ())).toList();
        for (var pos : stale) {
            Door found = DOORS.remove(pos);
            if (found != null) found.display.destroy();
        }
        if (!stale.isEmpty()) com.cobbletowers.TowerLog.info("Cell {} wiped: forgot {} trick-or-treat door(s) it held", cell, stale.size());
        return stale.size();
    }

    /**
     * Walls the doorway and stands the door in it, shut or open. Idempotent for the same run. Not done (false) while a player stands where the
     * walling would trap them, or when the opening is too small: the caller tries again later.
     */
    public static boolean install(ServerLevel level, UUID runId, FloorDoor door, BlockPos origin, boolean open) {
        FloorBox box = door.open();
        if (!DoorGeometry.fits(box)) return false;
        BlockPos min = box.minIn(origin);
        Door existing = DOORS.get(min);
        if (existing != null) {
            if (existing.runId.equals(runId)) return true;
            existing.display.destroy();
            DOORS.remove(min);
        }
        BlockPos max = box.maxIn(origin);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1))) {
            BlockPos feet = player.blockPosition();
            boolean inGap = isGap(box, min, feet) || isGap(box, min, feet.above());
            if (!open || !inGap) return false;   // an open gap is air, so only a player in the wall itself is trapped
        }
        // The Grand door frame (pillars, crown, header) is part of its model, so the jambs and header are invisible barrier and the model shows;
        // the standard door sits in a plain brick wall.
        BlockState surround = DoorGeometry.grand(box) ? GAP_SHUT : WALL;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) continue;
            level.setBlock(pos, isGap(box, min, pos) ? (open ? GAP_OPEN : GAP_SHUT) : surround, Block.UPDATE_ALL);
        }
        DoorDisplay display = new DoorDisplay(level, new Vec3(min.getX() + DoorGeometry.gapX(box) - box.minX(), min.getY(), min.getZ()), FRONT_TURNS, open, DoorGeometry.grand(box));
        DOORS.put(min.immutable(), new Door(runId, display, box, open));
        TowerLog.info("Run {}: trick-or-treat door installed at {} ({})", runId, min.toShortString(), open ? "open" : "shut");
        return true;
    }

    /** Whether this doorway holds a trick door. */
    public static boolean isTrick(FloorDoor door, BlockPos origin) {
        return DOORS.containsKey(door.open().minIn(origin));
    }

    public static boolean isOpen(FloorDoor door, BlockPos origin) {
        Door found = DOORS.get(door.open().minIn(origin));
        return found != null && found.open;
    }

    /** Opens the door: the gap clears at once (collision follows the logic), the wing swings over 12 ticks. */
    public static boolean open(ServerLevel level, FloorDoor door, BlockPos origin) {
        return open(level, door.open().minIn(origin));
    }

    private static boolean open(ServerLevel level, BlockPos min) {
        Door found = DOORS.get(min);
        if (found == null) return false;
        if (found.open) return true;
        paintGap(level, found.box, min, GAP_OPEN);
        found.open = true;
        found.display.setOpen(true);
        sound(level, found.box, min, true);
        return true;
    }

    /** Shuts the door unless a player stands in the gap. @return whether it is shut now */
    public static boolean close(ServerLevel level, FloorDoor door, BlockPos origin) {
        return close(level, door.open().minIn(origin));
    }

    private static boolean close(ServerLevel level, BlockPos min) {
        Door found = DOORS.get(min);
        if (found == null) return false;
        if (!found.open) return true;
        FloorBox box = found.box;
        BlockPos max = box.maxIn(min.offset(-box.minX(), -box.minY(), -box.minZ()));
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1))) {
            if (isGap(box, min, player.blockPosition()) || isGap(box, min, player.blockPosition().above())) return false;
        }
        paintGap(level, box, min, GAP_SHUT);
        found.open = false;
        found.display.setOpen(false);
        sound(level, box, min, false);
        return true;
    }

    /** Operator tool: opens or shuts every trick door in {@code level}. @return how many changed state */
    public static int setAll(ServerLevel level, boolean open) {
        int changed = 0;
        for (BlockPos min : java.util.List.copyOf(DOORS.keySet())) {
            boolean was = DOORS.get(min).open;
            if ((open ? open(level, min) : close(level, min)) && was != open) changed++;
        }
        return changed;
    }

    /** One line per door, for the operator tool. */
    public static java.util.List<String> describe() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Door> entry : DOORS.entrySet()) {
            lines.add(entry.getKey().toShortString() + " run " + entry.getValue().runId + (entry.getValue().open ? " open" : " shut"));
        }
        return lines;
    }

    /** Takes the door and its wall out and puts the iron bars back (the lane is no longer a Trick or Treat stop). */
    public static void uninstall(ServerLevel level, FloorDoor door, BlockPos origin) {
        BlockPos min = door.open().minIn(origin);
        Door found = DOORS.remove(min);
        if (found == null) return;
        found.display.destroy();
        for (BlockPos pos : BlockPos.betweenClosed(min, door.open().maxIn(origin))) {
            if (level.isLoaded(pos)) level.setBlock(pos, BARS, Block.UPDATE_ALL);
        }
    }

    /** The run is over: its displays go (the blocks go with the cell). */
    public static void dropRun(UUID runId) {
        Iterator<Map.Entry<BlockPos, Door>> it = DOORS.entrySet().iterator();
        while (it.hasNext()) {
            Door door = it.next().getValue();
            if (!door.runId.equals(runId)) continue;
            door.display.destroy();
            it.remove();
        }
    }

    private static void clear() {
        for (Door door : DOORS.values()) door.display.destroy();
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

    private static void sound(ServerLevel level, FloorBox box, BlockPos min, boolean opening) {
        BlockPos centre = min.offset(DoorGeometry.gapX(box) - box.minX(), 1, 0);
        level.playSound(null, centre, opening ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1.2f, 0.7f);
        if (opening) level.playSound(null, centre, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.BLOCKS, 0.3f, 0.65f);
    }
}
