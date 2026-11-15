#!/usr/bin/env python3
"""Rootvale's own buildings (P59): THE HOLLOW TREE.

    py -3.14 -I tools/tower_gen/rootvale.py [--check] [--install]

The battle hall is the inside of a colossal hollow trunk: an elliptical hollow 128 x 92 at the floor, narrowing as it rises, heartwood
streaking up its inner face with knots of light in it. Eight great roots arch from the foot of the trunk up to a heartwood knot hanging over
the arena, glowing from within, azalea trailing from them. Far overhead a canopy of leaves with warm light coming down through it (the tower
dimension stands at noon and its floors are stacked, so the light above the leaves is part of the building). The floor is moss, fireflies
sunk in it, roots running flush across it, a worn path from the passage to the gate. The arena is a stump cut level, its growth rings for a
medallion, a moat round it crossed by two plank bridges. Shelf fungus grows out of the trunk as the spectator galleries, fenced.

Leaves are drawn see-through: the trim must keep the solid behind the canopy, or the void would show through the gaps in the leaves. The moat
is checked like Tideforge's water: nothing can run out. Shared parts and the walk check: grand_hall.py.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cathedral  # noqa: E402  (its no-spill check)
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
from grand_hall import MID  # noqa: E402
from tower_gen import CHAIN, Grid  # noqa: E402

THEME = "rootvale"
BUILDINGS = ((1, 2), (3, 4, 5), (6, 7), (8, 9, 10))
CROWNS = (5, 10)
CANOPY = 50                             # the leaves start this far above the feet (the crown's 6 higher); warm light 4 above them
PITCH = 68
BASE_FEET = 6
CENTRE_Z = 94
TRUNK = (64, 46)                        # the hollow's half axes (x, z) at the floor; it narrows above feet + 16
STUMP = 20                              # the arena: a stump cut level, this radius
MOAT = (21.5, 24.5)                     # the moat between these radii
PATH_HALF = 3
LEDGE_UP = 11                           # the shelf-fungus galleries at feet + 11, spectators stand at feet + 12
ROOT_ANGLES = (25, 65, 115, 155, 205, 245, 295, 335)       # degrees, 0 east, 90 south: clear of the path north and south

BARK = "minecraft:spruce_wood"
HEART = "minecraft:stripped_spruce_wood"
HEART2 = "minecraft:stripped_oak_wood"
ROOT = "minecraft:dark_oak_wood"
LEAF, LEAF2 = "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"
GLOW, GLOW2, FIREFLY = "minecraft:shroomlight", "minecraft:ochre_froglight", "minecraft:verdant_froglight"
WATER = "minecraft:water"
PLANKS, FENCE = "minecraft:spruce_planks", "minecraft:oak_fence"
CAP, STEM = "minecraft:brown_mushroom_block", "minecraft:mushroom_stem"
RING_A, RING_B = "minecraft:stripped_oak_log[axis=y]", "minecraft:stripped_spruce_log[axis=y]"
LIP = "minecraft:oak_log[axis=y]"

# leaves are seen through: the trim must keep what stands behind the canopy
if "leaves" not in tg.SEE_THROUGH:
    tg.SEE_THROUGH = tg.SEE_THROUGH + ("leaves",)


def noise(x: int, z: int, seed: int) -> float:
    return ((x * 73856093 ^ z * 19349663 ^ seed * 83492791) & 0xFFFF) / 65535.0


def hollow(f: int, y: int) -> tuple[float, float]:
    """The hollow's half axes at height y."""
    up = max(0, y - f - 16)
    return TRUNK[0] - 0.20 * up, TRUNK[1] - 0.14 * up


def hall(g: Grid, floor: int, f: int, crown: bool) -> None:
    """The hollow tree's battle hall for one floor (feet f)."""
    ground = f - 1
    rnd = random.Random(2000 + floor)
    canopy = f + CANOPY + (6 if crown else 0)
    x0, x1 = gh.HALL_X
    z0, z1 = gh.HALL_Z
    xs, zs = np.meshgrid(np.arange(g.grid.shape[0]), np.arange(g.grid.shape[2]), indexing="ij")
    inside_box = (xs >= x0) & (xs <= x1) & (zs >= z0) & (zs <= z1)
    ang = (np.degrees(np.arctan2(zs - CENTRE_Z, xs - MID)) + 360) % 360
    heart_id, heart2_id = g.id(HEART), g.id(HEART2)

    def ell(y: int) -> np.ndarray:
        rx, rz = hollow(f, y)
        return np.sqrt(((xs - MID) / rx) ** 2 + ((zs - CENTRE_Z) / rz) ** 2)

    # the hollow, floor to canopy: heartwood streaking up the inner face, two blocks thick, then the solid of the building
    for y in range(f, canopy + 5):
        e = ell(y)
        hollow_ = (e < 1) & inside_box
        face = (e >= 1) & (e < 1.06) & inside_box
        g.grid[:, y, :][hollow_] = 0
        streak = ((ang // 3).astype(int) % 3) == 0
        g.grid[:, y, :][face & streak] = heart2_id
        g.grid[:, y, :][face & ~streak] = heart_id
    floor_mask = (ell(f) < 1) & inside_box
    g.grid[:, ground, :][floor_mask] = g.id("floor")
    g.grid[:, ground - 1, :][floor_mask] = g.id("minecraft:rooted_dirt")
    for x, z in np.argwhere(floor_mask):
        if noise(int(x), int(z), 3) < 0.08:
            g.at("minecraft:rooted_dirt", int(x), ground, int(z))
        elif noise(int(x), int(z), 4) < 0.012:
            g.at(FIREFLY, int(x), ground, int(z))                  # fireflies: verdant light sunk in the moss

    # knots of light in the trunk's face
    for _ in range(220):
        y = rnd.randint(f + 2, canopy - 3)
        a = rnd.uniform(0, 2 * math.pi)
        rx, rz = hollow(f, y)
        x, z = round(MID + (rx + 0.6) * math.cos(a)), round(CENTRE_Z + (rz + 0.6) * math.sin(a))
        if x0 < x < x1 and z0 < z < z1 and g.grid[x, y, z] != 0:
            g.at(GLOW if rnd.random() < 0.7 else GLOW2, x, y, z)

    # the canopy: leaves, gaps in them, and warm light above showing through
    rx, rz = hollow(f, canopy)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if ((x - MID) / rx) ** 2 + ((z - CENTRE_Z) / rz) ** 2 >= 1:
                continue
            for y in range(canopy, canopy + 3):
                gap = math.sin(x * 0.45) * math.cos(z * 0.5) + (y - canopy) * 0.35 > 0.95
                if gap:
                    g.air(x, y, z, x, y, z)
                else:
                    g.at(LEAF2 if (x * 7 + z * 3 + y) % 5 == 0 else LEAF, x, y, z)
            g.air(x, canopy + 3, z, x, canopy + 3, z)
            g.at(GLOW2 if (x + 2 * z) % 4 == 0 else GLOW, x, canopy + 4, z)
    for _ in range(70):                                             # shroomlight clustered under the leaves, hanging from them
        x, z = rnd.randint(MID - int(rx) + 4, MID + int(rx) - 4), rnd.randint(CENTRE_Z - int(rz) + 4, CENTRE_Z + int(rz) - 4)
        if g.name(x, canopy, z) in (LEAF, LEAF2) and g.grid[x, canopy - 1, z] == 0:
            g.at(GLOW, x, canopy - 1, z)

    # roots running flush across the floor, from the trunk's foot in towards the moat
    for k in range(12):
        a = math.radians(k * 30 + 15)
        if abs(math.cos(a)) < 0.35:
            continue                                                # clear of the path north and south
        for t in np.linspace(0, 1, 90):
            r = 1 - t * 0.55
            x = round(MID + r * TRUNK[0] * math.cos(a) + 2 * math.sin(t * 9))
            z = round(CENTRE_Z + r * TRUNK[1] * math.sin(a))
            g.box(ROOT, x - 1, ground, z - 1, x + 1, ground, z + 1)

    # the worn path in from the passage and out to the gate, and the corridors through the trunk to them
    g.box("inlay", MID - PATH_HALF, ground, z0, MID + PATH_HALF, ground, z1)
    for za, zb in ((z0, z0 + 6), (z1 - 6, z1)):
        g.air(MID - PATH_HALF - 1, f, za, MID + PATH_HALF + 1, f + 6, zb)
        g.box("inlay", MID - PATH_HALF - 1, ground, za, MID + PATH_HALF + 1, ground, zb)
    for z in list(range(z0 + 9, CENTRE_Z - 26, 6)) + list(range(CENTRE_Z + 27, z1 - 6, 6)):
        for x in (MID - PATH_HALF - 2, MID + PATH_HALF + 2):
            g.box(FENCE, x, f, z, x, f + 2, z)
            g.at(GLOW, x, f + 3, z)

    # lamp posts in a ring outside the moat, clear of the path: light where the fight is watched from
    for deg in range(0, 360, 24):
        a = math.radians(deg)
        x, z = round(MID + 29 * math.cos(a)), round(CENTRE_Z + 27 * math.sin(a))
        if abs(x - MID) <= PATH_HALF + 2:
            continue
        g.box(FENCE, x, f, z, x, f + 2, z)
        g.at(GLOW, x, f + 3, z)

    # the arena: a stump cut level, its growth rings the medallion, a bark lip open where the path crosses; the moat round it
    for x in range(MID - 26, MID + 27):
        for z in range(CENTRE_Z - 26, CENTRE_Z + 27):
            d = math.hypot(x - MID, z - CENTRE_Z)
            if d <= STUMP:
                g.at(RING_A if int(d) % 4 < 2 else RING_B, x, ground, z)
            elif d <= MOAT[0]:
                g.at(LIP, x, ground, z)
                if abs(x - MID) > PATH_HALF:
                    lit = int(math.degrees(math.atan2(z - CENTRE_Z, x - MID)) + 360) % 15 < 3
                    g.at(GLOW if lit else LIP, x, f, z)            # the arena's edge glows: shroomlight set in the bark lip
            elif d <= MOAT[1]:
                g.box(WATER, x, f - 2, z, x, ground, z)
                g.at("minecraft:clay", x, f - 3, z)
                if abs(x - MID) <= PATH_HALF:
                    g.at(PLANKS, x, ground, z)                      # the bridges over it
    for z in range(CENTRE_Z - int(MOAT[1]) - 1, CENTRE_Z + int(MOAT[1]) + 2):
        d_far = abs(z - CENTRE_Z)
        if MOAT[0] - 1 < d_far <= MOAT[1] + 1:
            for x in (MID - PATH_HALF, MID + PATH_HALF):
                g.at(FENCE, x, f, z)
    g.at("accent", MID, ground, CENTRE_Z)

    # eight great roots arching from the trunk's foot up to the heartwood knot over the arena
    heart = (MID, f + 44 + (4 if crown else 0), CENTRE_Z)
    for deg in ROOT_ANGLES:
        a = math.radians(deg)
        rx0, rz0 = hollow(f, f)
        p0 = np.array([MID + (rx0 - 1) * math.cos(a), f, CENTRE_Z + (rz0 - 1) * math.sin(a)])
        p1 = np.array([MID + 0.55 * rx0 * math.cos(a), heart[1] - 4, CENTRE_Z + 0.55 * rz0 * math.sin(a)])
        p2 = np.array(heart, dtype=float)
        for t in np.linspace(0, 1, 300):
            p = (1 - t) ** 2 * p0 + 2 * (1 - t) * t * p1 + t * t * p2
            th = 2 if t < 0.55 else 1
            x, y, z = (int(round(v)) for v in p)
            g.box(ROOT, x - th, y - th, z - th, x + th, y + th, z + th)
            if 0.3 < t < 0.85 and rnd.random() < 0.06:
                drop = rnd.randint(2, 6)
                g.box(LEAF2 if rnd.random() < 0.4 else LEAF, x, y - th - drop, z, x, y - th - 1, z)      # azalea trailing off the root
            if 0.15 < t < 0.95 and rnd.random() < 0.08:
                g.at(GLOW, x, y - th - 1, z)
            if 0.05 < t < 0.9 and rnd.random() < 0.07:
                g.at(GLOW, x, y + th, z)                            # light along the root's back, so it reads as a root, not a shadow
    hx, hy, hz = heart
    for x in range(hx - 6, hx + 7):                                 # the knot: wood with glowing heart-light in it
        for y in range(hy - 7, hy + 6):
            for z in range(hz - 6, hz + 7):
                d = math.hypot(x - hx, (y - hy) * 0.8, z - hz)
                if d <= 6.3:
                    g.at(ROOT if d > 4.3 or (x + y + z) % 3 else GLOW2, x, y, z)
    for a in range(0, 360, 30):                                     # light hanging under the knot
        x, z = round(hx + 4 * math.cos(math.radians(a))), round(hz + 4 * math.sin(math.radians(a)))
        low = hy - 14 - (a // 30) % 3 * 3
        top = hy - 6
        while g.grid[x, top + 1, z] == 0:
            top += 1                                                # up to the knot's underside wherever it is
        g.box(CHAIN, x, low + 1, z, x, top, z)
        g.at(GLOW, x, low, z)

    # close the one-block nooks where a root meets the trunk or another root, and the one-block pinholes in the leaves: at full size they
    # read as missing blocks, not as the grain of a tree (integrity_check). The canopy keeps its larger gaps; the path, its corridors and the
    # gallery height are left alone.
    fill_nooks(g, f, canopy)

    # spectator galleries: shelf fungus growing out of the trunk on both sides, fenced at the open edge
    y = f + LEDGE_UP
    rx_l, rz_l = hollow(f, y)
    for deg in (180, 0):
        cells = set()
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                e = math.hypot((x - MID) / rx_l, (z - CENTRE_Z) / rz_l)
                if e >= 1:
                    continue
                da = abs((math.degrees(math.atan2(z - CENTRE_Z, x - MID)) - deg + 180) % 360 - 180)
                depth = (1 - e) * rx_l
                if da < 14 and depth < 9 * math.cos(math.radians(min(90, da * 6.4))):
                    cells.add((x, z))
        for x, z in cells:
            g.at(GLOW if (x + 2 * z) % 7 == 0 else CAP, x, y, z)     # shroomlight set in the cap: the gallery is lit where people stand
            g.at(STEM, x, y - 1, z)
            if any((x + dx, z + dz) not in cells and math.hypot((x + dx - MID) / rx_l, (z + dz - CENTRE_Z) / rz_l) < 1
                   for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                g.at(FENCE, x, y + 1, z)


def fill_nooks(g: Grid, f: int, canopy: int) -> None:
    x0, x1 = gh.HALL_X
    z0, z1 = gh.HALL_Z
    sub = g.grid[x0 - 1:x1 + 2, f:canopy + 4, z0 - 1:z1 + 2]
    for _ in range(2):                                              # filling one nook can close the next one in
        air = sub == 0
        solid = ~air
        count = np.zeros(sub.shape, dtype=np.int8)
        ring = np.zeros(sub.shape, dtype=bool)
        for axis in range(3):
            for d in (1, -1):
                count += np.roll(solid, d, axis=axis).astype(np.int8)
        for a, b in ((0, 2),):                                      # boxed in within the horizontal plane (a pinhole in the leaves)
            r = np.ones(sub.shape, dtype=bool)
            for axis in (a, b):
                for d in (1, -1):
                    r &= np.roll(solid, d, axis=axis)
            ring |= r
        nook = air & (count >= 5)
        nook[:, :2, :] = False                                      # never at the floor: that is the walk
        nook[:, LEDGE_UP - 1:LEDGE_UP + 3, :] = False               # nor at the galleries
        mid = MID - (x0 - 1)
        nook[mid - PATH_HALF - 3:mid + PATH_HALF + 4, :, :] = False  # nor over the path and its corridors
        pin = air & ring
        pin[:, :canopy - f, :] = False                              # pinholes only in the leaves
        ys = np.arange(sub.shape[1])[None, :, None]
        leaf_id, root_id = g.id(LEAF), g.id(ROOT)
        sub[nook & (ys < canopy - f)] = root_id
        sub[(nook | pin) & (ys >= canopy - f)] = leaf_id
    g.grid[x0 - 1:x1 + 2, f:canopy + 4, z0 - 1:z1 + 2] = sub


def spectator_x() -> int:
    """Four blocks out from the trunk on the west gallery (the hollow does not narrow until feet + 16, above the galleries)."""
    return round(MID - TRUNK[0] + 4)


SPEC = gh.Spec(
    theme=THEME, buildings=BUILDINGS, crowns=CROWNS, pitch=PITCH, base_feet=BASE_FEET,
    top=lambda crown: CANOPY + (6 if crown else 0) + 8,
    hall=hall,
    entry_z=CENTRE_Z + STUMP - 5, presentation_z=CENTRE_Z - STUMP + 6,
    spectator=(spectator_x(), LEDGE_UP + 1, CENTRE_Z),
    checks=lambda g: cathedral.spills(g),
    summary="arrival -> path -> bridge -> stump -> bridge -> gate A -> door -> chamber -> its own stair -> threshold; no water can run out",
)


def feet(floor: int) -> int:
    return gh.feet(SPEC, floor)


def build_building(theme: dict[str, str], b: int) -> Grid:
    return gh.build_building(SPEC, theme, b)


if __name__ == "__main__":
    sys.exit(gh.main(SPEC))
