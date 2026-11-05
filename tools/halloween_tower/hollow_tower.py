#!/usr/bin/env python3
"""Hollow's Eve: the walkable ten-floor Halloween tower (P55), generated, validated and exported.

    python tools/halloween_tower/hollow_tower.py            # build, validate, write .schem, .nbt and the layout JSON
    python tools/halloween_tower/hollow_tower.py --check    # build and validate only

Tower-local coordinates: T = (0,0,0) at the north-west corner of the foundation, +X east, +Z south, +Y up. The footprint is
97 x 97 and the envelope 97 x 121 x 97. Floor k (1-based) stands on feet level F = 6 + 10 (k - 1): eight blocks of clear space,
then a two-block slab that is the next floor's ground.

One floor (unmirrored, odd floors), from the arrival side to the stair:

    x:  0-1 wall | 2-17 ARRIVAL hall | 18-19 wall | 20-76 ARENA / INTER | 77-78 wall | 79-94 solid masonry | 95-96 wall
    z:  0-1 wall | 2-20 INTER (x 20-76) | 21-22 wall (GATE A, x 45-51) | 23-72 ARENA | 73-74 wall | 75-94 GALLERY

    arrive in the ARRIVAL hall by the lane stair of the floor below (a fade and a teleport, P58) -> walk to the door at z 46-50 -> ARENA
    fight -> GATE A opens -> INTER (vendor and the three modifier pedestals) -> a door -> its chamber -> the chamber's back doorway
    -> that lane's own stub (z 2-4): five steps up and a landing under an arch; stepping on the landing is the threshold (P58)

There is no shared corridor and no gate B: the three stubs never meet, so the party can only climb the lane the vote opened.

Even floors are the mirror image (x -> 96 - x). Floor 10 has no stubs: after its champion the run completes or starts a new cycle
by teleport, never on foot.

Nothing here touches Minecraft. The output is a Sponge .schem (mcschematic), converted to the vanilla .nbt by
validation/schem_to_structure.py, plus layout.json with every anchor and gate box for the floor definitions.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import deque
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import interiors  # noqa: E402

SIZE_X, SIZE_Y, SIZE_Z = 97, 121, 97
FLOORS = 10
BASE_FEET = 6
PITCH = 10
FURNITURE = 3       # a pumpkin, a step of the dais or a rock outcrop can be stood on, three blocks up at most; nothing taller may be
CLEAR = 8           # feet .. feet + 7 is open air; feet + 8 and feet + 9 are the slab
MID_X = 48

# ---- the zones (unmirrored x) ------------------------------------------------------------------------------------------------
ARRIVAL = (2, 17)
ROOMS_X = (20, 76)
ARENA_X = ROOMS_X
ARENA_Z = (37, 72)
GATE_A_Z = (35, 36)         # arena | intermission room wall
INTER_Z = (23, 34)
DOOR_WALL_Z = (21, 22)      # intermission room | the three route chambers
CHAMBER_Z = (6, 20)
GALLERY_Z = (75, 94)
DOOR_Z = (46, 50)           # arrival hall -> arena, in the wall x 18..19
GATE_A_X = (45, 51)
STUB_Z = (2, 4)             # the three lane stubs run along the outer north wall, behind the chambers
STUB_STEPS = 5              # a lane stair is five steps, so the landing is feet + 5: three blocks of headroom under the slab
STUB_LANDING = 3            # and a landing this long after the last step; the last two columns are the threshold
LANE_DX = (-1, 1, 1)        # which way each chamber's stair climbs from its back doorway (unmirrored): lane 0 west, lanes 1 and 2 east
ARRIVAL_X = 10              # lane arrival anchors stand here in the arrival hall, one per lane
ARRIVAL_ZS = (41, 48, 55)   # lane 0 is on the left of somebody walking from the hall into the arena
CHAMBER_CENTERS = (36, 48, 60)   # one door, one chamber and one pedestal each, in unmirrored x
CHAMBER_HALF = 5            # a chamber is 11 wide
DOOR_HALF = 2               # a door is 5 wide, 6 high
PEDESTAL_Z = 27             # the pedestals stand here, in front of their doors
VENDOR = (27, 31)

AIR = "minecraft:air"
SOLID_FACE = {"minecraft:cobweb"}

PLAIN_WALL = "minecraft:polished_blackstone_bricks"
OUTER = "minecraft:deepslate_bricks"
SLAB = "minecraft:polished_blackstone"
TRIM = "minecraft:crying_obsidian"
DARK = "minecraft:black_concrete"
PURPLE = "minecraft:purple_concrete"
TERRA = "minecraft:purple_terracotta"
ORANGE = "minecraft:orange_concrete"
GLASS = "minecraft:purple_stained_glass"
LANTERN = "minecraft:jack_o_lantern"
BARS = "minecraft:iron_bars"
WALL_CAP = "minecraft:polished_blackstone_brick_wall"
STAIR_BLOCK = "minecraft:polished_blackstone_brick_stairs[facing=south,half=bottom,shape=straight]"
CHISELED = "minecraft:chiseled_polished_blackstone"
AMETHYST = "minecraft:amethyst_block"
SHROOM = "minecraft:shroomlight"
COBWEB = "minecraft:cobweb"
GLOW_EAST = "minecraft:jack_o_lantern[facing=east]"      # faces into the room from the west wall; mirrored floors flip it
GLOW_WEST = "minecraft:jack_o_lantern[facing=west]"
GLOW_SOUTH = "minecraft:jack_o_lantern[facing=south]"
SOUL = "minecraft:soul_lantern[hanging=true]"
BLACKSTONE = "minecraft:blackstone"
TUFF = "minecraft:tuff"
COBBLED_DEEPSLATE = "minecraft:cobbled_deepslate"
ORANGE_GLASS = "minecraft:orange_stained_glass"
PURPLE_WOOL = "minecraft:purple_wool"
BLACK_WOOL = "minecraft:black_wool"
CHAIN = "minecraft:chain[axis=y]"
PUMPKIN = "minecraft:pumpkin"
GLOW_NORTH = "minecraft:jack_o_lantern[facing=north]"
STAIR_UP_EAST = "minecraft:polished_blackstone_brick_stairs[facing=east,half=bottom,shape=straight]"

# blocks a player can walk through; anything else blocks (iron bars, walls, glass, stairs and slabs included)
PASSABLE = {AIR, COBWEB, SOUL}
# collision boxes 1.5 high: nobody steps onto them
TALL = {WALL_CAP}


def feet(floor: int) -> int:
    return BASE_FEET + PITCH * (floor - 1)


def mirrored(floor: int) -> bool:
    return floor % 2 == 0


class Tower:
    """A numpy block grid with a palette. Writes are in floor-local coordinates and mirrored by the floor in progress."""

    def __init__(self) -> None:
        self.grid = np.zeros((SIZE_X, SIZE_Y, SIZE_Z), dtype=np.uint16)
        self.palette: list[str] = [AIR]
        self.index: dict[str, int] = {AIR: 0}
        self.mirror = False

    def block_id(self, name: str) -> int:
        found = self.index.get(name)
        if found is None:
            found = len(self.palette)
            self.palette.append(name)
            self.index[name] = found
        return found

    def _xs(self, x0: int, x1: int) -> tuple[int, int]:
        if x0 > x1:
            x0, x1 = x1, x0
        if self.mirror:
            x0, x1 = SIZE_X - 1 - x1, SIZE_X - 1 - x0
        return x0, x1

    def fill(self, x0: int, x1: int, y0: int, y1: int, z0: int, z1: int, block: str) -> None:
        x0, x1 = self._xs(x0, x1)
        if y0 > y1 or z0 > z1:
            raise ValueError(f"empty box {x0},{x1} {y0},{y1} {z0},{z1}")
        self.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1] = self.block_id(self._facing(block))

    def set(self, x: int, y: int, z: int, block: str) -> None:
        self.fill(x, x, y, y, z, z, block)

    def air(self, x0: int, x1: int, y0: int, y1: int, z0: int, z1: int) -> None:
        self.fill(x0, x1, y0, y1, z0, z1, AIR)

    def _facing(self, block: str) -> str:
        if not self.mirror:
            return block
        return block.replace("facing=east", "facing=@").replace("facing=west", "facing=east").replace("facing=@", "facing=west")

    def at(self, x: int, y: int, z: int) -> str:
        return self.palette[int(self.grid[x, y, z])]

    def free(self, x: int, y: int, z: int) -> bool:
        """Whether the cell is still air, in the floor-local (mirrored when the floor is) coordinates the writes use."""
        return self.at(SIZE_X - 1 - x if self.mirror else x, y, z) == AIR


# ---- one floor ---------------------------------------------------------------------------------------------------------------

def build_floor(t: Tower, floor: int) -> None:
    f = feet(floor)
    t.mirror = mirrored(floor)
    top = f + CLEAR - 1                      # last clear layer
    # ceiling slab, which is the next floor's ground
    t.fill(0, 96, f + CLEAR, f + CLEAR + 1, 0, 96, SLAB)
    # ground of this floor: plinth for floor 1, the slab of the floor below otherwise (already there)
    if floor == 1:
        t.fill(0, 96, 0, f - 1, 0, 96, SLAB)
    # outer walls, full height
    t.fill(0, 1, f, top, 0, 96, OUTER)
    t.fill(95, 96, f, top, 0, 96, OUTER)
    t.fill(2, 94, f, top, 0, 1, OUTER)
    t.fill(2, 94, f, top, 95, 96, OUTER)
    # the open interior
    t.air(2, 94, f, top, 2, 94)
    # partitions
    t.fill(18, 19, f, top, 2, 72, PLAIN_WALL)                 # arrival hall | rooms
    t.fill(77, 78, f, top, 2, 72, PLAIN_WALL)                 # rooms | stair hall
    t.fill(20, 76, f, top, GATE_A_Z[0], GATE_A_Z[1], PLAIN_WALL)   # arena | intermission room
    t.fill(79, 94, f, top, 2, 72, PLAIN_WALL)                 # the old stair hall is solid masonry now (P58): no stair needs it
    t.fill(20, 76, f, top, STUB_Z[0], STUB_Z[1], PLAIN_WALL)  # and so is the strip behind the chambers, until the lane stubs are cut into it
    t.fill(2, 94, f, top, 73, 74, PLAIN_WALL)                 # rooms | gallery
    t.fill(24, 72, f + 2, f + 5, 73, 74, GLASS)               # the gallery looks into the arena through a long window
    # behind the intermission room: the door wall, three chambers (the rest is solid) and the corridor behind them
    t.fill(20, 76, f, top, DOOR_WALL_Z[0], DOOR_WALL_Z[1], PLAIN_WALL)
    t.fill(20, 76, f, top, CHAMBER_Z[0] - 1, CHAMBER_Z[1], PLAIN_WALL)
    for c in CHAMBER_CENTERS:
        t.air(c - CHAMBER_HALF, c + CHAMBER_HALF, f, top, CHAMBER_Z[0], CHAMBER_Z[1])
        t.air(c - 1, c + 1, f, f + 3, CHAMBER_Z[0] - 1, CHAMBER_Z[0] - 1)           # the chamber's back doorway into the corridor
        t.air(c - DOOR_HALF, c + DOOR_HALF, f, f + 5, DOOR_WALL_Z[0], DOOR_WALL_Z[1])
        t.fill(c - DOOR_HALF, c + DOOR_HALF, f, f + 5, DOOR_WALL_Z[0], DOOR_WALL_Z[1], BARS)   # the door, shut
    # arrival door and the two gates
    t.air(18, 19, f, f + 5, DOOR_Z[0], DOOR_Z[1])
    t.air(GATE_A_X[0], GATE_A_X[1], f, f + 5, GATE_A_Z[0], GATE_A_Z[1])
    t.fill(GATE_A_X[0], GATE_A_X[1], f, f + 5, GATE_A_Z[0], GATE_A_Z[1], BARS)
    # windows in the outer walls of both halls and the gallery
    for z in range(8, 70, 8):
        t.fill(0, 1, f + 2, f + 5, z, z + 2, GLASS)
    for x in range(8, 90, 12):
        t.fill(x, x + 2, f + 2, f + 5, 95, 96, GLASS)
        t.fill(x, x + 2, f + 2, f + 5, 0, 1, GLASS)
    if floor < FLOORS:
        for lane, c in enumerate(CHAMBER_CENTERS):
            cut_stub(t, f, top, c, LANE_DX[lane])
    else:
        for c in CHAMBER_CENTERS:                                       # floor 10 is left by teleport: its chambers are dead ends
            t.fill(c - 1, c + 1, f, f + 3, CHAMBER_Z[0] - 1, CHAMBER_Z[0] - 1, PLAIN_WALL)
    decorate(t, floor, f, top)


def stub_span(c: int, dx: int) -> tuple[int, int, int, int]:
    """The columns of a lane stub: (first x of the air, last x of the air, first step x, threshold x) in unmirrored floor coordinates.
    The air runs from the chamber's doorway columns (c-1..c+1) out along the stair and the landing; the end wall is one past it."""
    steps_end = c + dx * (1 + STUB_STEPS)
    land_end = steps_end + dx * STUB_LANDING
    lo, hi = (land_end, c + 1) if dx < 0 else (c - 1, land_end)
    return lo, hi, c + dx * 2, land_end


def cut_stub(t: Tower, f: int, top: int, c: int, dx: int) -> None:
    """One lane's own way up (P58): from the chamber's back doorway, a flat three-wide vestibule, five steps and a landing under an arch.
    The party never reaches the next floor on foot: the last two columns of the landing are the threshold that fades and teleports."""
    z0, z1 = STUB_Z
    lo, hi, first_step, end = stub_span(c, dx)
    t.air(lo, hi, f, top, z0, z1)
    facing = "west" if dx < 0 else "east"
    step = STAIR_BLOCK.replace("facing=south", f"facing={facing}")
    for i in range(1, STUB_STEPS + 1):
        x = c + dx * (1 + i)
        y = f + i - 1
        if y - 1 >= f:
            t.fill(x, x, f, y - 1, z0, z1, PLAIN_WALL)
        t.fill(x, x, y, y, z0, z1, step)
    land0 = c + dx * (2 + STUB_STEPS)
    t.fill(min(land0, end), max(land0, end), f, f + STUB_STEPS - 1, z0, z1, PLAIN_WALL)       # the landing is solid up to its floor
    t.fill(end + dx, end + dx, f, top, z0, z1, PLAIN_WALL)                                    # the end wall


def noise(x: int, z: int, salt: int = 0) -> int:
    """A stable, scattered number from a position: the same tower every build, no random module."""
    return ((x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)) % 1000


def decorate_arena(t: Tower, floor: int, f: int, top: int, accent: str, ground: int) -> None:
    """The battle hall, after a gothic dungeon hall: a patterned disc in the floor, flame braziers with grinning bases, tall glowing window slits,
    banners, a coffered ceiling of lit lines, rock outcrops in the corners and a stepped dais with a great pumpkin at the far end."""
    import math
    cx, cz = 50, 48
    # paving, then the disc: crying-obsidian rim, a purple ring, dark ground with eight orange spokes, and a pumpkin face in the middle
    for x in range(ARENA_X[0], ARENA_X[1] + 1):
        for z in range(ARENA_Z[0], ARENA_Z[1] + 1):
            n = noise(x, z)
            block = SLAB if n < 330 else BLACKSTONE if n < 560 else PLAIN_WALL
            dx, dz = x - cx, z - cz
            r = math.hypot(dx, dz)
            if r <= 12.5:
                spoke = abs((math.atan2(dz, dx) / (math.pi / 4)) - round(math.atan2(dz, dx) / (math.pi / 4))) < 0.14
                if r >= 11.0:
                    block = TRIM
                elif r >= 9.0:
                    block = PURPLE
                elif r >= 4.8:
                    block = accent if spoke else DARK
                else:
                    block = accent
            t.set(x, ground, z, block)
    # the face, seen from the arrival side (its top is the far side, east): two eyes and a jagged mouth in black
    for ex, ez in ((cx + 2, cz - 2), (cx + 2, cz + 2), (cx + 1, cz - 2), (cx + 1, cz + 2)):
        t.set(ex, ground, ez, DARK)
    for mz in range(-3, 4):
        t.set(cx - 2 - (abs(mz) % 2), ground, cz + mz, DARK)
    # four braziers, each a three-block base with a grinning pumpkin on every side and a flame of orange glass round a glowing core
    for bx, bz in ((38, 40), (62, 40), (38, 58), (62, 58)):
        t.fill(bx - 1, bx + 1, f, f + 2, bz - 1, bz + 1, PLAIN_WALL)
        t.fill(bx - 1, bx + 1, f + 3, f + 3, bz - 1, bz + 1, CHISELED)
        t.set(bx, f + 1, bz - 1, GLOW_NORTH)
        t.set(bx, f + 1, bz + 1, GLOW_SOUTH)
        t.set(bx - 1, f + 1, bz, GLOW_WEST)
        t.set(bx + 1, f + 1, bz, GLOW_EAST)
        t.fill(bx - 1, bx + 1, f + 4, f + 4, bz - 1, bz + 1, ORANGE_GLASS)
        t.fill(bx, bx, f + 4, f + 5, bz, bz, SHROOM)
        t.fill(bx - 1, bx + 1, f + 5, f + 5, bz, bz, ORANGE_GLASS)
        t.fill(bx, bx, f + 5, f + 5, bz - 1, bz + 1, ORANGE_GLASS)
        t.fill(bx, bx, f + 5, f + 5, bz, bz, SHROOM)
        t.fill(bx, bx, f + 6, f + 6, bz, bz, ORANGE_GLASS)
    # tall lit lancets in the thick walls: the north wall (their lamps show in the intermission room), the west wall (clear of the door) and the east wall
    for x in (26, 34, 62, 70):
        interiors.lancet(t, x, f + 1, GATE_A_Z[1], -1, 0)
    for z in (40, 56, 62, 68):
        interiors.lancet_x(t, 19, f + 1, z, -1, 0)
        interiors.lancet_x(t, 77, f + 1, z, 1, 0)
    # banners on the north wall between the slits: purple cloth with an orange diamond, ending in a point
    for c in (30, 40, 56, 66):
        t.fill(c - 1, c + 1, f + 3, f + 7, ARENA_Z[0], ARENA_Z[0], PURPLE_WOOL)
        t.set(c, f + 2, ARENA_Z[0], PURPLE_WOOL)
        t.set(c, f + 5, ARENA_Z[0], ORANGE)
        t.fill(c - 1, c + 1, f + 4, f + 4, ARENA_Z[0], ARENA_Z[0], ORANGE)
        t.set(c, f + 3, ARENA_Z[0], ORANGE)
    # the coffered ceiling: lines of shroomlight in the slab, with a chain and a soul lantern at the crossings
    for x in (24, 36, 48, 60, 72):
        t.fill(x, x, f + CLEAR, f + CLEAR, ARENA_Z[0], ARENA_Z[1], SHROOM)
    for z in (41, 53, 65):
        t.fill(ARENA_X[0], ARENA_X[1], f + CLEAR, f + CLEAR, z, z, SHROOM)
    for x in (36, 60):
        for z in (41, 65):
            t.fill(x, x, top - 1, top, z, z, CHAIN)
            t.set(x, top - 2, z, SOUL)
    # pumpkins on the floor at the side walls, between the outcrops and clear of the door (z 44..52)
    for z in (54, 60):
        t.set(ARENA_X[0], f, z, GLOW_EAST)
        t.set(ARENA_X[1], f, z, GLOW_WEST)
    # rock outcrops in the four corners, clear of the door and of the route to the gate
    for (x0, x1, z0, z1, sx, sz) in ((20, 28, 37, 43, 20, 37), (68, 76, 37, 43, 76, 37), (20, 28, 66, 72, 20, 72), (68, 76, 66, 72, 76, 72)):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                d = math.hypot(x - sx, z - sz)
                h = int(3.6 - d * 0.5 + (noise(x, z, 7) % 160) / 100.0)
                if h <= 0:
                    continue
                for y in range(f, f + min(h, 3)):
                    n = noise(x, z, y)
                    t.set(x, y, z, TUFF if n < 380 else COBBLED_DEEPSLATE if n < 720 else BLACKSTONE)
    # the dais at the far end: two steps and a great pumpkin looking back at the door
    t.fill(68, 76, f, f, 41, 68, PLAIN_WALL)
    t.fill(67, 67, f, f, 41, 68, STAIR_UP_EAST)
    t.fill(72, 76, f + 1, f + 1, 45, 64, SLAB)
    t.fill(71, 71, f + 1, f + 1, 45, 64, STAIR_UP_EAST)
    t.fill(74, 76, f + 2, f + 4, 47, 49, PUMPKIN)
    for z in (47, 49):
        t.set(73, f + 4, z, GLOW_WEST)           # eyes
    for z in (47, 48, 49):
        t.set(73, f + 2, z, GLOW_WEST)           # mouth
    t.set(73, f + 3, 48, PUMPKIN)
    t.set(73, f + 3, 47, PUMPKIN)
    t.set(73, f + 3, 49, PUMPKIN)
    t.set(73, f + 4, 48, PUMPKIN)


def decorate(t: Tower, floor: int, f: int, top: int) -> None:
    ground = f - 1
    accent = (ORANGE, PURPLE, ORANGE)[(floor - 1) // 4 % 3]
    decorate_arena(t, floor, f, top, accent, ground)
    interiors.decorate_arena_walls(t, f, top)
    # intermission room: the gothic dressing lives in interiors.py
    interiors.decorate_inter(t, f, top, accent, {"x0": ROOMS_X[0], "x1": ROOMS_X[1], "z0": INTER_Z[0], "z1": INTER_Z[1],
                                                  "centers": CHAMBER_CENTERS, "door_half": DOOR_HALF, "vendor": VENDOR})
    for c in CHAMBER_CENTERS:
        t.set(c, top, PEDESTAL_Z, SOUL)
        # the door frame: a lintel and two jambs of crying obsidian (the lintel is what lights up)
        t.fill(c - DOOR_HALF - 1, c + DOOR_HALF + 1, f + 6, f + 7, DOOR_WALL_Z[0], DOOR_WALL_Z[1], TRIM)
        t.fill(c - DOOR_HALF - 1, c - DOOR_HALF - 1, f, f + 5, DOOR_WALL_Z[0], DOOR_WALL_Z[1], TRIM)
        t.fill(c + DOOR_HALF + 1, c + DOOR_HALF + 1, f, f + 5, DOOR_WALL_Z[0], DOOR_WALL_Z[1], TRIM)
        # the chamber behind it
        t.fill(c - CHAMBER_HALF, c + CHAMBER_HALF, ground, ground, CHAMBER_Z[0], CHAMBER_Z[1], DARK)
        t.fill(c - 1, c + 1, ground, ground, CHAMBER_Z[0], CHAMBER_Z[1], PURPLE)
        for z in (9, 13, 17):
            t.set(c, f + CLEAR, z, LANTERN)
    # the lane stubs behind the chambers
    if floor < FLOORS:
        for lane, c in enumerate(CHAMBER_CENTERS):
            lo, hi, first_step, end = stub_span(c, LANE_DX[lane])
            interiors.decorate_stub(t, f, top, accent, c, LANE_DX[lane], lo, hi, end, STUB_Z[0], STUB_Z[1], ground, STUB_STEPS)
    # halls: ribbed naves with a runner, lit pilasters and hanging lamps
    interiors.decorate_hall(t, f, top, accent, ARRIVAL, True, 2, 72, ground)
    # gallery: a long hall behind the arena window
    interiors.decorate_gallery(t, f, top, accent, 2, 94, GALLERY_Z[0], GALLERY_Z[1], ground, (44, 52), (24, 72))
    # webs above head height only (feet + 3 and up), in corners of the arena, the vendor's room and the gallery
    for x, z in ((20, 37), (76, 37), (20, 72), (76, 72), (20, 23), (76, 23), (2, 75), (94, 75), (2, 94), (94, 94)):
        t.set(x, top, z, COBWEB)
        t.set(x, top - 1, z, COBWEB)


# ---- the crown and the exterior ----------------------------------------------------------------------------------------------

def build_crown(t: Tower) -> None:
    t.mirror = False
    roof = feet(FLOORS) + CLEAR + 1          # top of the roof slab
    # parapet, two high
    t.fill(0, 96, roof + 1, roof + 2, 0, 1, OUTER)
    t.fill(0, 96, roof + 1, roof + 2, 95, 96, OUTER)
    t.fill(0, 1, roof + 1, roof + 2, 2, 94, OUTER)
    t.fill(95, 96, roof + 1, roof + 2, 2, 94, OUTER)
    # a stepped glass-and-pumpkin crown in the middle
    cx, cz = MID_X, MID_X
    for layer, half in enumerate((14, 12, 10, 8, 6, 4, 2)):
        y = roof + 1 + layer
        material = GLASS if layer % 2 == 0 else AMETHYST
        t.fill(cx - half, cx + half, y, y, cz - half, cz + half, material)
        if half > 2:
            t.air(cx - half + 1, cx + half - 1, y, y, cz - half + 1, cz + half - 1)
    t.fill(cx - 1, cx + 1, roof + 8, roof + 8, cz - 1, cz + 1, LANTERN)
    # corner towers with jack-o'-lantern tops
    for x, z in ((0, 0), (94, 0), (0, 94), (94, 94)):
        t.fill(x, x + 2, roof + 1, roof + 6, z, z + 2, OUTER)
        t.fill(x, x + 2, roof + 7, roof + 7, z, z + 2, LANTERN)
    # exterior trim: crying-obsidian band at every slab, orange accents in the corners
    for floor in range(1, FLOORS + 1):
        band = feet(floor) + CLEAR
        t.fill(0, 96, band, band, 0, 0, TRIM)
        t.fill(0, 96, band, band, 96, 96, TRIM)
        t.fill(0, 0, band, band, 1, 95, TRIM)
        t.fill(96, 96, band, band, 1, 95, TRIM)
        t.fill(0, 0, band - 6, band - 1, 0, 0, SHROOM)
        t.fill(96, 96, band - 6, band - 1, 0, 0, SHROOM)
        t.fill(0, 0, band - 6, band - 1, 96, 96, SHROOM)
        t.fill(96, 96, band - 6, band - 1, 96, 96, SHROOM)
    # a stepped plinth around the base is inside the footprint: carve a shallow lower course
    t.fill(0, 96, 0, 0, 0, 96, TRIM)


def build() -> Tower:
    t = Tower()
    for floor in range(1, FLOORS + 1):
        build_floor(t, floor)
    build_crown(t)
    t.mirror = False
    return t


# ---- the layout the floor definitions use -----------------------------------------------------------------------------------

def mx(floor: int, x: int) -> int:
    return SIZE_X - 1 - x if mirrored(floor) else x


def layout(floor: int) -> dict:
    f = feet(floor)

    def anchor(x: int, z: int, yaw: int) -> dict:
        return {"x": mx(floor, x), "y": f, "z": z, "yaw": yaw}

    into_arena = 270 if not mirrored(floor) else 90         # looking from the arrival side into the arena
    back = 90 if not mirrored(floor) else 270
    # the doors in physical west-to-east order, which is left to right for somebody facing them: lane 0 is the left door on every floor
    doors = []
    for c in sorted(CHAMBER_CENTERS, key=lambda c: mx(floor, c)):
        lane = len(doors)
        door = {
            "open": box(floor, c - DOOR_HALF, c + DOOR_HALF, f, f + 5, DOOR_WALL_Z[0], DOOR_WALL_Z[1]),
            "frame": box(floor, c - DOOR_HALF - 1, c + DOOR_HALF + 1, f + 6, f + 7, DOOR_WALL_Z[0], DOOR_WALL_Z[1]),
            "pedestal": anchor(c, PEDESTAL_Z, 0),
        }
        if floor < FLOORS:
            door["stair_top"] = stair_top(floor, c)
            door["arrival"] = arrival(floor + 1, lane)
        doors.append(door)
    out = {
        "entry": anchor(24, 48, into_arena),
        "presentation": anchor(46, 48, back),
        "spectator": anchor(MID_X, 84, 180),
        "exit": anchor(VENDOR[0], VENDOR[1], 90 if not mirrored(floor) else 270),
        "gate_a": box(floor, GATE_A_X[0], GATE_A_X[1], f, f + 5, GATE_A_Z[0], GATE_A_Z[1]),
        "doors": doors,
    }
    return out


def stair_top(floor: int, c: int) -> dict:
    """The threshold of the lane whose chamber is centred on {c}: the last two columns of its landing, from the landing floor up two blocks."""
    f = feet(floor)
    dx = LANE_DX[CHAMBER_CENTERS.index(c)]
    end = stub_span(c, dx)[3]
    return box(floor, end, end - dx, f + STUB_STEPS, f + STUB_STEPS + 1, STUB_Z[0], STUB_Z[1])


def arrival(floor: int, lane: int) -> dict:
    """Where lane {lane}'s stair drops the party on {floor}: in the arrival hall, looking at the door into the arena. Lane 0 is on the left."""
    f = feet(floor)
    zs = ARRIVAL_ZS if not mirrored(floor) else tuple(reversed(ARRIVAL_ZS))
    return {"x": mx(floor, ARRIVAL_X), "y": f, "z": zs[lane], "yaw": 270 if not mirrored(floor) else 90}


def box(floor: int, x0: int, x1: int, y0: int, y1: int, z0: int, z1: int) -> dict:
    a, b = sorted((mx(floor, x0), mx(floor, x1)))
    return {"min": [a, y0, z0], "max": [b, y1, z1]}


# ---- validation: a walk over the blocks themselves ----------------------------------------------------------------------------

def passable(t: Tower, x: int, y: int, z: int) -> bool:
    if not (0 <= x < SIZE_X and 0 <= y < SIZE_Y and 0 <= z < SIZE_Z):
        return False
    return t.at(x, y, z) in PASSABLE


def standable(t: Tower, x: int, y: int, z: int) -> bool:
    """Feet at y: two clear blocks and something solid under the feet."""
    return passable(t, x, y, z) and passable(t, x, y + 1, z) and not passable(t, x, y - 1, z) \
        and 0 <= y - 1 and t.at(x, y - 1, z) not in TALL


def reach(t: Tower, start: tuple[int, int, int], opened: list[dict]) -> set[tuple[int, int, int]]:
    """Flood fill on foot: four-way steps of at most one block up or two down, with headroom. The boxes in {opened} are carved first."""
    carved = []
    for box_ in opened:
        (ax, ay, az), (bx, by, bz) = box_["min"], box_["max"]
        for x in range(ax, bx + 1):
            for y in range(ay, by + 1):
                for z in range(az, bz + 1):
                    carved.append((x, y, z, int(t.grid[x, y, z])))
                    t.grid[x, y, z] = 0
    seen = {start}
    queue = deque([start])
    while queue:
        x, y, z = queue.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            for ny in (y, y + 1, y - 1, y - 2):
                if (nx, ny, nz) in seen or not standable(t, nx, ny, nz):
                    continue
                if ny == y + 1 and not passable(t, x, y + 2, z):
                    continue
                if ny < y and not passable(t, nx, y + 1, nz):
                    continue
                seen.add((nx, ny, nz))
                queue.append((nx, ny, nz))
                break
    for x, y, z, old in carved:
        t.grid[x, y, z] = old
    return seen


def _mid(b: dict, y: int) -> list[int]:
    """The cell in the middle of a box's x and z, at height {y}."""
    return [(b["min"][0] + b["max"][0]) // 2, y, (b["min"][2] + b["max"][2]) // 2]


def cell_of(lay: dict, name: str) -> tuple[int, int, int]:
    a = lay[name]
    return a["x"], a["y"], a["z"]


def validate(t: Tower) -> list[str]:
    problems: list[str] = []
    for floor in range(1, FLOORS + 1):
        lay = layout(floor)
        f = feet(floor)
        for name in ("entry", "presentation", "exit"):
            a = lay[name]
            if not standable(t, a["x"], a["y"], a["z"]):
                problems.append(f"floor {floor}: the {name} anchor {a} is not standable")
        for i, door in enumerate(lay["doors"]):
            p = door["pedestal"]
            if not standable(t, p["x"], p["y"], p["z"]):
                problems.append(f"floor {floor}: the pedestal in front of door {i} {p} is not standable")
        # nothing is a cobweb inside the head-height volume of the walk
        webs = [(x, y, z) for x in range(SIZE_X) for z in range(SIZE_Z) for y in range(f, f + 3) if t.at(x, y, z) == COBWEB]
        if webs:
            problems.append(f"floor {floor}: cobwebs inside the walking volume: {webs[:4]}")

    for floor in range(1, FLOORS + 1):
        lay = layout(floor)
        f = feet(floor)
        entry, vendor, pres = cell_of(lay, "entry"), cell_of(lay, "exit"), cell_of(lay, "presentation")
        gate_a = lay["gate_a"]
        doors = [d["open"] for d in lay["doors"]]
        chamber = [(mx(floor, c), f, 13) for c in sorted(CHAMBER_CENTERS, key=lambda c: mx(floor, c))]
        tops = [tuple(_mid(d["stair_top"], f + STUB_STEPS)) for d in lay["doors"]] if floor < FLOORS else []

        closed = reach(t, entry, [])
        if pres not in closed:
            problems.append(f"floor {floor}: the opponent's spot is not reachable from the entry on foot")
        if vendor in closed:
            problems.append(f"floor {floor}: the vendor's room is reachable with gate A closed")
        after_a = reach(t, entry, [gate_a])
        if vendor not in after_a:
            problems.append(f"floor {floor}: the vendor's room is not reachable with gate A open")
        for i, spot in enumerate(chamber):
            if spot in after_a:
                problems.append(f"floor {floor}: chamber {i} is reachable with its door shut")
        for i, spot in enumerate(tops):
            if spot in after_a:
                problems.append(f"floor {floor}: the threshold of lane {i} is reachable with every door shut")
        if floor < FLOORS and "gate_b" in lay:
            problems.append(f"floor {floor}: a gate B is still in the layout")
        for i, door in enumerate(doors):
            through = reach(t, entry, [gate_a, door])
            if chamber[i] not in through:
                problems.append(f"floor {floor}: chamber {i} is not reachable with its door open")
            for j, spot in enumerate(chamber):
                if j != i and spot in through:
                    problems.append(f"floor {floor}: chamber {j} is reachable through door {i}")
            if floor < FLOORS:
                if tops[i] not in through:
                    problems.append(f"floor {floor}: the threshold of lane {i} is not reachable through door {i}")
                for j, spot in enumerate(tops):
                    if j != i and spot in through:
                        problems.append(f"floor {floor}: the threshold of lane {j} is reachable through door {i}")
                ys = {c[1] for c in through}
                if max(ys) > f + STUB_STEPS or min(ys) < f:
                    problems.append(f"floor {floor}: the walk through door {i} leaves its floor, y {min(ys)}..{max(ys)}")
                landing = cell_of(layout(floor + 1), "entry")
                if landing in through:
                    problems.append(f"floor {floor}: the next floor's arena is reachable on foot through door {i}")
                arrive = lay["doors"][i]["arrival"]
                spot = (arrive["x"], arrive["y"], arrive["z"])
                if not standable(t, *spot):
                    problems.append(f"floor {floor}: the arrival of lane {i} {arrive} is not standable")
                elif landing not in reach(t, spot, []):
                    problems.append(f"floor {floor}: from the arrival of lane {i} the next floor's arena door is not reachable on foot")
                elif pres in reach(t, spot, []) or vendor in reach(t, spot, []):
                    problems.append(f"floor {floor}: the arrival of lane {i} reaches the wrong floor")
            else:
                everything = reach(t, entry, [gate_a] + doors)
                if max(c[1] for c in everything) > f + FURNITURE:
                    problems.append("floor 10 has a way up that was not designed")
    return problems


# ---- output ------------------------------------------------------------------------------------------------------------------

def palette_check(t: Tower, loot_tables: Path | None) -> list[str]:
    if loot_tables is None:
        return []
    real = {p.stem for p in loot_tables.glob("*.json")} | {"air", "barrier", "light", "iron_bars"}
    bad = []
    for name in t.palette:
        base = name.split("[")[0].removeprefix("minecraft:")
        if base not in real:
            bad.append(name)
    return bad


def write_schem(t: Tower, path: Path) -> int:
    import mcschematic
    schem = mcschematic.MCSchematic()
    count = 0
    xs, ys, zs = np.nonzero(t.grid)
    for x, y, z in zip(xs.tolist(), ys.tolist(), zs.tolist()):
        schem.setBlock((x, y, z), t.palette[int(t.grid[x, y, z])])
        count += 1
    path.parent.mkdir(parents=True, exist_ok=True)
    schem.save(str(path.parent), path.stem, mcschematic.Version.JE_1_21)
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="build and validate only")
    parser.add_argument("--no-exterior", action="store_true", help="the bare building, with no island, towers or roof (for the validator tests)")
    parser.add_argument("--out", type=Path, default=Path("validation/schematics"))
    parser.add_argument("--loot-tables", type=Path, help="data/minecraft/loot_table/blocks from a 1.21.1 jar, to check every block id")
    args = parser.parse_args()
    t = build()
    problems = validate(t)
    final, offset = t, (0, 0, 0)
    if not args.no_exterior:
        sys.path.insert(0, str(Path(__file__).parent))
        import exterior
        final = exterior.assemble(t)
        exterior.dress(final)
        # the finished canvas must still hold the same walkable building
        problems += [f"after the exterior: {p}" for p in validate(exterior.building_view(final))]
        final, offset = exterior.crop(final)
    problems += [f"unknown block id {name}" for name in palette_check(final, args.loot_tables)]
    solid = int(np.count_nonzero(final.grid))
    print(f"hollow tower: {final.grid.shape[0]}x{final.grid.shape[1]}x{final.grid.shape[2]}, {solid} blocks, {len(final.palette)} block states")
    for p in problems:
        print("FAIL", p)
    if problems:
        return 1
    print("validation: PASS (every floor: entry -> arena -> gate A -> door -> chamber -> its own lane stub -> threshold; the lanes never meet; each arrival leads to the next arena)")
    if args.check:
        return 0
    count = write_schem(final, args.out / "hollow_tower.schem")
    layout_json = {str(k): layout(k) for k in range(1, FLOORS + 1)}
    if not args.no_exterior:
        layout_json = {k: exterior.shift(v, offset) for k, v in layout_json.items()}
    (args.out / "hollow_tower.layout.json").write_text(json.dumps(layout_json, indent=2) + "\n")
    print(f"wrote {args.out / 'hollow_tower.schem'} ({count} blocks) and hollow_tower.layout.json (offset {offset})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
