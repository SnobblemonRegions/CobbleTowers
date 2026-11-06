"""The validator must bite: each mutation of a good tower has to fail validation with the expected complaint.

    python -m unittest tools/halloween_tower/test_hollow_tower.py   (from the repo root)
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import hollow_tower as ht  # noqa: E402


class HollowTowerTest(unittest.TestCase):
    def setUp(self):
        self.t = ht.build()

    def complaints(self):
        return "\n".join(ht.validate(self.t))

    def test_the_tower_is_valid(self):
        self.assertEqual([], ht.validate(self.t))

    def test_gates_and_doors_are_bars_all_through(self):
        """The wall-aging pass once scattered cracked and gilded bricks over the bars of gate A and the doors: aging only touches bricks."""
        for floor in range(1, 11):
            lay = ht.layout(floor)
            boxes = [("gate A", lay["gate_a"])] + [(f"door {i}", d["open"]) for i, d in enumerate(lay["doors"])]
            for name, b in boxes:
                for x in range(b["min"][0], b["max"][0] + 1):
                    for y in range(b["min"][1], b["max"][1] + 1):
                        for z in range(b["min"][2], b["max"][2] + 1):
                            self.assertEqual("minecraft:iron_bars", self.t.at(x, y, z), (floor, name, x, y, z))

    def test_dimensions_and_floor_levels(self):
        self.assertEqual((97, 121, 97), self.t.grid.shape)
        self.assertEqual([6, 16, 26, 36, 46, 56, 66, 76, 86, 96], [ht.feet(k) for k in range(1, 11)])
        top = ht.feet(10) + ht.CLEAR + 1
        self.assertLessEqual(top + 8, ht.SIZE_Y - 1)

    def test_every_anchor_stands_on_something(self):
        for floor in range(1, 11):
            for name in ("entry", "presentation", "exit"):
                a = ht.layout(floor)[name]
                self.assertTrue(ht.standable(self.t, a["x"], a["y"], a["z"]), (floor, name))

    def test_mirroring_swaps_the_halls_every_other_floor(self):
        self.assertEqual(24, ht.layout(1)["entry"]["x"])
        self.assertEqual(72, ht.layout(2)["entry"]["x"])
        self.assertEqual(24, ht.layout(3)["entry"]["x"])
        self.assertEqual(270, ht.layout(1)["entry"]["yaw"])
        self.assertEqual(90, ht.layout(2)["entry"]["yaw"])

    def test_an_open_gate_a_is_caught(self):
        g = ht.layout(3)["gate_a"]
        (ax, ay, az), (bx, by, bz) = g["min"], g["max"]
        self.t.grid[ax:bx + 1, ay:by + 1, az:bz + 1] = 0
        self.assertIn("vendor's room is reachable with gate A closed", self.complaints())

    def test_an_open_route_door_is_caught(self):
        d = ht.layout(3)["doors"][1]["open"]
        (ax, ay, az), (bx, by, bz) = d["min"], d["max"]
        self.t.grid[ax:bx + 1, ay:by + 1, az:bz + 1] = 0
        self.assertIn("chamber 1 is reachable with its door shut", self.complaints())

    def test_there_is_no_gate_b(self):
        for floor in range(1, 11):
            self.assertNotIn("gate_b", ht.layout(floor))

    def test_a_sealed_back_doorway_is_caught(self):
        f = ht.feet(5)
        self.t.mirror = False
        # wall off the back doorway of the left chamber: its stair is then not reachable through its door
        self.t.fill(35, 37, f, f + 3, 5, 5, ht.PLAIN_WALL)
        self.assertIn("the threshold of lane 0 is not reachable through door 0", self.complaints())

    def test_a_hole_between_two_chambers_is_caught(self):
        f = ht.feet(5)
        self.t.mirror = False
        # a doorway through the wall between chambers 1 and 2: one door would then reach both lanes
        self.t.fill(54, 54, f, f + 3, 10, 12, ht.AIR)
        complaints = self.complaints()
        self.assertIn("chamber 2 is reachable through door 1", complaints)
        self.assertIn("the threshold of lane 2 is reachable through door 1", complaints)

    def test_the_doors_run_west_to_east_with_a_pedestal_each(self):
        for floor in range(1, 11):
            doors = ht.layout(floor)["doors"]
            self.assertEqual(3, len(doors))
            xs = [d["pedestal"]["x"] for d in doors]
            self.assertEqual(sorted(xs), xs)
            self.assertEqual([36, 48, 60], xs)
            for d in doors:
                self.assertEqual((d["open"]["min"][0] + d["open"]["max"][0]) // 2, d["pedestal"]["x"], "a pedestal stands right in front of its door")
                self.assertTrue(ht.standable(self.t, d["pedestal"]["x"], d["pedestal"]["y"], d["pedestal"]["z"]))

    def test_a_walled_arrival_door_is_caught(self):
        f = ht.feet(2)
        self.t.mirror = True
        self.t.fill(18, 19, f, f + 5, 46, 50, ht.PLAIN_WALL)
        self.assertIn("floor 1: from the arrival of lane 0 the next floor's arena door is not reachable", self.complaints())

    def test_a_missing_step_is_caught(self):
        f = ht.feet(5)
        self.t.mirror = False
        # floor 5 is odd, so it is unmirrored: lane 1 climbs east from its doorway, step 3 is at x = 48 + 4
        self.t.fill(52, 52, f + 2, f + 2, 2, 4, ht.AIR)
        self.assertIn("the threshold of lane 1 is not reachable through door 1", self.complaints())

    def test_a_stair_that_climbs_past_its_landing_is_caught(self):
        f = ht.feet(5)
        self.t.mirror = False
        self.t.fill(56, 56, f + 5, f + 5, 2, 4, ht.PLAIN_WALL)       # a block on lane 1's landing: the party still gets there, one block higher
        self.t.fill(55, 55, f + 5, f + 5, 2, 4, ht.PLAIN_WALL)
        self.assertIn("the threshold of lane 1", self.complaints())

    def test_the_arrival_of_each_lane_is_on_the_next_floor(self):
        for floor in range(1, 10):
            for lane, door in enumerate(ht.layout(floor)["doors"]):
                a = door["arrival"]
                self.assertEqual(ht.feet(floor + 1), a["y"])
                self.assertTrue(ht.standable(self.t, a["x"], a["y"], a["z"]), (floor, lane))
        self.assertNotIn("arrival", ht.layout(10)["doors"][0])
        self.assertNotIn("stair_top", ht.layout(10)["doors"][0])

    def test_lane_zero_arrives_on_the_left_whichever_way_the_floor_faces(self):
        for floor in range(1, 10):
            z = [d["arrival"]["z"] for d in ht.layout(floor)["doors"]]
            self.assertEqual(sorted(z) if not ht.mirrored(floor + 1) else sorted(z, reverse=True), z, floor)

    def test_a_cobweb_in_the_walking_volume_is_caught(self):
        f = ht.feet(7)
        self.t.set(30, f + 1, 30, ht.COBWEB)
        self.assertIn("cobwebs inside the walking volume", self.complaints())

    def test_a_lane_stair_is_five_steps_and_a_landing(self):
        f = ht.feet(1)
        for lane, c in enumerate(ht.CHAMBER_CENTERS):
            dx = ht.LANE_DX[lane]
            facing = "west" if dx < 0 else "east"
            for i in range(1, ht.STUB_STEPS + 1):
                self.assertEqual(ht.STAIR_BLOCK.replace("facing=south", f"facing={facing}"), self.t.at(c + dx * (1 + i), f + i - 1, 3), (lane, i))
            top = ht.stair_top(1, c)
            self.assertEqual(f + ht.STUB_STEPS, top["min"][1])

    def test_nothing_unknown_in_the_palette(self):
        import interiors
        # every block is a constant of the generator or of the interiors, or a stair, slab or jack-o'-lantern those modules build by helper
        def base(v):
            return v.split("[")[0]
        known = {base(v) for mod in (ht, interiors) for v in vars(mod).values() if isinstance(v, str) and v.startswith("minecraft:")}
        known |= {"minecraft:polished_blackstone_brick_stairs", "minecraft:polished_blackstone_brick_slab", "minecraft:jack_o_lantern"}
        self.assertEqual(set(), {base(v) for v in self.t.palette} - known)


class RoomsInChambersTest(unittest.TestCase):
    """Every room, in every chamber of a mirrored and an unmirrored floor, still lets the party through from the door to its lane's threshold."""

    def place(self, t, floor, door_index, room):
        import rooms
        lay = ht.layout(floor)
        door = lay["doors"][door_index]
        cx = (door["open"]["min"][0] + door["open"]["max"][0]) // 2
        f = ht.feet(floor)
        x0, z0 = cx - 5, door["open"]["min"][2] - rooms.D
        t.grid[x0:x0 + rooms.W, f:f + 8, z0:z0 + rooms.D] = 0                       # the dresser clears the chamber first
        for x in range(rooms.W):
            for y in range(rooms.H):
                for z in range(rooms.D):
                    block = int(room.grid[x, y, z])
                    if block:
                        t.grid[x0 + x, f - 1 + y, z0 + z] = t.block_id(room.palette[block])

    def test_every_room_in_every_chamber(self):
        sys.path.insert(0, str(Path(__file__).parent))
        import rooms
        for floor in (2, 3):
            for kind in rooms.ROOMS:
                t = ht.build()
                for i in range(3):
                    self.place(t, floor, i, rooms.build(kind))
                lay = ht.layout(floor)
                entry = ht.cell_of(lay, "entry")
                for i, door in enumerate(lay["doors"]):
                    through = ht.reach(t, entry, [lay["gate_a"], door["open"]])
                    top = tuple(ht._mid(door["stair_top"], ht.feet(floor) + ht.STUB_STEPS))
                    self.assertIn(top, through, f"floor {floor}, {kind} behind door {i}: the threshold is not reachable")


if __name__ == "__main__":
    unittest.main()
