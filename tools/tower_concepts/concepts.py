#!/usr/bin/env python3
"""Concept blockouts (P59 themed towers): the battle hall of each regional tower at the enlarged cell size, so the three can be judged side by
side before any of them is built properly. Not game structures: no trim, no anchors, no shells; the arena, the entry bridge and the gate sit
where the real flow needs them (party arrives from the south, walks north to the gate).

    py -3.14 -I tools/tower_concepts/concepts.py <out_dir> [tideforge|duskvale|rootvale ...]

Writes <tower>_<shot>.png for each shot and concepts_sheet.png.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tower_gen"))
sys.path.insert(0, str(HERE.parent / "halloween_tower"))
import tower_gen as tg  # noqa: E402
import view as V  # noqa: E402

W, D = 150, 124                 # x by z: the hall alone, inside the radius-5 cell window (176 x 176)
MID, CZ = 75, 62                # the arena's centre


def ellipse(x: float, x0: float, x1: float, rise: float) -> int:
    t = (x - (x0 + x1) / 2) / ((x1 - x0) / 2)
    return int(round(rise * math.sqrt(max(0.0, 1 - t * t))))


def disc(g: tg.Grid, block: str, cx: int, y: int, cz: int, r0: float, r1: float = -1.0) -> None:
    """A flat ring (r1 < dist <= r0) or disc (r1 < 0) at height y."""
    for x in range(int(cx - r0) - 1, int(cx + r0) + 2):
        for z in range(int(cz - r0) - 1, int(cz + r0) + 2):
            d = math.hypot(x - cx, z - cz)
            if r1 < d <= r0:
                g.at(block, x, y, z)


def hang(g: tg.Grid, x: int, z: int, top: int, low: int, light: str, ring: int = 0, frame: str | None = None) -> None:
    """A chain from the ceiling at `top` down to `low`, a light there, optionally a ring of lights round it."""
    g.box(tg.CHAIN, x, low + 1, z, x, top, z)
    g.box(light, x, low - 1, z, x, low, z)
    if ring:
        for a in range(0, 360, 30):
            px, pz = x + round(ring * math.cos(math.radians(a))), z + round(ring * math.sin(math.radians(a)))
            g.at(light if a % 60 == 0 else (frame or light), px, low, pz)
            if a % 60 == 0:
                g.box(tg.CHAIN, px, low + 1, pz, px, low + 3, pz)


# ---- Tideforge: the sunken cathedral --------------------------------------------------------------------------------------------

def tideforge() -> tuple[tg.Grid, int, dict]:
    H, f = 100, 16
    PB, DP, GL, SL = "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:light_blue_stained_glass", "minecraft:sea_lantern"
    WATER, DIO, LAPIS, DIA, RAIL = "minecraft:water", "minecraft:polished_diorite", "minecraft:lapis_block", "minecraft:diamond_block", "minecraft:prismarine_wall"
    rnd = random.Random(7)
    g = tg.Grid({}, (W, H, D))
    g.box(PB, 0, 0, 0, W - 1, H - 1, D - 1)
    spring, rise, x0, x1 = f + 33, 34, 10, 139
    g.air(x0, 3, 2, x1, spring, D - 3)
    for x in range(x0, x1 + 1):                                   # the glass vault with an ocean on top of it
        top = spring + ellipse(x, x0 - 1, x1 + 1, rise)
        g.air(x, spring, 2, x, top, D - 3)
        g.box(GL, x, top + 1, 2, x, top + 1, D - 3)
        g.box(WATER, x, top + 2, 2, x, top + 5, D - 3)
        for z in range(2, D - 2):
            if z % 10 == 2 or x == MID:
                g.box(DP, x, top, z, x, top + 1, z)           # ribs every 10 and a spine
            if x % 9 == 4 and z % 9 == 4:
                g.at(SL, x, top + 5, z)                       # light falling through the sea above
    # the pool: a deep bed lit from below, coral growing up out of it
    g.box(DP, x0, 2, 2, x1, 2, D - 3)
    for x in range(x0 + 2, x1, 5):
        for z in range(4, D - 3, 5):
            g.at(SL, x, 2, z)
    g.box(WATER, x0, 3, 2, x1, f - 2, D - 3)
    corals = ["minecraft:tube_coral_block", "minecraft:brain_coral_block", "minecraft:bubble_coral_block", "minecraft:fire_coral_block", "minecraft:horn_coral_block"]
    for _ in range(140):
        x, z = rnd.randint(x0 + 1, x1 - 1), rnd.randint(4, D - 5)
        if 52 <= x <= 98 and 38 <= z <= 82 or 66 <= x <= 84:
            continue
        g.box(rnd.choice(corals), x, 3, z, x, 3 + rnd.randint(0, 5), z)
    # aquarium walls: water behind floor-to-spring glass, mullions every 10, coral and light on the back wall
    for gx, wx0, wx1, back in ((x0 - 1, 2, x0 - 2, 1), (x1 + 1, x1 + 2, W - 3, W - 2)):
        g.box(WATER, wx0, 3, 2, wx1, spring - 2, D - 3)
        g.box("minecraft:glass", gx, 3, 2, gx, spring - 2, D - 3)
        g.box(DP, min(wx0, gx), spring - 1, 2, max(wx1, gx), spring - 1, D - 3)
        for z in range(2, D - 2):
            if z % 10 == 2:
                g.box(DP, gx, 3, z, gx, spring - 1, z)
                g.at(SL, gx, spring - 2, z)
            if z % 6 == 0:
                for y in range(f, spring - 4, 7):
                    g.at(SL, back, y, z)
        for _ in range(60):
            z = rnd.randint(3, D - 4)
            x = rnd.randint(min(wx0, wx1), max(wx0, wx1))
            g.box(rnd.choice(corals), x, 3, z, x, 3 + rnd.randint(2, 12), z)
    # galleries: decks cantilevered off the mullions at f + 12
    for dx0, dx1, rail in ((x0, x0 + 7, x0 + 7), (x1 - 7, x1, x1 - 7)):
        g.box(DP, dx0, f + 11, 2, dx1, f + 11, D - 3)
        g.box(RAIL, rail, f + 12, 2, rail, f + 12, D - 3)
        for z in range(7, D - 3, 10):
            g.at(SL, rail, f + 11, z)
        for z in range(2, D - 2, 10):
            for k in range(1, 4):
                g.box(DP, min(dx0, dx1), f + 11 - k, z, max(dx0, dx1) - (4 - k) * 2, f + 11 - k, z) if dx0 == x0 else g.box(DP, dx0 + (4 - k) * 2, f + 11 - k, z, dx1, f + 11 - k, z)
    # the arena: an island in the pool, ringed in lapis, a diamond medallion
    g.box(PB, 57, 3, 42, 93, f - 1, 78)
    g.box(DIO, 57, f - 1, 42, 93, f - 1, 78)
    g.box(LAPIS, 57, f - 1, 42, 93, f - 1, 42)
    g.box(LAPIS, 57, f - 1, 78, 93, f - 1, 78)
    g.box(LAPIS, 57, f - 1, 42, 57, f - 1, 78)
    g.box(LAPIS, 93, f - 1, 42, 93, f - 1, 78)
    disc(g, LAPIS, MID, f - 1, 60, 12, 11)
    disc(g, DIA, MID, f - 1, 60, 4)
    g.at(SL, MID, f - 1, 60)
    for px, pz in ((57, 42), (92, 42), (57, 77), (92, 77)):      # pylons at the island's corners
        g.box(DP, px, f, pz, px + 1, f + 6, pz + 1)
        g.box(SL, px, f + 7, pz, px + 1, f + 8, pz + 1)
        g.box(DIA, px, f + 9, pz, px + 1, f + 9, pz + 1)
    # bridges: south to the island, island to the gate; arches under them down to the bed
    for z0, z1 in ((79, D - 3), (2, 41)):
        g.box(DP, 71, f - 1, z0, 79, f - 1, z1)
        g.box(DIO, 73, f - 1, z0, 77, f - 1, z1)
        g.box(LAPIS, 75, f - 1, z0, 75, f - 1, z1)
        g.box(RAIL, 71, f, z0, 71, f, z1)
        g.box(RAIL, 79, f, z0, 79, f, z1)
        for z in range(z0 + 3, z1, 8):
            for x in (71, 79):
                g.box(PB, x, 3, z, x, f - 2, z + 1)
                g.box(DP, x, f, z, x, f + 3, z)
                g.at(SL, x, f + 4, z)
                g.at(DIA, x, f + 5, z)
    # the gate wall: a gate, and over it a rose window backlit by the sea
    g.air(71, f, 0, 79, f + 10, 1)
    g.box(tg.BARS, 71, f, 1, 79, f + 10, 1)
    for x in range(MID - 16, MID + 17):
        for y in range(f + 24, f + 58):
            d = math.hypot(x - MID, y - (f + 40))
            a = math.degrees(math.atan2(y - (f + 40), x - MID)) % 30
            if d <= 15:
                g.at(LAPIS if 14 <= d or d <= 3 else DP if a < 2.5 or 5.5 < d < 6.5 else GL, x, y, 1)
                g.at(SL, x, y, 0)
    # waterfalls pouring from spouts at the spring line into the pool
    for x in (24, 124):
        for z in (20, 60, 100):
            g.box(DP, x - 2, spring - 1, z - 2, x + 2, spring, z + 2)
            g.box(WATER, x - 1, f - 1, z - 1, x + 1, spring - 2, z + 1)
    # chandeliers: a great ring over the arena, smaller ones over the bridges
    hang(g, MID, 60, spring + rise - 1, f + 24, SL, ring=6, frame=DP)
    for z in (20, 100):
        hang(g, MID, z, spring + rise - 1, f + 20, SL, ring=3, frame=DP)
    shots = {
        "arrival": (MID + 0.5, f + 1.62, D - 5.5, 180, -4, 100),
        "gallery": (x0 + 3.5, f + 12 + 1.62, D - 8.5, 215, 12, 100),
        "island": (MID + 0.5, f + 1.62, 74.5, 180, -32, 105),
    }
    return g, f, shots


# ---- Duskvale: the shattered moonlit cathedral ----------------------------------------------------------------------------------

def duskvale() -> tuple[tg.Grid, int, dict]:
    H, f = 116, 16
    DB, PD, DT, CT = "minecraft:deepslate_bricks", "minecraft:polished_deepslate", "minecraft:deepslate_tiles", "minecraft:calcite"
    AM, MOON, GLW = "minecraft:amethyst_block", "minecraft:pearlescent_froglight", "minecraft:light_gray_stained_glass"
    CRACK, CLUSTER = "minecraft:cracked_deepslate_tiles", "minecraft:amethyst_cluster[facing=up]"
    rnd = random.Random(11)
    g = tg.Grid({}, (W, H, D))
    g.box(DB, 0, 0, 0, W - 1, H - 1, D - 1)
    x0, x1, spring = 6, 143, f + 26
    span = x1 - x0
    R = span * 0.82
    tops = {}
    for x in range(x0, x1 + 1):                                   # a pointed (gothic) vault, two arcs meeting at the ridge
        c = x0 - 1 + R if x <= MID else x1 + 1 - R
        h = math.sqrt(max(0.0, R * R - (x - c) ** 2)) * 0.62
        tops[x] = min(H - 6, spring + int(h))
        g.air(x, f, 2, x, tops[x], D - 3)
    # the floor: tiles, cracked in drifts
    g.box(DT, x0, f - 1, 2, x1, f - 1, D - 3)
    for _ in range(400):
        g.at(CRACK, rnd.randint(x0, x1), f - 1, rnd.randint(2, D - 3))
    for _ in range(26):                                           # cracks running through the floor, lit from underneath
        x, z = rnd.randint(x0 + 4, x1 - 4), rnd.randint(6, D - 6)
        for _ in range(rnd.randint(8, 22)):
            g.at(MOON, x, f - 1, z)
            x, z = x + rnd.choice((-1, 0, 1, 1)), z + rnd.choice((-1, 0, 1))
            if not (x0 < x < x1 and 2 < z < D - 3):
                break
    for cx in (42, 108):
        for z in range(8, D - 4, 12):
            g.box(PD, cx, f, z, cx, f + 3, z)
            g.at(CT, cx, f + 4, z)
            g.box(MOON, cx, f + 5, z, cx, f + 6, z)
            g.at("minecraft:end_rod", cx, f + 7, z)
    # the roof is broken open over the nave: holes through the vault, the night beyond
    holes = [(MID - 18, 46, 20, 13), (MID + 22, 30, 14, 10), (MID + 4, 84, 16, 12), (MID - 34, 92, 10, 9), (MID + 36, 98, 9, 8)]
    for x in range(x0, x1 + 1):
        for z in range(2, D - 2):
            for hx, hz, rx, rz in holes:
                e = ((x - hx) / rx) ** 2 + ((z - hz) / rz) ** 2 + 0.25 * math.sin(x * 1.7 + z * 0.9)
                if e < 1:
                    g.air(x, tops[x], z, x, H - 1, z)
    # pieces of that roof hanging in the air where they broke off: Disruption
    for hx, hz, rx, rz in holes:
        for _ in range(int(rx * rz / 9)):
            x, z = hx + rnd.randint(-rx, rx), hz + rnd.randint(-rz, rz)
            if not (x0 < x < x1 and 2 < z < D - 3):
                continue
            y = tops[x] - rnd.randint(6, 26)
            sx, sy, sz = rnd.randint(1, 3), rnd.randint(1, 2), rnd.randint(1, 4)
            g.box(DB, x, y, z, x + sx, y + sy, z + sz)
            g.box(CT, x, y + sy, z, x + sx, y + sy, z)
    # clustered piers down both aisles, every 12: some broken off, their tops floating above them
    for px in (30, 119):
        for pz in range(10, D - 6, 12):
            top = tops[px] - 1
            broken = rnd.random() < 0.4
            stop = f + rnd.randint(14, 30) if broken else top
            for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                g.box(PD, px + dx, f, pz + dz, px + dx, stop, pz + dz)
            g.box(CT, px - 1, f, pz - 1, px + 1, f + 1, pz + 1)
            if broken:
                for k in range(3):
                    y = stop + 4 + k * 5
                    if y < top - 2:
                        g.box(PD, px - (k % 2), y, pz, px, y + 2, pz + (k % 2))
            else:
                g.box(CT, px - 1, top - 1, pz - 1, px + 1, top, pz + 1)
                hang(g, px + (8 if px < MID else -8), pz, tops[px + (8 if px < MID else -8)] - 1, f + 12, MOON)
    # lancet windows in both side walls, moonlight behind them
    for wx, back in ((x0 - 1, x0 - 2), (x1 + 1, x1 + 2)):
        for z in range(16, D - 10, 12):
            for y in range(f + 4, spring + 2):
                w = 2 if y < spring - 2 else 1 if y < spring else 0
                g.box(GLW, wx, y, z - w, wx, y, z + w)
                g.box(MOON, back, y, z - w, back, y, z + w)
    # the moon itself through the shattered rose window over the gate
    mx, my = MID, f + 58
    for x in range(MID - 26, MID + 27):
        for y in range(f + 30, min(H - 2, f + 86)):
            d = math.hypot(x - mx, y - my)
            if d <= 24:
                g.air(x, y, 0, x, y, 2)
                if d <= 17:
                    g.at(MOON, x, y, 0)
                ang = math.degrees(math.atan2(y - my, x - mx)) % 45
                if (ang < 2.0 and d < 24 - (x * 7 + y * 3) % 9) or 22 <= d <= 24 and (x + y) % 5:
                    g.at(CT, x, y, 2)                         # what is left of the tracery
    g.air(71, f, 0, 79, f + 12, 1)
    g.box(tg.BARS, 71, f, 1, 79, f + 12, 1)
    # a chasm torn round the arena; the arena hangs over it, the bridges cross it
    for x in range(36, 115):
        for z in range(26, 100):
            e = ((x - MID) / 38) ** 2 + ((z - CZ) / 36) ** 2 + 0.06 * math.sin(x * 0.8) * math.cos(z * 0.7)
            if e < 1:
                g.air(x, 0, z, x, f - 1, z)
    for x in range(54, 97):
        for z in range(42, 83):
            e = ((x - MID) / 21) ** 2 + ((z - CZ) / 20) ** 2
            if e < 1:
                depth = int(4 + 22 * (1 - e) ** 1.5)               # an inverted spire under the island
                g.box(DB, x, f - 1 - depth, z, x, f - 1, z)
                g.at(PD, x, f - 1, z)
    disc(g, AM, MID, f - 1, CZ, 13, 12)
    for a in range(0, 360, 45):
        for r in range(4, 13):
            g.at(CT, MID + round(r * math.cos(math.radians(a))), f - 1, CZ + round(r * math.sin(math.radians(a))))
    disc(g, AM, MID, f - 1, CZ, 3)
    for a in range(0, 360, 40):
        g.at(CLUSTER, MID + round(17 * math.cos(math.radians(a))), f, CZ + round(17 * math.sin(math.radians(a))))
    for z0, z1 in ((80, D - 3), (2, 44)):
        g.box(PD, 72, f - 1, z0, 78, f - 1, z1)
        g.box(DB, 72, f - 4, z0, 78, f - 2, z1)
        g.box(CT, 72, f, z0, 72, f, z1)
        g.box(CT, 78, f, z0, 78, f, z1)
        for z in range(z0, z1 + 1, 3):
            if rnd.random() < 0.5:
                g.air(72 if rnd.random() < 0.5 else 78, f - 4, z, 72 if rnd.random() < 0.5 else 78, f, z)
    # the great dark chandelier: a ring of moonstones over the arena
    hang(g, MID, CZ, tops[MID] - 1, f + 30, MOON, ring=7, frame=AM)
    shots = {
        "arrival": (MID + 0.5, f + 1.62, D - 5.5, 180, -10, 100),
        "aisle": (24.5, f + 1.62, D - 10.5, 205, -14, 100),
        "island": (MID + 0.5, f + 1.62, 76.5, 180, -38, 105),
    }
    return g, f, shots


# ---- Rootvale: inside a colossal hollow tree ------------------------------------------------------------------------------------

def rootvale() -> tuple[tg.Grid, int, dict]:
    H, f = 124, 12
    BARK, BARK2, INNER, INNER2 = "minecraft:spruce_wood", "minecraft:dark_oak_wood", "minecraft:stripped_spruce_wood", "minecraft:stripped_oak_wood"
    ROOT, MOSS, LEAF, LEAF2 = "minecraft:dark_oak_wood", "minecraft:moss_block", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"
    SHROOM, OCHRE, PATH, WATER = "minecraft:shroomlight", "minecraft:ochre_froglight", "minecraft:dirt_path", "minecraft:water"
    CAP, STEM = "minecraft:brown_mushroom_block", "minecraft:mushroom_stem"
    rnd = random.Random(5)
    g = tg.Grid({}, (W, H, D))
    g.box(BARK, 0, 0, 0, W - 1, H - 1, D - 1)
    canopy = f + 92

    def radius(y: int) -> float:
        return 60 - max(0, y - f - 20) * 0.22                     # the trunk narrows as it rises

    xs, zs = np.meshgrid(np.arange(W), np.arange(D), indexing="ij")
    dist = np.hypot(xs - MID, zs - CZ)
    ang = (np.degrees(np.arctan2(zs - CZ, xs - MID)) + 360) % 360
    inner_id, inner2_id, air_id = g.id(INNER), g.id(INNER2), 0
    for y in range(f - 1, canopy):
        r = radius(y)
        hollow = dist < r
        face = (dist >= r) & (dist < r + 2)
        g.grid[:, y, :][hollow] = air_id
        streak = (ang // 3).astype(int) % 3 == 0                 # the grain of the heartwood runs up the inside
        g.grid[:, y, :][face & streak] = inner2_id
        g.grid[:, y, :][face & ~streak] = inner_id
    # the canopy: light coming down through leaves, the sky past them
    r = radius(canopy)
    for y in range(canopy, canopy + 5):
        for x in range(W):
            for z in range(D):
                d = math.hypot(x - MID, z - CZ)
                if d < r + 1:
                    v = math.sin(x * 0.5) * math.cos(z * 0.45) + (y - canopy) * 0.3
                    g.grid[x, y, z] = 0 if v > 0.9 else g.id(LEAF2 if (x * 7 + z * 3 + y) % 5 == 0 else LEAF) if y < canopy + 3 else 0
    for _ in range(90):
        a, rr = rnd.uniform(0, 2 * math.pi), rnd.uniform(4, r - 2)
        g.at(SHROOM, round(MID + rr * math.cos(a)), canopy - 1, round(CZ + rr * math.sin(a)))
    g.air(0, canopy + 5, 0, W - 1, H - 1, D - 1)
    for _ in range(260):                                          # glowing knots in the trunk wall
        y = rnd.randint(f + 2, canopy - 2)
        a = rnd.uniform(0, 2 * math.pi)
        rr = radius(y) + 0.5
        g.box(SHROOM if rnd.random() < 0.7 else OCHRE, round(MID + rr * math.cos(a)), y, round(CZ + rr * math.sin(a)),
              round(MID + rr * math.cos(a)), y + rnd.randint(0, 1), round(CZ + rr * math.sin(a)))
    # the floor: moss, a worn path in, roots running flush across it
    floor = dist < radius(f)
    g.grid[:, f - 1, :][floor] = g.id(MOSS)
    g.grid[:, f - 2, :][floor] = g.id("minecraft:rooted_dirt")
    for k in range(8):
        a = math.radians(k * 45 + 22.5)
        for t in np.linspace(radius(f), 24, 80):
            x, z = round(MID + t * math.cos(a) + 2 * math.sin(t * 0.3)), round(CZ + t * math.sin(a))
            g.box(ROOT, x - 1, f - 1, z - 1, x + 1, f - 1, z + 1)
    g.box(PATH, MID - 3, f - 1, CZ + 24, MID + 3, f - 1, D - 1)
    for _ in range(120):                                          # fireflies: verdant light sunk in the moss
        a, rr = rnd.uniform(0, 2 * math.pi), rnd.uniform(26, radius(f) - 2)
        g.at("minecraft:verdant_froglight", round(MID + rr * math.cos(a)), f - 1, round(CZ + rr * math.sin(a)))
    for z in range(CZ + 28, D - 2, 6):                            # lamp posts up the path
        for x in (MID - 4, MID + 4):
            g.box("minecraft:oak_fence", x, f, z, x, f + 2, z)
            g.at(SHROOM, x, f + 3, z)
    # the arena: a stump cut level, its growth rings for a medallion, a moat round it
    for x in range(MID - 26, MID + 27):
        for z in range(CZ - 26, CZ + 27):
            d = math.hypot(x - MID, z - CZ)
            if d <= 19:
                g.at("minecraft:stripped_oak_log[axis=y]" if int(d) % 4 < 2 else "minecraft:stripped_spruce_log[axis=y]", x, f - 1, z)
            elif d <= 20.5:
                g.at("minecraft:oak_log[axis=y]", x, f - 1, z)
                if abs(x - MID) > 4:
                    g.at("minecraft:oak_log[axis=y]", x, f, z)       # the bark lip, open where the paths cross it
            elif d <= 23.5:
                g.box(WATER, x, f - 2, z, x, f - 1, z)
                g.at(OCHRE if int(math.degrees(math.atan2(z - CZ, x - MID))) % 12 < 3 else "minecraft:clay", x, f - 3, z)
    for z0, z1 in ((CZ + 20, CZ + 24), (CZ - 24, CZ - 20)):
        g.box("minecraft:oak_planks", MID - 3, f - 1, z0, MID + 3, f - 1, z1)
        g.box("minecraft:oak_fence", MID - 3, f, z0 + 1, MID - 3, f, z1 - 1)
        g.box("minecraft:oak_fence", MID + 3, f, z0 + 1, MID + 3, f, z1 - 1)
    # eight great roots arching from the walls up to the heartwood knot that hangs over the arena
    heart = (MID, f + 54, CZ)
    for k in range(8):
        a = math.radians(k * 45)
        r0 = radius(f) - 1
        p0 = np.array([MID + r0 * math.cos(a), f, CZ + r0 * math.sin(a)])
        p1 = np.array([MID + 34 * math.cos(a), f + 62, CZ + 34 * math.sin(a)])
        p2 = np.array(heart, dtype=float)
        for t in np.linspace(0, 1, 260):
            p = (1 - t) ** 2 * p0 + 2 * (1 - t) * t * p1 + t * t * p2
            th = 3 if t < 0.6 else 2
            x, y, z = (int(round(v)) for v in p)
            g.box(ROOT, x - th + 1, y - th + 1, z - th + 1, x + th - 1, y + th - 1, z + th - 1)
            if 0.25 < t < 0.85 and rnd.random() < 0.08:
                g.box(LEAF2, x, y - th - rnd.randint(2, 6), z, x, y - th, z)    # azalea trailing off the roots
            if 0.1 < t < 0.95 and rnd.random() < 0.14:
                g.at(SHROOM, x, y - th, z)
    hx, hy, hz = heart
    for x in range(hx - 6, hx + 7):                                # the knot: wood, and glowing heart-light inside it
        for y in range(hy - 7, hy + 6):
            for z in range(hz - 6, hz + 7):
                d = math.hypot(x - hx, (y - hy) * 0.8, z - hz)
                if d <= 6.3:
                    g.at(ROOT if d > 4.3 or (x + y + z) % 3 else OCHRE, x, y, z)
    for a in range(0, 360, 30):                                    # light hanging under the knot
        x, z = round(hx + 4 * math.cos(math.radians(a))), round(hz + 4 * math.sin(math.radians(a)))
        hang(g, x, z, hy - 6, hy - 14 - (a // 30) % 3 * 3, SHROOM)
    # spectator ledges: shelf fungus growing out of the trunk wall, a cap on a stem underside
    for deg, y in ((160, f + 12), (200, f + 12), (20, f + 12), (340, f + 12), (180, f + 26), (0, f + 26), (120, f + 40), (60, f + 40)):
        a = math.radians(deg)
        rr = radius(y)
        for x in range(W):
            for z in range(D):
                d = math.hypot(x - MID, z - CZ)
                da = abs((math.degrees(math.atan2(z - CZ, x - MID)) - deg + 180) % 360 - 180)
                reach = 9 * math.cos(math.radians(min(90, da * 3.2)))
                if rr - reach <= d < rr and da < 28:
                    g.at(CAP, x, y, z)
                    if d < rr - reach + 1.2:
                        g.at("minecraft:oak_fence", x, y + 1, z)
                    elif d < rr - 1:
                        g.at(STEM, x, y - 1, z)
    shots = {
        "arrival": (MID + 0.5, f + 1.62, D - 8.5, 180, -12, 100),
        "ledge": (MID - 49.5, f + 13 + 1.62, CZ + 16.5, 250, 8, 100),
        "up": (MID + 0.5, f + 1.62, CZ + 16.5, 180, -50, 110),
    }
    return g, f, shots


TOWERS = {"tideforge": tideforge, "duskvale": duskvale, "rootvale": rootvale}


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "concepts_out")
    out.mkdir(parents=True, exist_ok=True)
    names = sys.argv[2:] or list(TOWERS)
    thumbs = []
    for name in names:
        g, _, shots = TOWERS[name]()
        scene = V.Scene(g.grid, g.palette, sky=True)
        if scene.missing:
            print(name, "no texture for:", sorted(scene.missing))
        for shot, (x, y, z, yaw, pitch, fov) in shots.items():
            im = scene.render((x, y, z), yaw, pitch, 960, 540, fov, exposure=1.15)
            im.save(out / f"{name}_{shot}.png")
            thumbs.append((f"{name} / {shot}", im))
            print("wrote", out / f"{name}_{shot}.png")
    cols = 3
    rows = math.ceil(len(thumbs) / cols)
    sheet = Image.new("RGB", (cols * 640, rows * 380), (12, 10, 16))
    for i, (label, im) in enumerate(thumbs):
        x, y = (i % cols) * 640, (i // cols) * 380
        sheet.paste(im.resize((640, 360)), (x, y + 20))
        ImageDraw.Draw(sheet).text((x + 6, y + 4), label, fill=(230, 220, 200))
    sheet.save(out / "concepts_sheet.png")
    print("wrote", out / "concepts_sheet.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
