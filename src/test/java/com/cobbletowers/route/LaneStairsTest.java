package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorBox;
import com.cobbletowers.definition.FloorDoor;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.intermission.IntermissionRound;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lane stairs of a walkable tower (P58): the rules that need no Minecraft server. */
class LaneStairsTest {

    private static final FloorAnchor AT = new FloorAnchor(1, 2, 3, 0);
    private static final FloorBox BOX = new FloorBox(0, 0, 0, 1, 1, 1);

    private static FloorDoor stairDoor() {
        return new FloorDoor(BOX, BOX, AT, Optional.of(BOX), Optional.of(AT));
    }

    private static FloorLayout layout(String structure, List<FloorDoor> doors) {
        return new FloorLayout(ResourceLocation.fromNamespaceAndPath("cobbletowers", structure), AT, AT, AT, AT, Optional.of(BOX), Optional.empty(), doors);
    }

    @Test
    @DisplayName("a floor whose three doors each have a stair is left on foot, with no gate B")
    void laneStairsAreWalkable() {
        FloorLayout stairs = layout("hollow", List.of(stairDoor(), stairDoor(), stairDoor()));
        assertTrue(stairs.laneStairs());
        assertTrue(stairs.walkable());
        assertTrue(WalkRules.onFoot(stairs, Optional.of(layout("hollow", List.of(stairDoor(), stairDoor(), stairDoor())))));
        assertFalse(WalkRules.onFoot(stairs, Optional.of(layout("duskvale", List.of()))), "a detour to another building is left by teleport");
    }

    @Test
    @DisplayName("a door without a stair makes the floor a teleport floor, and so does having no doors")
    void aMissingStairIsATeleportFloor() {
        FloorDoor plain = new FloorDoor(BOX, BOX, AT);
        assertFalse(layout("hollow", List.of(stairDoor(), plain, stairDoor())).laneStairs());
        assertFalse(layout("hollow", List.of(plain, plain, plain)).walkable());
        assertFalse(layout("hollow", List.of()).walkable());
    }

    @Test
    @DisplayName("a door's stair_top and arrival go together")
    void theTwoFieldsGoTogether() {
        assertThrows(IllegalArgumentException.class, () -> new FloorDoor(BOX, BOX, AT, Optional.of(BOX), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FloorDoor(BOX, BOX, AT, Optional.empty(), Optional.of(AT)));
    }

    private static JsonObject box(int x0, int y0, int z0, int x1, int y1, int z1) {
        JsonObject out = new JsonObject();
        JsonArray min = new JsonArray();
        JsonArray max = new JsonArray();
        for (int v : new int[] {x0, y0, z0}) min.add(v);
        for (int v : new int[] {x1, y1, z1}) max.add(v);
        out.add("min", min);
        out.add("max", max);
        return out;
    }

    private static JsonObject anchor(int x, int y, int z, int yaw) {
        JsonObject out = new JsonObject();
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("yaw", yaw);
        return out;
    }

    @Test
    @DisplayName("the floor JSON of a stair door reads back, and an old door with no stair still reads")
    void parsing() {
        JsonObject withStair = new JsonObject();
        withStair.add("open", box(1, 2, 3, 4, 5, 6));
        withStair.add("frame", box(1, 7, 3, 4, 8, 6));
        withStair.add("pedestal", anchor(2, 2, 9, 0));
        withStair.add("stair_top", box(27, 11, 2, 28, 12, 4));
        withStair.add("arrival", anchor(10, 16, 41, 270));
        JsonObject plain = new JsonObject();
        plain.add("open", box(1, 2, 3, 4, 5, 6));
        plain.add("frame", box(1, 7, 3, 4, 8, 6));
        plain.add("pedestal", anchor(2, 2, 9, 0));
        JsonArray doors = new JsonArray();
        doors.add(withStair);
        doors.add(plain);
        JsonObject root = new JsonObject();
        root.add("doors", doors);
        List<FloorDoor> read = FloorDoor.list(root, "doors");
        assertEquals(new FloorBox(27, 11, 2, 28, 12, 4), read.get(0).stairTop().orElseThrow());
        assertEquals(new FloorAnchor(10, 16, 41, 270), read.get(0).arrival().orElseThrow());
        assertTrue(read.get(1).stairTop().isEmpty());
        assertTrue(read.get(1).arrival().isEmpty());
    }

    @Test
    @DisplayName("a team spreads sideways from the arrival spot: 0, +1, -1, +2, -2")
    void spread() {
        assertEquals(List.of(0, 1, -1, 2, -2, 3), List.of(0, 1, 2, 3, 4, 5).stream().map(LaneStairs::spread).toList());
    }

    @Test
    @DisplayName("the landing is the whole block, from its floor to two blocks up")
    void inside() {
        BlockPos min = new BlockPos(100, 21, 52);
        BlockPos max = new BlockPos(101, 22, 54);
        assertTrue(LaneStairs.inside(100.5, 21, 53.5, min, max));
        assertTrue(LaneStairs.inside(101.99, 21, 52.01, min, max));
        assertFalse(LaneStairs.inside(102.1, 21, 53, min, max), "one block along the stair is not on the landing");
        assertFalse(LaneStairs.inside(100.5, 16, 53, min, max), "nor is the vestibule five blocks below");
        assertFalse(LaneStairs.inside(100.5, 21, 55.5, min, max));
    }

    @Test
    @DisplayName("props stand behind the party: west when it faces east (yaw 270), east when it faces west")
    void propsStandBehind() {
        BlockPos arrival = new BlockPos(10, 16, 41);
        LaneArrival.Prop prop = new LaneArrival.Prop(4, -1, 1, null);
        assertEquals(new BlockPos(6, 17, 40), LaneArrival.where(arrival, 270, prop));
        assertEquals(new BlockPos(14, 17, 40), LaneArrival.where(arrival, 90, prop));
    }

    @Test
    @DisplayName("a climb has two stages: the landing, then the walk from the arrival hall; cancelling clears both")
    void roundStages() {
        IntermissionRound round = new IntermissionRound();
        round.beginWalk(1_000, 90_000);
        round.beginClimb(2);
        assertTrue(round.climbing());
        assertEquals(2, round.climbLane());
        assertFalse(round.fading());
        round.beginFade(2_000, 1_200);
        assertTrue(round.fading());
        assertFalse(round.fadeDue(3_199));
        assertTrue(round.fadeDue(3_200));
        round.markTransferred(3_200, 45_000);
        assertFalse(round.climbing(), "once taken up the walk is into the arena");
        assertFalse(round.fading());
        assertTrue(round.transferred());
        assertTrue(round.walking());
        assertFalse(round.walkDue(48_199));
        assertTrue(round.walkDue(48_200));
        round.cancelWalk();
        assertEquals(-1, round.climbLane());
        assertFalse(round.transferred());
        assertFalse(round.walking());
    }
}
