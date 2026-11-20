"""Duskvale's boss halls (owner, 15 Nov 2026: floors 5 and 10 fully rebuilt, unlike each other and every other floor).

FLOOR 5, THE ECLIPSE. A tall octagonal chapter house, its walls rising 56 to a star vault: tall lancet windows on six sides look out on the
night, and on the north wall over gate A hangs the eclipse, a black sun forty across with a burning lavender corona and amethyst rays. The
arena floor is an eight-pointed star of tuff with an eclipse at its heart; candelabra stand round it, a gallery rings the walls.

FLOOR 10, THE FALLEN SKY. The cathedral is gone. The party walks out of the passage onto a causeway of broken floor hanging in the night,
to an island of cathedral floor floating above a long fall: column stumps on its rim, a rose window lying in its pavement. Shattered spires,
arches and a broken rose drift round it, the moon hangs huge in the east, stars on every side and below.
"""
from __future__ import annotations

import math
import random

import numpy as np

import boss_kit as K
import grand_hall as gh
import tower_gen as tg
from boss_kit import CZ, MID, X0, X1, Z0, Z1, NIGHT
from tower_gen import CHAIN, Grid

AMETHYST = "minecraft:amethyst_block"
CALCITE = "minecraft:calcite"
RUBBLE = ("minecraft:cobbled_deepslate", "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_tiles",
          "minecraft:cracked_deepslate_tiles", "minecraft:polished_deepslate")
GLASS = "minecraft:light_gray_stained_glass"

# ---- floor 5: the eclipse ---------------------------------------------------------------------------------------------------------------
OCT = 44
WALL_UP = 56
VAULT_RISE = 10
GALLERY_UP = 14


def oct_d(shape) -> np.ndarray:
    xs, zs, _, _ = K.plan(shape)
    dx, dz = np.abs(xs - MID), np.abs(zs - CZ)
    return np.maximum(np.maximum(dx, dz), (dx + dz) / math.sqrt(2))


def eclipse(g: Grid, floor: int, f: int) -> None:
    ground = f - 1
    d = oct_d(g.grid.shape)
    xs, zs, r, ang = K.plan(g.grid.shape)
    inside = d < OCT
    # the chapter house: walls to f + 56, then a low star vault to its boss
    for x, z in np.argwhere(inside):
        x, z = int(x), int(z)
        t = f + WALL_UP + int(VAULT_RISE * math.sqrt(max(0.0, 1 - (d[x, z] / OCT) ** 2)))
        g.air(x, f, z, x, t, z)
        g.at("vault", x, t + 1, z)
        g.at("floor", x, ground, z)
    v = (ang - 22.5) % 45
    corner = np.minimum(v, 45 - v)                                    # degrees from the nearest octagon corner (at 22.5 + 45k)
    for x, z in np.argwhere(inside & (d > OCT - 1.5) & (corner * np.radians(1) * r < 1.5)):
        g.box("trim", int(x), f, int(z), int(x), f + WALL_UP, int(z))  # the shafts in the corners, moonlight set in them
        for y in range(f + 4, f + WALL_UP, 6):
            g.at("light", int(x), y, int(z))
    for x, z in np.argwhere(inside):                                    # ribs from the corners to the boss
        x, z = int(x), int(z)
        if corner[x, z] * math.radians(1) * r[x, z] < 1.0 or abs(((ang[x, z]) % 45) - 22.5) > 21.8:
            t = f + WALL_UP + int(VAULT_RISE * math.sqrt(max(0.0, 1 - (d[x, z] / OCT) ** 2)))
            g.at("rib", x, t, z)
    top = f + WALL_UP + VAULT_RISE
    g.box("light", MID - 2, top - 1, CZ - 2, MID + 2, top, CZ + 2)
    g.box(CALCITE, MID - 3, top - 2, CZ - 3, MID + 3, top - 2, CZ + 3)
    K.fill_dents(g, MID - 12, top - 6, CZ - 12, MID + 12, top + 2, CZ + 12, "vault")
    # lancet windows on six faces, the night behind them: carve the pockets, line them, then set the glass
    pocket_parts, panes = [], []
    for a in (0, 45, 135, 180, 225, 315):
        n = (math.cos(math.radians(a)), math.sin(math.radians(a)))
        t_ = (-n[1], n[0])
        for w in range(-4, 5):
            for depth in range(0, 7):
                x = round(MID + n[0] * (OCT + 0.5 + depth) + t_[0] * w)
                z = round(CZ + n[1] * (OCT + 0.5 + depth) + t_[1] * w)
                arch = 34 + int(math.sqrt(max(0.0, 16 - w * w)) * 1.6)
                for y in range(f + 8, f + arch + 1):
                    if depth == 0:
                        panes.append((x, y, z, "rib" if abs(w) == 4 or (abs(w) == 0 and (y - f) % 7 == 0) else GLASS))
                    elif abs(w) < 4:
                        pocket_parts.append((x, y, z))
    pts = np.array(pocket_parts)
    for x, y, z in pocket_parts:
        g.grid[x, y, z] = 0
    y0 = int(pts[:, 1].min()) - 1
    ny = int(pts[:, 1].max()) - y0 + 2
    pocket = np.zeros((g.grid.shape[0], ny, g.grid.shape[2]), dtype=bool)
    pocket[pts[:, 0], pts[:, 1] - y0, pts[:, 2]] = True
    K.night_lining(g, pocket, y0, 505, density=0.05)
    for x, y, z in pocket_parts:                                         # the glass closes the pocket off: two thick, so a diagonal face
        if d[x, z] < OCT + 1.6:                                          # leaves no corner open
            g.at(GLASS, x, y, z)
    for x, y, z, name in panes:
        if d[x, z] >= OCT - 0.5:
            g.at(name, x, y, z)
    # the eclipse on the north face, over gate A
    ez, ey = CZ - OCT, f + 29
    for x in range(MID - 26, MID + 27):
        for y in range(ey - 26, ey + 27):
            rr = math.hypot(x - MID, y - ey)
            if y < f + 9 or rr > 26:
                continue
            a = math.degrees(math.atan2(y - ey, x - MID)) % 360
            for z in (ez - 1, ez):
                if rr <= 10:
                    g.at(NIGHT, x, y, z)
                elif rr <= 13:
                    g.at("light", x, y, z)
                elif rr <= 14:
                    g.at(AMETHYST, x, y, z)
                elif (a % 15 < 3.6) and rr <= 18 - (int(a / 15) % 2) * 2.5:
                    g.at("light" if rr < 16 else AMETHYST, x, y, z)       # the rays
    # the floor: an eight-pointed star of tuff, an eclipse at its heart, the candelabra
    for x, z in np.argwhere(inside & (d < 30)):
        x, z = int(x), int(z)
        a = math.radians(ang[x, z])
        star = 30 * (0.62 + 0.38 * abs(math.cos(4 * a)) ** 3)
        if r[x, z] < star and (r[x, z] > star - 2 or abs(math.sin(4 * a)) < 0.04):
            g.at("inlay", x, ground, z)
    for x, z in np.argwhere(r < 8.5):
        g.at(AMETHYST if r[int(x), int(z)] > 6.5 else NIGHT, int(x), ground, int(z))
    for a in range(0, 360, 45):
        cx, cz = round(MID + 33 * math.cos(math.radians(a + 22.5))), round(CZ + 33 * math.sin(math.radians(a + 22.5)))
        g.box("rail", cx, f, cz, cx, f + 6, cz)
        g.box("light", cx - 1, f + 7, cz, cx + 1, f + 7, cz)
        g.box("light", cx, f + 7, cz - 1, cx, f + 7, cz + 1)
        g.at(AMETHYST, cx, f + 8, cz)
    # the gallery round the walls
    deck = inside & (d >= OCT - 7)
    for x, z in np.argwhere(deck):
        if g.grid[int(x), f + GALLERY_UP, int(z)] == 0:
            g.at("rib", int(x), f + GALLERY_UP, int(z))
    K.rail_edge(g, f + GALLERY_UP + 1, deck, inside & ~deck)
    edge = deck & K.dilate(np.stack([inside & ~deck] * 1, axis=1))[:, 0, :]
    for x, z in np.argwhere(edge):
        if (int(x) + int(z)) % 4 == 0:
            g.at("light", int(x), f + GALLERY_UP, int(z))                # lights along the gallery's edge, shining down
    K.ways(g, f, CZ - OCT, CZ + OCT)


def eclipse_checks(g: Grid) -> list[str]:
    f = BOSS5.base_feet
    d = oct_d(g.grid.shape)
    return K.drops(g, f, [(f + GALLERY_UP + 1, (d < OCT) & (d >= OCT - 7))])


BOSS5 = gh.Boss(hall=eclipse, top=WALL_UP + VAULT_RISE + 4, base_feet=9,
                entry_z=CZ + 22, presentation_z=CZ - 20, spectator=(MID - 40, GALLERY_UP + 1, CZ),
                checks=eclipse_checks)

# ---- floor 10: the fallen sky -----------------------------------------------------------------------------------------------------------
DEPTH = 40
SKY_UP = 66
ISLE = 24
CONE = 34
FREE: list[tuple[int, int, int]] = []


def fallen_sky(g: Grid, floor: int, f: int) -> None:
    rnd = random.Random(1010)
    ground = f - 1
    xs, zs, r, ang = K.plan(g.grid.shape)
    FREE.clear()
    # the night: the whole hall open from far below to far above, lined with stars
    y0 = f - DEPTH
    ny = DEPTH + SKY_UP + 1
    g.air(X0, y0, Z0, X1, f + SKY_UP, Z1)
    inside = np.zeros((g.grid.shape[0], ny, g.grid.shape[2]), dtype=bool)
    inside[X0:X1 + 1, :, Z0:Z1 + 1] = True
    K.night_lining(g, inside, y0, 1001, density=0.02)
    # the island: cathedral floor on an inverted spire of rubble
    noise = np.random.default_rng(1002).random(g.grid.shape[::2])
    for y in range(ground, ground - CONE, -1):
        k = (ground - y) / CONE
        rad = ISLE * (1 - k) ** 0.75
        for x, z in np.argwhere(r <= rad + 1.5 * noise - 0.75):
            g.at(RUBBLE[(int(x) * 7 + int(z) * 3 + y) % len(RUBBLE)], int(x), y, int(z))
    K.fill_dents(g, MID - ISLE - 3, ground - CONE, CZ - ISLE - 3, MID + ISLE + 3, ground - 1, CZ + ISLE + 3, RUBBLE[0])
    for x, z in np.argwhere(r <= ISLE):
        g.at("floor", int(x), ground, int(z))
    for x in range(MID - ISLE, MID + ISLE + 1):                               # the rose window, fallen into the pavement
        for z in range(CZ - 14, CZ + 15):
            rr = math.hypot(x - MID, z - CZ)
            if rr > 13:
                continue
            a = math.degrees(math.atan2(z - CZ, x - MID)) % 30
            g.at("inlay" if rr >= 12 or rr <= 2 else "rib" if a < 2.5 or abs(rr - 6) < 0.6 else "light", x, ground, z)
    for a in range(15, 360, 40):                                              # column stumps on the rim, broken off
        cx, cz = round(MID + (ISLE - 2.5) * math.cos(math.radians(a))), round(CZ + (ISLE - 2.5) * math.sin(math.radians(a)))
        if abs(cx - MID) < 7:
            continue
        h = rnd.randint(3, 10)
        g.box("pier", cx, f, cz, cx + 1, f + h, cz + 1)
        g.at(RUBBLE[1], cx, f + h + 1, cz)
    # the causeways: broken floor hanging in the night, in from the passage and out to gate A
    walk = (r <= ISLE) | ((np.abs(xs - MID) <= 3) & (zs >= Z0) & (zs <= Z1))
    for z in range(Z0, Z1 + 1):
        if abs(z - CZ) <= ISLE - 2:
            continue
        seg = (z // 6) % 3
        g.box("floor" if seg != 1 else RUBBLE[3], MID - 3, ground, z, MID + 3, ground, z)
        depth = 2 + (z * 7) % 4
        g.box(RUBBLE[seg], MID - 2, ground - depth, z, MID + 2, ground - 1, z)
    box = (xs >= X0) & (xs <= X1) & (zs >= Z0) & (zs <= Z1)
    K.rail_edge(g, f, walk, box & ~walk)
    K.fill_dents(g, MID - 4, ground - 7, Z0, MID + 4, ground - 1, Z1, RUBBLE[0])          # under the causeways and the island's rim
    # the spectators' fragment, west, railed all round
    sx0, sx1, sz0, sz1 = MID - 54, MID - 42, CZ - 7, CZ + 7
    g.box("floor", sx0, f + 7, sz0, sx1, f + 7, sz1)
    g.box(RUBBLE[0], sx0 + 1, f + 3, sz0 + 1, sx1 - 1, f + 6, sz1 - 1)
    plat = (xs >= sx0) & (xs <= sx1) & (zs >= sz0) & (zs <= sz1)
    K.rail_edge(g, f + 8, plat, ~plat)
    FREE.append((sx0, f + 7, sz0))
    # the moon, huge, in the east
    mx, my, mz = MID + 46, f + 46, CZ - 22
    for x in range(mx - 11, mx + 12):
        for y in range(my - 11, my + 12):
            for z in range(mz - 11, mz + 12):
                dd = math.hypot(x - mx, y - my, z - mz)
                if dd <= 10.5:
                    crater = ((x * 13 + y * 7 + z * 5) % 17) < 2
                    g.at("minecraft:white_concrete" if crater else "light", x, y, z)
    FREE.append((mx, my, mz))
    # the fallen: spires, arches and a broken rose drifting round the island
    pieces = [(MID - 40, f + 22, CZ - 30), (MID + 38, f + 18, CZ + 30), (MID - 30, f + 38, CZ + 34), (MID + 26, f + 30, CZ - 36),
              (MID - 52, f - 18, CZ + 20), (MID + 50, f - 12, CZ + 6), (MID + 10, f + 44, CZ + 28), (MID - 14, f - 24, CZ - 34)]
    for i, (px, py, pz) in enumerate(pieces):
        kind = i % 3
        if kind == 0:                                                         # a spire's tip, tilted
            tilt = (rnd.uniform(-0.5, 0.5), rnd.uniform(-0.5, 0.5))
            prev = (px, pz)
            for k in range(16):
                w = max(1, 2 - k // 6)
                cx, cz = round(px + tilt[0] * k), round(pz + tilt[1] * k)
                lo_x, hi_x = min(cx, prev[0]), max(cx, prev[0])        # each course overlaps the one under it, so the spire holds together
                lo_z, hi_z = min(cz, prev[1]), max(cz, prev[1])
                g.box("wall" if k % 5 else "trim", lo_x - w, py + k, lo_z - w, hi_x + w, py + k, hi_z + w)
                prev = (cx, cz)
        elif kind == 1:                                                       # a broken arch
            for t in np.linspace(0, math.pi * 0.8, 40):
                g.box("wall", round(px + 7 * math.cos(t)), round(py + 7 * math.sin(t)), pz, round(px + 7 * math.cos(t)) + 1,
                      round(py + 7 * math.sin(t)) + 1, pz + 1)
        else:                                                                 # a broken rose, upright
            for x in range(px - 8, px + 9):
                for y in range(py - 8, py + 9):
                    rr = math.hypot(x - px, y - py)
                    a = math.degrees(math.atan2(y - py, x - px)) % 360
                    if rr <= 8 and a < 250:
                        g.at("inlay" if rr >= 7 or rr <= 1.5 else "rib" if a % 30 < 4 else "light", x, y, pz)
        FREE.append((round(px + 7), py, pz) if kind == 1 else (px, py, pz))      # a block of the piece (an arch's centre is open)


def fallen_checks(g: Grid) -> list[str]:
    f = BOSS10.base_feet
    xs, zs, r, _ = K.plan(g.grid.shape)
    walk = (r <= ISLE) | ((np.abs(xs - MID) <= 3) & (zs >= Z0) & (zs <= Z1))
    plat = (xs >= MID - 54) & (xs <= MID - 42) & (zs >= CZ - 7) & (zs <= CZ + 7)
    return K.drops(g, f, [(f, walk), (f + 8, plat)])


BOSS10 = gh.Boss(hall=fallen_sky, top=SKY_UP + 4, base_feet=DEPTH + 4,
                 entry_z=CZ + 16, presentation_z=CZ - 15, spectator=(MID - 48, 8, CZ),
                 checks=fallen_checks, floating=FREE)
