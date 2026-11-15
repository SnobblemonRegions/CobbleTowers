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

The north part, the passage and the arrival hall, the buildings, the layout and the walk check are shared with the other themed towers
(grand_hall.py). A cathedral floor needs about 66 blocks of height and a cell has 320 above its paste point, so the ten floors are four
buildings (BUILDINGS): the crowns (5, 10) each top one.
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
from grand_hall import ARRIVAL_ANCHOR_Z, HALL_X, HALL_Z, MID, PASSAGE_Z, ellipse, hang, rose  # noqa: E402,F401
from tower_gen import Grid  # noqa: E402
from themes import THEMES  # noqa: E402,F401

THEME = "tideforge"
BUILDINGS = ((1, 2), (3, 4, 5), (6, 7), (8, 9, 10))
CROWNS = (5, 10)
PITCH = 68                              # a floor runs from its pool bed (feet - 10) to the cap over its sea (feet + 55); the crown to feet + 65
BASE_FEET = 12

AQUARIA = ((8, 2, 7, 1), (141, 142, 147, 148))     # glass x, water x0..x1, back wall x
GALLERIES = ((9, 16, 16), (133, 140, 133))         # deck x0..x1, rail x
BRIDGE_HALF = gh.PASSAGE_HALF
ISLAND_X = (MID - 20, MID + 20)
FALLS_X = (24, 124)
ISLAND_Z = (72, 116)
CENTRE_Z = (ISLAND_Z[0] + ISLAND_Z[1]) // 2      # 94
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


def hall(g: Grid, floor: int, f: int, crown: bool) -> None:
    """The sunken cathedral's battle hall for one floor (feet f)."""
    ground = f - 1
    spring, rise = CROWN if crown else HALL
    rnd = random.Random(floor)
    x0, x1 = HALL_X
    z0, z1 = HALL_Z

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

SPEC = gh.Spec(
    theme=THEME, buildings=BUILDINGS, crowns=CROWNS, pitch=PITCH, base_feet=BASE_FEET,
    top=lambda crown: sum(CROWN if crown else HALL) + 8,
    hall=hall,
    entry_z=ISLAND_Z[1] - 4, presentation_z=ISLAND_Z[0] + 6,
    spectator=((GALLERIES[0][0] + GALLERIES[0][1]) // 2, GALLERY_UP + 1, CENTRE_Z),
    rails=tuple((rail, GALLERY_UP + 1) for _, _, rail in GALLERIES),
    checks=lambda g: spills(g),
    summary="arrival -> bridge -> island -> bridge -> gate A -> door -> chamber -> its own stair -> threshold; no water can run out",
)


# the names the previews and integrity_check use
def feet(floor: int) -> int:
    return gh.feet(SPEC, floor)


def building_of(floor: int) -> int:
    return gh.building_of(SPEC, floor)


def build_building(theme: dict[str, str], b: int) -> Grid:
    return gh.build_building(SPEC, theme, b)


def layout(floor: int) -> dict:
    return gh.layout(SPEC, floor)


if __name__ == "__main__":
    sys.exit(gh.main(SPEC))
