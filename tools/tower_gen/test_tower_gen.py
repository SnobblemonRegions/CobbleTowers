"""The generator's validator must bite: each mutation of a good tower has to fail with the expected complaint.

    py -3.14 -I tools/tower_gen/test_tower_gen.py      (from the repo root)
"""
import copy
import re
import sys
import unittest

import numpy as np
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import tower_gen as tg  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
THEME = "neutral"


class TowerGenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.acts, cls.prep, cls.stats = tg.build_all(THEME)

    def setUp(self):
        self.a = {k: copy.deepcopy(g) for k, g in self.acts.items()}
        self.p = copy.deepcopy(self.prep)

    def complaints(self):
        return "\n".join(tg.validate(self.a, THEME) + tg.validate_prep(self.p) + tg.shells(self.a, self.p, THEME))

    # ---- the good tower ---------------------------------------------------------------------------------------------------

    def test_the_tower_is_valid(self):
        self.assertEqual("", self.complaints())

    def test_trimming_keeps_well_under_half_of_each_building(self):
        for name, (solid, dropped) in self.stats.items():
            self.assertLess((solid - dropped) / solid, 0.4, name)

    def test_the_dresser_box_matches_room_dresser(self):
        """The chamber size is the runtime's: RoomDresser clears and pastes ROOM_WIDTH x ROOM_HEIGHT x ROOM_DEPTH north of the door."""
        java = (ROOT / "src/main/java/com/cobbletowers/route/RoomDresser.java").read_text()
        size = {k: int(re.search(rf"{k} = (\d+);", java).group(1)) for k in ("ROOM_WIDTH", "ROOM_HEIGHT", "ROOM_DEPTH")}
        self.assertEqual(size["ROOM_WIDTH"], 2 * tg.CHAMBER_HALF + 1)
        self.assertEqual(size["ROOM_HEIGHT"], tg.CHAMBER_H)
        self.assertEqual(size["ROOM_DEPTH"], tg.CHAMBER_Z[1] - tg.CHAMBER_Z[0] + 1)
        self.assertIn("door.open().minZ() - ROOM_DEPTH", java)

    def test_gates_and_doors_are_bars(self):
        bosses = tg.build_bosses(THEME, do_trim=False)[0]
        for floor in range(1, 11):
            lay = tg.layout(floor, THEME)
            g = bosses[floor] if floor in tg.BOSS_FLOORS else self.a[tg.act_of(floor)]
            for name, b in [("gate A", lay["gate_a"])] + [(f"door {i}", d["open"]) for i, d in enumerate(lay["doors"])]:
                for x in range(b["min"][0], b["max"][0] + 1):
                    for y in range(b["min"][1], b["max"][1] + 1):
                        for z in range(b["min"][2], b["max"][2] + 1):
                            self.assertEqual(tg.BARS, g.name(x, y, z).split("[")[0], (floor, name, x, y, z))   # joined bars carry their connections

    def test_the_boss_floors_are_their_own_buildings_and_floor_ten_has_no_stairs(self):
        """Owner, 15 Nov 2026: floors 5 and 10 are boss halls of their own; floor 4 climbs into boss 5, boss 5 into act 2."""
        four, five = tg.layout(4, THEME), tg.layout(5, THEME)
        self.assertEqual("cobbletowers:neutral_act1", four["structure"])
        self.assertEqual("cobbletowers:neutral_boss5", five["structure"])
        self.assertEqual(tg.boss_spec(THEME).boss[5].base_feet, four["doors"][0]["arrival"]["y"])
        self.assertEqual(tg.feet(1), five["doors"][0]["arrival"]["y"])
        self.assertEqual("cobbletowers:neutral_act2", tg.layout(6, THEME)["structure"])
        self.assertEqual("cobbletowers:neutral_boss10", tg.layout(10, THEME)["structure"])
        self.assertTrue(all("stair_top" not in d for d in tg.layout(10, THEME)["doors"]))

    def test_arrivals_move_with_the_building_they_land_in(self):
        """An act is written PAD_Z rows padded, a boss building is not: an arrival is shifted by its target's padding, not its own floor's."""
        four, five, six = (tg.final_layout(k, THEME) for k in (4, 5, 6))
        self.assertEqual(tg.layout(4, THEME)["doors"][1]["arrival"]["z"], four["doors"][1]["arrival"]["z"])
        self.assertEqual(tg.layout(5, THEME)["doors"][1]["arrival"]["z"] + tg.PAD_Z, five["doors"][1]["arrival"]["z"])
        self.assertEqual(tg.layout(5, THEME)["entry"]["z"], five["entry"]["z"])
        self.assertEqual(tg.layout(6, THEME)["entry"]["z"] + tg.PAD_Z, six["entry"]["z"])

    def test_the_rescue_spot_in_front_of_the_vendor_is_open_floor(self):
        """TowerGates.rescueFromGallery stands players at the vendor + 2.5 z."""
        lay = tg.layout(3, THEME)
        w = tg.Walker(self.a[1])
        self.assertTrue(w.standable(lay["exit"]["x"], lay["exit"]["y"], lay["exit"]["z"] + 2))

    def test_every_building_fits_the_chunks_a_cell_holds(self):
        """The first rig run of a 118-row act lost its north rows (the lane stairs): a cell only pastes and clears its held chunks (11 x 11)."""
        java = (ROOT / "src/main/java/com/cobbletowers/instance/CellTickets.java").read_text()
        radius = int(re.search(r"RADIUS_CHUNKS = (\d+);", java).group(1))
        self.assertEqual((-16 * radius, 16 * radius + 15), (tg.HELD_LOW, tg.HELD_HIGH))
        for a in (1, 2):
            self.assertEqual([], tg.held_window_problems(tg.padded(self.acts[a]).grid, f"act{a}"))
        self.assertEqual([], tg.held_window_problems(self.prep.grid, "prep"))
        too_long = np.pad(self.acts[1].grid, ((0, 0), (0, 0), (0, 90)))           # 90 empty rows south push the north rows out of the window
        self.assertTrue(tg.held_window_problems(too_long, "act1"), "a building longer than the held window is caught")

    def test_the_padding_moves_every_anchor_with_the_blocks(self):
        lay = tg.layout(2, THEME)
        moved = tg.shift_layout(lay, tg.PAD_Z)
        self.assertEqual(lay["entry"]["z"] + tg.PAD_Z, moved["entry"]["z"])
        self.assertEqual(lay["doors"][1]["stair_top"]["min"][2] + tg.PAD_Z, moved["doors"][1]["stair_top"]["min"][2])
        self.assertEqual(lay["doors"][1]["arrival"]["z"] + tg.PAD_Z, moved["doors"][1]["arrival"]["z"])
        g = tg.padded(self.acts[1])
        e = moved["entry"]
        self.assertTrue(tg.Walker(g).standable(e["x"], e["y"], e["z"]))

    # ---- mutations ---------------------------------------------------------------------------------------------------------

    def test_a_hole_between_two_chambers_is_caught(self):
        f = tg.feet(2)
        x = tg.LANES[0] + tg.CHAMBER_HALF + 1                       # the wall between chamber 0 and chamber 1
        self.a[1].air(x, f, 20, x + 4, f + 1, 20)
        self.a[1].box("floor", x, f - 1, 20, x + 4, f - 1, 20)     # trimming hollowed the thick wall, so the hole needs a floor
        self.assertIn("floor 2: chamber 1 is reachable through door 0", self.complaints())

    def test_a_missing_step_is_caught(self):
        f = tg.feet(3)
        c = tg.LANES[1]
        z = tg.VEST_Z - 3                                           # step 3
        self.a[1].air(c - tg.STUB_HALF, f, z, c + tg.STUB_HALF, f + 2, z)
        self.assertIn("floor 3: the threshold of lane 1 is not reachable through door 1", self.complaints())

    def test_an_open_gate_is_caught(self):
        b = tg.layout(4, THEME)["gate_a"]
        self.a[1].air(*b["min"], *b["max"])
        self.assertIn("floor 4: the vendor is reachable with gate A shut", self.complaints())

    def test_a_block_in_the_dresser_box_is_caught(self):
        f = tg.feet(1)
        self.a[2].at("wall", tg.LANES[2], f + 3, 20)
        self.assertIn("floor 6: RoomDresser's box behind door 2 is not empty air", self.complaints())

    def test_a_gap_in_the_gallery_rail_is_caught(self):
        f = tg.feet(2)
        self.a[1].air(tg.GALLERY_E[0], f + 6, 70, tg.GALLERY_E[0], f + 6, 70)
        self.assertIn("floor 2: the gallery rail at x 56 has gaps", self.complaints())

    def test_a_hole_through_the_shell_is_caught(self):
        f = tg.feet(1)
        self.a[1].air(0, f + 2, 70, tg.HALL_X[0] - 1, f + 2, 70)    # a tunnel from the battle hall out through its west wall
        self.assertIn("act 1: open space from the rooms", self.complaints())

    def test_an_unreachable_dais_is_caught(self):
        lay = tg.prep_layout()
        d = lay["dais"]
        x0 = d["min"][0] - 6                                        # a wall right across the hall, west of the dais steps
        self.p.box("wall", x0, tg.PREP_FEET, tg.PREP_ROOM[2], x0, tg.PREP_FEET + 10, tg.PREP_ROOM[3])
        self.assertIn("prep: the ready dais is not reachable from the entry", self.complaints())


if __name__ == "__main__":
    unittest.main()
