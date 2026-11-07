package com.cobbletowers.route;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.halloween.door.TrickDoors;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedRun;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The three route doors of a walkable tower (P55): when the party's vote settles, the door of the chosen lane lights up (its lintel turns to
 * shroomlight) and opens (its bars are cleared). When a floor begins every door is shut and dark again. The state is the blocks themselves, so
 * a restart cannot disagree with it. Server thread only.
 */
public final class TowerDoors {

    static final BlockState SHUT = Blocks.IRON_BARS.defaultBlockState();
    static final BlockState LIT = Blocks.SHROOMLIGHT.defaultBlockState();
    static final BlockState DARK = Blocks.CRYING_OBSIDIAN.defaultBlockState();

    private TowerDoors() {}

    /** The door a lane leads to: lanes and doors are both counted left to right. Empty when the floor has no such door. */
    public static Optional<FloorDoor> doorOf(FloorLayout layout, int lane) {
        List<FloorDoor> doors = layout.doors();
        return lane >= 0 && lane < doors.size() ? Optional.of(doors.get(lane)) : Optional.empty();
    }

    /**
     * The route settled on {@code lane}: its door lights up and opens. Never throws: a tower that fails to open a door must not stop the vote.
     * @return the blocks changed
     */
    public static int chosen(MinecraftServer server, PersistedRun run, int lane) {
        try {
            return chosenUnguarded(server, run, lane);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: opening the door of lane {} failed", run.runId(), lane, ex);
            return 0;
        }
    }

    /**
     * A pedestal that stands in front of a door was picked (a draft with no lanes, such as a Lock-In or a relic): that door lights up and opens, not the
     * middle one the party would otherwise be sent through. Never throws.
     * @return the blocks changed; 0 when the card stands at no door
     */
    public static int chosenByCard(MinecraftServer server, PersistedRun run, int cardIndex, int cardCount) {
        try {
            Located at = locate(server, run);
            if (at == null) return 0;
            int lane = com.cobbletowers.intermission.PedestalRules.doorOfCard(cardIndex, cardCount, at.layout.doors().size());
            return lane < 0 ? 0 : chosenUnguarded(server, run, lane);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: opening the door of pedestal {} failed", run.runId(), cardIndex, ex);
            return 0;
        }
    }

    private static int chosenUnguarded(MinecraftServer server, PersistedRun run, int lane) {
        Located at = locate(server, run);
        if (at == null) return 0;
        Optional<FloorDoor> door = doorOf(at.layout, lane);
        if (door.isEmpty()) return 0;
        int changed = paint(at.level, door.get().frame(), at.origin, LIT, null);
        if (TrickDoors.isTrick(door.get(), at.origin)) {   // a Trick or Treat doorway: the wall stays and the door swings (P57)
            if (!TrickDoors.isOpen(door.get(), at.origin) && TrickDoors.open(at.level, door.get(), at.origin)) changed++;
            TowerLog.info("Run {}: trick-or-treat door {} of floor {} lit and opened", run.runId(), lane, run.floorIndex());
            return changed;
        }
        changed += paint(at.level, door.get().open(), at.origin, Blocks.AIR.defaultBlockState(), null);
        if (changed > 0) {
            BlockPos centre = door.get().open().minIn(at.origin).offset(2, 2, 1);
            at.level.playSound(null, centre, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.4f, 0.8f);
            at.level.playSound(null, centre, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 1.6f);
            TowerLog.info("Run {}: door {} of floor {} lit and opened", run.runId(), lane, run.floorIndex());
        }
        return changed;
    }

    /**
     * Makes sure the party can get through: a floor whose draft had no route lanes (a relic at a boss floor, say) never chose a door, so the middle
     * one is lit and opened as the team sets out for the stair. A floor with a door already open is left alone.
     * @return the blocks changed
     */
    public static int ensureOpen(MinecraftServer server, PersistedRun run) {
        try {
            Located at = locate(server, run);
            if (at == null) return 0;
            for (FloorDoor door : at.layout.doors()) {
                if (TrickDoors.isTrick(door, at.origin)) {
                    if (TrickDoors.isOpen(door, at.origin)) return 0;
                    continue;
                }
                FloorBox box = door.open();
                BlockPos centre = box.minIn(at.origin).offset((box.maxX() - box.minX()) / 2, (box.maxY() - box.minY()) / 2, 0);
                if (at.level.isLoaded(centre) && at.level.getBlockState(centre).isAir()) return 0;
            }
            return chosenUnguarded(server, run, at.layout.doors().size() / 2);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: opening a default door failed", run.runId(), ex);
            return 0;
        }
    }

    /**
     * The lane whose door is open on the run's current floor, or -1 when none is. A door counts as open when the middle of its doorway is air, or
     * when it is a Trick or Treat door standing open.
     */
    public static int openLane(MinecraftServer server, PersistedRun run) {
        Located at = locate(server, run);
        if (at == null) return -1;
        List<FloorDoor> doors = at.layout.doors();
        for (int lane = 0; lane < doors.size(); lane++) {
            FloorDoor door = doors.get(lane);
            if (TrickDoors.isTrick(door, at.origin)) {
                if (TrickDoors.isOpen(door, at.origin)) return lane;
                continue;
            }
            FloorBox box = door.open();
            BlockPos centre = box.minIn(at.origin).offset((box.maxX() - box.minX()) / 2, (box.maxY() - box.minY()) / 2, 0);
            if (at.level.isLoaded(centre) && at.level.getBlockState(centre).isAir()) return lane;
        }
        return -1;
    }

    /**
     * Shuts and darkens every door of the run's current floor, as the floor begins. A door with a player in it is left open, and logged: bars
     * would trap them.
     * @return the blocks changed
     */
    public static int resetAll(MinecraftServer server, PersistedRun run) {
        Located at = locate(server, run);
        if (at == null) return 0;
        int changed = 0;
        for (FloorDoor door : at.layout.doors()) {
            changed += paint(at.level, door.frame(), at.origin, DARK, null);
            if (TrickDoors.isTrick(door, at.origin)) {
                if (TrickDoors.close(at.level, door, at.origin)) changed++;
                else TowerLog.warn("Run {}: a trick-or-treat door of floor {} was left open because a player stands in it", run.runId(), run.floorIndex());
                continue;
            }
            BlockPos min = door.open().minIn(at.origin);
            BlockPos max = door.open().maxIn(at.origin);
            if (!at.level.getEntitiesOfClass(ServerPlayer.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1)).isEmpty()) {
                TowerLog.warn("Run {}: a door of floor {} was left open because a player stands in it", run.runId(), run.floorIndex());
                continue;
            }
            changed += paint(at.level, door.open(), at.origin, SHUT, state -> state.isAir());
        }
        if (changed > 0) TowerLog.info("Run {}: the doors of floor {} were shut and darkened again ({} blocks)", run.runId(), run.floorIndex(), changed);
        return changed;
    }

    /**
     * The Trick or Treat vault is shown behind the door of {@code lane} (the middle one when unknown): that doorway becomes the trick door, standing open
     * as the vault vote runs. Idempotent; false until it could be done (a player standing in the new wall).
     */
    public static boolean standTrickDoor(MinecraftServer server, PersistedRun run, int lane) {
        Located at = locate(server, run);
        if (at == null) return false;
        List<FloorDoor> doors = at.layout.doors();
        FloorDoor door = doors.get(lane >= 0 && lane < doors.size() ? lane : doors.size() / 2);
        return TrickDoors.isTrick(door, at.origin) || TrickDoors.install(at.level, run.runId(), door, at.origin, true);
    }

    /** Where the chamber behind a door keeps its vault: on the dais by the east wall, the mimic fighting in the aisle in front of it. */
    public record RoomSpot(ServerLevel level, BlockPos vault, BlockPos front) {}

    /** The vault spot of the chamber behind the door of {@code lane} (the middle door when the lane is unknown), if the floor has doors and is built. */
    public static Optional<RoomSpot> roomSpot(MinecraftServer server, PersistedRun run, int lane) {
        Located at = locate(server, run);
        if (at == null) return Optional.empty();
        List<FloorDoor> doors = at.layout.doors();
        FloorDoor door = doors.get(RoomDresser.doorOfLane(lane, doors.size()));
        int centre = (door.open().minX() + door.open().maxX()) / 2;
        int feet = door.pedestal().y();
        int back = door.open().minZ() - RoomDresser.ROOM_DEPTH;
        // the dais is against the east wall, off the aisle the party walks to the corridor; the mimic fights in the aisle in front of it
        BlockPos vault = at.origin.offset(centre + RoomDresser.VAULT_EAST, feet + RoomDresser.DAIS_HEIGHT, back + RoomDresser.VAULT_BACK);
        return Optional.of(new RoomSpot(at.level, vault, at.origin.offset(centre, feet, back + RoomDresser.VAULT_BACK)));
    }

    private record Located(ServerLevel level, FloorLayout layout, BlockPos origin) {}

    private static Located locate(MinecraftServer server, PersistedRun run) {
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty()) return null;
        Optional<FloorLayout> layout = FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (layout.isEmpty() || layout.get().doors().isEmpty()) return null;
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        return origin.map(o -> new Located(level, layout.get(), o)).orElse(null);
    }

    /** Sets every loaded block of {@code box} to {@code state}, only where {@code onlyWhere} (when given) accepts the block there. */
    private static int paint(ServerLevel level, FloorBox box, BlockPos origin, BlockState state, java.util.function.Predicate<BlockState> onlyWhere) {
        int changed = 0;
        for (BlockPos pos : BlockPos.betweenClosed(box.minIn(origin), box.maxIn(origin))) {
            if (!level.isLoaded(pos)) continue;
            BlockState now = level.getBlockState(pos);
            if (now == state || (onlyWhere != null && !onlyWhere.test(now))) continue;
            level.setBlock(pos, state, Block.UPDATE_ALL);
            changed++;
        }
        return changed;
    }
}
