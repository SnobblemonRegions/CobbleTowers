#!/usr/bin/env python3
"""The themed tower generator (P59): two act buildings and a preparation room per tower, built from roles, trimmed to shells, validated.

    py -3.14 -I tools/tower_gen/tower_gen.py                  # build, validate, write .nbt and the layout JSON to validation/schematics/<theme>/
    py -3.14 -I tools/tower_gen/tower_gen.py --check          # build and validate only
    py -3.14 -I tools/tower_gen/tower_gen.py --install        # also copy the .nbt files into src/main/resources/data/cobbletowers/structure/

Coordinates are building-local: x east, y up, z south. The party walks NORTH (-z) through a floor, because RoomDresser clears and dresses an
11 x 15 x 8 box directly north of each door's opening (`back = door.open().minZ() - ROOM_DEPTH`). One floor, south to north:

    z 93-104  ARRIVAL hall (the lane stairs of the floor below land here, one arch per lane on the south wall)
    z 91-92   wall with the passage into the battle hall
    z 47-90   BATTLE HALL: 60 x 44, a barrel vault, two spectator galleries, the medallion
    z 45-46   wall with GATE A (iron bars until the floor is won)
    z 30-44   ATRIUM: the vendor stand (east), the pedestals, three doors in its north wall
    z 28-29   the door wall
    z 13-27   three CHAMBERS, exactly RoomDresser's box: 11 wide, 15 deep, 8 high (feet .. feet + 7), with a vault above it
    z 12      the chamber's back wall, a doorway three wide
    z 3-11    that lane's own stair: a vestibule, five steps north, a landing; the landing's last two rows are the threshold (fade + teleport)

Floors stack at a 24-block pitch; the top floor of each act is the crown (a taller hall). Floor 10 has no stairs: it is left by teleport.

A cell only holds (loads, pastes and clears) the 11 x 11 chunks round its centre: 80 blocks north or west of it to 95 south or east, and a
structure is pasted centred (CellTickets.pasteOrigin). So a building must store nothing outside that window: the act is padded with empty rows
on its north side (PAD_Z) so its 110 rows land inside, and `held_window_problems` checks every stored block. The first rig run of the 118-row
plan lost its north rows (the lane stairs) to this.
Everything a player cannot see is left out (a 2-block shell, see `trim`), which is most of the box.
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from collections import deque
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(ROOT / "validation"))
from themes import THEMES  # noqa: E402

DATA_VERSION = 3955                     # 1.21.1
FLOORS, PER_ACT = 10, 4
ACT_FLOORS = {1: (1, 2, 3, 4), 2: (6, 7, 8, 9)}
BOSS_FLOORS = (5, 10)                   # owner, 15 Nov 2026: the boss floors are halls of their own (boss_<theme>.py), each its own building
PITCH = 24
BASE_FEET = 4                           # floor 1 of an act stands at y 4 (its floor block is y 3)
SX, SZ = 66, 110
SY = BASE_FEET + PITCH * (PER_ACT - 1) + 30     # the crown's vault tops out at feet + 26

# ---- the plan (x) -----------------------------------------------------------------------------------------------------------
HALL_X = (2, 62)                        # 61 wide, centred on MID_X like the lanes, the gate and the medallion
MID_X = 32
LANES = (16, 32, 48)                    # door / chamber / stair centres, west to east: lane 0 is the left door for somebody facing it
DOOR_HALF = 2                           # doors are 5 wide, 6 high (the grand-door size)
CHAMBER_HALF = 5                        # RoomDresser.ROOM_WIDTH = 11
STUB_HALF = 2                           # a lane stair is 5 wide
GATE_X = (29, 35)
PASSAGE_X = (28, 36)
ARRIVAL_X = (11, 53)
GALLERY_W, GALLERY_E = (2, 8), (56, 62)
PIERS_X = (9, 55)                       # mirrored about MID_X; each stands under a vault rib and runs up to it
STAND = (52, 31)                        # the vendor stand's north-west corner in the atrium; the vendor stands at STAND + (3, 2), facing south

# ---- the plan (z), north to south -----------------------------------------------------------------------------------------------
LANDING_Z = (3, 5)                      # threshold = the two northernmost rows, z 3..4
STEP_Z = (6, 10)                        # step i (1..5) at z 11 - i, top at feet + i - 1
VEST_Z = 11
BACK_Z = 12
CHAMBER_Z = (13, 27)                    # RoomDresser.ROOM_DEPTH = 15
DOOR_WALL_Z = (28, 29)
ATRIUM_Z = (30, 44)
GATE_Z = (45, 46)
HALL_Z = (47, 90)
PASSAGE_Z = (91, 92)
ARRIVAL_Z = (93, 104)
ARCH_Z = (105, 106)
PEDESTAL_Z = 33
ARRIVAL_ANCHOR_Z = 101
MEDALLION = (MID_X, 68)
PIER_Z0 = HALL_Z[0] + 3                 # column rows (and the hall's ribs) every 6 from here: 50 .. 86, mirrored about the medallion

STEPS = 5
CHAMBER_H = 8                           # RoomDresser.ROOM_HEIGHT
HALL_SPRING, HALL_RISE = 11, 8
CROWN_SPRING, CROWN_RISE = 14, 12

# The chunks a cell holds, relative to its centre (a chunk boundary): CellTickets.RADIUS_CHUNKS = 5 either side of the centre chunk.
HELD_LOW, HELD_HIGH = -80, 95
PAD_Z = 16                              # empty rows added north of each act, so its 110 rows sit inside the held window (14..18 would do)

AIR = "minecraft:air"
BARS = "minecraft:iron_bars"
CHAIN = "minecraft:chain[axis=y]"
LANTERN = "minecraft:lantern[hanging=true]"
SEE_THROUGH = ("air", "glass", "slab", "stairs", "_wall", "fence", "bars", "pane", "lantern", "chain", "carpet", "door", "torch", "barrel",
               "flower_pot", "cauldron")


def feet(local_floor: int) -> int:
    return BASE_FEET + PITCH * (local_floor - 1)


def act_of(floor: int) -> int:
    return 1 if floor < BOSS_FLOORS[0] else 2


def local(floor: int) -> int:
    return ACT_FLOORS[act_of(floor)].index(floor) + 1


class Grid:
    """A block grid with a palette. Blocks are given as theme roles or as block ids."""

    def __init__(self, theme: dict[str, str], size: tuple[int, int, int]) -> None:
        self.theme = theme
        self.grid = np.zeros(size, dtype=np.uint16)
        self.palette = [AIR]
        self.index = {AIR: 0}
        self.removed = np.zeros(size, dtype=bool)       # what trim() left out

    def id(self, block: str) -> int:
        block = self.theme.get(block, block)
        found = self.index.get(block)
        if found is None:
            found = self.index[block] = len(self.palette)
            self.palette.append(block)
        return found

    def box(self, block: str, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
        xa, xb = sorted((x0, x1))
        ya, yb = sorted((y0, y1))
        za, zb = sorted((z0, z1))
        self.grid[xa:xb + 1, ya:yb + 1, za:zb + 1] = self.id(block)

    def at(self, block: str, x: int, y: int, z: int) -> None:
        self.box(block, x, y, z, x, y, z)

    def air(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
        self.box(AIR, x0, y0, z0, x1, y1, z1)

    def name(self, x: int, y: int, z: int) -> str:
        return self.palette[int(self.grid[x, y, z])]


# ---- room pieces --------------------------------------------------------------------------------------------------------------

def room(g: Grid, x0: int, x1: int, z0: int, z1: int, f: int, height: int) -> None:
    """A rectangular room in the solid: air feet .. feet + height - 1, a floor, and a two-row plinth on its walls."""
    g.air(x0, f, z0, x1, f + height - 1, z1)
    g.box("floor", x0, f - 1, z0, x1, f - 1, z1)
    for z in (z0 - 1, z1 + 1):
        g.box("wall_low", x0, f, z, x1, f + 1, z)
    for x in (x0 - 1, x1 + 1):
        g.box("wall_low", x, f, z0, x, f + 1, z1)


def vault(g: Grid, x0: int, x1: int, z0: int, z1: int, f: int, spring: int, rise: int, rib_every: int, coves: bool = True,
          rib_from: int | None = None) -> None:
    """A barrel vault along z: clear to feet + spring - 1 at the walls, climbing by `rise` to the ridge. Ribs stand one block proud every
    `rib_every` rows from `rib_from` (z0 when not given; never lower than feet + 8, so they stay over everybody's head); light coves run under a
    trim band on both long walls."""
    cx, half = (x0 + x1) / 2, (x1 - x0) / 2 + 0.5
    for x in range(x0, x1 + 1):
        top = f + spring - 1 + int(round(rise * math.sqrt(max(0.0, 1 - ((x - cx) / half) ** 2))))
        g.air(x, f, z0, x, top, z1)
        g.box("vault", x, top + 1, z0, x, top + 1, z1)
        if top >= f + 8:
            for z in range(z0 if rib_from is None else rib_from, z1 + 1, rib_every):
                g.at("rib", x, top, z)
    if coves:
        for x in (x0 - 1, x1 + 1):
            g.box("trim", x, f + spring - 1, z0, x, f + spring - 1, z1)
            for z in range(z0 + 1, z1, 3):
                g.at("light", x, f + spring - 2, z)


def column(g: Grid, x: int, z: int, f: int) -> None:
    """A column from the floor to whatever is over it (a vault rib or the vault itself), with a trim capital where it meets it.
    A theme may give `column` (a fluid such as water) instead: the core is that fluid and every air block beside it becomes `glass`, so the
    fluid has nowhere to flow. The floor under it and the capital over it are solid, and walls, rails and decks already beside it hold too."""
    top = ceiling_above(g, x, f, z)
    core = g.theme.get("column", "pier")
    if core != "pier":
        # each pane of the casing runs up to whatever is over it, beside the capital too: stopped at the core's height it left a gap under a
        # curved ceiling and the column read as unfinished
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):     # a square shaft: corners too, no nooks
            cx, cz = x + dx, z + dz
            for y in range(f, ceiling_above(g, cx, f, cz) + 1):
                if g.grid[cx, y, cz] == 0:
                    g.at("glass", cx, y, cz)
    g.box(core, x, f, z, x, top - 1, z)
    g.at("trim", x, top, z)


def ceiling_above(g: Grid, x: int, y: int, z: int) -> int:
    """The last air block above (x, y, z) before something solid."""
    while y + 1 < g.grid.shape[1] and g.grid[x, y + 1, z] == 0:
        y += 1
    return y


def chandelier(g: Grid, x: int, z: int, f: int, low: int) -> None:
    """Light brought down to the people: a chain from the ceiling to feet + low, a light block, a lantern on each side and one below.
    Block light loses a level per block, so in a hall over about 10 high a lamp in the vault leaves the floor dark."""
    top = ceiling_above(g, x, f + low, z)
    y = f + low
    g.box(CHAIN, x, y + 2, z, x, top, z)
    g.at("trim", x, y + 1, z)
    g.at("light", x, y, z)
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (0, 0)):
        g.at(LANTERN, x + dx, y - 1, z + dz)


def medallion(g: Grid, cx: int, cz: int, ground: int, r: int) -> None:
    for x in range(cx - r, cx + r + 1):
        for z in range(cz - r, cz + r + 1):
            d = math.hypot(x - cx, z - cz)
            if r - 1 <= d <= r:
                g.at("inlay", x, ground, z)
            elif r - 3 <= d < r - 2:
                g.at("light" if (x == cx or z == cz) else "trim", x, ground, z)
            elif d < 2:
                g.at("accent", x, ground, z)
            elif d < r - 3 and (x == cx or z == cz or abs(x - cx) == abs(z - cz)):
                g.at("inlay", x, ground, z)


def banner(g: Grid, x: int, y_top: int, z: int, length: int) -> None:
    g.box("hanging", x, y_top - length + 1, z, x + 1, y_top, z)
    g.box("accent", x, y_top - length, z, x + 1, y_top - length, z)


def vendor_stand(g: Grid, x: int, z: int, f: int, facing: str) -> None:
    """A market stall 7 wide. In stall rows v (0 the back, 4 the open floor in front), mapped to z by `facing`: south, z + v; north, z + 4 - v.
    The vendor stands at row 2 (z + 2 either way), behind a counter one block high at row 3 so the villager shows from the waist up (a two-high
    counter hid it, first rig run). Row 4 stays open floor: TowerGates.rescueFromGallery stands rescued players at the vendor's spot + 2.5 z.

    Back wall of stocked shelves with candles and lanterns, stripped-log corner posts, plank side panels, an upside-down-stair counter with a lip,
    a rug, a striped awning stepping down to the front with a scalloped valance, lanterns over the vendor and a framed sign board on top."""
    flip = facing == "north"

    def zv(v: int) -> int:
        return z + 4 - v if flip else z + v

    def put(block: str, u0: int, u1: int, y0: int, y1: int, v0: int, v1: int) -> None:
        g.box(block, x + u0, f + y0, zv(v0), x + u1, f + y1, zv(v1))

    toward_vendor = "south" if flip else "north"      # the counter's solid half is on the vendor's side, the lip overhangs the customers
    # floor: a rug inside the stall
    put("inlay", 1, 5, -1, -1, 1, 2)
    # back wall: shelves of stock, two high, with candles and lanterns along the top
    for u in range(1, 6):
        lower, upper = ("minecraft:barrel", "minecraft:bookshelf") if u % 2 else ("minecraft:bookshelf", "minecraft:barrel")
        put(lower, u, u, 0, 0, 0, 0)
        put(upper, u, u, 1, 1, 0, 0)
        put("minecraft:candle[candles=3,lit=true]" if u % 2 else "minecraft:lantern[hanging=false]", u, u, 2, 2, 0, 0)
    # a plank back panel behind the shelves, up to the awning, with a trim stripe: the stall reads as a booth, not a counter in front of a wall
    put("wood", 0, 6, 0, 4, -1, -1)
    put("trim", 0, 6, 3, 3, -1, -1)
    # barrels stacked at the front corners, outside the stall, with a lantern on each
    for u in (-1, 7):
        put("minecraft:barrel", u, u, 0, 1, 3, 3)
        put("minecraft:lantern[hanging=false]", u, u, 2, 2, 3, 3)
    # corner posts and side panels
    for u in (0, 6):
        for v in (0, 3):
            put("minecraft:stripped_spruce_log[axis=y]", u, u, 0, 3, v, v)
        put("wood", u, u, 0, 0, 1, 2)
        put("minecraft:spruce_slab[type=bottom]", u, u, 1, 1, 1, 2)
    # the counter: one block high, an upside-down stair so its top overhangs the customers' side, candles at its ends
    put(f"minecraft:spruce_stairs[facing={toward_vendor},half=top,shape=straight]", 1, 5, 0, 0, 3, 3)
    put("minecraft:candle[candles=2,lit=true]", 1, 1, 1, 1, 3, 3)
    put("minecraft:candle[candles=2,lit=true]", 5, 5, 1, 1, 3, 3)
    # the awning: stripes along the stall, higher at the back, stepping down over the counter and out over the customers
    for u in range(0, 7):
        stripe = "hanging" if u % 2 == 0 else "minecraft:white_wool"
        put(stripe, u, u, 5, 5, -1, 1)
        put(stripe, u, u, 4, 4, 2, 4)
        if u % 2 == 0:
            put("hanging", u, u, 3, 3, 4, 4)            # the scalloped valance along the front edge
    # light over the vendor, under the awning
    for u in (2, 4):
        put(LANTERN, u, u, 3, 3, 2, 2)
    # the sign board on top of the awning's back: trimmed ends, an accent panel, a lantern hanging in front of it
    put("trim", 1, 1, 6, 7, 0, 0)
    put("trim", 5, 5, 6, 7, 0, 0)
    put("accent", 2, 4, 6, 7, 0, 0)
    # the board hangs from the ceiling: the chain runs all the way up to it (two links stopped a block short and hung in the air)
    top = ceiling_above(g, x + 3, f + 8, zv(0))
    put("minecraft:chain[axis=y]", 3, 3, 8, top - f, 0, 0)


# ---- one floor ----------------------------------------------------------------------------------------------------------------

def build_floor(g: Grid, floor: int) -> None:
    f = feet(local(floor))
    ground = f - 1
    crown = False                       # the act's top floor is an ordinary hall now: the boss floors are their own buildings
    stairs = floor < FLOORS
    spring, rise = (CROWN_SPRING, CROWN_RISE) if crown else (HALL_SPRING, HALL_RISE)

    # arrival hall: low and long, ribbed, one landing arch per lane on the south wall (backlit glass: nothing is outside to look at)
    room(g, *ARRIVAL_X, *ARRIVAL_Z, f, 8)
    g.box("rib", ARRIVAL_X[0], f + 8, ARRIVAL_Z[0], ARRIVAL_X[1], f + 8, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0], ARRIVAL_X[1] + 1, 4):
        g.box("rib", x, f + 7, ARRIVAL_Z[0], x, f + 7, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0] + 3, ARRIVAL_X[1], 6):
        g.at("light", x, f + 8, (ARRIVAL_Z[0] + ARRIVAL_Z[1]) // 2)
    for c in LANES:
        g.air(c - 2, f, ARCH_Z[0], c + 2, f + 4, ARCH_Z[1])
        g.box("glass", c - 2, f, ARCH_Z[1], c + 2, f + 4, ARCH_Z[1])
        g.box("light", c - 2, f, ARCH_Z[1] + 1, c + 2, f + 4, ARCH_Z[1] + 1)
        g.box("floor", c - 2, ground, ARCH_Z[0], c + 2, ground, ARCH_Z[1])
        g.box("trim", c - 3, f, ARCH_Z[0], c - 3, f + 5, ARCH_Z[0])
        g.box("trim", c + 3, f, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.box("trim", c - 3, f + 5, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.at("accent", c, f + 5, ARCH_Z[0])

    # battle hall: a barrel vault, galleries with rails on decks over the side aisles, pier lines, the medallion, chandeliers, banners
    room(g, *HALL_X, *HALL_Z, f, spring)
    vault(g, *HALL_X, *HALL_Z, f, spring, rise, 6, rib_from=PIER_Z0)
    for xa, xb, rail in ((*GALLERY_W, GALLERY_W[1]), (*GALLERY_E, GALLERY_E[0])):
        g.box("rib", xa, f + 5, HALL_Z[0], xb, f + 5, HALL_Z[1])
        g.box("rail", rail, f + 6, HALL_Z[0], rail, f + 6, HALL_Z[1])
        g.air(xa, f, HALL_Z[0], xb, f + 4, HALL_Z[1])
        for z in range(HALL_Z[0] + 2, HALL_Z[1], 6):
            g.at(LANTERN, (xa + xb) // 2, f + 4, z)
    for x in PIERS_X:
        for z in range(PIER_Z0, HALL_Z[1] - 1, 6):
            column(g, x, z, f)
    medallion(g, *MEDALLION, ground, 14 if crown else 12)
    g.box("inlay", MID_X - 1, ground, HALL_Z[0], MID_X + 1, ground, MEDALLION[1] - (16 if crown else 14))
    g.box("inlay", MID_X - 1, ground, MEDALLION[1] + (16 if crown else 14), MID_X + 1, ground, HALL_Z[1])
    for z in (56, 68, 80):
        for x in (17, MID_X, 47):
            chandelier(g, x, z, f, 8 if x == MID_X else 6)
    for x in (6, 14, 49, 57):                 # two wide (x, x + 1): mirrored pairs about MID_X
        banner(g, x, f + spring - 2, GATE_Z[1], 7 if crown else 5)
        banner(g, x, f + spring - 2, PASSAGE_Z[0], 7 if crown else 5)
    if crown:
        # the crown's dais: a raised accent ring at the far (north) end where the champion stands, and a second tier of light
        g.box("trim", MID_X - 6, ground, HALL_Z[0] + 4, MID_X + 6, ground, HALL_Z[0] + 10)
        g.box("accent", MID_X - 1, ground, HALL_Z[0] + 6, MID_X + 1, ground, HALL_Z[0] + 8)
        for z in (56, 80):
            for x in (24, 40):
                chandelier(g, x, z, f, 12)

    # atrium: a flatter ribbed vault, the vendor stand at the east end, chandeliers clear of the pedestals
    room(g, *HALL_X, *ATRIUM_Z, f, 9)
    vault(g, *HALL_X, *ATRIUM_Z, f, 9, 3, 4)
    vendor_stand(g, STAND[0], STAND[1], f, "south")
    for x in LANES:
        chandelier(g, x, 38, f, 6)
    g.box("inlay", HALL_X[0], ground, 38, STAND[0] - 2, ground, 38)

    # openings, cut once the rooms on both sides exist (a room's plinth would close them otherwise)
    g.air(PASSAGE_X[0], f, PASSAGE_Z[0], PASSAGE_X[1], f + 6, PASSAGE_Z[1])
    g.box("floor", PASSAGE_X[0], ground, PASSAGE_Z[0], PASSAGE_X[1], ground, PASSAGE_Z[1])
    g.box("trim", PASSAGE_X[0] - 1, f + 7, PASSAGE_Z[0], PASSAGE_X[1] + 1, f + 7, PASSAGE_Z[0])

    g.air(GATE_X[0], f, GATE_Z[0], GATE_X[1], f + 5, GATE_Z[1])
    g.box(BARS, GATE_X[0], f, GATE_Z[0], GATE_X[1], f + 5, GATE_Z[1])
    g.box("trim", GATE_X[0] - 1, f + 6, GATE_Z[0], GATE_X[1] + 1, f + 6, GATE_Z[1])
    g.at("accent", MID_X, f + 6, GATE_Z[1])


    # doors, chambers and the lane stairs behind them
    for c in LANES:
        room(g, c - CHAMBER_HALF, c + CHAMBER_HALF, *CHAMBER_Z, f, CHAMBER_H)
        vault(g, c - CHAMBER_HALF, c + CHAMBER_HALF, *CHAMBER_Z, f, CHAMBER_H, 3, 3)
        g.box("inlay", c - 1, ground, CHAMBER_Z[0], c + 1, ground, CHAMBER_Z[1])
        if stairs:
            cut_stair(g, c, f)
    for c in LANES:                       # the doors last: the chambers' plinths would cover their bottom rows otherwise
        g.air(c - DOOR_HALF, f, DOOR_WALL_Z[0], c + DOOR_HALF, f + 5, DOOR_WALL_Z[1])
        g.box(BARS, c - DOOR_HALF, f, DOOR_WALL_Z[0], c + DOOR_HALF, f + 5, DOOR_WALL_Z[1])
        g.box("trim", c - DOOR_HALF - 1, f, DOOR_WALL_Z[0], c - DOOR_HALF - 1, f + 5, DOOR_WALL_Z[1])
        g.box("trim", c + DOOR_HALF + 1, f, DOOR_WALL_Z[0], c + DOOR_HALF + 1, f + 5, DOOR_WALL_Z[1])
        g.box("trim", c - DOOR_HALF - 1, f + 6, DOOR_WALL_Z[0], c + DOOR_HALF + 1, f + 7, DOOR_WALL_Z[1])
        g.box("light", c - DOOR_HALF - 1, f + 8, DOOR_WALL_Z[1], c + DOOR_HALF + 1, f + 8, DOOR_WALL_Z[1])
        g.at("accent", c, f + 7, DOOR_WALL_Z[1])


def cut_stair(g: Grid, c: int, f: int) -> None:
    """A lane's own way up (P58), north of its chamber: a doorway, a vestibule, five steps, a landing under an arch. The landing's last two
    rows are the threshold; walking onto them fades and teleports the party. The stairs of two lanes never meet."""
    lo, hi = c - STUB_HALF, c + STUB_HALF
    g.air(c - 1, f, BACK_Z, c + 1, f + 3, BACK_Z)
    g.air(lo, f, LANDING_Z[0], hi, f + 9, VEST_Z)
    g.box("floor", lo, f - 1, LANDING_Z[0], hi, f - 1, VEST_Z)
    for i in range(1, STEPS + 1):
        z, y = VEST_Z - i, f + i - 1
        if y > f:
            g.box("wall_low", lo, f, z, hi, y - 1, z)
        g.box("minecraft:polished_andesite_stairs[facing=north,half=bottom,shape=straight]", lo, y, z, hi, y, z)
    g.box("wall_low", lo, f, LANDING_Z[0], hi, f + STEPS - 2, LANDING_Z[1])
    g.box("floor", lo, f + STEPS - 1, LANDING_Z[0], hi, f + STEPS - 1, LANDING_Z[1])
    # the arch: backlit end wall, trim jambs on the side walls, a lintel with a keystone
    g.box("light", c - 1, f + STEPS + 1, LANDING_Z[0] - 1, c + 1, f + STEPS + 3, LANDING_Z[0] - 1)
    g.box("trim", lo - 1, f + STEPS, LANDING_Z[0], lo - 1, f + 9, LANDING_Z[0] + 1)
    g.box("trim", hi + 1, f + STEPS, LANDING_Z[0], hi + 1, f + 9, LANDING_Z[0] + 1)
    g.box("trim", lo, f + 9, LANDING_Z[0] + 1, hi, f + 9, LANDING_Z[0] + 1)
    g.at("accent", c, f + 9, LANDING_Z[0] + 1)
    g.at(LANTERN, c, f + 9, VEST_Z - 1)                   # hung from the ceiling (f + 10): one lower it floated


def build_act(theme: dict[str, str], act: int) -> Grid:
    g = Grid(theme, (SX, SY, SZ))
    g.grid[:] = g.id("wall")
    for floor in ACT_FLOORS[act]:
        build_floor(g, floor)
    connect(g.grid, g.palette, g.id)
    return g


# ---- the preparation room -----------------------------------------------------------------------------------------------------

PREP_SIZE = (56, 26, 44)
PREP_ROOM = (5, 44, 5, 36)              # x0, x1, z0, z1 of the domed hall
PREP_FEET = 4


def build_prep(theme: dict[str, str]) -> Grid:
    """About 40 x 32 under a shallow dome. West: where the party lands from the lobby. North: the route-map wall. South: the vendor stand (run
    supplies only). East: the ready dais, then the great door and a short passage whose far end is the threshold into floor 1."""
    g = Grid(theme, PREP_SIZE)
    g.grid[:] = g.id("wall")
    f = PREP_FEET
    x0, x1, z0, z1 = PREP_ROOM
    room(g, x0, x1, z0, z1, f, 12)
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2

    def dome(x: int, z: int) -> int:
        r = math.hypot((x - cx) / ((x1 - x0) / 2 + 1), (z - cz) / ((z1 - z0) / 2 + 1))
        return f + 12 + int(round(6 * math.sqrt(max(0.0, 1 - min(r, 1) ** 2))))

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            # rounding leaves the odd cell one higher than all four round it: a one-block pocket in the dome, so it is brought down to them
            top = min(dome(x, z), max(dome(x + dx, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))))
            r = math.hypot((x - cx) / ((x1 - x0) / 2 + 1), (z - cz) / ((z1 - z0) / 2 + 1))          # for the ring rib below
            g.air(x, f, z, x, top, z)
            g.at("vault", x, top + 1, z)
            ang = math.atan2(z - cz, x - cx)
            off = abs((ang + math.pi / 8) % (math.pi / 4) - math.pi / 8) * math.hypot(x - cx, z - cz)
            if off < 0.75 or abs(r - 0.55) < 0.03:
                g.at("rib", x, top, z)
    for x in range(x0, x1 + 1):                 # a cell under the dome walled in on all four sides (beside a rib) is a pocket: close it
        for z in range(z0, z1 + 1):
            y = ceiling_above(g, x, f, z)
            if all(g.grid[x + dx, y, z + dz] != 0 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                g.at("vault", x, y, z)
    for x in (x0 - 1, x1 + 1):
        g.box("trim", x, f + 11, z0, x, f + 11, z1)
        for z in range(z0 + 2, z1, 4):
            g.at("light", x, f + 10, z)
    for z in (z0 - 1, z1 + 1):
        g.box("trim", x0, f + 11, z, x1, f + 11, z)
        for x in range(x0 + 2, x1, 4):
            g.at("light", x, f + 10, z)
    # four columns at the corners of the floor, mirrored both ways, clear of the map wall, the stand and the dais, up to the dome
    for x in (x0 + 4, x1 - 4):
        for z in (z0 + 4, z1 - 4):
            column(g, x, z, f)
    medallion(g, int(cx), int(cz), f - 1, 9)
    icz = int(cz)
    # west: the arch the party lands in front of
    g.air(x0 - 2, f, icz - 2, x0 - 1, f + 5, icz + 2)
    g.box("glass", x0 - 2, f, icz - 2, x0 - 2, f + 5, icz + 2)
    g.box("light", x0 - 3, f, icz - 2, x0 - 3, f + 5, icz + 2)
    g.box("trim", x0 - 1, f + 6, icz - 3, x0 - 1, f + 6, icz + 3)
    # north: the route-map wall (the real icons are display entities in front of this panel)
    g.box("minecraft:black_concrete", 13, f + 2, z0 - 1, 36, f + 8, z0 - 1)
    g.box("trim", 12, f + 1, z0 - 1, 37, f + 1, z0 - 1)
    g.box("trim", 12, f + 9, z0 - 1, 37, f + 9, z0 - 1)
    # east: the ready dais (two steps up to a gold ring), the great door and the threshold passage behind it
    for s, r in enumerate((5, 4)):
        g.box("wall_low", x1 - 1 - 2 * r, f + s, icz - r, x1, f + s, icz + r)
    g.box("accent", x1 - 7, f + 1, icz - 3, x1 - 2, f + 1, icz + 3)
    g.box("floor", x1 - 6, f + 1, icz - 2, x1 - 3, f + 1, icz + 2)
    g.air(x1 + 1, f + 2, icz - 3, x1 + 8, f + 11, icz + 3)
    g.box("floor", x1 + 1, f + 1, icz - 3, x1 + 8, f + 1, icz + 3)
    g.box(BARS, x1 + 2, f + 2, icz - 3, x1 + 2, f + 11, icz + 3)            # the great door, shut until the party is ready
    g.box("light", x1 + 9, f + 2, icz - 2, x1 + 9, f + 8, icz + 2)
    g.box("trim", x1 + 1, f + 12, icz - 4, x1 + 1, f + 12, icz + 4)
    g.at("accent", x1 + 1, f + 12, icz)
    # south: the vendor stand
    vendor_stand(g, 26, z1 - 5, f, "north")
    chandelier(g, int(cx), icz, f, 8)
    for x, z in ((int(cx) - 10, icz - 7), (int(cx) + 10, icz - 7), (int(cx) - 10, icz + 7), (int(cx) + 10, icz + 7)):
        chandelier(g, x, z, f, 7)
    connect(g.grid, g.palette, g.id)
    return g


def prep_layout() -> dict:
    x0, x1, z0, z1 = PREP_ROOM
    icz = (z0 + z1) // 2
    f = PREP_FEET
    return {
        "structure_size": list(PREP_SIZE),
        "entry": {"x": x0 + 1, "y": f, "z": icz, "yaw": 270},
        "vendor": {"x": 29, "y": f, "z": z1 - 3, "yaw": 180},
        "dais": box(x1 - 7, f + 2, icz - 3, x1 - 2, f + 3, icz + 3),
        "door": box(x1 + 2, f + 2, icz - 3, x1 + 2, f + 11, icz + 3),
        "threshold": box(x1 + 7, f + 2, icz - 3, x1 + 8, f + 3, icz + 3),
    }


# ---- the layout the floor definitions use -------------------------------------------------------------------------------------

def box(x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> dict:
    return {"min": [min(x0, x1), min(y0, y1), min(z0, z1)], "max": [max(x0, x1), max(y0, y1), max(z0, z1)]}


def anchor(x: int, y: int, z: int, yaw: int) -> dict:
    return {"x": x, "y": y, "z": z, "yaw": yaw}


def arrival(floor: int, lane: int) -> dict:
    """Where lane {lane}'s stair lands the party on {floor}, in that floor's own building, facing north into the hall. Lane 0 is on the left."""
    if floor in BOSS_FLOORS:
        import grand_hall as gh
        return anchor(gh.LANES[lane], boss_spec("neutral").boss[floor].base_feet, gh.ARRIVAL_ANCHOR_Z, 180)
    return anchor(LANES[lane], feet(local(floor)), ARRIVAL_ANCHOR_Z, 180)


def boss_spec(theme_name: str):
    """The boss floors as grand_hall buildings: boss_<theme>.BOSS5 and BOSS10 (floor 6 only so that a layout of floor 5 can be asked for)."""
    import importlib
    import grand_hall as gh
    m = importlib.import_module(f"boss_{theme_name}")
    return gh.Spec(theme=theme_name, buildings=((5,), (10,), (6,)), crowns=(), pitch=0, base_feet=0, top=lambda c: 0, hall=None,
                   entry_z=0, presentation_z=0, spectator=(0, 0, 0), boss={5: m.BOSS5, 10: m.BOSS10})


def boss_layout(floor: int, theme_name: str) -> dict:
    import grand_hall as gh
    lay = gh.layout(boss_spec(theme_name), floor)
    lay["structure"] = f"cobbletowers:{theme_name}_boss{floor}"
    for lane, door in enumerate(lay["doors"]):
        if "arrival" in door:
            door["arrival"] = arrival(floor + 1, lane)
    return lay


def final_layout(floor: int, theme_name: str) -> dict:
    """A floor's layout as written: an act's anchors move PAD_Z south with its padding; an arrival moves with the building it lands in."""
    lay = layout(floor, theme_name)
    out = shift_layout(lay, 0 if floor in BOSS_FLOORS else PAD_Z)
    for door, raw in zip(out["doors"], lay["doors"]):
        if "arrival" in raw:
            door["arrival"] = shift_layout(raw["arrival"], 0 if floor + 1 in BOSS_FLOORS else PAD_Z)
    return out


def layout(floor: int, theme_name: str) -> dict:
    if floor in BOSS_FLOORS:
        return boss_layout(floor, theme_name)
    f = feet(local(floor))
    doors = []
    for lane, c in enumerate(LANES):
        door = {
            "open": box(c - DOOR_HALF, f, DOOR_WALL_Z[0], c + DOOR_HALF, f + 5, DOOR_WALL_Z[1]),
            "frame": box(c - DOOR_HALF - 1, f + 6, DOOR_WALL_Z[0], c + DOOR_HALF + 1, f + 7, DOOR_WALL_Z[1]),
            "pedestal": anchor(c, f, PEDESTAL_Z, 0),
        }
        if floor < FLOORS:
            door["stair_top"] = box(c - STUB_HALF, f + STEPS, LANDING_Z[0], c + STUB_HALF, f + STEPS + 1, LANDING_Z[0] + 1)
            door["arrival"] = arrival(floor + 1, lane)
        doors.append(door)
    return {
        "structure": f"cobbletowers:{theme_name}_act{act_of(floor)}",
        "entry": anchor(MID_X, f, 86, 180),
        "presentation": anchor(MID_X, f, 57, 0),
        "spectator": anchor((GALLERY_W[0] + GALLERY_W[1]) // 2, f + 6, MEDALLION[1], 270),
        "exit": anchor(STAND[0] + 3, f, STAND[1] + 2, 0),
        "gate_a": box(GATE_X[0], f, GATE_Z[0], GATE_X[1], f + 5, GATE_Z[1]),
        "doors": doors,
    }


# ---- trimming ------------------------------------------------------------------------------------------------------------------

# ---- connecting shapes ---------------------------------------------------------------------------------------------------------
# A wall, iron bars, a pane or a fence joins its neighbours through block-state properties the game sets when a neighbour changes. The
# server pastes with UPDATE_KNOWN_SHAPE (no neighbour updates: CellPreparer.PLACE_FLAGS), so a structure must carry the joined states itself:
# stored bare, every rail and every gate's bars stood as loose posts (rig 2, 14 Nov).

PARTIAL = ("slab", "stairs", "_wall", "fence", "bars", "pane", "lantern", "chain", "carpet", "door", "torch", "flower_pot", "cauldron",
           "water", "end_rod", "candle", "cluster", "button", "ladder", "sign", "banner", "trapdoor", "pressure_plate", "rail")


def _base(name: str) -> str:
    return name.split("[")[0].removeprefix("minecraft:")


def _full(name: str) -> bool:
    """A full, solid cube a wall, bars or a fence join onto."""
    base = _base(name)
    return base != "air" and not any(k in base for k in PARTIAL)


def _kind(name: str) -> str | None:
    base = _base(name)
    if base.endswith("_wall"):
        return "wall"
    if base == "iron_bars" or base.endswith("_pane"):
        return "bars"
    if base.endswith("_fence"):
        return "fence"
    return None


def connect(grid: np.ndarray, palette: list[str], intern, edge_solid: bool = False) -> int:
    """Gives every wall, bars, pane and fence in the grid the connections its neighbours call for. `intern(name)` returns the palette index of
    a block name, adding it when new. `edge_solid`: whether what lies past the edge of the grid is solid (a stop room's chamber wall).
    Returns how many blocks it set."""
    kinds = {i: _kind(n) for i, n in enumerate(palette) if _kind(n)}
    if not kinds:
        return 0
    sx, sy, sz = grid.shape
    names = list(palette)

    def at(x: int, y: int, z: int) -> str | None:
        if 0 <= x < sx and 0 <= y < sy and 0 <= z < sz:
            return names[int(grid[x, y, z])] if int(grid[x, y, z]) < len(names) else palette[int(grid[x, y, z])]
        return None

    def joins(kind: str, other: str | None, neighbour_side_full: bool) -> bool:
        if other is None:
            return edge_solid
        if _full(other):
            return True
        ok = _kind(other)
        if kind == "fence":
            return ok == "fence" or _base(other).endswith("fence_gate")
        return ok in ("wall", "bars") or _base(other).endswith("fence_gate")

    set_count = 0
    mask = np.isin(grid, list(kinds))
    for x, y, z in np.argwhere(mask):
        x, y, z = int(x), int(y), int(z)
        name = palette[int(grid[x, y, z])]
        kind = kinds[int(grid[x, y, z])]
        base = name.split("[")[0]
        sides = {d: joins(kind, at(x + dx, y, z + dz), False) for d, (dx, dz) in
                 {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}.items()}
        above = at(x, y + 1, z)
        if kind == "wall":
            straight = (sides["north"] and sides["south"] and not sides["east"] and not sides["west"]) or                        (sides["east"] and sides["west"] and not sides["north"] and not sides["south"])
            height = "tall" if above is not None and _full(above) else "low"
            props = {d: (height if sides[d] else "none") for d in sides}
            props["up"] = "false" if straight and not (above is not None and _kind(above) == "wall") else "true"
        else:
            props = {d: ("true" if sides[d] else "false") for d in sides}
        props["waterlogged"] = "false"
        grid[x, y, z] = intern(base + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]")
        set_count += 1
    return set_count


def see_through(name: str) -> bool:
    base = name.split("[")[0].removeprefix("minecraft:")
    if base == "sea_lantern":                 # a full light block, not a lantern
        return False
    return any(k in base for k in SEE_THROUGH)


def opaque_mask(g: Grid) -> np.ndarray:
    lookup = np.array([not see_through(n) for n in g.palette])
    return lookup[g.grid]


def trim(g: Grid, shell: int = 2) -> int:
    """Leaves out every opaque block with no open space (air or a see-through block) within `shell` blocks, diagonals included. Returns how
    many blocks were left out. The box keeps its size, so every anchor stays where it was."""
    opaque = opaque_mask(g)
    padded = np.pad(~opaque, shell, constant_values=False)          # outside the box is not open space: nobody stands there
    near = sliding_window_view(padded, (2 * shell + 1,) * 3).any(axis=(-1, -2, -3))
    drop = opaque & ~near
    g.grid[drop] = 0
    g.removed |= drop
    return int(drop.sum())


def shell_closed(g: Grid, starts: list[tuple[int, int, int]]) -> list[str]:
    """The open space of the rooms (6-way through air and see-through blocks, from every start) must never touch a block trim() left out or
    the edge of the box: no room looks out into the void."""
    open_ = ~opaque_mask(g) & ~g.removed
    sx, sy, sz = g.grid.shape
    seen = np.zeros_like(open_)
    queue = deque()
    for s_ in starts:
        if not seen[s_]:
            seen[s_] = True
            queue.append(s_)
    while queue:
        x, y, z = queue.popleft()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = (x + dx, y + dy, z + dz)
            if not (0 <= n[0] < sx and 0 <= n[1] < sy and 0 <= n[2] < sz):
                return [f"open space from the rooms reaches the edge of the box at {(x, y, z)}"]
            if g.removed[n]:
                return [f"open space from the rooms at {(x, y, z)} touches a trimmed-away block at {n}"]
            if open_[n] and not seen[n]:
                seen[n] = True
                queue.append(n)
    return []


# ---- validation: a walk over the blocks themselves ------------------------------------------------------------------------------

PASSABLE_NAMES = (AIR, CHAIN, LANTERN)


class Walker:
    def __init__(self, g: Grid) -> None:
        self.g = g
        n = len(g.palette)
        self.pass_ = np.array([g.palette[i] in PASSABLE_NAMES for i in range(n)])
        self.tall = np.array([g.palette[i].split("[")[0] == g.theme["rail"] or "_wall" in g.palette[i] for i in range(n)])
        self.wet = np.array([g.palette[i].startswith("minecraft:water") for i in range(n)])     # nobody stands on water

    def passable(self, x: int, y: int, z: int) -> bool:
        sx, sy, sz = self.g.grid.shape
        return 0 <= x < sx and 0 <= y < sy and 0 <= z < sz and bool(self.pass_[self.g.grid[x, y, z]])

    def standable(self, x: int, y: int, z: int) -> bool:
        return self.passable(x, y, z) and self.passable(x, y + 1, z) and y >= 1 and not self.passable(x, y - 1, z) \
            and not self.tall[self.g.grid[x, y - 1, z]] and not self.wet[self.g.grid[x, y - 1, z]]

    def reach(self, start: tuple[int, int, int], opened: list[dict]) -> set[tuple[int, int, int]]:
        """Flood fill on foot: four-way steps of one block up or down to two blocks down, with headroom. Boxes in {opened} are carved first."""
        saved = []
        for b in opened:
            (ax, ay, az), (bx, by, bz) = b["min"], b["max"]
            saved.append((b, self.g.grid[ax:bx + 1, ay:by + 1, az:bz + 1].copy()))
            self.g.grid[ax:bx + 1, ay:by + 1, az:bz + 1] = 0
        seen = {start}
        queue = deque([start])
        while queue:
            x, y, z = queue.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                for ny in (y, y + 1, y - 1, y - 2):
                    if (nx, ny, nz) in seen or not self.standable(nx, ny, nz):
                        continue
                    if ny == y + 1 and not self.passable(x, y + 2, z):
                        continue
                    if ny < y and not self.passable(nx, y + 1, nz):
                        continue
                    seen.add((nx, ny, nz))
                    queue.append((nx, ny, nz))
                    break
        for b, old in saved:
            (ax, ay, az), (bx, by, bz) = b["min"], b["max"]
            self.g.grid[ax:bx + 1, ay:by + 1, az:bz + 1] = old
        return seen


def _cell(a: dict) -> tuple[int, int, int]:
    return a["x"], a["y"], a["z"]


def _mid(b: dict, y: int) -> tuple[int, int, int]:
    return (b["min"][0] + b["max"][0]) // 2, y, (b["min"][2] + b["max"][2]) // 2


def dresser_box(door: dict) -> tuple[int, int, int, int, int, int]:
    """RoomDresser's box for a door: centred on the opening, the 15 rows north of it, feet .. feet + 7 (x0, y0, z0, x1, y1, z1)."""
    centre = (door["open"]["min"][0] + door["open"]["max"][0]) // 2
    y = door["pedestal"]["y"]
    back = door["open"]["min"][2] - CHAMBER_Z[1] + CHAMBER_Z[0] - 1
    return centre - CHAMBER_HALF, y, back, centre + CHAMBER_HALF, y + CHAMBER_H - 1, back + (CHAMBER_Z[1] - CHAMBER_Z[0])


def validate(acts: dict[int, Grid], theme_name: str, bosses: dict[int, Grid] | None = None) -> list[str]:
    import grand_hall as gh
    problems: list[str] = []
    if bosses is None:
        bosses = build_bosses(theme_name)[0]
    bspec = boss_spec(theme_name)
    walkers = {("act", a): Walker(g) for a, g in acts.items()} | {("boss", k): Walker(g) for k, g in bosses.items()}

    def walker_of(fl: int) -> Walker:
        return walkers[("boss", fl)] if fl in BOSS_FLOORS else walkers[("act", act_of(fl))]

    for floor in range(1, FLOORS + 1):
        w = walker_of(floor)
        lay = layout(floor, theme_name)
        boss = floor in BOSS_FLOORS
        f = bspec.boss[floor].base_feet if boss else feet(local(floor))
        lanes = gh.LANES if boss else LANES
        for name in ("entry", "presentation", "spectator", "exit"):
            if not w.standable(*_cell(lay[name])):
                problems.append(f"floor {floor}: the {name} anchor {lay[name]} is not standable")
        for i, door in enumerate(lay["doors"]):
            if not w.standable(*_cell(door["pedestal"])):
                problems.append(f"floor {floor}: the pedestal of door {i} is not standable")
            x0, y0, z0, x1, y1, z1 = dresser_box(door)
            room_ = w.g.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1]
            if room_.any():
                problems.append(f"floor {floor}: RoomDresser's box behind door {i} is not empty air ({int(np.count_nonzero(room_))} blocks)")

        rails = bspec.boss[floor].rails if boss else ((GALLERY_W[1], 6), (GALLERY_E[0], 6))
        zr = gh.HALL_Z if boss else HALL_Z
        for rail_x, up in rails:
            gaps = [z for z in range(zr[0], zr[1] + 1) if w.g.name(rail_x, f + up, z).split("[")[0] != w.g.theme["rail"]]
            if gaps:
                problems.append(f"floor {floor}: the gallery rail at x {rail_x} has gaps (z {gaps[:4]}): a spectator could drop into the arena")
        entry, pres, spec = _cell(lay["entry"]), _cell(lay["presentation"]), _cell(lay["spectator"])
        vendor = (lay["exit"]["x"], f, lay["exit"]["z"] + 2)            # in front of the counter: where TowerGates stands rescued players
        if not w.standable(*vendor):
            problems.append(f"floor {floor}: the spot in front of the vendor's counter {vendor} is not standable")
        chambers = [(c, f, (CHAMBER_Z[0] + CHAMBER_Z[1]) // 2) for c in lanes]
        tops = [_mid(d["stair_top"], f + STEPS) for d in lay["doors"]] if floor < FLOORS else []
        gate = lay["gate_a"]
        doors = [d["open"] for d in lay["doors"]]

        closed = w.reach(entry, [])
        if pres not in closed:
            problems.append(f"floor {floor}: the opponent's spot is not reachable from the entry")
        if vendor in closed:
            problems.append(f"floor {floor}: the vendor is reachable with gate A shut")
        if spec in closed:
            problems.append(f"floor {floor}: the gallery is reachable from the arena on foot")
        if pres in w.reach(spec, []):
            problems.append(f"floor {floor}: a spectator can walk from the gallery into the arena")
        after_a = w.reach(entry, [gate])
        if vendor not in after_a:
            problems.append(f"floor {floor}: the vendor is not reachable with gate A open")
        for i, spot in enumerate(chambers):
            if spot in after_a:
                problems.append(f"floor {floor}: chamber {i} is reachable with its door shut")
        for i, door in enumerate(doors):
            through = w.reach(entry, [gate, door])
            if chambers[i] not in through:
                problems.append(f"floor {floor}: chamber {i} is not reachable through its door")
            for j, spot in enumerate(chambers):
                if j != i and spot in through:
                    problems.append(f"floor {floor}: chamber {j} is reachable through door {i}")
            ys = {c[1] for c in through}
            if floor < FLOORS:
                if tops[i] not in through:
                    problems.append(f"floor {floor}: the threshold of lane {i} is not reachable through door {i}")
                for j, spot in enumerate(tops):
                    if j != i and spot in through:
                        problems.append(f"floor {floor}: the threshold of lane {j} is reachable through door {i}")
                if max(ys) > f + STEPS or min(ys) < f:
                    problems.append(f"floor {floor}: the walk through door {i} leaves its floor, y {min(ys)}..{max(ys)}")
                arrive = lay["doors"][i]["arrival"]
                nw = walker_of(floor + 1)
                spot = _cell(arrive)
                if not nw.standable(*spot):
                    problems.append(f"floor {floor}: the arrival of lane {i} {arrive} is not standable")
                else:
                    onward = nw.reach(spot, [])
                    nlay = layout(floor + 1, theme_name)
                    if _cell(nlay["entry"]) not in onward:
                        problems.append(f"floor {floor}: from the arrival of lane {i} the next battle hall is not reachable")
                    if (nlay["exit"]["x"], nlay["exit"]["y"], nlay["exit"]["z"] + 2) in onward:
                        problems.append(f"floor {floor}: from the arrival of lane {i} the next vendor is reachable with gate A shut")
            elif not boss and max(ys) > f + 3:                          # a boss hall may have stairs of its own (the throne's)
                problems.append("floor 10 has a way up that was not designed")
    return problems


def validate_prep(g: Grid) -> list[str]:
    lay = prep_layout()
    w = Walker(g)
    problems = []
    for name in ("entry", "vendor"):
        if not w.standable(*_cell(lay[name])):
            problems.append(f"prep: the {name} anchor is not standable")
    shut = w.reach(_cell(lay["entry"]), [])
    if not any(c in shut for c in [_mid(lay["dais"], lay["dais"]["min"][1])]):
        problems.append("prep: the ready dais is not reachable from the entry")
    if _mid(lay["threshold"], lay["threshold"]["min"][1]) in shut:
        problems.append("prep: the threshold is reachable with the great door shut")
    if _mid(lay["threshold"], lay["threshold"]["min"][1]) not in w.reach(_cell(lay["entry"]), [lay["door"]]):
        problems.append("prep: the threshold is not reachable with the great door open")
    return problems


# ---- output --------------------------------------------------------------------------------------------------------------------

def held_window_problems(grid: np.ndarray, name: str) -> list[str]:
    """Every stored block must lie in the chunks the cell holds once the structure is pasted centred (CellTickets.pasteOrigin: centre - size / 2).
    Outside them it is neither pasted by the sliced build nor ever cleared."""
    problems = []
    for axis, label in ((0, "x"), (2, "z")):
        length = grid.shape[axis]
        used = np.nonzero(np.any(grid != 0, axis=tuple(a for a in range(3) if a != axis)))[0]
        if used.size == 0:
            continue
        low, high = -(length // 2) + int(used[0]), -(length // 2) + int(used[-1])
        if low < HELD_LOW or high > HELD_HIGH:
            problems.append(f"{name}: stored blocks reach {low}..{high} in {label} from the cell centre; a cell holds {HELD_LOW}..{HELD_HIGH}")
    return problems


def padded(g: Grid) -> Grid:
    """The act as it is written: PAD_Z empty rows on its north side, so it lands inside the held window. Anchors shift with it (shift_layout)."""
    out = Grid(g.theme, (g.grid.shape[0], g.grid.shape[1], g.grid.shape[2] + PAD_Z))
    out.palette, out.index = list(g.palette), dict(g.index)
    out.grid[:, :, PAD_Z:] = g.grid
    return out


def shift_layout(value, dz: int):
    """A layout with every anchor and box moved dz rows south."""
    if isinstance(value, dict):
        if {"x", "y", "z"} <= value.keys():
            return {**value, "z": value["z"] + dz}
        if {"min", "max"} <= value.keys():
            return {"min": [value["min"][0], value["min"][1], value["min"][2] + dz], "max": [value["max"][0], value["max"][1], value["max"][2] + dz]}
        return {k: shift_layout(v, dz) for k, v in value.items()}
    if isinstance(value, list):
        return [shift_layout(v, dz) for v in value]
    return value


def write_nbt(g: Grid, path: Path) -> int:
    from schem_to_structure import write_structure
    xs, ys, zs = np.nonzero(g.grid)
    placed = [((int(x), int(y), int(z)), g.palette[int(g.grid[x, y, z])]) for x, y, z in zip(xs, ys, zs)]
    write_structure(path, g.grid.shape, placed, DATA_VERSION)
    return len(placed)


def build_all(theme_name: str, do_trim: bool = True) -> tuple[dict[int, Grid], Grid, dict]:
    theme = THEMES[theme_name]
    acts = {a: build_act(theme, a) for a in (1, 2)}
    prep = build_prep(theme)
    stats = {}
    for name, g in (("act1", acts[1]), ("act2", acts[2]), ("prep", prep)):
        solid = int(np.count_nonzero(g.grid))
        dropped = trim(g) if do_trim else 0
        stats[name] = (solid, dropped)
    return acts, prep, stats


def build_bosses(theme_name: str, do_trim: bool = True) -> tuple[dict[int, Grid], list[str]]:
    """The boss floors' buildings, with their own checks (before and after the trim), shells and held window."""
    import grand_hall as gh
    spec = boss_spec(theme_name)
    out, problems = {}, []
    for b, floor in ((1, 5), (2, 10)):
        boss = spec.boss[floor]
        g = gh.build_building(spec, THEMES[theme_name], b)
        problems += [f"boss {floor}: {p}" for p in boss.checks(g)]
        if do_trim:
            gh.tg.trim(g)
            problems += [f"boss {floor} after the trim: {p}" for p in boss.checks(g)]
            starts = [_cell(layout(floor, theme_name)[k]) for k in ("entry", "spectator")]
            problems += [f"boss {floor}: {p}" for p in gh.shell_problems(g, starts, boss.open_sky)]
            problems += held_window_problems(g.grid, f"{theme_name}_boss{floor}")
        out[floor] = g
    return out, problems


def shells(acts: dict[int, Grid], prep: Grid, theme_name: str) -> list[str]:
    problems = []
    for a, g in acts.items():
        floors = ACT_FLOORS[a]
        starts = [s for k in floors for s in (_cell(layout(k, theme_name)["entry"]), _cell(layout(k, theme_name)["spectator"]))]
        problems += [f"act {a}: {p}" for p in shell_closed(g, starts)]
    problems += [f"prep: {p}" for p in shell_closed(prep, [_cell(prep_layout()["entry"])])]
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--theme", default="neutral", choices=sorted(THEMES))
    parser.add_argument("--check", action="store_true", help="build and validate only")
    parser.add_argument("--install", action="store_true", help="also copy the .nbt files into the mod's structure folder")
    parser.add_argument("--out", type=Path, default=ROOT / "validation" / "schematics")
    args = parser.parse_args()

    acts, prep, stats = build_all(args.theme)
    bosses, boss_problems = build_bosses(args.theme)
    problems = validate(acts, args.theme, bosses) + validate_prep(prep) + boss_problems
    problems += shells(acts, prep, args.theme)
    problems += held_window_problems(padded(acts[1]).grid, f"{args.theme}_act1") + held_window_problems(padded(acts[2]).grid, f"{args.theme}_act2")
    problems += held_window_problems(prep.grid, f"{args.theme}_prep")
    for name, (solid, dropped) in stats.items():
        print(f"{args.theme}_{name}: {solid:,} blocks before the trim, {solid - dropped:,} kept ({100 * (solid - dropped) / solid:.0f}%)")
    for p in problems:
        print("FAIL", p)
    if problems:
        return 1
    print("validation: PASS (every floor: arrival -> battle hall -> gate A -> door -> chamber -> its own stair -> threshold; lanes never meet;"
          " each arrival leads to the next battle hall; the gallery is cut off from the arena; shells closed)")
    if args.check:
        return 0
    out = args.out / args.theme
    files = {f"{args.theme}_act1": padded(acts[1]), f"{args.theme}_act2": padded(acts[2]), f"{args.theme}_prep": prep}
    files |= {f"{args.theme}_boss{k}": g for k, g in bosses.items()}
    spec = boss_spec(args.theme)
    for k in bosses:
        sidecar = out / f"{args.theme}_boss{k}.floating.json"
        if spec.boss[k].floating:
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            sidecar.write_text(json.dumps([list(p) for p in spec.boss[k].floating]) + "\n")
        else:
            sidecar.unlink(missing_ok=True)
    for stem, g in files.items():
        n = write_nbt(g, out / f"{stem}.nbt")
        print(f"wrote {out / (stem + '.nbt')} ({n:,} blocks, {g.grid.shape[0]}x{g.grid.shape[1]}x{g.grid.shape[2]})")
    lay = {"floors": {str(k): final_layout(k, args.theme) for k in range(1, FLOORS + 1)}, "prep": prep_layout()}
    (out / f"{args.theme}.layout.json").write_text(json.dumps(lay, indent=2) + "\n")
    print(f"wrote {out / (args.theme + '.layout.json')}")
    if args.install:
        dest = ROOT / "src/main/resources/data/cobbletowers/structure"
        for stem in files:
            shutil.copyfile(out / f"{stem}.nbt", dest / f"{stem}.nbt")
            print("installed", dest / f"{stem}.nbt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
