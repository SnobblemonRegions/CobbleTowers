package com.cobbletowers.route;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.halloween.door.TrickDoors;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedDraft;
import com.cobbletowers.persistence.PersistedRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Dresses the three chambers behind a walkable tower's route doors (P55): when the route draft opens, the chamber behind each door is cleared and the
 * room for the kind of stop that lane leads to is placed in it (a battle's gauntlet, a rest, a shrine, a gambler's wheel, a forge, a library, a vault, a
 * pool). Rooms are dressing only; what a stop does is unchanged. Idempotent: a chamber is only redone when its kind changes. Server thread only.
 */
public final class RoomDresser {

    /** A room is this deep, the inside of a chamber plus nothing: the chamber is 11 wide and 15 deep, and the room's floor is the row below its feet. */
    public static final int ROOM_WIDTH = 11;
    public static final int ROOM_HEIGHT = 8;
    public static final int ROOM_DEPTH = 15;
    /** The Trick or Treat room's dais: one block high, {@value #VAULT_EAST} blocks east of the aisle's centre and {@value #VAULT_BACK} in from the back wall. */
    public static final int DAIS_HEIGHT = 1;
    public static final int VAULT_EAST = 3;
    public static final int VAULT_BACK = 5;

    private static final Map<UUID, List<String>> DRESSED = new HashMap<>();

    static {
        ServerState.onStop(DRESSED::clear);
    }

    private RoomDresser() {}

    /** The structure that dresses a stop of {@code kind} (a RouteGraph.Kind name): {@code room_<kind>}, a plain battle's gauntlet for anything unknown. */
    public static ResourceLocation templateOf(String kind) {
        return CobbleTowers.id("room_" + RouteGraph.Kind.parse(kind).name().toLowerCase(Locale.ROOT));
    }

    /**
     * The kind of stop behind each door, left to right: the card whose lane is that door's, and a plain battle for a door no card rides on.
     * @param lanes the lane each card rides on; empty for a draft with no lanes, which dresses every door as a battle
     */
    public static List<String> kindsByDoor(List<Integer> lanes, List<String> kinds, int doors) {
        List<String> out = new ArrayList<>(doors);
        for (int door = 0; door < doors; door++) {
            String kind = RouteGraph.Kind.BATTLE.name();
            for (int card = 0; card < lanes.size() && card < kinds.size(); card++) {
                if (lanes.get(card) == door) kind = kinds.get(card);
            }
            out.add(kind);
        }
        return out;
    }

    /** The door a lane leads through (the middle one when the lane is unknown): the same pick the vault spot makes. */
    public static int doorOfLane(int lane, int doors) {
        return lane >= 0 && lane < doors ? lane : doors / 2;
    }

    /** Dresses the chambers for the run's open route draft; does nothing for a floor without doors; a draft without lanes dresses every chamber as a battle. Never throws. */
    public static void dress(MinecraftServer server, PersistedRun run, FloorLayout layout, BlockPos origin) {
        try {
            dressUnguarded(server, run, layout, origin);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: dressing the chambers failed", run.runId(), ex);
        }
    }

    private static void dressUnguarded(MinecraftServer server, PersistedRun run, FloorLayout layout, BlockPos origin) {
        if (layout.doors().isEmpty()) return;
        // A relic or lock-in draft has no lanes: every chamber is dressed as a battle, so a door of its own never opens onto the bare shell.
        Optional<PersistedDraft> draft = run.modifiers().draft().filter(PersistedDraft::route);
        ServerLevel level = TowerDimension.level(server);
        if (level == null) return;
        List<String> wanted = kindsByDoor(draft.map(PersistedDraft::lanes).orElse(List.of()), draft.map(PersistedDraft::kinds).orElse(List.of()),
                layout.doors().size());
        List<String> have = DRESSED.getOrDefault(run.runId(), List.of());
        // The vault's own vote is a draft with no lanes: its room stays the Trick or Treat room, not a bare battle gauntlet with the vault floating in it.
        if (draft.isEmpty() && com.cobbletowers.trick.TrickOrTreatService.holdsRoom(run)) {
            wanted = new ArrayList<>();
            for (int door = 0; door < layout.doors().size(); door++) {
                wanted.add(door < have.size() && !have.get(door).isEmpty() ? have.get(door) : RouteGraph.Kind.BATTLE.name());
            }
            wanted.set(doorOfLane(run.modifiers().lastLane(), wanted.size()), RouteGraph.Kind.TRICK_OR_TREAT.name());
        }
        List<String> now = new ArrayList<>(wanted);
        for (int door = 0; door < wanted.size(); door++) {
            if (door < have.size() && have.get(door).equals(wanted.get(door))) continue;
            FloorDoor doorway = layout.doors().get(door);
            if (!place(server, level, doorway, origin, wanted.get(door))) {
                now.set(door, "");   // not dressed: try again next time
                continue;
            }
            // A Trick or Treat lane gets the Hallowbound door in a wall; any other lane has plain bars (P57).
            if (RouteGraph.Kind.parse(wanted.get(door)) == RouteGraph.Kind.TRICK_OR_TREAT) {
                if (!TrickDoors.install(level, run.runId(), doorway, origin, false)) now.set(door, "");
            } else {
                TrickDoors.uninstall(level, doorway, origin);
            }
        }
        DRESSED.put(run.runId(), now);
    }

    private static boolean place(MinecraftServer server, ServerLevel level, FloorDoor door, BlockPos origin, String kind) {
        Optional<StructureTemplate> template = server.getStructureManager().get(templateOf(kind));
        if (template.isEmpty()) {
            TowerLog.warn("No room structure {} for a {} stop", templateOf(kind), kind);
            return false;
        }
        int centre = (door.open().minX() + door.open().maxX()) / 2;
        int feet = door.pedestal().y();
        int back = door.open().minZ() - ROOM_DEPTH;
        BlockPos from = origin.offset(centre - ROOM_WIDTH / 2, feet, back);
        BlockPos to = from.offset(ROOM_WIDTH - 1, ROOM_HEIGHT - 1, ROOM_DEPTH - 1);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!level.isLoaded(pos)) return false;
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return template.get().placeInWorld(level, from.below(), from.below(),
                new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(), Block.UPDATE_CLIENTS);
    }

    /** The kind of stop dressed behind door {@code door} for the run's open draft (a {@link RouteGraph.Kind} name), empty when none is known. */
    public static Optional<String> kindBehind(UUID runId, int door) {
        List<String> kinds = DRESSED.getOrDefault(runId, List.of());
        return door >= 0 && door < kinds.size() && !kinds.get(door).isEmpty() ? Optional.of(kinds.get(door)) : Optional.empty();
    }

    /** The run's intermission is over: the next one dresses afresh. */
    public static void forget(UUID runId) {
        DRESSED.remove(runId);
        com.cobbletowers.trick.TrickOrTreatService.forget(runId);
    }
}
