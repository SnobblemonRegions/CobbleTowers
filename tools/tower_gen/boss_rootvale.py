"""Rootvale's boss halls (owner, 15 Nov 2026: floors 5 and 10 fully rebuilt, unlike each other and every other floor).

FLOOR 5, THE MUSHROOM HOLLOW. A cavern under the roots of the world tree: an earthen dome threaded with roots, hanging roots and spore
blossoms, and a forest of giant mushrooms, red domes and broad brown shelves, lit warm from under their caps. The arena is a fairy ring in the
moss; the spectators stand on the broad cap of a giant brown mushroom to the west.

FLOOR 10, THE CROWN. After nine floors inside the tree the party climbs out on top of it, into the sun: a nest of woven branches high in
the crown with the real sky over it (glass), a sea of treetops all round, blossom in the leaves, a great branch arching overhead with a golden
fruit hanging from it over the arena. The party walks out along a hollow branch; spectators watch from a platform on a side branch.

Leaves are placed persistent: leaves far from a log decay on their own otherwise.
"""
from __future__ import annotations

import math
import random

import numpy as np

import boss_kit as K
import grand_hall as gh
import tower_gen as tg
from boss_kit import CZ, MID, X0, X1, Z0, Z1
from tower_gen import CHAIN, Grid

DIRT, COARSE, ROOTED = "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt"
ROOT = "minecraft:dark_oak_wood"
HANGING = "minecraft:hanging_roots"
SPORE = "minecraft:spore_blossom"
STEM, RED, BROWN = "minecraft:mushroom_stem", "minecraft:red_mushroom_block", "minecraft:brown_mushroom_block"
GLOW, GOLD, FIREFLY = "minecraft:shroomlight", "minecraft:ochre_froglight", "minecraft:verdant_froglight"
PODZOL, MOSS_CARPET = "minecraft:podzol", "minecraft:moss_carpet"
LEAVES = ("minecraft:azalea_leaves[persistent=true]", "minecraft:flowering_azalea_leaves[persistent=true]",
          "minecraft:oak_leaves[persistent=true]", "minecraft:cherry_leaves[persistent=true]")
LOG_Y, LOG_X, LOG_Z = "minecraft:oak_log[axis=y]", "minecraft:oak_log[axis=x]", "minecraft:oak_log[axis=z]"
DLOG_X, DLOG_Z = "minecraft:dark_oak_log[axis=x]", "minecraft:dark_oak_log[axis=z]"
PLANKS = "minecraft:spruce_planks"

# ---- floor 5: the mushroom hollow -------------------------------------------------------------------------------------------------------
CAVE = (64, 46)
ROOF = (30, 20)                    # the dome's wall height and rise above the feet
CAP_UP = 12                        # the spectators' cap


def mushroom(g: Grid, x: int, z: int, f: int, h: int, cap: int, red: bool) -> None:
    """A giant mushroom: a stem three wide and a cap, lit from under it. Red caps are domes (a shell two thick, whole in three dimensions,
    the stem running up into its crown, glowing gills set in its inner face); brown caps are broad shelves with their rims turned up."""
    top = f + h
    rise = cap * 0.75
    g.box(STEM, x - 1, f, z - 1, x + 1, top + (int(rise) if red else 0), z + 1)
    if red:
        for dx in range(-cap - 1, cap + 2):
            for dz in range(-cap - 1, cap + 2):
                for dy in range(0, int(rise) + 2):
                    e = math.sqrt((dx * dx + dz * dz) / (cap + 0.5) ** 2 + (dy / (rise + 0.5)) ** 2)
                    if 0.78 <= e <= 1.0:
                        inner = e < 0.86
                        g.at(GLOW if inner and (dx * 3 + dz * 5 + dy) % 7 == 0 else RED, x + dx, top + dy, z + dz)
        return
    for dx in range(-cap, cap + 1):
        for dz in range(-cap, cap + 1):
            d = math.hypot(dx, dz)
            if d > cap + 0.5:
                continue
            g.box(BROWN, x + dx, top, z + dz, x + dx, top + (1 if d > cap - 1.5 else 0), z + dz)
            if d < cap - 1 and (dx * 5 + dz * 3) % 6 == 0:
                g.at(GLOW, x + dx, top - 1, z + dz)


def hollow(g: Grid, floor: int, f: int) -> None:
    rnd = random.Random(5050)
    ground = f - 1
    e = K.ell(g.grid.shape, *CAVE)
    xs, zs, r, ang = K.plan(g.grid.shape)
    cave = e < 1
    roof = f + ROOF[0] + (ROOF[1] * np.sqrt(np.clip(1 - e ** 2, 0, 1))).astype(int)
    for x, z in np.argwhere(cave):
        x, z = int(x), int(z)
        t = int(roof[x, z])
        g.air(x, f, z, x, t, z)
        n = (x * 31 + z * 17) % 13
        g.at(ROOTED if n < 3 else COARSE if n < 5 else DIRT, x, t + 1, z)
        g.at(PODZOL if n == 7 else "floor", x, ground, z)
        if n == 2 and r[x, z] > 24:
            g.at(MOSS_CARPET, x, f, z)
        if n in (4, 9) and rnd.random() < 0.25:
            g.box(HANGING, x, t, z, x, t, z)
        elif n == 11 and rnd.random() < 0.08:
            g.at(SPORE, x, t, z)
        elif n == 6 and rnd.random() < 0.05:
            g.at(FIREFLY, x, t + 1, z)
    shell = (e >= 1) & (e < 1.06)                                             # the cavern wall: earth with roots running up it
    for x, z in np.argwhere(shell):
        x, z = int(x), int(z)
        streak = int(ang[x, z] // 4) % 5 == 0
        g.box(ROOT if streak else DIRT, x, f, z, x, f + ROOF[0], z)
    for k in range(7):                                                        # roots across the roof
        a = math.radians(k * 26 + 10)
        for t in np.linspace(-1, 1, 200):
            x = round(MID + t * CAVE[0] * 0.95 * math.cos(a))
            z = round(CZ + t * CAVE[1] * 0.95 * math.sin(a))
            y = int(roof[min(max(x, 0), g.grid.shape[0] - 1), min(max(z, 0), g.grid.shape[2] - 1)]) - 1
            g.box(ROOT, x - 1, y - 1, z - 1, x + 1, y + 1, z + 1)
    # the giant mushrooms round the arena
    gal = (MID - 46, CZ)
    spots = K.scatter(rnd, 13, lambda x, z: 0.42 < e[x, z] < 0.86 and abs(x - MID) > 10
                      and math.hypot(x - gal[0], z - gal[1]) > 18)
    for i, (x, z) in enumerate(spots):
        mushroom(g, x, z, f, rnd.randint(14, 26), rnd.randint(7, 11), red=i % 3 != 2)
    # the spectators' mushroom, west: a broad cap, railed
    mushroom(g, gal[0], gal[1], f, CAP_UP, 10, red=False)
    cap = (np.hypot(xs - gal[0], zs - gal[1]) <= 10.5)
    K.rail_edge(g, f + CAP_UP + 1, cap & (np.hypot(xs - gal[0], zs - gal[1]) <= 9.5), ~cap | (np.hypot(xs - gal[0], zs - gal[1]) > 9.5))
    K.fill_dents(g, X0, f + 16, Z0, X1, f + ROOF[0] + ROOF[1] + 1, Z1, RED)           # dents in the caps and the roof, above everyone
    # the fairy ring and the path
    for x, z in np.argwhere((r > 16.5) & (r < 18.5)):
        x, z = int(x), int(z)
        if int(ang[x, z]) % 20 < 3 and abs(x - MID) > 4:
            g.at(RED if int(ang[x, z]) % 40 < 20 else BROWN, x, f, z)
    for x, z in np.argwhere(r < 16):
        if int(r[int(x), int(z)]) % 5 == 0:
            g.at("inlay", int(x), ground, int(z))
    g.at("accent", MID, ground, CZ)
    g.box("inlay", MID - 2, ground, Z0, MID + 2, ground, CZ - 19)
    g.box("inlay", MID - 2, ground, CZ + 19, MID + 2, ground, Z1)
    for z in list(range(Z0 + 6, CZ - 20, 8)) + list(range(CZ + 24, Z1 - 2, 8)):
        for x in (MID - 5, MID + 5):
            g.box("minecraft:oak_fence", x, f, z, x, f + 2, z)
            g.at(GLOW, x, f + 3, z)
    K.ways(g, f, Z0 + 2, Z1 - 2, rib_every=0)


def hollow_checks(g: Grid) -> list[str]:
    f = BOSS5.base_feet
    xs, zs, _, _ = K.plan(g.grid.shape)
    cap = np.hypot(xs - (MID - 46), zs - CZ) <= 10.5
    return K.drops(g, f, [(f + CAP_UP + 1, cap)])


BOSS5 = gh.Boss(hall=hollow, top=ROOF[0] + ROOF[1] + 4, base_feet=6,
                entry_z=CZ + 12, presentation_z=CZ - 12, spectator=(MID - 46, CAP_UP + 1, CZ),
                checks=hollow_checks)

# ---- floor 10: the crown ----------------------------------------------------------------------------------------------------------------
SKY_UP = 62
LEAF_DOWN = 14
NEST = 22
BRANCH_R = 4


def crown(g: Grid, floor: int, f: int) -> None:
    rnd = random.Random(1010)
    ground = f - 1
    xs, zs, r, ang = K.plan(g.grid.shape)
    e = K.ell(g.grid.shape, 64, 46)
    box = (xs >= X0) & (xs <= X1) & (zs >= Z0) & (zs <= Z1)
    # open from the treetops up to a glass roof with the sky over it
    g.air(X0, f - LEAF_DOWN, Z0, X1, g.grid.shape[1] - 1, Z1)
    g.box(K.CLEAR, X0, f + SKY_UP, Z0, X1, f + SKY_UP, Z1)
    # the treetops: a floor of leaves, rising into crowns of other branches round the edge
    noise = np.random.default_rng(1011).random(r.shape)
    height = ground - 1 + np.clip((e - 0.55) * 70, 0, 44) + (np.sin(xs * 0.21) * np.cos(zs * 0.17) * 4)
    for x, z in np.argwhere(box):
        x, z = int(x), int(z)
        h = int(height[x, z])
        for y in range(f - LEAF_DOWN, h + 1):
            pick = (x * 7 + y * 3 + z * 5) % 23
            g.at(LEAVES[3] if pick == 0 else LEAVES[1] if pick < 5 else LEAVES[2] if pick < 9 else LEAVES[0], x, y, z)
        if noise[x, z] < 0.004 and h < f + 30:
            g.at(GOLD, x, h, z)                                               # sun-catching fruit in the leaves
    # the branches: the path in and out along hollow branches, a nest of woven logs for the arena
    for za, zb in ((Z0, CZ - NEST + 1), (CZ + NEST - 1, Z1)):
        for z in range(za, zb + 1):
            for x in range(MID - BRANCH_R - 1, MID + BRANCH_R + 2):
                for y in range(ground - BRANCH_R * 2, ground + 1):
                    if math.hypot(x - MID, y - (ground - BRANCH_R)) <= BRANCH_R + 0.5:
                        g.at(LOG_Z, x, y, z)
            g.box(PLANKS, MID - 3, ground, z, MID + 3, ground, z)
            g.air(MID - 3, f, z, MID + 3, f + 5, z)
    for x, z in np.argwhere(r <= NEST + 2):
        x, z = int(x), int(z)
        d = r[x, z]
        ring = int(d) // 2
        g.box(LOG_X if ring % 2 else LOG_Z, x, ground - 3 + (1 if d > NEST - 1 else 0), z, x, ground, z)
        g.air(x, f, z, x, f + 6, z)
        if d > NEST:
            g.at(DLOG_X if ring % 2 else DLOG_Z, x, f, z)                     # the nest's rim, a woven lip
    for x, z in np.argwhere(r < 9):
        g.at("inlay" if int(r[int(x), int(z)]) % 3 == 0 else "floor", int(x), ground, int(z))
    g.at("accent", MID, ground, CZ)
    walk = (r <= NEST) | ((np.abs(xs - MID) <= 3) & box)
    K.rail_edge(g, f, walk, box & ~walk & ~(r <= NEST + 2))
    for x, z in np.argwhere((r > NEST - 1) & (r <= NEST) & (np.abs(xs - MID) > 3)):
        g.at("rail", int(x), f, int(z))
    # the great branch arching over the arena, the golden fruit hanging from it
    for t in np.linspace(0, 1, 260):
        x = MID - 60 + 120 * t
        y = f + 8 + 34 * math.sin(math.pi * t)
        z = CZ - 10 + 6 * math.sin(2 * math.pi * t)
        rad = 3.2 - 1.2 * abs(t - 0.5)
        for dx in range(-4, 5):
            for dy in range(-4, 5):
                for dz in range(-4, 5):
                    if math.hypot(dx, dy, dz) <= rad:
                        g.at(LOG_X, round(x) + dx, round(y) + dy, round(z) + dz)
        if rnd.random() < 0.25:                                              # a tuft of leaves sitting on the branch (loose leaves read as debris)
            cx, cy, cz = round(x), round(y + rad), round(z)
            for dx in range(-3, 4):
                for dy in range(0, 4):
                    for dz in range(-3, 4):
                        if math.hypot(dx, dy * 1.2, dz) <= 2.8 and g.grid[cx + dx, cy + dy, cz + dz] == 0:
                            g.at(LEAVES[(dx * 3 + dy * 5 + dz * 7) % 4], cx + dx, cy + dy, cz + dz)
    fy = f + 22
    g.box(CHAIN, MID, fy + 6, CZ - 10, MID, f + 41, CZ - 10)
    for x in range(MID - 6, MID + 7):
        for y in range(fy - 6, fy + 7):
            for z in range(CZ - 16, CZ - 3):
                d = math.hypot(x - MID, (y - fy) * 0.9, z - (CZ - 10))
                if d <= 5.8:
                    g.at(GOLD if (x + y + z) % 4 else GLOW, x, y, z)
    # the spectators' platform on a side branch, west, railed all round
    px0, px1, pz0, pz1 = MID - 56, MID - 44, CZ + 2, CZ + 14
    g.box(PLANKS, px0, f + 6, pz0, px1, f + 6, pz1)
    g.air(px0, f + 7, pz0, px1, f + 12, pz1)
    plat = (xs >= px0) & (xs <= px1) & (zs >= pz0) & (zs <= pz1)
    K.rail_edge(g, f + 7, plat, ~plat)
    for x in range(px0, px1 + 1):
        g.box(LOG_X, x, f + 3, pz0 + 5, x, f + 5, pz0 + 7)
    g.air(px1 + 1, f + 7, pz0, MID - NEST - 3, f + 16, pz1)                  # a gap in the leaves to watch through
    for x, z in np.argwhere(r <= NEST + 2):                                     # leaves right up under the nest: no gap below its logs
        x, z = int(x), int(z)
        top_leaf = ground
        while top_leaf > f - LEAF_DOWN and g.grid[x, top_leaf, z] != 0:
            top_leaf -= 1                                                       # down past the nest's logs
        while top_leaf > f - LEAF_DOWN and g.grid[x, top_leaf, z] == 0:
            g.at(LEAVES[0], x, top_leaf, z)
            top_leaf -= 1
    K.fill_dents(g, X0, f - LEAF_DOWN, Z0, X1, ground - 2, Z1, LEAVES[0])
    K.fill_dents(g, X0, f + 17, Z0, X1, f + SKY_UP - 1, Z1, LEAVES[0])


def crown_checks(g: Grid) -> list[str]:
    f = BOSS10.base_feet
    xs, zs, r, _ = K.plan(g.grid.shape)
    plat = (xs >= MID - 56) & (xs <= MID - 44) & (zs >= CZ + 2) & (zs <= CZ + 14)
    return K.drops(g, f, [(f, r <= NEST + 3), (f + 7, plat)])


BOSS10 = gh.Boss(hall=crown, top=SKY_UP + 4, base_feet=LEAF_DOWN + 3,
                 entry_z=CZ + 14, presentation_z=CZ - 14, spectator=(MID - 50, 7, CZ + 8),
                 checks=crown_checks, open_sky=True)
