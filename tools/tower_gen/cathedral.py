#!/usr/bin/env python3
"""Tideforge's own buildings (P59, owner direction 14 Nov 2026: each themed tower gets its own geometry, not a re-skin of Neutral).

    py -3.14 -I tools/tower_gen/cathedral.py                # build, validate, write .nbt and the layout JSON to validation/schematics/tideforge/
    py -3.14 -I tools/tower_gen/cathedral.py --check        # build and validate only
    py -3.14 -I tools/tower_gen/cathedral.py --install      # also copy the .nbt files into the mod's structure folder

THE SUNKEN CATHEDRAL. The battle hall is 132 x 96 under a glass barrel vault with the sea lying on top of it (lit from inside the water). The
floor of the hall is a lit pool nine deep; the arena is an island in it, reached by a bridge from the arrival and left by a bridge to gate A.
Both long walls are aquariums: clear glass from the pool bed to the spring of the vault, water and coral behind, mullions every 8. Spectator
galleries run along the aquarium walls eleven above the floor. A rose window over the gate, a smaller one over the passage, waterfalls
pouring from pendants in the vault into the pool, a ring chandelier over the island.

North of the hall everything the mod relies on is Neutral's, moved OX blocks east: gate A, the atrium with the vendor stand and pedestals,
the three doors, RoomDresser's 11 x 15 x 8 chambers and each lane's own stair (tower_gen.py has the z plan). South: the passage and the
arrival hall the lane stairs of the floor below land in.

A cathedral floor needs about 66 blocks of height and a cell has 320 above its paste point, so the ten floors are four buildings
(BUILDINGS): the crowns (5, 10) each top one. Leaving the top floor of a building is the lane stair into the next building, the way Neutral's
floor 5 climbs into its Act II (WalkRules.climbsAcross); the next building is prepared in its own cell while the party walks.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import shutil
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tower_gen as tg  # noqa: E402
from tower_gen import BARS, CHAIN, Grid, room, vault  # noqa: E402
from themes import THEMES  # noqa: E402

THEME = "tideforge"
BUILDINGS = ((1, 2), (3, 4, 5), (6, 7), (8, 9, 10))
CROWNS = (5, 10)
PITCH = 68                              # a floor runs from its pool bed (feet - 10) to the cap over its sea (feet + 55); the crown to feet + 65
BASE_FEET = 12

# ---- the plan (x): 150 wide, the shared north part moved OX east so it stays centred ------------------------------------------------
SX = 150
OX = (SX - 66) // 2                     # 42
MID = tg.MID_X + OX                     # 74
LANES = tuple(c + OX for c in tg.LANES)
GATE_X = (tg.GATE_X[0] + OX, tg.GATE_X[1] + OX)
ATRIUM_X = (tg.HALL_X[0] + OX, tg.HALL_X[1] + OX)
STAND = (tg.STAND[0] + OX, tg.STAND[1])
ARRIVAL_X = (tg.ARRIVAL_X[0] + OX, tg.ARRIVAL_X[1] + OX)
HALL_X = (9, 140)                       # the hall's open width; glass at 8 and 141, aquarium water 2..7 and 142..147, the outer wall 0..1, 148..149
AQUARIA = ((8, 2, 7, 1), (141, 142, 147, 148))     # glass x, water x0..x1, back wall x
GALLERIES = ((9, 16, 16), (133, 140, 133))         # deck x0..x1, rail x
BRIDGE_HALF = 4
ISLAND_X = (MID - 20, MID + 20)
FALLS_X = (24, 124)

# ---- the plan (z): the north part as Neutral's, then the long hall --------------------------------------------------------------------
HALL_Z = (47, 142)
ISLAND_Z = (72, 116)
CENTRE_Z = (ISLAND_Z[0] + ISLAND_Z[1]) // 2      # 94
PASSAGE_Z = (143, 144)
ARRIVAL_Z = (145, 156)
ARCH_Z = (157, 158)
ARRIVAL_ANCHOR_Z = ARRIVAL_Z[0] + 8
SZ = ARCH_Z[1] + 2                      # the arches' backlight is the last row
FALLS_Z = (62, CENTRE_Z, 126)

POOL = 9                                # water feet - 9 .. feet - 2, the bed at feet - 10; the floor and bridges at feet - 1
HALL = (30, 18)                         # spring, rise of the vault above the feet
CROWN = (36, 22)
GALLERY_UP = 11                         # the gallery deck at feet + 11, its spectators stand at feet + 12

WATER = "minecraft:water"
FALLING = "minecraft:water[level=8]"
CLEAR = "minecraft:glass"
CORALS = ("minecraft:tube_coral_block", "minecraft:brain_coral_block", "minecraft:bubble_coral_block", "minecraft:fire_coral_block",
          "minecraft:horn_coral_block")

# water is seen through: the generator must neither trim it (a hollow pool would drain into the gap in game) nor treat it as a wall
if "water" not in tg.SEE_THROUGH:
    tg.SEE_THROUGH = tg.SEE_THROUGH + ("water",)


def building_of(floor: int) -> int:
    return next(i for i, b in enumerate(BUILDINGS, 1) if floor in b)


def feet(floor: int) -> int:
    return BASE_FEET + PITCH * BUILDINGS[building_of(floor) - 1].index(floor)


def height(b: int) -> int:
    floors = BUILDINGS[b - 1]
    spring, rise = CROWN if floors[-1] in CROWNS else HALL
    return feet(floors[-1]) + spring + rise + 8


def ellipse(x: int, x0: int, x1: int, rise: int) -> int:
    t = (x - (x0 + x1) / 2) / ((x1 - x0) / 2)
    return int(round(rise * math.sqrt(max(0.0, 1 - t * t))))


def hang(g: Grid, x: int, z: int, top: int, low: int, ring: int) -> None:
    """A chain from the vault at `top` to `low`, a light block there, and six lights hung on short chains from a packed-ice frame: a ring at
    low + 4 joined to the central chain by six spokes, so every piece hangs from the vault through something (stray_check finds floaters)."""
    g.box(CHAIN, x, low + 1, z, x, top, z)
    g.box("light", x, low - 1, z, x, low, z)
    frame = low + 4
    for dx in range(-ring - 1, ring + 2):                           # the ring: a band wide enough that it joins face to face, not corner to corner
        for dz in range(-ring - 1, ring + 2):
            if abs(math.hypot(dx, dz) - ring) <= 0.71:
                g.at("rib", x + dx, frame, z + dz)
    g.box("rib", x - ring, frame, z, x + ring, frame, z)             # four straight spokes from the central chain (a diagonal one joins only by
    g.box("rib", x, frame, z - ring, x, frame, z + ring)             # corners)
    for a in range(0, 360, 60):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        px, pz = x + round(ring * ca), z + round(ring * sa)
        g.box(CHAIN, px, low + 1, pz, px, frame - 1, pz)
        g.at("light", px, low, pz)


def rose(g: Grid, cx: int, cy: int, z: int, back: int, r: int) -> None:
    """A rose window in a wall face at z, backlit from the row behind it: a lapis rim and hub, packed-ice tracery, coloured glass between."""
    for x in range(cx - r - 1, cx + r + 2):
        for y in range(cy - r - 1, cy + r + 2):
            d = math.hypot(x - cx, y - cy)
            if d > r:
                continue
            a = math.degrees(math.atan2(y - cy, x - cx)) % 30
            g.at("inlay" if d >= r - 1 or d <= 2 else "rib" if a < 2.5 or abs(d - r * 0.45) < 0.6 else "glass", x, y, z)
            g.at("light", x, y, back)


# ---- one floor -------------------------------------------------------------------------------------------------------------------

def build_floor(g: Grid, floor: int) -> None:
    f = feet(floor)
    ground = f - 1
    crown = floor in CROWNS
    stairs = floor < tg.FLOORS
    spring, rise = CROWN if crown else HALL
    x0, x1 = HALL_X
    z0, z1 = HALL_Z
    rnd = random.Random(floor)

    # the hall: open from the pool bed to the spring, then the glass vault, and on top of the glass the sea, lit from inside
    g.air(x0, f - POOL, z0, x1, f + spring, z1)

    def top_at(x: int) -> int:
        return f + spring + ellipse(min(max(x, x0), x1), x0 - 1, x1 + 1, rise)

    for x in range(x0, x1 + 1):
        top = top_at(x)
        # the glass climbs to its neighbours' height: where the vault is steep, the sea over one column would otherwise sit beside the open
        # hall under the next one and pour into it
        lid = max(top_at(x - 1), top, top_at(x + 1)) + 1
        g.air(x, f + spring, z0, x, top, z1)
        g.box("glass", x, top + 1, z0, x, lid, z1)
        g.box(WATER, x, lid + 1, z0, x, lid + 2, z1)
        for z in range(z0, z1 + 1):
            if (z - z0) % 8 == 3 or x == MID:
                g.box("rib", x, top, z, x, top + 1, z)              # packed-ice ribs every 8, and a spine
            if x % 9 == 4 and z % 9 == 4:
                g.at("light", x, lid + 2, z)                        # light coming down through the sea

    # the pool: a lit bed, coral growing up out of it clear of the bridges and the island
    g.box("wall_low", x0, f - POOL - 1, z0, x1, f - POOL - 1, z1)
    for x in range(x0 + 2, x1, 5):
        for z in range(z0 + 2, z1, 5):
            g.at("light", x, f - POOL - 1, z)
    g.box(WATER, x0, f - POOL, z0, x1, f - 2, z1)
    for _ in range(170):
        x, z = rnd.randint(x0 + 1, x1 - 1), rnd.randint(z0 + 1, z1 - 1)
        if abs(x - MID) <= BRIDGE_HALF + 2 or ISLAND_X[0] - 2 <= x <= ISLAND_X[1] + 2 and ISLAND_Z[0] - 2 <= z <= ISLAND_Z[1] + 2:
            continue
        g.box(rnd.choice(CORALS), x, f - POOL, z, x, f - POOL + rnd.randint(0, 5), z)

    # the aquarium walls: glass from the pool bed to the spring, water and coral behind, mullions every 8, light on the back wall
    for gx, wa, wb, back in AQUARIA:
        g.box(WATER, wa, f - POOL, z0, wb, f + spring - 2, z1)
        g.box("wall_low", wa, f - POOL - 1, z0, wb, f - POOL - 1, z1)
        g.box(CLEAR, gx, f - POOL, z0, gx, f + spring - 2, z1)
        g.box("wall_low", min(wa, gx), f + spring - 1, z0, max(wb, gx), f + spring - 1, z1)
        for z in range(z0, z1 + 1):
            if (z - z0) % 8 == 3:
                g.box("wall_low", gx, f - POOL, z, gx, f + spring - 1, z)
                g.at("light", gx, f + spring - 2, z)
            if (z - z0) % 6 == 0:
                for y in range(f - POOL + 3, f + spring - 3, 6):
                    g.at("light", back, y, z)
        for _ in range(70):
            x, z = rnd.randint(min(wa, wb), max(wa, wb)), rnd.randint(z0 + 1, z1 - 1)
            g.box(rnd.choice(CORALS), x, f - POOL, z, x, f - POOL + rnd.randint(2, 14), z)

    # the spectator galleries: decks along the aquaria, a rail at the open edge, brackets under them at the mullions, lamps in the rail
    for xa, xb, rail in GALLERIES:
        g.box("rib", xa, f + GALLERY_UP, z0, xb, f + GALLERY_UP, z1)
        g.box("rail", rail, f + GALLERY_UP + 1, z0, rail, f + GALLERY_UP + 1, z1)
        for z in range(z0, z1 + 1):
            if (z - z0) % 8 == 3:
                for k in range(1, 4):
                    reach = 7 - 2 * k
                    lo, hi = (xa, xa + reach) if xa < MID else (xb - reach, xb)
                    g.box("wall_low", lo, f + GALLERY_UP - k, z, hi, f + GALLERY_UP - k, z)
            if (z - z0) % 8 == 7:
                g.at("light", rail, f + GALLERY_UP, z)

    # the island: an arena standing in the pool, ringed in inlay, the medallion, a pylon at each corner
    ix0, ix1 = ISLAND_X
    iz0, iz1 = ISLAND_Z
    g.box("wall", ix0, f - POOL - 1, iz0, ix1, ground, iz1)
    g.box("floor", ix0, ground, iz0, ix1, ground, iz1)
    for z in (iz0, iz1):
        g.box("inlay", ix0, ground, z, ix1, ground, z)
    for x in (ix0, ix1):
        g.box("inlay", x, ground, iz0, x, ground, iz1)
    tg.medallion(g, MID, CENTRE_Z, ground, 16 if crown else 14)
    for px, pz in ((ix0, iz0), (ix1 - 1, iz0), (ix0, iz1 - 1), (ix1 - 1, iz1 - 1)):
        g.box("wall_low", px, f, pz, px + 1, f + 6, pz + 1)
        g.box("light", px, f + 7, pz, px + 1, f + 8, pz + 1)
        g.box("accent", px, f + 9, pz, px + 1, f + 9, pz + 1)

    # the bridges: arrival -> island, island -> gate A; rails, lamp posts on the rails, piers under them down to the bed
    for za, zb in ((iz1 + 1, z1), (z0, iz0 - 1)):
        g.box("wall_low", MID - BRIDGE_HALF, ground, za, MID + BRIDGE_HALF, ground, zb)
        g.box("floor", MID - BRIDGE_HALF + 2, ground, za, MID + BRIDGE_HALF - 2, ground, zb)
        g.box("inlay", MID, ground, za, MID, ground, zb)
        for x in (MID - BRIDGE_HALF, MID + BRIDGE_HALF):
            g.box("rail", x, f, za, x, f, zb)
            for z in range(za + 3, zb - 1, 8):
                g.box("wall", x, f - POOL - 1, z, x, ground - 1, z + 1)
                g.box("wall_low", x, f, z, x, f + 3, z)
                g.at("light", x, f + 4, z)
                g.at("accent", x, f + 5, z)

    # the end walls: a rose window over the gate (backlit from inside the gate wall, above the atrium) and a smaller one over the passage
    rose(g, MID, f + 27, tg.GATE_Z[1], tg.GATE_Z[0], 13)
    rose(g, MID, f + 22, PASSAGE_Z[0], PASSAGE_Z[1], 9)

    # waterfalls: a pendant hangs from the vault, a basin of still water in its foot pours down into the pool
    for fx in FALLS_X:
        for fz in FALLS_Z:
            top = f + spring + ellipse(fx, x0 - 1, x1 + 1, rise)
            g.box("wall_low", fx - 2, f + spring - 2, fz - 2, fx + 2, top, fz + 2)
            g.box("rib", fx - 2, f + spring - 2, fz - 2, fx + 2, f + spring - 2, fz + 2)
            for cx in (fx - 2, fx + 2):                             # a light at each corner of the pendant's foot, so it is seen
                for cz in (fz - 2, fz + 2):
                    g.box("light", cx, f + spring - 2, cz, cx, f + spring + 2, cz)
            g.box(WATER, fx - 1, f + spring - 1, fz - 1, fx + 1, f + spring - 1, fz + 1)
            g.air(fx - 1, f + spring - 2, fz - 1, fx + 1, f + spring - 2, fz + 1)
            g.box(FALLING, fx - 1, ground, fz - 1, fx + 1, f + spring - 2, fz + 1)

    # chandeliers: a great ring over the island, smaller ones over the two bridges
    peak = f + spring + rise
    hang(g, MID, CENTRE_Z, peak - 1, f + (28 if crown else 24), 7 if crown else 6)
    for z in ((z0 + iz0) // 2, (iz1 + z1) // 2):
        hang(g, MID, z, peak - 1, f + 20, 3)

    # the passage south into the arrival hall, and that hall: low, ribbed, a landing arch per lane on its south wall
    g.air(MID - BRIDGE_HALF, f, PASSAGE_Z[0], MID + BRIDGE_HALF, f + 6, PASSAGE_Z[1])
    g.box("floor", MID - BRIDGE_HALF, ground, PASSAGE_Z[0], MID + BRIDGE_HALF, ground, PASSAGE_Z[1])
    g.box("trim", MID - BRIDGE_HALF - 1, f + 7, PASSAGE_Z[0], MID + BRIDGE_HALF + 1, f + 7, PASSAGE_Z[0])
    room(g, *ARRIVAL_X, *ARRIVAL_Z, f, 8)
    g.air(MID - BRIDGE_HALF, f, PASSAGE_Z[1], MID + BRIDGE_HALF, f + 6, PASSAGE_Z[1] + 1)       # the arrival's plinth would close the passage
    g.box("rib", ARRIVAL_X[0], f + 8, ARRIVAL_Z[0], ARRIVAL_X[1], f + 8, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0], ARRIVAL_X[1] + 1, 4):
        g.box("wall_low", x, f + 7, ARRIVAL_Z[0], x, f + 7, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0] + 3, ARRIVAL_X[1], 6):
        g.at("light", x, f + 8, (ARRIVAL_Z[0] + ARRIVAL_Z[1]) // 2)
    g.box("inlay", MID, ground, ARRIVAL_Z[0], MID, ground, ARRIVAL_Z[1])
    for c in LANES:
        g.air(c - 2, f, ARCH_Z[0], c + 2, f + 4, ARCH_Z[1])
        g.box("glass", c - 2, f, ARCH_Z[1], c + 2, f + 4, ARCH_Z[1])
        g.box("light", c - 2, f, ARCH_Z[1] + 1, c + 2, f + 4, ARCH_Z[1] + 1)
        g.box("floor", c - 2, ground, ARCH_Z[0], c + 2, ground, ARCH_Z[1])
        g.box("trim", c - 3, f, ARCH_Z[0], c - 3, f + 5, ARCH_Z[0])
        g.box("trim", c + 3, f, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.box("trim", c - 3, f + 5, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.at("accent", c, f + 5, ARCH_Z[0])

    # north: the atrium, gate A, the doors, the chambers and the lane stairs, exactly Neutral's moved OX east
    room(g, *ATRIUM_X, *tg.ATRIUM_Z, f, 9)
    vault(g, *ATRIUM_X, *tg.ATRIUM_Z, f, 9, 3, 4)
    tg.vendor_stand(g, STAND[0], STAND[1], f, "south")
    for x in LANES:
        tg.chandelier(g, x, 38, f, 6)
    g.box("inlay", ATRIUM_X[0], ground, 38, STAND[0] - 2, ground, 38)
    g.air(GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1])
    g.box(BARS, GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1])
    g.box("trim", GATE_X[0] - 1, f + 6, tg.GATE_Z[0], GATE_X[1] + 1, f + 6, tg.GATE_Z[1])
    g.at("accent", MID, f + 6, tg.GATE_Z[1])
    for c in LANES:
        room(g, c - tg.CHAMBER_HALF, c + tg.CHAMBER_HALF, *tg.CHAMBER_Z, f, tg.CHAMBER_H)
        vault(g, c - tg.CHAMBER_HALF, c + tg.CHAMBER_HALF, *tg.CHAMBER_Z, f, tg.CHAMBER_H, 3, 3)
        g.box("inlay", c - 1, ground, tg.CHAMBER_Z[0], c + 1, ground, tg.CHAMBER_Z[1])
        if stairs:
            tg.cut_stair(g, c, f)
    for c in LANES:
        h = tg.DOOR_HALF
        g.air(c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1])
        g.box(BARS, c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c - h - 1, f, tg.DOOR_WALL_Z[0], c - h - 1, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c + h + 1, f, tg.DOOR_WALL_Z[0], c + h + 1, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c - h - 1, f + 6, tg.DOOR_WALL_Z[0], c + h + 1, f + 7, tg.DOOR_WALL_Z[1])
        g.box("light", c - h - 1, f + 8, tg.DOOR_WALL_Z[1], c + h + 1, f + 8, tg.DOOR_WALL_Z[1])
        g.at("accent", c, f + 7, tg.DOOR_WALL_Z[1])


def build_building(theme: dict[str, str], b: int) -> Grid:
    g = Grid(theme, (SX, height(b), SZ))
    g.grid[:] = g.id("wall")
    for floor in BUILDINGS[b - 1]:
        build_floor(g, floor)
    return g


# ---- the layout the floor definitions use -------------------------------------------------------------------------------------

def arrival(floor: int, lane: int) -> dict:
    return tg.anchor(LANES[lane], feet(floor), ARRIVAL_ANCHOR_Z, 180)


def layout(floor: int) -> dict:
    f = feet(floor)
    doors = []
    for lane, c in enumerate(LANES):
        h = tg.DOOR_HALF
        door = {
            "open": tg.box(c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1]),
            "frame": tg.box(c - h - 1, f + 6, tg.DOOR_WALL_Z[0], c + h + 1, f + 7, tg.DOOR_WALL_Z[1]),
            "pedestal": tg.anchor(c, f, tg.PEDESTAL_Z, 0),
        }
        if floor < tg.FLOORS:
            door["stair_top"] = tg.box(c - tg.STUB_HALF, f + tg.STEPS, tg.LANDING_Z[0], c + tg.STUB_HALF, f + tg.STEPS + 1, tg.LANDING_Z[0] + 1)
            door["arrival"] = arrival(floor + 1, lane)
        doors.append(door)
    return {
        "structure": f"cobbletowers:{THEME}_hall{building_of(floor)}",
        "entry": tg.anchor(MID, f, ISLAND_Z[1] - 4, 180),
        "presentation": tg.anchor(MID, f, ISLAND_Z[0] + 6, 0),
        "spectator": tg.anchor((GALLERIES[0][0] + GALLERIES[0][1]) // 2, f + GALLERY_UP + 1, CENTRE_Z, 270),
        "exit": tg.anchor(STAND[0] + 3, f, STAND[1] + 2, 0),
        "gate_a": tg.box(GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1]),
        "doors": doors,
    }


# ---- validation: the same walk as Neutral's, over these buildings -----------------------------------------------------------------

def validate(buildings: dict[int, Grid]) -> list[str]:
    problems: list[str] = []
    walkers = {b: tg.Walker(g) for b, g in buildings.items()}
    for floor in range(1, tg.FLOORS + 1):
        w = walkers[building_of(floor)]
        lay = layout(floor)
        f = feet(floor)
        for name in ("entry", "presentation", "spectator", "exit"):
            if not w.standable(*tg._cell(lay[name])):
                problems.append(f"floor {floor}: the {name} anchor {lay[name]} is not standable")
        for i, door in enumerate(lay["doors"]):
            if not w.standable(*tg._cell(door["pedestal"])):
                problems.append(f"floor {floor}: the pedestal of door {i} is not standable")
            x0, y0, z0, x1, y1, z1 = tg.dresser_box(door)
            if w.g.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1].any():
                problems.append(f"floor {floor}: RoomDresser's box behind door {i} is not empty air")
        for _, _, rail in GALLERIES:
            gaps = [z for z in range(*HALL_Z) if w.g.name(rail, f + GALLERY_UP + 1, z) != w.g.theme["rail"]]
            if gaps:
                problems.append(f"floor {floor}: the gallery rail at x {rail} has gaps (z {gaps[:4]})")
        entry, pres, spec = tg._cell(lay["entry"]), tg._cell(lay["presentation"]), tg._cell(lay["spectator"])
        vendor = (lay["exit"]["x"], f, lay["exit"]["z"] + 2)
        if not w.standable(*vendor):
            problems.append(f"floor {floor}: the spot in front of the vendor's counter {vendor} is not standable")
        chambers = [(c, f, (tg.CHAMBER_Z[0] + tg.CHAMBER_Z[1]) // 2) for c in LANES]
        tops = [tg._mid(d["stair_top"], f + tg.STEPS) for d in lay["doors"]] if floor < tg.FLOORS else []
        gate = lay["gate_a"]
        closed = w.reach(entry, [])
        if pres not in closed:
            problems.append(f"floor {floor}: the opponent's spot is not reachable from the entry")
        if vendor in closed:
            problems.append(f"floor {floor}: the vendor is reachable with gate A shut")
        if spec in closed or pres in w.reach(spec, []):
            problems.append(f"floor {floor}: the gallery and the arena are joined on foot")
        after_a = w.reach(entry, [gate])
        if vendor not in after_a:
            problems.append(f"floor {floor}: the vendor is not reachable with gate A open")
        for i, spot in enumerate(chambers):
            if spot in after_a:
                problems.append(f"floor {floor}: chamber {i} is reachable with its door shut")
        for i, door in enumerate(lay["doors"]):
            through = w.reach(entry, [gate, door["open"]])
            if chambers[i] not in through:
                problems.append(f"floor {floor}: chamber {i} is not reachable through its door")
            if any(spot in through for j, spot in enumerate(chambers) if j != i):
                problems.append(f"floor {floor}: another chamber is reachable through door {i}")
            if floor < tg.FLOORS:
                if tops[i] not in through:
                    problems.append(f"floor {floor}: the threshold of lane {i} is not reachable through door {i}")
                if any(spot in through for j, spot in enumerate(tops) if j != i):
                    problems.append(f"floor {floor}: another lane's threshold is reachable through door {i}")
                ys = {c[1] for c in through}
                if max(ys) > f + tg.STEPS or min(ys) < f:
                    problems.append(f"floor {floor}: the walk through door {i} leaves its floor, y {min(ys)}..{max(ys)}")
                nw = walkers[building_of(floor + 1)]
                spot = tg._cell(door["arrival"])
                if not nw.standable(*spot):
                    problems.append(f"floor {floor}: the arrival of lane {i} {door['arrival']} is not standable")
                elif tg._cell(layout(floor + 1)["entry"]) not in nw.reach(spot, []):
                    problems.append(f"floor {floor}: from the arrival of lane {i} the next battle hall is not reachable")
    return problems


def spills(g: Grid) -> list[str]:
    """Still water may only touch water, glass or a solid block on its four sides and below, or it runs out over the floors in game. Falling
    water (a waterfall) may have air beside it but must land on water."""
    names = np.array(g.palette)
    still = np.isin(g.grid, [i for i, n in enumerate(names) if n == WATER])
    falling = np.isin(g.grid, [i for i, n in enumerate(names) if n == FALLING])
    air = g.grid == 0
    problems = []
    sides = np.zeros_like(still)
    for axis, shift in ((0, 1), (0, -1), (2, 1), (2, -1), (1, -1)):
        sides |= np.roll(air, -shift, axis=axis) if shift > 0 else np.roll(air, 1, axis=axis)
    bad = still & sides
    if bad.any():
        problems.append(f"{int(bad.sum())} still water block(s) with air beside or below, first at {tuple(int(v) for v in np.argwhere(bad)[0])}")
    below = np.roll(g.grid, 1, axis=1)
    landing = falling & ~np.isin(below, [i for i, n in enumerate(names) if n in (WATER, FALLING)])
    if landing.any():
        problems.append(f"{int(landing.sum())} falling water block(s) not over water, first at {tuple(int(v) for v in np.argwhere(landing)[0])}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="build and validate only")
    parser.add_argument("--install", action="store_true", help="also copy the .nbt files into the mod's structure folder")
    parser.add_argument("--out", type=Path, default=tg.ROOT / "validation" / "schematics")
    args = parser.parse_args()
    theme = THEMES[THEME]

    buildings = {b: build_building(theme, b) for b in range(1, len(BUILDINGS) + 1)}
    prep = tg.build_prep(theme)
    problems = validate(buildings) + tg.validate_prep(prep)
    for b, g in buildings.items():
        problems += [f"hall{b}: {p}" for p in spills(g)]
        solid = int(np.count_nonzero(g.grid))
        kept = solid - tg.trim(g)
        print(f"{THEME}_hall{b}: {g.grid.shape[0]}x{g.grid.shape[1]}x{g.grid.shape[2]}, {solid:,} blocks before the trim, {kept:,} kept")
        starts = [s for k in BUILDINGS[b - 1] for s in (tg._cell(layout(k)["entry"]), tg._cell(layout(k)["spectator"]))]
        problems += [f"hall{b}: {p}" for p in tg.shell_closed(g, starts)]
        problems += tg.held_window_problems(g.grid, f"{THEME}_hall{b}")
        problems += [f"hall{b} after the trim: {p}" for p in spills(g)]
    tg.trim(prep)
    problems += [f"prep: {p}" for p in tg.shell_closed(prep, [tg._cell(tg.prep_layout()["entry"])])]
    problems += tg.held_window_problems(prep.grid, f"{THEME}_prep")
    for p in problems:
        print("FAIL", p)
    if problems:
        return 1
    print("validation: PASS (every floor: arrival -> bridge -> island -> bridge -> gate A -> door -> chamber -> its own stair -> threshold;"
          " lanes never meet; each arrival leads to the next hall; galleries cut off from the arena; no water can run out; shells closed)")
    if args.check:
        return 0
    out = args.out / THEME
    out.mkdir(parents=True, exist_ok=True)
    files = {f"{THEME}_hall{b}": g for b, g in buildings.items()} | {f"{THEME}_prep": prep}
    for stem, g in files.items():
        n = tg.write_nbt(g, out / f"{stem}.nbt")
        print(f"wrote {out / (stem + '.nbt')} ({n:,} blocks)")
    lay = {"floors": {str(k): layout(k) for k in range(1, tg.FLOORS + 1)}, "prep": tg.prep_layout()}
    (out / f"{THEME}.layout.json").write_text(json.dumps(lay, indent=2) + "\n")
    print(f"wrote {out / (THEME + '.layout.json')}")
    if args.install:
        dest = tg.ROOT / "src/main/resources/data/cobbletowers/structure"
        for stem in files:
            shutil.copyfile(out / f"{stem}.nbt", dest / f"{stem}.nbt")
            print("installed", dest / f"{stem}.nbt")
        for old in ("act1", "act2"):                      # the re-skin of Neutral's acts this replaces
            (dest / f"{THEME}_{old}.nbt").unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
