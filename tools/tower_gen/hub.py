#!/usr/bin/env python3
"""The Tower Hub (owner, 19 Nov 2026): a schematic pasted once into its own multiworld world, reached by a warp. Each of the four towers with
halls has a bay: its host (an NPC) to talk to, a portal in the tower's colours to walk into, and the tower itself standing behind it.

    py -3.14 -I tools/tower_gen/hub.py [--out DIR] [--veil]     # writes tower_hub.nbt (and with --veil a concept copy with the portals filled)

THE PLAN (owner: round, not side by side; then "not enough vertical decoration, more elaborate and higher quality"). A round island. The
party warps onto a pad south of a three-tier fountain whose spire is banded in the four towers' colours, in a round plaza ringed with tall
lamps. The four towers stand round it at the compass points, Neutral north, Tideforge east, Duskvale south, Rootvale west, each behind its
gatehouse facing the plaza: a portal arch with a lit archivolt, an emblem over it, banners, turrets with pinnacles, a canopied dais for its
host (CobbleStatues, placed in game) and tall lamps in its stone.

  Neutral     a two-stage quartz spire: buttressed corners rising to gold pinnacles, arched windows, a rose on its face, red banners,
              balustrades, an octagonal lantern and a gold dome
  Tideforge   a sea temple: a ring of water columns in glass round a pool, an arcaded drum, a ribbed glass dome and a lantern cupola,
              prismarine obelisks at the corners
  Duskvale    a broken gothic spire: clasping buttresses and pinnacles, flying buttresses, lancets and a rose, the top broken off and its
              shards rising in a spiral over it, two smaller ruined spires beside it
  Rootvale    the world tree: buttress roots arching out of the ground, a trunk with glowing knots, two tiers of branches and a crown of
              leafy lobes with lights hanging under them

Each tower's quarter of the island is its own garden, and every garden has height: Neutral's topiary cones and spheres, pergolas and
gold-tipped obelisks; Tideforge's coral spires, aquarium pillars with kelp, a rock with a waterfall into a pool and prismarine arches;
Duskvale's ruined pointed arches with lights hanging in them, dark trees and amethyst crystal spires; Rootvale's giant mushrooms, flowering
trees and root arches. On each diagonal, where two gardens meet, a pylon carries both towers' colours.

A bay is built facing south in a grid of its own (the gate's face at z 68, the tower behind it) and turned to face the plaza: quarter turns
only, so nothing is drawn at an angle; logs swap their x and z axes on a quarter turn, and no block that faces a way is used in a bay.

The portal opening is left as air: the mod draws the veil (display entities and particles) and handles walking in. `--veil` fills it with
the tower's colour for concept pictures only.
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tower_gen as tg  # noqa: E402
from themes import THEMES  # noqa: E402
from tower_gen import CHAIN, Grid  # noqa: E402

SX, SY, SZ = 220, 152, 220
C = 110                                 # the island's centre (x and z)
ISLAND = 104
PLAZA = 34                              # the round plaza's radius
G = 48                                  # the walking level (the paving at G - 1); the island's rock hangs below it
GATE_R = 40                             # from the centre to a gate's face
BAY_W, BAY_D, BAY_X = 60, 84, 30        # a bay's own grid, built facing south: the gate's face at z 68, the tower's centre at z 38
WALL_Z = (63, 68)
WALL_UP = 18
PORTAL_HALF, PORTAL_UP = 3, 11          # the opening: 7 wide, 11 high
TOWER_Z = 38
BAYS = (("neutral", (0, -1), "minecraft:yellow_stained_glass"), ("tideforge", (1, 0), "minecraft:light_blue_stained_glass"),
        ("duskvale", (0, 1), "minecraft:magenta_stained_glass"), ("rootvale", (-1, 0), "minecraft:lime_stained_glass"))
SPAWN = (C, C + 18)
HOST_S = 29                             # each host's plinth this far out from the fountain, on its tower's path
WALL_R = (97.0, 102.5)                  # the curtain wall round the rim
WALL_H = 18

PAVE, PAVE2, KERB = "minecraft:polished_andesite", "minecraft:smooth_stone", "minecraft:stone_bricks"
GRASS, DIRT, STONE = "minecraft:grass_block", "minecraft:dirt", "minecraft:stone"
WATER, FALLING = "minecraft:water", "minecraft:water[level=8]"
LANTERN = "minecraft:lantern[hanging=true]"
QUARTZ, QPILLAR, CHISELED, GOLD = "minecraft:quartz_bricks", "minecraft:quartz_pillar", "minecraft:chiseled_quartz_block", "minecraft:gold_block"
PRISMARINE = "minecraft:prismarine"
LEAVES = ("minecraft:oak_leaves[persistent=true]", "minecraft:azalea_leaves[persistent=true]",
          "minecraft:flowering_azalea_leaves[persistent=true]", "minecraft:cherry_leaves[persistent=true]")
DARK_LEAVES = "minecraft:dark_oak_leaves[persistent=true]"
CORALS = ("minecraft:tube_coral_block", "minecraft:brain_coral_block", "minecraft:bubble_coral_block", "minecraft:fire_coral_block",
          "minecraft:horn_coral_block")


def role(tower: str, r: str) -> str:
    return THEMES[tower].get(r, THEMES[tower]["pier"])


# ---- drawing helpers ---------------------------------------------------------------------------------------------------------------------

def disc(g: Grid, name: str, cx: float, y: int, cz: float, r: float, only_air: bool = False) -> None:
    for x in range(int(cx - r - 1), int(cx + r + 2)):
        for z in range(int(cz - r - 1), int(cz + r + 2)):
            if math.hypot(x - cx, z - cz) <= r and (not only_air or g.grid[x, y, z] == 0):
                g.at(name, x, y, z)


def ring(g: Grid, name: str, cx: float, y: int, cz: float, r0: float, r1: float) -> None:
    for x in range(int(cx - r1 - 1), int(cx + r1 + 2)):
        for z in range(int(cz - r1 - 1), int(cz + r1 + 2)):
            if r0 <= math.hypot(x - cx, z - cz) <= r1:
                g.at(name, x, y, z)


def blob(g: Grid, pick, cx: float, cy: float, cz: float, rx: float, ry: float, rz: float | None = None, only_air: bool = True) -> None:
    """An ellipsoid; `pick(x, y, z)` names the block (a string works too)."""
    rz = rx if rz is None else rz
    for x in range(int(cx - rx - 1), int(cx + rx + 2)):
        for y in range(int(cy - ry - 1), int(cy + ry + 2)):
            for z in range(int(cz - rz - 1), int(cz + rz + 2)):
                if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 + ((z - cz) / rz) ** 2 <= 1 and 0 <= y < g.grid.shape[1]:
                    if not only_air or g.grid[x, y, z] == 0:
                        g.at(pick(x, y, z) if callable(pick) else pick, x, y, z)


def tube(g: Grid, name: str, pts: list[tuple[float, float, float]], r: float) -> None:
    """A round tube along a polyline (roots, branches, buttresses): every sample a ball of radius r, and its centre line stepped one
    axis at a time, so even a thin tube is joined face to face (corner-to-corner steps leave it in pieces)."""
    prev = None
    for (x0, y0, z0), (x1, y1, z1) in zip(pts, pts[1:]):
        n = max(2, int(math.dist((x0, y0, z0), (x1, y1, z1)) * 2))
        for t in np.linspace(0, 1, n):
            x, y, z = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t
            cur = [round(x), round(y), round(z)]
            if prev is not None:
                step = list(prev)
                for axis in (1, 0, 2):
                    while step[axis] != cur[axis]:
                        step[axis] += 1 if cur[axis] > step[axis] else -1
                        g.at(name, *step)
            prev = cur
            ri = int(math.ceil(r))
            for dx in range(-ri, ri + 1):
                for dy in range(-ri, ri + 1):
                    for dz in range(-ri, ri + 1):
                        if dx * dx + dy * dy + dz * dz <= r * r + 0.3:
                            g.at(name, round(x) + dx, round(y) + dy, round(z) + dz)


def bezier(p0, p1, p2, n: int = 24) -> list[tuple[float, float, float]]:
    return [tuple((1 - t) ** 2 * a + 2 * (1 - t) * t * b + t * t * c for a, b, c in zip(p0, p1, p2)) for t in np.linspace(0, 1, n)]


def lamp(g: Grid, x: int, z: int, h: int, post: str, light: str, arms: bool = True) -> None:
    """A tall lamp: a post, a lit head, and (with arms) a crossbar with a lantern hung from each end."""
    g.box(post, x, G, z, x, G + h, z)
    g.at(light, x, G + h + 1, z)
    if arms:
        g.box(post, x - 2, G + h - 1, z, x + 2, G + h - 1, z)
        for ax in (x - 2, x + 2):
            g.at(CHAIN, ax, G + h - 2, z)
            g.at(LANTERN, ax, G + h - 3, z)


def pointed_arch(g: Grid, name: str, x0: int, z0: int, x1: int, z1: int, h: int, thick: int = 1) -> None:
    """A free-standing pointed arch from (x0, z0) to (x1, z1) along x or z: two piers, and from each a curve rising to the apex, drawn as
    tubes so the arch is one piece. Returns nothing; the apex's underside is at G + h - 2."""
    spring = G + int(h * 0.5)
    apex = G + h - 1
    mx, mz = (x0 + x1) / 2, (z0 + z1) / 2
    w = thick - 1
    for px, pz in ((x0, z0), (x1, z1)):
        g.box(name, px - w * (z0 == z1), G, pz - w * (x0 == x1), px + w * (z0 == z1), spring, pz + w * (x0 == x1))
        tube(g, name, bezier((px, spring, pz), (px, apex, pz), (mx, apex, mz), 20), 0.6 + 0.4 * w)


# ---- the ground, the plaza, the fountain monument ----------------------------------------------------------------------------------------

def plan() -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    xs, zs = np.meshgrid(np.arange(SX), np.arange(SZ), indexing="ij")
    return xs, zs, np.hypot(xs - C, zs - C), (np.degrees(np.arctan2(zs - C, xs - C)) + 360) % 360


def ground(g: Grid) -> None:
    """A floating island: grass and earth on top; under it rock in bands (stone, andesite, tuff, deepslate at the bottom) tapering to a
    point some forty blocks down, mossy and root-hung near the rim; a few spires of rock hanging under it. Only a shell of it is built
    (the top, the underside and the rim), the inside is never seen."""
    _, _, r, ang = plan()
    rng = np.random.default_rng(48)
    noise = rng.random(r.shape)
    bands = ("minecraft:stone", "minecraft:andesite", "minecraft:stone", "minecraft:tuff", "minecraft:cobblestone", "minecraft:stone",
             "minecraft:andesite", "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:deepslate")

    def depth(x: int, z: int) -> int:
        k = r[x, z] / ISLAND
        wob = math.sin(ang[x, z] * math.radians(5)) * 2 + math.sin(ang[x, z] * math.radians(13)) * 1.2
        return int(5 + 40 * max(0.0, 1 - k) ** 1.35 + wob * (1 - k) + noise[x, z] * 2.2)

    for x, z in np.argwhere(r < ISLAND):
        x, z = int(x), int(z)
        d = depth(x, z)
        bottom = G - 1 - d
        rim = r[x, z] > ISLAND - 5
        for y in range(max(0, bottom), G - 1):
            from_top = G - 1 - y
            if not rim and from_top > 5 and y > bottom + 3:
                continue                                                  # the hidden inside
            if from_top <= 2:
                name = DIRT
            else:
                name = bands[min(len(bands) - 1, int((from_top - 3) / 4.2 + noise[x, z] * 0.8))]
                if rim and from_top < 10 and (x * 3 + y * 5 + z) % 5 == 0:
                    name = "minecraft:mossy_cobblestone"
            g.at(name, x, y, z)
        g.at(GRASS, x, G - 1, z)
        if r[x, z] > ISLAND - 1.5:
            g.box(KERB, x, G - 1, z, x, G, z)
        if r[x, z] > ISLAND - 9 and (x * 7 + z * 13) % 9 == 0 and bottom - 1 > 0:
            g.at("minecraft:hanging_roots", x, bottom - 1, z)                # roots hanging from the rim's underside
    for k in range(9):                                                      # spires of rock hanging under the island
        a = math.radians(k * 40 + 11)
        rr = 18 + (k * 23) % 52
        x0, z0 = round(C + rr * math.cos(a)), round(C + rr * math.sin(a))
        top = G - 1 - depth(x0, z0)
        length = 10 + (k * 7) % 14
        for i in range(length):
            w = 3.2 * (1 - i / length) + 0.4
            disc(g, bands[min(len(bands) - 1, 6 + i // 5)], x0, max(0, top - i), z0, w)


def plaza(g: Grid) -> None:
    """The round plaza: paving in rings and rays, kerbs, tall lamps between the gates, a path out to each gate in that tower's stone."""
    _, _, r, ang = plan()
    for x, z in np.argwhere(r <= PLAZA):
        x, z = int(x), int(z)
        ring_, ray = int(r[x, z]) // 4, int(ang[x, z]) // 15
        g.at(PAVE if (ring_ + ray) % 2 else PAVE2, x, G - 1, z)
        if r[x, z] > PLAZA - 1.2 or abs(r[x, z] - 15) < 0.6:
            g.at("minecraft:chiseled_stone_bricks" if int(ang[x, z]) % 10 == 0 else KERB, x, G - 1, z)
    for a in range(15, 360, 30):
        x, z = round(C + (PLAZA - 3) * math.cos(math.radians(a))), round(C + (PLAZA - 3) * math.sin(math.radians(a)))
        g.box(KERB, x - 1, G, z - 1, x + 1, G, z + 1)
        lamp(g, x, z, 7, "minecraft:stone_brick_wall", "minecraft:sea_lantern")
    for tower, (ox, oz), _ in BAYS:                                         # where each path leaves the plaza: an apron in the tower's
        floor, trim, accent = role(tower, "floor"), role(tower, "trim"), role(tower, "accent")      # floor, and the host's round plinth
        hx, hz = C + ox * HOST_S, C + oz * HOST_S                            # in the middle of it, facing the fountain; the path parts round it
        for x in range(hx - 7, hx + 8):
            for z in range(hz - 7, hz + 8):
                d = math.hypot(x - hx, z - hz)
                if d <= 6.5 and r[x, z] > 15.5:
                    g.at(role(tower, "inlay") if d > 5.6 else floor, x, G - 1, z)
                    g.air(x, G, z, x, G + 2, z)                                  # nothing standing on the apron but the host
                if d <= 1.6:
                    g.box(trim, x, G - 1, z, x, G, z)
        g.at(accent, hx, G, hz)
    for tower, (ox, oz), _ in BAYS:                                         # standards either side of each path where it leaves the plaza
        for side in (-1, 1):
            sx_, sz_ = C + ox * 30 + (-oz) * 6 * side, C + oz * 30 + ox * 6 * side
            g.box(KERB, sx_ - 1, G, sz_ - 1, sx_ + 1, G, sz_ + 1)
            g.box(role(tower, "rail"), sx_, G + 1, sz_, sx_, G + 11, sz_)
            g.box(role(tower, "trim"), sx_ - 1, G + 12, sz_ - 1, sx_ + 1, G + 12, sz_ + 1)
            g.box(role(tower, "accent"), sx_, G + 13, sz_, sx_, G + 14, sz_)
            g.at(role(tower, "light"), sx_, G + 15, sz_)
            bx_, bz_ = sx_ - ox, sz_ - oz                                     # the banner hangs on the plaza side of the pole
            g.box(role(tower, "hanging"), bx_, G + 4, bz_, bx_, G + 11, bz_)
            g.at(role(tower, "accent"), bx_, G + 3, bz_)
    for tower, (ox, oz), _ in BAYS:
        floor, inlay, accent = role(tower, "floor"), role(tower, "inlay"), role(tower, "accent")
        for s in range(12, GATE_R + 2):
            for w in range(-3, 4):
                x, z = C + ox * s + (-oz) * w, C + oz * s + ox * w
                g.at(inlay if w == 0 or abs(w) == 3 else floor, x, G - 1, z)
            if s % 6 == 0:
                g.at(accent, C + ox * s, G - 1, C + oz * s)
    for x, z in np.argwhere((r > 11.3) & (r < 23.4)):                        # a compass rose round the fountain: a ray to each gate in that
        x, z = int(x), int(z)                                                # tower's inlay, rimmed in its accent; short plain rays between
        a, rv = ang[x, z], r[x, z]
        hw = 3.4 * (1 - (rv - 11.5) / 12) + 0.5
        for tower, (ox, oz), _ in BAYS:
            k = math.degrees(math.atan2(oz, ox)) % 360
            off = abs(((a - k + 180) % 360) - 180) * math.radians(rv)
            if off <= hw:
                g.at(role(tower, "inlay") if off < hw - 0.9 else role(tower, "accent"), x, G - 1, z)
        for k in (45, 135, 225, 315):
            off = abs(((a - k + 180) % 360) - 180) * math.radians(rv)
            if off <= hw * 0.55 and rv < 19:
                g.at("minecraft:chiseled_stone_bricks" if off > hw * 0.55 - 0.9 else "minecraft:polished_diorite", x, G - 1, z)
        if abs(rv - 11.9) < 0.5:
            g.at("minecraft:chiseled_stone_bricks", x, G - 1, z)
    for x, z in np.argwhere((r >= 23.6) & (r < 25.4)):                      # a low hedge ring, open at the four paths and the diagonals
        x, z = int(x), int(z)
        a = ang[x, z]
        if min(abs(((a - k + 180) % 360) - 180) for k in (0, 90, 180, 270)) * math.radians(r[x, z]) <= 5.5:
            continue
        if min(abs(((a - k + 180) % 360) - 180) for k in (45, 135, 225, 315)) * math.radians(r[x, z]) <= 2.5:
            continue
        g.at(LEAVES[0], x, G, z)
    for k in range(0, 360, 90):                                               # urns at the ends of each hedge, benches inside them
        for side in (-1, 1):
            a = math.radians(k + 45 + side * 8)
            ux, uz = round(C + 24.5 * math.cos(a)), round(C + 24.5 * math.sin(a))
            g.box("minecraft:chiseled_stone_bricks", ux, G, uz, ux, G, uz)
            g.at("minecraft:potted_azalea_bush", ux, G + 1, uz)
        for b in (k + 22, k + 68):
            for dd in (-1, 0, 1):
                a = math.radians(b) + dd / 22.5
                g.at("minecraft:smooth_stone_slab[type=bottom]", round(C + 22.4 * math.cos(a)), G, round(C + 22.4 * math.sin(a)))
    sx, sz = SPAWN
    for x in range(sx - 5, sx + 6):
        for z in range(sz - 5, sz + 6):
            d = math.hypot(x - sx, z - sz)
            if d <= 5.3:
                g.at(CHISELED if d > 4 else QUARTZ, x, G - 1, z)
    g.at(GOLD, sx, G - 1, sz)


def fountain(g: Grid) -> None:
    """A three-tier fountain: a wide basin; a middle basin on a column, water falling from it at four points into the wide one; a small
    top basin; and rising through them a spire banded in the four towers' accents, crowned with lights and gold."""
    fx, fz = C, C
    for x in range(fx - 11, fx + 12):
        for z in range(fz - 11, fz + 12):
            d = math.hypot(x - fx, z - fz)
            if d <= 11:
                g.at(KERB, x, G - 2, z)
                if d > 9.5:
                    g.box("minecraft:chiseled_stone_bricks" if (x + z) % 4 == 0 else KERB, x, G - 1, z, x, G, z)
                else:
                    g.at(WATER, x, G - 1, z)
    for a in range(0, 360, 30):
        x, z = round(fx + 8.5 * math.cos(math.radians(a))), round(fz + 8.5 * math.sin(math.radians(a)))
        g.at("minecraft:sea_lantern", x, G - 2, z)
    g.box(QUARTZ, fx - 2, G - 1, fz - 2, fx + 2, G + 3, fz + 2)
    disc(g, KERB, fx, G + 4, fz, 6.2)                                           # the middle basin: a floor, a rim, water
    ring(g, "minecraft:chiseled_stone_bricks", fx, G + 5, fz, 5.3, 6.4)
    disc(g, WATER, fx, G + 5, fz, 5.2)
    for ox, oz in ((1, 0), (-1, 0), (0, 1), (0, -1)):                           # four falls: a gap in the basin's floor at its edge
        x, z = fx + ox * 4, fz + oz * 4
        g.air(x, G + 4, z, x, G + 4, z)
        g.box(FALLING, x, G, z, x, G + 4, z)
    g.box(QUARTZ, fx - 1, G + 5, fz - 1, fx + 1, G + 9, fz + 1)
    disc(g, QUARTZ, fx, G + 10, fz, 2.6)                                        # the top basin
    ring(g, CHISELED, fx, G + 11, fz, 1.8, 2.8)
    bands = [role(t, "accent") for t, _, _ in BAYS]
    for y in range(G + 11, G + 30):                                             # the spire, banded in the towers' accents
        k = (y - G - 11) // 3
        w = 1 if y < G + 29 else 0
        name = bands[k % 4] if k < 4 else (QPILLAR if y < G + 26 else CHISELED)
        g.box(name, fx - w, y, fz - w, fx + w, y, fz + w)
    for ox, oz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        g.box("minecraft:sea_lantern", fx + ox * 2, G + 26, fz + oz * 2, fx + ox * 2, G + 27, fz + oz * 2)
        g.at(GOLD, fx + ox * 2, G + 28, fz + oz * 2)
    g.box(GOLD, fx, G + 30, fz, fx, G + 32, fz)


# ---- a bay: gatehouse, portal, emblem, banners, turrets, the host's dais, lamps ----------------------------------------------------------

def bay(g: Grid, tower: str, cx: int, veil: str | None) -> None:
    wall, trim, accent, light, glass, inlay, hang = (role(tower, r) for r in ("wall", "trim", "accent", "light", "glass", "inlay", "hanging"))
    pier, low, floor, rail, rib = (role(tower, r) for r in ("pier", "wall_low", "floor", "rail", "rib"))
    z0, zf = WALL_Z
    top = G + WALL_UP
    g.box(low, cx - 15, G - 1, z0, cx + 15, G + 2, zf)                          # the gatehouse: plinth, wall, cornice, stepped merlons
    g.box(wall, cx - 15, G + 3, z0, cx + 15, top, zf)
    g.box(trim, cx - 16, top + 1, z0 - 1, cx + 16, top + 1, zf + 1)
    for x in range(cx - 15, cx + 16):
        step = max(0, 4 - abs(x - cx) // 3)
        if (x - cx) % 2 == 0:
            g.box(trim, x, top + 2, z0, x, top + 2 + step, zf)
    g.box(rib, cx - 15, G + 3, zf, cx + 15, G + 3, zf)                          # a string course
    for x in range(cx - 13, cx + 14, 4):
        g.box(pier, x, G + 3, z0 - 1, x, top, z0 - 1)                          # buttress strips on the back face
        g.box(light, x + 2, G + 8, z0, x + 2, G + 12, z0) if abs(x + 2 - cx) > PORTAL_HALF + 4 else None
    h, ptop = PORTAL_HALF, G + PORTAL_UP
    spring = ptop - h
    for x in range(cx - h - 4, cx + h + 5):                                     # the portal: the opening, its lit rim, a band round that
        for y in range(G, ptop + h + 5):
            dx = abs(x - cx)
            d = math.hypot(dx, y - spring) if y >= spring else dx
            for z in range(z0, zf + 2):
                if d <= h + 0.4:
                    if z <= zf:
                        g.at(veil if veil else tg.AIR, x, y, z)
                elif d <= h + 1.5 and z <= zf + 1:
                    g.at(light, x, y, z)
                elif d <= h + 3.4 and z <= zf + 1:
                    g.at(trim if y >= spring else pier, x, y, z)
    g.at(accent, cx, ptop + h + 3, zf + 1)
    ey = ptop + h + 9
    for x in range(cx - 4, cx + 5):                                             # the emblem: a disc of the tower's light in its accent
        for y in range(ey - 4, ey + 5):
            d = math.hypot(x - cx, y - ey)
            if d <= 4.3:
                g.at(accent if d > 3.3 else light if d < 1.5 else glass, x, y, zf + 1)
                g.at(light, x, y, zf)
    g.box(floor, cx - h, G - 1, z0 - 4, cx + h, G - 1, zf)
    g.air(cx - h, G, z0 - 4, cx + h, G + 3, z0 - 1)                             # a step behind the gate, so the arch is a way through
    for bx in (cx - 9, cx + 9):                                                 # banners either side of the gate, pilasters further out
        g.box(hang, bx - 1, G + 5, zf + 1, bx + 1, top - 2, zf + 1)
        g.box(accent, bx - 1, top - 1, zf + 1, bx + 1, top - 1, zf + 1)
        g.at(accent, bx, G + 4, zf + 1)
    for px in (cx - 13, cx + 13):
        g.box(pier, px, G + 3, zf + 1, px, top, zf + 1)
    for tx in (cx - 17, cx + 17):                                               # turrets: quoins, string courses, lit windows on every face
        g.box(wall, tx - 2, G - 1, z0 - 1, tx + 2, top + 8, zf + 2)
        for qx in (tx - 2, tx + 2):
            for qz in (z0 - 1, zf + 2):
                for y in range(G - 1, top + 9):
                    if (y - G) % 4 < 2:
                        g.at(pier, qx, y, qz)                                   # quoins at the corners, alternating
        for y in range(G + 7, top + 8, 8):
            g.box(trim, tx - 3, y, z0 - 2, tx + 3, y, zf + 3)                   # a string course, proud of the faces
            g.box(wall, tx - 2, y, z0 - 1, tx + 2, y, zf + 2)
        for y in range(G + 2, top + 4, 8):
            for wx, wz, ix, iz in ((tx, zf + 2, tx, zf + 1), (tx, z0 - 1, tx, z0), (tx - 2, (z0 + zf) // 2 + 1, tx - 1, (z0 + zf) // 2 + 1),
                                   (tx + 2, (z0 + zf) // 2 + 1, tx + 1, (z0 + zf) // 2 + 1)):
                g.box(glass, wx, y, wz, wx, y + 3, wz)
                g.box(light, ix, y, iz, ix, y + 3, iz)
                g.at(accent, wx, y + 4, wz)
        g.box(trim, tx - 3, top + 9, z0 - 2, tx + 3, top + 9, zf + 3)
        for px_, pz_ in ((tx - 2, z0 - 1), (tx + 2, z0 - 1), (tx - 2, zf + 2), (tx + 2, zf + 2)):
            g.box(pier, px_, top + 10, pz_, px_, top + 11, pz_)
            g.at(accent, px_, top + 12, pz_)
        g.box(pier, tx, top + 10, (z0 + zf) // 2, tx, top + 14, (z0 + zf) // 2)
        g.box(accent, tx, top + 15, (z0 + zf) // 2, tx, top + 16, (z0 + zf) // 2)
    g.box(floor, cx - 13, G - 1, zf + 2, cx + 13, G - 1, zf + 13)               # the forecourt
    g.box(inlay, cx - 13, G - 1, zf + 13, cx + 13, G - 1, zf + 13)
    for x in range(cx - 13, cx + 14, 2):
        g.at(inlay, x, G - 1, zf + 2)
    for lx in (cx - 13, cx + 13):                                               # tall lamps at the forecourt's front corners
        g.box(low, lx - 1, G, zf + 12, lx + 1, G + 1, zf + 14)
        lamp(g, lx, zf + 13, 9, rail, light)


# ---- the towers behind the gatehouses (each in its bay's grid, the tower's centre at (cx, TOWER_Z)) --------------------------------------

def neutral_spire(g: Grid, cx: int) -> None:
    t = "neutral"
    wall, trim, accent, low, light, glass, hang, pier, rail = (role(t, r) for r in
                                                               ("wall", "trim", "accent", "wall_low", "light", "glass", "hanging", "pier", "rail"))
    cz = TOWER_Z
    g.box(low, cx - 15, G - 1, cz - 15, cx + 15, G + 1, cz + 15)               # a stepped plinth
    g.box(low, cx - 14, G + 2, cz - 14, cx + 14, G + 2, cz + 14)
    for half, y0, y1 in ((12, G + 3, G + 40), (9, G + 41, G + 63)):
        g.box(wall, cx - half, y0, cz - half, cx + half, y1, cz + half)
        g.box(trim, cx - half - 1, y1, cz - half - 1, cx + half + 1, y1, cz + half + 1)
        g.box(rail, cx - half - 1, y1 + 1, cz - half - 1, cx + half + 1, y1 + 1, cz + half + 1)   # a balustrade round each stage
        g.air(cx - half, y1 + 1, cz - half, cx + half, y1 + 1, cz + half)
        for k in (-half + 3, 0, half - 3):                                     # tall arched windows on every face, lit from behind
            for y in range(y0 + 4, y1 - 10, 14):
                for yy in range(y, y + 10):
                    w = 1 if yy < y + 8 else 0
                    for kk in range(k - w, k + w + 1):
                        for x, z, bx, bz in ((cx + kk, cz + half, cx + kk, cz + half - 1), (cx + kk, cz - half, cx + kk, cz - half + 1),
                                             (cx + half, cz + kk, cx + half - 1, cz + kk), (cx - half, cz + kk, cx - half + 1, cz + kk)):
                            g.at(glass, x, yy, z)
                            g.at(light, bx, yy, bz)
                g.at(accent, cx + k, y + 10, cz + half)
        for sx in (-1, 1):                                                     # clasping buttresses at the corners, up to pinnacles
            for sz in (-1, 1):
                x, z = cx + sx * half, cz + sz * half
                g.box(pier, x - 1, y0, z - 1, x + 1, y1 + 4, z + 1)
                g.box(trim, x - 1, y1 + 5, z - 1, x + 1, y1 + 5, z + 1)
                g.box(pier, x, y1 + 6, z, x, y1 + 8, z)
                g.at(accent, x, y1 + 9, z)
    rose_y = G + 30
    for x in range(cx - 6, cx + 7):                                             # the rose on the south face
        for y in range(rose_y - 6, rose_y + 7):
            d = math.hypot(x - cx, y - rose_y)
            if d <= 5.6:
                a = math.degrees(math.atan2(y - rose_y, x - cx)) % 45
                g.at(accent if d > 4.6 or d < 1.2 else trim if a < 6 else glass, x, y, cz + 13)
                g.at(light, x, y, cz + 12)
    for bx in (cx - 8, cx + 8):                                                 # red banners down the lower stage
        g.box(hang, bx - 1, G + 10, cz + 13, bx + 1, G + 36, cz + 13)
        g.box(accent, bx - 1, G + 37, cz + 13, bx + 1, G + 37, cz + 13)
    for y in range(G + 64, G + 78):                                             # the octagonal lantern
        for x in range(cx - 7, cx + 8):
            for z in range(cz - 7, cz + 8):
                dx, dz = abs(x - cx), abs(z - cz)
                d = max(dx, dz, (dx + dz) / math.sqrt(2))
                if d <= 6.5:
                    opening = 5.5 < d and (y - G - 64) in range(3, 11) and min(dx, dz) <= 1
                    g.at(light if d < 5.5 else (glass if opening else wall), x, y, z)
    for x in range(cx - 7, cx + 8):                                             # the gold dome, a spire and a ball
        for z in range(cz - 7, cz + 8):
            d = math.hypot(x - cx, z - cz)
            if d <= 7:
                g.box(accent, x, G + 78, z, x, G + 78 + int(7 * math.sqrt(max(0.0, 1 - (d / 7.2) ** 2))), z)
    g.box(accent, cx, G + 86, cz, cx, G + 93, cz)
    blob(g, accent, cx, G + 95, cz, 1.6, 1.6)


def tideforge_temple(g: Grid, cx: int) -> None:
    t = "tideforge"
    wall, trim, accent, low, light, rib, glass = (role(t, r) for r in ("wall", "trim", "accent", "wall_low", "light", "rib", "glass"))
    cz = TOWER_Z
    for x in range(cx - 26, cx + 27):                                           # a stepped platform, a ring pool, coral in it
        for z in range(cz - 26, cz + 27):
            d = math.hypot(x - cx, z - cz)
            if d <= 25.5:
                g.box(low, x, G - 3, z, x, G - 1 + (1 if d > 23.5 else 0), z)
            if 16.5 < d <= 22.5:
                g.at(low, x, G - 3, z)
                g.box(WATER, x, G - 2, z, x, G - 1, z)
                if (x * 5 + z * 3) % 13 == 0:
                    g.at(CORALS[(x + z) % 5], x, G - 2, z)
                if (x * 7 + z * 11) % 23 == 0:
                    g.at(light, x, G - 3, z)
    for x in range(cx - 16, cx + 17):                                           # the drum, arcaded, pilasters between the arches
        for z in range(cz - 16, cz + 17):
            d = math.hypot(x - cx, z - cz)
            if d <= 15.5:
                g.box(wall, x, G - 1, z, x, G + 30, z)
                if d > 14.5:
                    a = math.degrees(math.atan2(z - cz, x - cx)) % 30
                    if 8 < a < 22:
                        u = abs(a - 15) / 7
                        g.box(glass, x, G + 4, z, x, G + 18 + int(4 * (1 - u * u) ** 0.5), z)
                        if 12 < a < 18:
                            g.at(light, x, G + 26, z)
                    elif a < 3 or a > 27:
                        g.box(trim, x, G - 1, z, x, G + 30, z)
    ring(g, trim, cx, G + 31, cz, 14.5, 16.8)
    ring(g, accent, cx, G + 32, cz, 15.2, 16.2)
    tops = {}
    for x in range(cx - 16, cx + 17):                                           # the ribbed glass dome, lit under it
        for z in range(cz - 16, cz + 17):
            d = math.hypot(x - cx, z - cz)
            if d <= 15.6:
                tops[x, z] = G + 32 + int(15 * math.sqrt(max(0.0, 1 - (d / 15.8) ** 2)))
    for (x, z), top in tops.items():
        d = math.hypot(x - cx, z - cz)
        a = math.degrees(math.atan2(z - cz, x - cx)) % 30
        low = min(tops.get((x + dx, z + dz), G + 32) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        g.box(rib if a < 3 or a > 27 or d < 2.2 else "minecraft:light_blue_stained_glass", x, max(G + 32, min(top, low)), z, x, top, z)
        if d < 14:
            g.at(light, x, G + 31, z)
    for y in range(G + 47, G + 55):                                             # the cupola and a diamond point
        disc(g, wall if y < G + 49 else light if y < G + 53 else trim, cx, y, cz, 3.2)
    g.box(accent, cx, G + 55, cz, cx, G + 58, cz)
    for a in range(0, 360, 22):                                                 # water columns in glass round the pool, a ring on them
        x, z = round(cx + 19.5 * math.cos(math.radians(a))), round(cz + 19.5 * math.sin(math.radians(a)))
        g.box("minecraft:glass", x - 1, G - 2, z - 1, x + 1, G + 20, z + 1)
        g.box(WATER, x, G - 2, z, x, G + 19, z)
        g.at(light, x, G - 2, z)
        g.box(trim, x - 1, G + 21, z - 1, x + 1, G + 21, z + 1)
    ring(g, trim, cx, G + 22, cz, 18.2, 20.8)
    ring(g, accent, cx, G + 23, cz, 19.0, 20.0)
    for sx in (-1, 1):                                                          # prismarine obelisks at the platform's corners
        for sz in (-1, 1):
            x, z = cx + sx * 23, cz + sz * 23
            for y in range(G, G + 32):
                w = 2 if y < G + 4 else 1 if y < G + 28 else 0
                g.box(trim if y < G + 4 else wall, x - w, y, z - w, x + w, y, z + w)
            g.box(light, x, G + 32, z, x, G + 33, z)
            g.at(accent, x, G + 34, z)


def duskvale_ruin(g: Grid, cx: int) -> None:
    t = "duskvale"
    wall, trim, accent, low, light, glass, rib, pier = (role(t, r) for r in ("wall", "trim", "accent", "wall_low", "light", "glass", "rib", "pier"))
    cz = TOWER_Z
    rubble = ("minecraft:cobbled_deepslate", "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_bricks")

    def spire(sx: int, sz: int, r: float, top: int, slope: tuple[float, float]) -> None:
        for y in range(G - 1, top + 1):
            rr = r - (y - G) * 0.04
            for x in range(sx - int(r) - 3, sx + int(r) + 4):
                for z in range(sz - int(r) - 3, sz + int(r) + 4):
                    dx, dz = abs(x - sx), abs(z - sz)
                    d = max(dx, dz, (dx + dz) / math.sqrt(2))
                    if d > rr or y > top - 14 + (x - sx) * slope[0] + (z - sz) * slope[1]:
                        continue
                    g.at(wall if d > rr - 1.5 else low, x, y, z)
        for a in range(0, 360, 45):                                             # clasping buttresses up the corners, pinnacles on them
            ang = math.radians(a + 22.5)
            bx, bz = round(sx + (r + 0.5) * math.cos(ang)), round(sz + (r + 0.5) * math.sin(ang))
            h = top - 18 - (a // 45) % 3 * 4
            g.box(pier, bx - 1, G - 1, bz - 1, bx + 1, h, bz + 1)
            g.box(trim, bx - 1, h + 1, bz - 1, bx + 1, h + 1, bz + 1)
            g.box(pier, bx, h + 2, bz, bx, h + 5, bz)
            g.at(accent, bx, h + 6, bz)

    spire(cx, cz, 11, G + 72, (1.4, 0.3))
    for y in range(G + 8, G + 50, 14):                                          # lancets on four faces, lit from inside
        for x, z, bx, bz, along_x in ((cx, cz + 11, cx, cz + 10, True), (cx, cz - 11, cx, cz - 10, True),
                                      (cx + 11, cz, cx + 10, cz, False), (cx - 11, cz, cx - 10, cz, False)):
            for yy in range(y, y + 11):
                w = 1 if yy < y + 9 else 0
                for k in range(-w, w + 1):
                    px, pz = (x + k, z) if along_x else (x, z + k)
                    qx, qz = (bx + k, bz) if along_x else (bx, bz + k)
                    g.at(rib if (yy - y) % 4 == 3 else glass, px, yy, pz)
                    g.at(light, qx, yy, qz)
    rose_y = G + 56
    for x in range(cx - 6, cx + 7):                                             # a rose high on the south face
        for y in range(rose_y - 6, rose_y + 7):
            d = math.hypot(x - cx, y - rose_y)
            if d <= 5.5 and g.grid[x, y, cz + 10] != 0:
                a = math.degrees(math.atan2(y - rose_y, x - cx)) % 30
                g.at(trim if d > 4.5 or d < 1.3 else rib if a < 5 else glass, x, y, cz + 11)
                g.at(light, x, y, cz + 10)
    for a in (45, 135, 225, 315):                                               # flying buttresses: piers at the corners, arcs to the wall
        ang = math.radians(a)
        px, pz = cx + 17 * math.cos(ang), cz + 17 * math.sin(ang)
        g.box(pier, round(px) - 1, G - 1, round(pz) - 1, round(px) + 1, G + 22, round(pz) + 1)
        g.box(accent, round(px), G + 23, round(pz), round(px), G + 25, round(pz))
        wx, wz = cx + 10 * math.cos(ang), cz + 10 * math.sin(ang)
        tube(g, wall, bezier((px, G + 21, pz), ((px + wx) / 2, G + 34, (pz + wz) / 2), (wx, G + 38, wz)), 1.0)
    spire(cx - 22, cz + 4, 4, G + 34, (0.6, 0.0))                              # two smaller ruined spires
    spire(cx + 22, cz - 3, 4, G + 42, (-0.8, 0.2))
    for i in range(7):                                                          # the broken top's shards, rising in a spiral
        a = math.radians(i * 52)
        r = 4 + i * 0.8
        ox, oy, oz = cx + r * math.cos(a), G + 74 + i * 4, cz + r * math.sin(a)
        size = 3.2 - i * 0.25
        blob(g, lambda x, y, z: (wall if (x + y + z) % 3 else accent), ox, oy, oz, size, size * 1.4, size * 0.8, only_air=False)
        g.at(light, round(ox), round(oy - size * 1.4) + 1, round(oz))
    rnd = random.Random(7)
    for _ in range(26):                                                         # rubble at its foot
        a, d = rnd.uniform(0, 2 * math.pi), rnd.uniform(13, 24)
        blob(g, lambda x, y, z: rubble[(x + z) % 3], cx + d * math.cos(a), G - 0.5, cz + d * math.sin(a), rnd.uniform(1.2, 2.2), 1.2)


def rootvale_tree(g: Grid, cx: int) -> None:
    t = "rootvale"
    bark, heart = "minecraft:oak_wood", "minecraft:stripped_oak_wood"
    glow, gold = role(t, "light"), role(t, "accent")
    cz = TOWER_Z
    for y in range(G - 1, G + 54):                                              # the trunk, flaring at the foot, streaked
        r = 8 + max(0, 10 - (y - G)) * 0.7 - (y - G) * 0.03
        for x in range(cx - 16, cx + 17):
            for z in range(cz - 16, cz + 17):
                d = math.hypot(x - cx, z - cz)
                if d <= r:
                    a = int(math.degrees(math.atan2(z - cz, x - cx)) + 360) // 12
                    g.at(heart if d <= r - 1.5 else bark if a % 3 else heart, x, y, z)
    for k in range(9):                                                          # buttress roots arching out of the ground
        a = math.radians(k * 40 + 10)
        tube(g, bark, bezier((cx + 7 * math.cos(a), G + 10, cz + 7 * math.sin(a)), (cx + 16 * math.cos(a), G + 8, cz + 16 * math.sin(a)),
                             (cx + 24 * math.cos(a), G - 2, cz + 24 * math.sin(a)), 30), 1.8)
    ends = []
    for tier, (y0, n, reach, rise) in enumerate(((G + 34, 6, 20, 10), (G + 46, 5, 15, 12))):
        for k in range(n):                                                      # branches, curving up
            a = math.radians(k * 360 / n + tier * 30 + 10)
            p2 = (cx + reach * math.cos(a), y0 + rise, cz + reach * math.sin(a))
            tube(g, bark, bezier((cx + 4 * math.cos(a), y0, cz + 4 * math.sin(a)),
                                 (cx + reach * 0.6 * math.cos(a), y0 + rise * 0.3, cz + reach * 0.6 * math.sin(a)), p2, 24), 1.6 - tier * 0.4)
            ends.append(p2)
    ends.append((cx, G + 64, cz))

    def leaf(x: int, y: int, z: int) -> str:
        if (x * 7 + y * 3 + z * 5) % 41 == 0:
            return glow
        return LEAVES[3] if (x + y * 2 + z) % 11 == 0 else LEAVES[2] if (x * 3 + y + z * 5) % 4 == 0 else LEAVES[1]

    for i, (x, y, z) in enumerate(ends):                                        # the crown: leafy lobes at the branch ends
        rad = 10 if i == len(ends) - 1 else 8
        blob(g, leaf, x, y + 2, z, rad, rad * 0.62)
        for k in range(4):                                                      # lights hanging under each lobe, from its underside
            a = math.radians(k * 90 + i * 20)
            hx, hz = round(x + rad * 0.55 * math.cos(a)), round(z + rad * 0.55 * math.sin(a))
            under = round(y + 2 - rad * 0.62) - 2
            while under < G + 80 and g.grid[hx, under + 1, hz] == 0:
                under += 1
            low = under - 3 - k % 2 * 2
            g.box(CHAIN, hx, low + 1, hz, hx, under, hz)
            g.at(glow, hx, low, hz)
    for y in range(G + 8, G + 50, 5):                                           # knots of light set in the trunk's face
        a = math.radians((y * 47) % 360)
        r = 8 + max(0, 10 - (y - G)) * 0.7 - (y - G) * 0.03 - 0.6
        g.at(glow if y % 2 else gold, round(cx + r * math.cos(a)), y, round(cz + r * math.sin(a)))
    for x in range(cx - 3, cx + 4):                                             # a glowing hollow at the foot, facing the gate
        for y in range(G, G + 8):
            if abs(x - cx) <= 3 - max(0, y - G - 4):
                g.at(gold if (x + y) % 2 else glow, x, y, cz + 9)


def quarter_of(ang: float) -> str:
    """The tower whose quarter an angle (degrees, 0 east, 90 south) lies in."""
    return {0: "tideforge", 1: "duskvale", 2: "rootvale", 3: "neutral"}[int(((ang + 45) % 360) // 90)]


def curtain_wall(g: Grid) -> None:
    """A curtain wall round the island's rim, in plain stone: a battered plinth, the wall, a corbelled walk with merlons; on its inner
    face buttresses and lit arrow slits, and banners in the colours of the quarter it stands in; round bastions every 30 degrees,
    taller than the wall, with lit windows and conical caps in that quarter's accent."""
    _, _, r, ang = plan()
    stone = ("minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks")
    band = (r >= WALL_R[0]) & (r < WALL_R[1])
    for x, z in np.argwhere(band):
        x, z = int(x), int(z)
        rv = r[x, z]
        for y in range(G - 1, G + WALL_H + 1):
            if y < G + 2 and rv < WALL_R[0] + 1:
                continue
            g.at(stone[(x * 7 + y * 3 + z * 5) % 4], x, y, z)
        g.at("minecraft:chiseled_stone_bricks", x, G + WALL_H + 1, z) if rv < WALL_R[0] + 1 else None
        if rv > WALL_R[1] - 1.2 or rv < WALL_R[0] + 1.0:
            if int(ang[x, z] * 3) % 2 == 0:
                g.box(stone[0], x, G + WALL_H + 1, z, x, G + WALL_H + 2, z)          # merlons on both edges of the walk
    for x, z in np.argwhere((r >= WALL_R[0] - 1) & (r < WALL_R[0])):               # the inner face's plinth and string course
        x, z = int(x), int(z)
        g.box("minecraft:polished_andesite", x, G - 1, z, x, G + 1, z)
        g.at("minecraft:stone_brick_slab[type=bottom]", x, G + 2, z)
    for k in range(0, 360, 6):                                                      # buttresses, slits and banners round the inner face
        a = math.radians(k)
        bx, bz = round(C + (WALL_R[0] - 1.5) * math.cos(a)), round(C + (WALL_R[0] - 1.5) * math.sin(a))
        if k % 30 == 15:
            continue                                                                # a bastion stands here
        if k % 12 == 0:
            g.box("minecraft:stone_bricks", bx, G - 1, bz, bx, G + WALL_H - 2, bz)
            g.at("minecraft:chiseled_stone_bricks", bx, G + WALL_H - 1, bz)
        else:
            sx, sz = round(C + (WALL_R[0] + 0.2) * math.cos(a)), round(C + (WALL_R[0] + 0.2) * math.sin(a))
            tower = quarter_of(k)
            if k % 12 == 6:
                g.box(role(tower, "hanging"), sx, G + 6, sz, sx, G + WALL_H - 3, sz)
                g.at(role(tower, "accent"), sx, G + WALL_H - 2, sz)
            else:
                g.box("minecraft:sea_lantern", sx, G + 9, sz, sx, G + 11, sz)
    for x, z in np.argwhere((r >= WALL_R[1]) & (r < WALL_R[1] + 1.4)):          # the outer face: a corbel table under the walk, whole
        x, z = int(x), int(z)
        g.at("minecraft:chiseled_stone_bricks" if int(ang[x, z] / 2) % 2 else "minecraft:stone_bricks", x, G + WALL_H - 1, z)
    for k in range(0, 360, 4):                                                      # buttresses and lit slits round the outer face
        a = math.radians(k)
        if k % 12 == 0 and k % 30 != 15:
            for rr in (WALL_R[1] + 0.3, WALL_R[1] + 1.0):
                bx_, bz_ = round(C + rr * math.cos(a)), round(C + rr * math.sin(a))
                for y in range(G - 1, G + WALL_H - 3):
                    if rr < WALL_R[1] + 0.8 or y < G + WALL_H - 8:
                        g.at("minecraft:stone_bricks" if (y - G) % 5 else "minecraft:polished_andesite", bx_, y, bz_)
        elif k % 12 == 6:
            sx_, sz_ = round(C + (WALL_R[1] - 0.2) * math.cos(a)), round(C + (WALL_R[1] - 0.2) * math.sin(a))
            g.box("minecraft:sea_lantern", sx_, G + 8, sz_, sx_, G + 10, sz_)               # lit slits on the outside too
    for k in range(3, 360, 6):                                                      # lanterns standing on the inner merlons
        a = math.radians(k)
        lx, lz = round(C + (WALL_R[0] + 0.5) * math.cos(a)), round(C + (WALL_R[0] + 0.5) * math.sin(a))
        if g.grid[lx, G + WALL_H + 2, lz] != 0:
            g.at("minecraft:lantern", lx, G + WALL_H + 3, lz)
    for k in range(15, 360, 30):                                                    # bastions
        a = math.radians(k)
        bx, bz = C + (WALL_R[0] + 2) * math.cos(a), C + (WALL_R[0] + 2) * math.sin(a)
        tower = quarter_of(k)
        accent, light = role(tower, "accent"), role(tower, "light")
        for y in range(G - 1, G + WALL_H + 9):
            rr = 5.5 if y < G + 3 else 5
            disc(g, stone[(y // 3) % 4] if y % 6 else "minecraft:chiseled_stone_bricks", bx, y, bz, rr)
        for y in range(G + 4, G + WALL_H + 6, 7):                                   # lit windows facing the island
            wx, wz = round(bx - 4.4 * math.cos(a)), round(bz - 4.4 * math.sin(a))      # set in the bastion's face, lit from inside
            if math.hypot(wx - bx, wz - bz) <= 5:
                g.box("minecraft:glass", wx, y, wz, wx, y + 2, wz)
                ix, iz = round(bx - 3.2 * math.cos(a)), round(bz - 3.2 * math.sin(a))
                g.box(light, ix, y, iz, ix, y + 2, iz)
        ring(g, "minecraft:stone_bricks", bx, G + WALL_H + 9, bz, 4.5, 6.2)
        for h in range(0, 10):                                                      # a conical cap in the quarter's accent, a light under it
            disc(g, accent if h % 3 else role(tower, "trim"), bx, G + WALL_H + 10 + h, bz, 5.6 * (1 - h / 10) + 0.3)
        g.box(light, round(bx), G + WALL_H + 20, round(bz), round(bx), G + WALL_H + 21, round(bz))
        fx, fz = round(bx), round(bz)                                               # a flag on a pole above the cap, in the quarter's colour
        g.box("minecraft:stone_brick_wall", fx, G + WALL_H + 22, fz, fx, G + WALL_H + 28, fz)
        tx, tz = (1, 0) if abs(math.sin(a)) > abs(math.cos(a)) else (0, 1)
        for s in range(1, 5):
            g.box(role(tower, "hanging"), fx + tx * s, G + WALL_H + 25, fz + tz * s, fx + tx * s, G + WALL_H + 28 - (1 if s == 4 else 0), fz + tz * s)
        g.at(accent, fx, G + WALL_H + 29, fz)


def big_tree(g: Grid, tower: str, x: int, z: int, rnd: random.Random) -> None:
    """A large tree for the background, in the quarter's kind: a broad oak (Neutral), a cluster of palms (Tideforge), a gnarled dark oak
    with lanterns hung in it (Duskvale), a great flowering azalea (Rootvale)."""
    if tower == "neutral":
        tube(g, "minecraft:oak_wood", [(x, G - 1, z), (x, G + 14, z)], 1.6)
        ends = []
        for k in range(5):
            a = math.radians(k * 72 + rnd.uniform(-15, 15))
            p2 = (x + 8 * math.cos(a), G + 20 + rnd.randint(-2, 2), z + 8 * math.sin(a))
            tube(g, "minecraft:oak_wood", bezier((x, G + 12, z), (x + 3 * math.cos(a), G + 17, z + 3 * math.sin(a)), p2, 16), 0.9)
            ends.append(p2)
        for ex, ey, ez in ends + [(x, G + 24, z)]:
            blob(g, LEAVES[0], ex, ey + 1, ez, 5.5, 3.6)
    elif tower == "tideforge":
        for k in range(3):                                                          # three palms leaning apart
            a = math.radians(k * 120 + rnd.uniform(0, 40))
            h = rnd.randint(16, 22)
            top = (x + 5 * math.cos(a), G + h, z + 5 * math.sin(a))
            tube(g, "minecraft:jungle_wood", bezier((x + math.cos(a), G - 1, z + math.sin(a)), (x + 1.5 * math.cos(a), G + h * 0.6, z + 1.5 * math.sin(a)), top, 20), 0.7)
            for f in range(7):                                                      # fronds drooping out from the crown
                b = math.radians(f * 51 + k * 17)
                tube(g, "minecraft:jungle_leaves[persistent=true]", bezier(top, (top[0] + 3.5 * math.cos(b), top[1] + 2, top[2] + 3.5 * math.sin(b)),
                                                                    (top[0] + 6.5 * math.cos(b), top[1] - 2.5, top[2] + 6.5 * math.sin(b)), 14), 0.6)
            g.at("minecraft:sea_lantern", round(top[0]), round(top[1]) - 1, round(top[2]))
    elif tower == "duskvale":
        tube(g, "minecraft:dark_oak_wood", [(x, G - 1, z), (x + 1, G + 12, z)], 1.8)
        for k in range(6):                                                          # gnarled limbs, sparse dark leaves, lanterns hung
            a = math.radians(k * 60 + rnd.uniform(-20, 20))
            mid = (x + 4 * math.cos(a), G + 15 + k % 2 * 3, z + 4 * math.sin(a))
            end = (x + 10 * math.cos(a), G + 13 + rnd.randint(0, 8), z + 10 * math.sin(a))
            tube(g, "minecraft:dark_oak_wood", bezier((x, G + 10, z), mid, end, 18), 0.8)
            blob(g, DARK_LEAVES, end[0], end[1] + 1, end[2], 3.2, 2)
            lx, ly, lz = round((mid[0] + end[0]) / 2), round((mid[1] + end[1]) / 2) - 2, round((mid[2] + end[2]) / 2)
            while g.grid[lx, ly + 1, lz] == 0 and ly < G + 30:
                ly += 1
            g.box(CHAIN, lx, ly - 2, lz, lx, ly, lz)
            g.at("minecraft:pearlescent_froglight", lx, ly - 3, lz)
    else:
        tube(g, "minecraft:oak_wood", [(x, G - 1, z), (x, G + 12, z)], 1.5)
        for k in range(4):
            a = math.radians(k * 90 + 45)
            p2 = (x + 7 * math.cos(a), G + 17, z + 7 * math.sin(a))
            tube(g, "minecraft:oak_wood", bezier((x, G + 10, z), (x + 2 * math.cos(a), G + 15, z + 2 * math.sin(a)), p2, 14), 0.8)
            blob(g, lambda a_, b_, c_: LEAVES[3] if (a_ + b_ + c_) % 3 == 0 else LEAVES[2], p2[0], p2[1] + 2, p2[2], 5.5, 3.8)
        blob(g, lambda a_, b_, c_: LEAVES[3] if (a_ * 3 + c_) % 4 == 0 else LEAVES[2], x, G + 23, z, 6.5, 4)
        for k in range(6):
            a = math.radians(k * 60)
            lx, lz = round(x + 5 * math.cos(a)), round(z + 5 * math.sin(a))
            ly = G + 12
            while g.grid[lx, ly + 1, lz] == 0 and ly < G + 30:
                ly += 1
            g.box(CHAIN, lx, ly - 2, lz, lx, ly, lz)
            g.at("minecraft:shroomlight", lx, ly - 3, lz)


def big_tree_spots() -> list[tuple[str, int, int]]:
    """Two large trees in the outer part of each quarter, either side of its tower, between the tower and the wall."""
    out = []
    for tower, (ox, oz), _ in BAYS:
        base = math.degrees(math.atan2(oz, ox))
        for side in (-30, 30):
            a = math.radians(base + side)
            out.append((tower, round(C + 82 * math.cos(a)), round(C + 82 * math.sin(a))))
    return out


TOWERS = {"neutral": neutral_spire, "tideforge": tideforge_temple, "duskvale": duskvale_ruin, "rootvale": rootvale_tree}


# ---- the gardens: each tower's quarter of the island, with height ------------------------------------------------------------------------

def margin(out: tuple[int, int]) -> np.ndarray:
    """How far inside the tower's quarter a cell is (negative: past the diagonal into the next one)."""
    xs, zs, _, _ = plan()
    ox, oz = out
    return (xs - C) * ox + (zs - C) * oz - np.abs((xs - C) * -oz + (zs - C) * ox)


def sector(out: tuple[int, int], slack: int = 0) -> np.ndarray:
    _, _, r, _ = plan()
    return (r > PLAZA + 1) & (r < WALL_R[0] - 3) & (margin(out) > 1 - slack)


BLEND = 7                               # the gardens' grounds dither into each other this far either side of a diagonal


def ground_cells(free_wide: np.ndarray, out: tuple[int, int], rnd: random.Random) -> list[tuple[int, int]]:
    """Where this garden lays its ground: all of its quarter, and past the diagonal less and less, so two grounds mix at the seam."""
    m = margin(out)
    cells = []
    for x, z in np.argwhere(free_wide):
        x, z = int(x), int(z)
        if m[x, z] >= BLEND or rnd.random() < 0.5 + m[x, z] / (2 * BLEND):
            cells.append((x, z))
    return cells


def spots(rnd: random.Random, free: np.ndarray, n: int, clear: int, taken: list) -> list[tuple[int, int]]:
    """Up to n places in `free` with `clear` blocks of room round each, apart from each other and from `taken`."""
    cells = [tuple(map(int, c)) for c in np.argwhere(free)]
    rnd.shuffle(cells)
    out = []
    for x, z in cells:
        if len(out) >= n:
            break
        if not free[max(0, x - clear):x + clear + 1, max(0, z - clear):z + clear + 1].all():
            continue
        if all(math.hypot(x - a, z - b) > clear * 2 + 2 for a, b in out + taken):
            out.append((x, z))
    taken.extend(out)
    return out


def rock_pool(g: Grid, x: int, z: int, r: float, rnd: random.Random) -> None:
    for xx in range(int(x - r - 2), int(x + r + 3)):
        for zz in range(int(z - r - 2), int(z + r + 3)):
            d = math.hypot(xx - x, zz - z)
            if d <= r:
                g.box(WATER, xx, G - 3, zz, xx, G - 1, zz)
                g.at(PRISMARINE, xx, G - 4, zz)
                roll = rnd.random()
                if roll < 0.18:
                    g.at("minecraft:kelp_plant", xx, G - 3, zz)
                    g.at("minecraft:kelp[age=25]", xx, G - 2, zz)
                elif roll < 0.28:
                    g.at("minecraft:sea_pickle[pickles=4,waterlogged=true]", xx, G - 3, zz)
            elif d <= r + 1.3:
                g.at(rnd.choice(CORALS + (PRISMARINE,)), xx, G - 1, zz)


def garden(g: Grid, tower: str, free: np.ndarray, wide: np.ndarray, out: tuple[int, int], rnd: random.Random) -> None:
    taken: list = []
    cells = ground_cells(wide, out, rnd)
    if tower == "neutral":
        flowers = ("minecraft:poppy", "minecraft:oxeye_daisy", "minecraft:red_tulip", "minecraft:lily_of_the_valley")
        for x, z in cells:                                                  # a formal parterre: beds hedged round, gravel walks between
            a, b = (x - C) % 11, (z - C) % 11
            if not free[x, z]:
                continue
            if a == 0 or b == 0:
                g.at("minecraft:gravel", x, G - 1, z)
            elif a in (1, 10) or b in (1, 10):
                g.at(LEAVES[0], x, G, z)                                    # the bed's clipped hedge, knee high
            elif (a + b) % 2 == 0:
                g.at(flowers[((x - C) // 11 + (z - C) // 11) % 4], x, G, z)
        for x, z in spots(rnd, free, 9, 3, taken):                          # topiary: cones, and stacked spheres on clipped trunks
            g.box("minecraft:oak_log[axis=y]", x, G, z, x, G + 2, z)
            if rnd.random() < 0.5:
                for y in range(G + 1, G + 11):
                    disc(g, LEAVES[0], x, y, z, 3.2 * (1 - (y - G - 1) / 10), only_air=True)
            else:
                g.box("minecraft:oak_log[axis=y]", x, G + 3, z, x, G + 8, z)
                blob(g, LEAVES[0], x, G + 5, z, 2.8, 2.8)
                blob(g, LEAVES[0], x, G + 10, z, 1.8, 1.8)
        for x, z in spots(rnd, free, 4, 2, taken):                          # gold-tipped obelisks
            g.box(QUARTZ, x - 1, G, z - 1, x + 1, G + 1, z + 1)
            g.box(QPILLAR, x, G + 2, z, x, G + 15, z)
            g.at(CHISELED, x, G + 16, z)
            g.box(GOLD, x, G + 17, z, x, G + 18, z)
        for x, z in spots(rnd, free, 3, 5, taken):                          # pergolas: four columns, beams, leaves over them, a lantern
            for px in (x - 4, x + 4):
                for pz in (z - 4, z + 4):
                    g.box(QPILLAR, px, G, pz, px, G + 5, pz)
            g.box(CHISELED, x - 5, G + 6, z - 4, x + 5, G + 6, z - 4)
            g.box(CHISELED, x - 5, G + 6, z + 4, x + 5, G + 6, z + 4)
            for bx in range(x - 4, x + 5, 2):
                g.box("minecraft:quartz_slab[type=bottom]", bx, G + 7, z - 4, bx, G + 7, z + 4)
                for bz in range(z - 4, z + 5):
                    if rnd.random() < 0.45:
                        g.at(LEAVES[2] if rnd.random() < 0.3 else LEAVES[0], bx, G + 8, bz)
            g.at(GOLD, x, G - 1, z)
            g.box(CHAIN, x, G + 5, z, x, G + 6, z)
            g.at(LANTERN, x, G + 4, z)
    elif tower == "tideforge":
        for x, z in cells:                                                  # sand
            g.at("minecraft:sand", x, G - 1, z)
            g.at("minecraft:sandstone", x, G - 2, z)
        for x, z in spots(rnd, free, 5, 5, taken):                          # rock pools
            rock_pool(g, x, z, 3.8, rnd)
        for x, z in spots(rnd, free, 7, 2, taken):                          # coral spires
            h = rnd.randint(7, 14)
            for y in range(G, G + h):
                disc(g, CORALS[(y + x) % 5], x, y, z, 1.6 * (1 - (y - G) / h) + 0.4)
            g.at("minecraft:sea_lantern", x, G + h, z)
        for x, z in spots(rnd, free, 4, 2, taken):                          # aquarium pillars: glass round water, kelp up the middle
            h = rnd.randint(9, 13)
            g.box("minecraft:dark_prismarine", x - 2, G - 1, z - 2, x + 2, G, z + 2)
            g.box("minecraft:glass", x - 1, G + 1, z - 1, x + 1, G + h, z + 1)
            g.box(WATER, x, G + 1, z, x, G + h - 1, z)
            g.box("minecraft:kelp_plant", x, G + 1, z, x, G + h - 3, z)
            g.at("minecraft:kelp[age=25]", x, G + h - 2, z)
            g.box("minecraft:dark_prismarine", x - 2, G + h + 1, z - 2, x + 2, G + h + 1, z + 2)
            g.at("minecraft:sea_lantern", x, G + h + 2, z)
        for x, z in spots(rnd, free, 1, 8, taken):                          # a rock with a waterfall into a pool at its foot
            blob(g, lambda a, b, c: PRISMARINE if (a + b + c) % 3 else "minecraft:dark_prismarine", x, G + 4, z, 4.5, 9, 4.5, only_air=False)
            rock_pool(g, x, z + 8, 3.2, rnd)
            g.box(PRISMARINE, x - 1, G + 9, z + 4, x + 1, G + 10, z + 4)     # a basin on the rock's face: back, sides, lip
            g.at(PRISMARINE, x - 1, G + 9, z + 5)
            g.at(PRISMARINE, x + 1, G + 9, z + 5)
            g.at(PRISMARINE, x, G + 9, z + 6)
            g.at(WATER, x, G + 9, z + 5)
            g.box(FALLING, x, G, z + 5, x, G + 8, z + 5)                     # and the fall from it into the pool
        for x, z in spots(rnd, free, 2, 5, taken):                          # prismarine arches with a light hung in them
            pointed_arch(g, "minecraft:prismarine_bricks", x - 4, z, x + 4, z, 11)
            g.at(CHAIN, x, G + 9, z)
            g.at("minecraft:sea_lantern", x, G + 8, z)
    elif tower == "duskvale":
        for x, z in cells:
            g.at("minecraft:deepslate_tiles" if (x * 5 + z * 3) % 6 == 0 else "minecraft:podzol" if (x + z) % 3 else "minecraft:moss_block",
                 x, G - 1, z)
            if rnd.random() < 0.025:
                g.at(rnd.choice(("minecraft:lily_of_the_valley", "minecraft:azure_bluet")), x, G, z)
        for x, z in spots(rnd, free, 3, 6, taken):                          # ruined pointed arches, a pale light hung in each
            if rnd.random() < 0.5:
                pointed_arch(g, "minecraft:deepslate_bricks", x - 5, z, x + 5, z, 15, thick=2)
            else:
                pointed_arch(g, "minecraft:deepslate_bricks", x, z - 5, x, z + 5, 15, thick=2)
            g.box(CHAIN, x, G + 9, z, x, G + 13, z)
            g.at("minecraft:pearlescent_froglight", x, G + 8, z)
        for x, z in spots(rnd, free, 5, 3, taken):                          # dark trees
            h = rnd.randint(7, 11)
            g.box("minecraft:dark_oak_log[axis=y]", x, G, z, x, G + h, z)
            for k in range(3):
                a = math.radians(k * 120 + x)
                tube(g, "minecraft:dark_oak_wood", [(x, G + h - 3, z), (x + 3 * math.cos(a), G + h, z + 3 * math.sin(a))], 0.5)
            blob(g, DARK_LEAVES, x, G + h + 1, z, 3.5, 2.2)
        for x, z in spots(rnd, free, 5, 2, taken):                          # amethyst crystal spires on tuff
            blob(g, "minecraft:tuff", x, G - 0.5, z, 2.2, 1.4, only_air=False)
            h = rnd.randint(5, 10)
            for y in range(G, G + h):
                g.at("minecraft:amethyst_block", x, y, z)
                if y < G + h - 3:
                    for ox, oz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        if (y + ox + oz) % 3 == 0:
                            g.at("minecraft:amethyst_block", x + ox, y, z + oz)
            g.at("minecraft:amethyst_cluster[facing=up]", x, G + h, z)
        for x, z in spots(rnd, free, 4, 2, taken):                          # pale lights on tall posts
            lamp(g, x, z, 8, "minecraft:polished_deepslate_wall", "minecraft:pearlescent_froglight")
    else:
        for x, z in cells:
            g.at("minecraft:moss_block" if (x * 3 + z) % 9 else "minecraft:rooted_dirt", x, G - 1, z)
            roll = rnd.random()
            if roll < 0.04:
                g.at(rnd.choice(("minecraft:azalea", "minecraft:flowering_azalea")), x, G, z)
            elif roll < 0.09:
                g.at(rnd.choice(("minecraft:dandelion", "minecraft:cornflower", "minecraft:moss_carpet", "minecraft:allium")), x, G, z)
        for x, z in spots(rnd, free, 6, 6, taken):                          # giant mushrooms, far enough apart that no cap cuts another
            h = rnd.randint(8, 15)
            cap = rnd.randint(4, 5)
            red = rnd.random() < 0.6
            if red:
                for dx in range(-cap - 1, cap + 2):                         # a red dome, a thick shell
                    for dy in range(0, cap + 1):
                        for dz in range(-cap - 1, cap + 2):
                            e = math.sqrt((dx * dx + dz * dz) / (cap + 0.5) ** 2 + (dy / (cap * 0.7 + 0.5)) ** 2)
                            if 0.6 <= e <= 1.0:                     # thick enough that each layer meets the next
                                g.at("minecraft:shroomlight" if e < 0.8 and (dx + dz) % 4 == 0 else "minecraft:red_mushroom_block",
                                     x + dx, G + h + dy, z + dz)
                g.box("minecraft:mushroom_stem", x, G, z, x, G + h + int(cap * 0.7), z)        # the stem last, up into the crown
            else:
                disc(g, "minecraft:brown_mushroom_block", x, G + h + 1, z, cap + 1.5)
                g.box("minecraft:mushroom_stem", x, G, z, x, G + h, z)
                for k in range(4):
                    a = math.radians(k * 90 + 45)
                    g.at("minecraft:shroomlight", round(x + 2 * math.cos(a)), G + h, round(z + 2 * math.sin(a)))
        for x, z in spots(rnd, free, 4, 4, taken):                          # flowering trees
            h = rnd.randint(7, 10)
            g.box("minecraft:oak_log[axis=y]", x, G, z, x, G + h, z)
            blob(g, lambda a, b, c: LEAVES[3] if (a + b + c) % 4 == 0 else LEAVES[2] if (a + c) % 3 == 0 else LEAVES[1], x, G + h + 1, z, 4.2, 3)
            g.at("minecraft:shroomlight", x, G + h - 1, z + 1)
        for x, z in spots(rnd, free, 3, 6, taken):                          # root arches, a light hung under each
            a = rnd.uniform(0, math.pi)
            tube(g, "minecraft:dark_oak_wood", bezier((x - 5 * math.cos(a), G - 1, z - 5 * math.sin(a)), (x, G + 12, z),
                                                      (x + 5 * math.cos(a), G - 1, z + 5 * math.sin(a)), 30), 1.1)
            top = G + 4
            while g.grid[x, top + 1, z] == 0:
                top += 1
            g.box(CHAIN, x, G + 4, z, x, top, z)
            g.at("minecraft:shroomlight", x, G + 3, z)


def pylon(g: Grid, x: int, z: int, a: str, b: str) -> None:
    """Where two gardens meet: a tall pylon in both towers' stone, both accents banded up it, both lights at the top."""
    g.box(KERB, x - 3, G - 1, z - 3, x + 3, G, z + 3)
    g.box(role(a, "trim"), x - 2, G + 1, z - 2, x + 2, G + 2, z + 2)
    for y in range(G + 3, G + 24):
        g.box(role(a, "wall") if (y // 4) % 2 else role(b, "wall"), x - 1, y, z - 1, x + 1, y, z + 1)
    for y in (G + 9, G + 17):
        g.box(role(a, "accent"), x - 1, y, z - 1, x + 1, y, z + 1)
        g.box(role(b, "accent"), x - 1, y + 1, z - 1, x + 1, y + 1, z + 1)
    g.box(role(b, "trim"), x - 2, G + 24, z - 2, x + 2, G + 24, z + 2)
    g.box(role(a, "light"), x - 1, G + 25, z - 1, x, G + 26, z)
    g.box(role(b, "light"), x, G + 25, z, x + 1, G + 26, z + 1)
    g.box(role(a, "accent"), x, G + 27, z, x, G + 29, z)


# ---- the bays, turned to face the plaza --------------------------------------------------------------------------------------------------

def bay_grid(g: Grid, tower: str, veil: str | None) -> np.ndarray:
    sub = Grid({}, (BAY_W, SY, BAY_D))
    sub.palette, sub.index = g.palette, g.index
    TOWERS[tower](sub, BAY_X)
    bay(sub, tower, BAY_X, veil)
    return sub.grid


def turned(arr: np.ndarray, out: tuple[int, int], g: Grid) -> tuple[np.ndarray, tuple[int, int]]:
    """The bay turned so its gate faces the plaza from direction `out`, and where the middle of the gate's face lands in it."""
    face = np.zeros(arr.shape[::2], dtype=bool)
    back = np.zeros_like(face)
    face[BAY_X, WALL_Z[1]] = True
    back[BAY_X, 0] = True
    for k in range(4):
        f_, b_ = np.rot90(face, k), np.rot90(back, k)
        fx, fz = np.argwhere(f_)[0]
        bx, bz = np.argwhere(b_)[0]
        if np.sign(bx - fx) == out[0] and np.sign(bz - fz) == out[1]:      # the tower lies out from the gate, away from the plaza
            arr = np.rot90(arr, k, axes=(0, 2)).copy()
            if k % 2:                                                       # logs lying along x now lie along z, and the other way
                swap = {}
                for i, name in enumerate(list(g.palette)):
                    if "axis=x" in name or "axis=z" in name:
                        swap[i] = g.id(name.replace("axis=x", "axis=#").replace("axis=z", "axis=x").replace("axis=#", "axis=z"))
                src = arr.copy()
                for i, j in swap.items():
                    arr[src == i] = j
            return arr, (int(fx), int(fz))
    raise ValueError(out)


EMITS = {"sea_lantern": 15, "shroomlight": 15, "ochre_froglight": 15, "verdant_froglight": 15, "pearlescent_froglight": 15, "lantern": 15,
         "glowstone": 15, "end_rod": 14, "amethyst_cluster": 5, "sea_pickle": 15}
SEE_LIGHT = ("air", "glass", "leaves", "water", "chain", "lantern", "_wall", "fence", "bars", "pane", "kelp", "seagrass", "coral_fan", "pickle",
             "light", "flower", "potted", "poppy", "daisy", "tulip", "lily", "bluet", "dandelion", "cornflower", "allium", "azalea", "carpet",
             "short_grass", "cluster", "end_rod", "slab")
NOT_SPAWN_ON = ("leaves", "glass", "slab", "_wall", "fence", "bars", "water", "lantern", "chain", "light", "pot", "carpet", "cluster", "end_rod",
                "kelp", "pickle", "coral_fan", "azalea", "flower", "poppy", "daisy", "tulip", "lily", "bluet", "dandelion", "cornflower", "allium",
                "short_grass")


def block_light(g: Grid) -> np.ndarray:
    """Block light (no sky), spread fifteen steps through everything light passes."""
    names = [n.split("[")[0].removeprefix("minecraft:") for n in g.palette]
    emit = np.array([EMITS.get(n, 0) if not n.startswith("light") else int(g.palette[i].split("level=")[1].rstrip("]"))
                     if "level=" in g.palette[i] else 0 for i, n in enumerate(names)], dtype=np.int16)
    passes = np.array([n == "air" or any(k in n for k in SEE_LIGHT) for n in names])
    light = emit[g.grid]
    open_ = passes[g.grid]
    for _ in range(15):
        spread = light.copy()
        for axis in range(3):
            for d in (1, -1):
                s = np.roll(light, d, axis=axis) - 1
                spread = np.maximum(spread, np.where(open_, s, 0))
        if np.array_equal(spread, light):
            break
        light = spread
    return light


def dark_ground(g: Grid) -> np.ndarray:
    """Where a hostile mob could spawn: an open cell with headroom over a full block, inside the wall, at block light 0."""
    names = [n.split("[")[0].removeprefix("minecraft:") for n in g.palette]
    passes = np.array([n == "air" or any(k in n for k in SEE_LIGHT) for n in names])
    floor_ok = np.array([n != "air" and not any(k in n for k in NOT_SPAWN_ON) for n in names])
    gr = g.grid
    spawn = passes[gr] & np.roll(passes[gr], -1, axis=1) & np.roll(floor_ok[gr], 1, axis=1)
    spawn[:, 0, :] = False
    spawn[:, -1, :] = False
    _, _, r, _ = plan()
    spawn &= (r < WALL_R[1] + 6)[:, None, :]
    return spawn & (block_light(g) == 0)


def spawn_proof(g: Grid) -> int:
    """Light every dark spawnable cell with invisible light blocks, a few blocks apart, until none is left; returns how many it placed."""
    placed = 0
    for _ in range(8):
        dark = np.argwhere(dark_ground(g))
        if not len(dark):
            break
        taken = []
        for x, y, z in dark:
            if all(max(abs(x - a), abs(y - b), abs(z - c)) > 7 for a, b, c in taken):
                taken.append((x, y, z))
                g.at("minecraft:light[level=13]", int(x), int(y), int(z))
                placed += 1
    return placed


VEIL_RGB = {"neutral": 0xF2C744, "tideforge": 0x3FA9F5, "duskvale": 0xB57BEA, "rootvale": 0x7BD35A}


def bay_frame(out: tuple[int, int]) -> tuple[np.ndarray, np.ndarray, int, int]:
    """Where a bay's own cells land in the hub: (local x, local z) for every cell of the turned bay, and its paste corner."""
    lx, lz = np.meshgrid(np.arange(BAY_W), np.arange(BAY_D), indexing="ij")
    probe = np.zeros((BAY_W, 1, BAY_D), dtype=np.uint16)
    _, (fx, fz) = turned(probe, out, Grid({}, (1, 1, 1)))
    for k in range(4):                                                  # the same quarter turn turned() chose
        rx, rz = np.rot90(lx, k), np.rot90(lz, k)
        face = np.argwhere((rx == BAY_X) & (rz == WALL_Z[1]))
        if len(face) and tuple(face[0]) == (fx, fz):
            return rx, rz, C + out[0] * GATE_R - fx, C + out[1] * GATE_R - fz
    raise ValueError(out)


# ---- the party fixtures (owner, 20 Nov 2026): the warp pad's rim is the party ring; each gate has its Gate Altar --------------------

PARTY_LIT = "minecraft:ochre_froglight"         # the party's own colour, gold: never a tower's
PARTY_UNLIT = "minecraft:polished_deepslate"
RING_R = (4.0, 5.3)                             # the pad's rim, in four arcs (one lights per player standing on the pad)
GATHER = ((-6, 6), (1, 7))                      # across, out from the gate's face: the portal's mouth (a party there is at its gate)
ALTAR_D = 16                                    # the Gate Altar on the path, on the plaza side of the host: the first thing met on the way in
READY_R = (2.6, 3.4)                            # its ready ring in the paving: a member standing inside it is ready


def gate_spot(out: tuple[int, int], a: int, d: int) -> tuple[int, int]:
    """A spot in front of a gate: `d` out from its face toward the plaza, `a` across (positive is on your right, facing the gate)."""
    ox, oz = out
    return C + ox * (GATE_R - d) + (-oz) * a, C + oz * (GATE_R - d) + ox * a


def ring_cells() -> list[tuple[int, int, int]]:
    """The pad's rim: (x, z, arc 0-3), leaving a chiseled gap at each compass point between the arcs."""
    sx, sz = SPAWN
    cells = []
    for x in range(sx - 6, sx + 7):
        for z in range(sz - 6, sz + 7):
            d = math.hypot(x - sx, z - sz)
            if not RING_R[0] < d <= RING_R[1]:
                continue
            a = math.degrees(math.atan2(z - sz, x - sx)) % 360
            if min(abs(((a - k + 180) % 360) - 180) for k in (0, 90, 180, 270)) < 9:
                continue
            cells.append((x, z, int(a // 90)))
    return cells


def altar_spot(g: Grid, tower: str, out: tuple[int, int]) -> None:
    """Where the Gate Altar stands (the mod draws it): a 3 x 3 trim plinth under it, one barrier in its middle (the mod keeps players off the rest),
    and a ring in the tower's inlay round it, the ready ring."""
    x, z = gate_spot(out, 0, ALTAR_D)
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            d = math.hypot(dx, dz)
            if READY_R[0] < d <= READY_R[1]:
                g.at(role(tower, "inlay"), x + dx, G - 1, z + dz)
    g.air(x - 2, G, z - 2, x + 2, G + 4, z + 2)
    g.box(role(tower, "trim"), x - 1, G - 1, z - 1, x + 1, G - 1, z + 1)
    # one barrier in the altar's middle column, which the model closes in on every side so its outline never shows; the mod nudges anyone
    # off the rest of the altar (owner, 25 Nov 2026: the 3 x 3 x 2 barriers' outline showed over the lowered altar)
    g.at("minecraft:barrier", x, G, z)
    # the twin mode boards either side (drawn by the mod): clear their ground (the hedge and benches pass behind), barriers under them
    for side in (-1, 1):
        for a in range(6, 10):
            for d in range(-5, 6):
                bx, bz = gate_spot(out, side * a, ALTAR_D + d)
                g.air(bx, G, bz, bx, G + 5, bz)
                if a in (7, 8) and abs(d) <= 4:
                    g.at(role(tower, "floor"), bx, G - 1, bz)
                # the barriers stand in the column just behind the board's face (owner, 24 Nov 2026): the face (7.44 out) hides their
                # outline, so looking over the board shows none, and a click still lands (it passes through the drawn board)
                if a == 8 and abs(d) <= 4:
                    g.box("minecraft:barrier", bx, G, bz, bx, G + 3, bz)


def party_fixtures(g: Grid) -> None:
    for x, z, _ in ring_cells():
        g.at(PARTY_UNLIT, x, G - 1, z)
    for tower, out, _ in BAYS:
        altar_spot(g, tower, out)


def party_layout(tower: str, out: tuple[int, int]) -> dict:
    (a0, a1), (d0, d1) = GATHER
    corners = [gate_spot(out, a, d) for a in (a0, a1) for d in (d0, d1)]
    xs, zs = [c[0] for c in corners], [c[1] for c in corners]
    x, z = gate_spot(out, 0, ALTAR_D)
    return {
        "gather": {"min": [min(xs), G, min(zs)], "max": [max(xs), G + 3, max(zs)]},
        # the altar's front faces the plaza (away from the gate); yaw turns the model's +z onto that direction
        "altar": {"x": x, "y": G, "z": z, "yaw": round(math.degrees(math.atan2(-out[0], -out[1])), 1), "ready": READY_R[1]},
    }


def to_world(out: tuple[int, int], x: int, y: int, z: int) -> tuple[int, int, int]:
    rx, rz, x0, z0 = bay_frame(out)
    i, j = np.argwhere((rx == x) & (rz == z))[0]
    return int(x0 + i), y, int(z0 + j)


def facing(dx: int, dz: int) -> float:
    """Minecraft's yaw for looking along (dx, dz): 0 is south (+z), 90 west, 180 north, 270 east."""
    return (math.degrees(math.atan2(-dx, dz)) + 360) % 360


def layout() -> dict:
    """What the mod needs to know about the pasted hub, in its own coordinates: each tower's portal (the opening and the step behind it),
    the spot in front to set a walker back on, the host's dais, the veil's colour; and the warp pad."""
    portals = []
    for tower, out, _ in BAYS:
        h = PORTAL_HALF
        corners = [to_world(out, x, 0, z) for x in (BAY_X - h, BAY_X + h) for z in (WALL_Z[0] - 4, WALL_Z[1])]
        xs_, zs_ = [c[0] for c in corners], [c[2] for c in corners]
        inward = facing(-out[0], -out[1])
        back = to_world(out, BAY_X, G, WALL_Z[1] + 6)
        host = (C + out[0] * HOST_S, G + 1, C + out[1] * HOST_S)
        portals.append({
            "tower": f"cobbletowers:{tower}",
            "min": [min(xs_), G, min(zs_)], "max": [max(xs_), G + PORTAL_UP + 1, max(zs_)],
            "back": {"x": back[0], "y": back[1], "z": back[2], "yaw": inward},
            "host": {"x": host[0], "y": host[1], "z": host[2], "yaw": inward},
            "color": VEIL_RGB[tower],
            **party_layout(tower, out),
        })
    return {"structure": "cobbletowers:tower_hub", "size": [SX, SY, SZ],
            "pad": {"x": SPAWN[0], "y": G, "z": SPAWN[1], "yaw": 180.0}, "portals": portals, "shrines": shrine_layout(),
            "records": records_layout(),
            "ring": {"y": G - 1, "r": RING_R[1], "lit": PARTY_LIT, "unlit": PARTY_UNLIT,
                     "stone": {"lit": PARTY_LIT, "unlit": PARTY_UNLIT}, "cells": [[x, z, arc] for x, z, arc in ring_cells()]}}



# ---- the Trials Pavilion (NE diagonal) and the Records Hall's site (SW) (owner, 20 Nov 2026) --------------------------------------------

TRIALS_R, TRIALS_SIZE = 60, 16                  # the pavilion's centre, out from the fountain on the NE diagonal; its radius
RECORDS_R, RECORDS_SIZE = 60, 18                # the Records Hall's site on the SW diagonal (marked out; the hall comes next)
CIVIC = ((1, -1), (-1, 1))                      # the diagonals the two buildings take (their pylons and garden corners give way)


def civic_centre(diag: tuple[int, int], r: int) -> tuple[int, int]:
    return round(C + diag[0] * r / math.sqrt(2)), round(C + diag[1] * r / math.sqrt(2))


def civic_mask(xs: np.ndarray, zs: np.ndarray) -> np.ndarray:
    """The two buildings' ground and the paths to them from the plaza's diagonal gaps: kept clear of the gardens."""
    m = np.zeros(xs.shape, dtype=bool)
    for diag, r, size in (((1, -1), TRIALS_R, TRIALS_SIZE), ((-1, 1), RECORDS_R, RECORDS_SIZE)):
        cx, cz = civic_centre(diag, r)
        m |= np.hypot(xs - cx, zs - cz) <= size + 4
        ux, uz = diag[0] / math.sqrt(2), diag[1] / math.sqrt(2)
        along = (xs - C) * ux + (zs - C) * uz
        across = np.abs(-(xs - C) * uz + (zs - C) * ux)
        m |= (along > PLAZA - 2) & (along < r) & (across <= 4.5)
    return m


def civic_path(g: Grid, diag: tuple[int, int], r: int, size: int) -> None:
    """A path along the diagonal from the plaza's gap to the building, kerbed, with lamps either side."""
    ux, uz = diag[0] / math.sqrt(2), diag[1] / math.sqrt(2)
    for x in range(SX):
        for z in range(SZ):
            along = (x + 0.5 - C) * ux + (z + 0.5 - C) * uz
            if not PLAZA - 1 < along < r - size + 1:
                continue
            across = abs(-(x + 0.5 - C) * uz + (z + 0.5 - C) * ux)
            if across <= 2.5:
                g.at(PAVE2 if across < 1.6 else "minecraft:polished_diorite", x, G - 1, z)
                g.air(x, G, z, x, G + 3, z)
            elif across <= 3.4:
                g.at(KERB, x, G - 1, z)
    for t in range(PLAZA + 4, r - size, 6):
        for side in (-1, 1):
            lx = round(C + ux * t + (-uz) * 4.2 * side)
            lz = round(C + uz * t + ux * 4.2 * side)
            g.at(KERB, lx, G - 1, lz)
            lamp(g, lx, lz, 5, "minecraft:stone_brick_wall", "minecraft:sea_lantern", arms=False)


def shrine(g: Grid, cx: int, cz: int, facing: tuple[int, int], daily: bool, veil: bool = False) -> None:
    """One trial shrine set into the pavilion's ring: a wall with an arched portal (its veil drawn by the mod), the emblem over it (the
    Daily's sun, the Weekly's crescent moon), pilasters, and a ready ring in front. `facing` points from the wall into the pavilion."""
    fx, fz = facing
    rx, rz = -fz, fx                                         # along the wall
    wall = "minecraft:quartz_bricks"
    rim = "minecraft:gold_block" if daily else "minecraft:amethyst_block"
    glow = "minecraft:ochre_froglight" if daily else "minecraft:pearlescent_froglight"
    veil = ("minecraft:yellow_stained_glass" if daily else "minecraft:purple_stained_glass") if veil else tg.AIR
    dark = "minecraft:polished_blackstone" if not daily else "minecraft:smooth_quartz"

    def put(name, a, depth, y):                              # a: along the wall, depth: toward the pavilion's middle
        g.at(name, cx + rx * a + fx * depth, y, cz + rz * a + fz * depth)

    for a in range(-7, 8):
        for depth in (-1, 0):
            for y in range(G - 1, G + 14):
                put(wall, a, depth, y)
    for a in range(-8, 9):                                   # a cornice and a plinth course
        for depth in (-1, 0, 1):
            put("minecraft:chiseled_quartz_block", a, depth, G + 14)
        put("minecraft:smooth_quartz", a, 1, G)
    for a in (-7, 7):                                        # pilasters, lit
        for y in range(G, G + 14):
            put("minecraft:quartz_pillar", a, 1, y)
        put(glow, a, 1, G + 6)
    spring, half = G + 6, 2
    for a in range(-half - 2, half + 3):                     # the arch: opening (veil), lit rim, trim band
        for y in range(G, spring + half + 3):
            d = math.hypot(a, y - spring) if y >= spring else abs(a)
            for depth in (-1, 0):
                if d <= half + 0.4:
                    put(veil, a, depth, y)
                elif d <= half + 1.4:
                    put(rim, a, depth, y)
            if half + 1.4 < d <= half + 2.4:
                put("minecraft:chiseled_quartz_block", a, 1, y)
    ey = G + 11                                              # the emblem
    for a in range(-4, 5):
        for y in range(ey - 4, ey + 5):
            d = math.hypot(a, y - ey)
            if daily:
                if d <= 1.5:
                    put(glow, a, 1, y)
                elif d <= 2.6:
                    put(rim, a, 1, y)
                elif d <= 3.4 and (a == 0 or y == ey or abs(a) == abs(y - ey)):
                    put(rim, a, 1, y)                        # rays
            else:                                            # a bright crescent on a night-dark disc, in an amethyst rim
                if d <= 3.0:
                    lit = math.hypot(a + 1.4, y - ey - 0.4) > 2.3
                    put("minecraft:pearlescent_froglight" if lit else "minecraft:black_concrete", a, 1, y)
                elif d <= 3.6:
                    put(rim, a, 1, y)
    for a in range(-5, 6):                                   # the ready ring in front, in the shrine's colour
        for depth in range(1, 10):
            d = math.hypot(a, depth - 5.5)
            if 2.7 < d <= 3.5:
                put(rim if daily else "minecraft:purpur_block", a, depth, G - 1)


SHRINES = (("daily", (0, -12), (0, 1)), ("weekly", (12, 0), (-1, 0)))     # (kind, offset from the pavilion's middle, facing in)
SHRINE_RGB = {"daily": 0xF2C744, "weekly": 0xB57BEA}


def shrine_layout() -> list[dict]:
    """What the mod needs of the shrines: each portal's opening (as a box, like a gate's), the spot a walker is set back on (inside the
    pavilion, facing in), the ready ring (centre and radius) and where the plate hangs; the same frame shrine() builds in."""
    px, pz = civic_centre((1, -1), TRIALS_R)
    out = []
    for kind, (ox, oz), (fx, fz) in SHRINES:
        cx, cz = px + ox, pz + oz
        rx, rz = -fz, fx

        def at(a, depth):
            return cx + rx * a + fx * depth, cz + rz * a + fz * depth

        cells = [at(a, depth) for a in (-2, 2) for depth in (-1, 0)]
        xs, zs = [c[0] for c in cells], [c[1] for c in cells]
        bx, bz = at(0, 6)
        ready = (cx + fx * 5.5 + 0.5, cz + fz * 5.5 + 0.5)
        plate = at(0, 1)
        out.append({
            "kind": kind, "min": [min(xs), G, min(zs)], "max": [max(xs), G + 8, max(zs)],
            "back": {"x": bx, "y": G, "z": bz, "yaw": facing(fx, fz)},
            "ready": {"x": round(ready[0], 2), "z": round(ready[1], 2), "r": 3.5},
            "plate": [plate[0], G + 16, plate[1]], "color": SHRINE_RGB[kind],
        })
    return out


def stepped(g: Grid, name: str, pts: list[tuple[int, int, int]]) -> None:
    """A line through the points that steps one axis at a time, so every block shares a face with the next (no corner-only joins)."""
    x, y, z = pts[0]
    g.at(name, x, y, z)
    for nx, ny, nz in pts[1:]:
        while (x, y, z) != (nx, ny, nz):
            if x != nx:
                x += 1 if nx > x else -1
            elif z != nz:
                z += 1 if nz > z else -1
            else:
                y += 1 if ny > y else -1
            g.at(name, x, y, z)


def trials_pavilion(g: Grid, veil: bool = False) -> None:
    """The Trials Pavilion: a round quartz platform with a dial floor, a colonnade carrying an open ribbed dome with a lit oculus, the
    Daily shrine (north side, gold, sun) and the Weekly shrine (east side, violet, moon), a sundial in the middle, open to the plaza."""
    px, pz = civic_centre((1, -1), TRIALS_R)
    R = TRIALS_SIZE
    ent = math.degrees(math.atan2(C - pz, C - px)) % 360      # the side facing the plaza: the way in
    for x in range(px - R - 2, px + R + 3):
        for z in range(pz - R - 2, pz + R + 3):
            d = math.hypot(x - px, z - pz)
            if d > R + 1.5:
                continue
            a = math.degrees(math.atan2(z - pz, x - px)) % 360
            g.air(x, G, z, x, G + 26, z)
            if d > R + 0.5:
                g.at(KERB, x, G - 1, z)
            elif d < 3.2:
                g.at("minecraft:chiseled_quartz_block", x, G - 1, z)
            elif abs(((a + 15) % 30) - 15) < 1.6 and d > 5 and int(d) % 2 == 0:
                g.at("minecraft:gold_block" if d > R - 2 else "minecraft:polished_diorite", x, G - 1, z)   # the dial's hour lines
            else:
                ring = int(d) // 3
                g.at("minecraft:smooth_quartz" if ring % 2 else "minecraft:quartz_bricks", x, G - 1, z)
    for k in range(12):                                       # the colonnade, open toward the plaza
        a = 15 + 30 * k
        if abs(((a - ent + 180) % 360) - 180) < 20:
            continue
        cx, cz = round(px + 15 * math.cos(math.radians(a))), round(pz + 15 * math.sin(math.radians(a)))
        g.box("minecraft:chiseled_quartz_block", cx - 1, G, cz - 1, cx + 1, G, cz + 1)
        g.box("minecraft:quartz_pillar", cx, G + 1, cz, cx, G + 9, cz)
        g.box("minecraft:chiseled_quartz_block", cx - 1, G + 10, cz - 1, cx + 1, G + 10, cz + 1)
        g.at("minecraft:gold_block", cx, G + 5, cz)
    for x in range(px - R - 1, px + R + 2):                   # the entablature ring and a gold band
        for z in range(pz - R - 1, pz + R + 2):
            d = math.hypot(x - px, z - pz)
            if 14 <= d <= 16.4:
                g.box("minecraft:quartz_bricks", x, G + 11, z, x, G + 11, z)
                g.at("minecraft:gold_block" if 15.4 < d else "minecraft:smooth_quartz", x, G + 12, z)
    for k in range(12):                                       # the dome's ribs, from the ring to the oculus, joined face to face
        a = math.radians(15 + 30 * k)
        pts = []
        for i in range(0, 41):
            t = i / 40
            rr = 15 - 12 * math.sin(t * math.pi / 2)
            y = G + 13 + round(11 * math.sin(t * math.pi / 2) ** 0.8)
            pts.append((round(px + rr * math.cos(a)), y, round(pz + rr * math.sin(a))))
        stepped(g, "minecraft:quartz_bricks", pts)
        for i in (12, 24, 34):                                # gold bosses along each rib
            g.at("minecraft:gold_block", *pts[i])
    for x in range(px - 13, px + 14):                         # the oculus ring
        for z in range(pz - 13, pz + 14):
            d = math.hypot(x - px, z - pz)
            if 2.5 <= d <= 3.6:
                g.at("minecraft:gold_block", x, G + 24, z)
                g.at("minecraft:ochre_froglight" if (x + z) % 2 else "minecraft:gold_block", x, G + 25, z)
    g.box("minecraft:chiseled_quartz_block", px - 1, G, pz - 1, px + 1, G, pz + 1)    # the sundial
    g.box("minecraft:smooth_quartz_slab[type=bottom]", px - 2, G + 1, pz - 2, px + 2, G + 1, pz + 2)
    g.box("minecraft:gold_block", px, G + 1, pz, px, G + 3, pz)
    g.at("minecraft:lightning_rod[facing=up]", px, G + 4, pz)
    for kind, (ox, oz), facing in SHRINES:                   # the Daily on the north side, the Weekly on the east, facing in
        shrine(g, px + ox, pz + oz, facing, daily=kind == "daily", veil=veil)
    civic_path(g, (1, -1), TRIALS_R, TRIALS_SIZE)


RECORDS_TRACK = 16                             # the season track's tier marks inlaid in the floor ring (the mod lights each viewer's own)
TRACK_R = 8.5                                  # the ring's radius: inside the trophy cases (fronts at 12.5), outside the dais (5.4)
CHEST_R = 4.6                                  # the Season Chest, on the dais's front step (3.9 to 5.4), facing the door
INLAY = "minecraft:chiseled_polished_blackstone"
MASTERY_ORDER = ("neutral", "tideforge", "duskvale", "rootvale")


def records_layout() -> dict:
    """Where the Records Hall's live parts are, for the mod (the same geometry records_hall builds): the leaderboard wall's three columns,
    the three statue plinths' tops, the sixteen tier marks in the floor ring (their top face), the Season Chest's foot and facing, each
    tower's trophy case (the panel face it stands against) and its plate, and the door's rank plate."""
    px, pz = civic_centre((-1, 1), RECORDS_R)
    R = RECORDS_SIZE
    door = math.degrees(math.atan2(C - pz, C - px)) % 360
    back = (door + 180) % 360
    ux, uz = math.cos(math.radians(door)), math.sin(math.radians(door))
    vx, vz = -uz, ux

    def off(a, b):
        return abs(((a - b + 180) % 360) - 180)

    def pt(x, y, z):
        return [round(x, 3), y, round(z, 3)]

    bx, bz = px + 0.5 - ux * (R - 2.0), pz + 0.5 - uz * (R - 2.0)
    columns = [pt(bx + vx * (1 - i) * 6.3, G + 9, bz + vz * (1 - i) * 6.3) for i in range(3)]
    statues = []
    for side, h in ((0, 3), (-1, 2), (1, 1)):
        sx, sz = round(px + vx * side * 2.2 + ux * 0.5), round(pz + vz * side * 2.2 + uz * 0.5)
        statues.append(pt(sx + 0.5, G + 3 + h, sz + 0.5))
    track = []
    for i in range(24):
        a = i * 15 + 7.5
        if min(off(a, gp) for gp in (door, back)) < 24:
            continue
        if len(track) >= RECORDS_TRACK:
            break
        track.append(pt(round(px + TRACK_R * math.cos(math.radians(a))) + 0.5, G, round(pz + TRACK_R * math.sin(math.radians(a))) + 0.5))
    shelves = {}
    for tower, a in zip(MASTERY_ORDER, (270, 0, 90, 180)):
        ax, az = math.cos(math.radians(a)), math.sin(math.radians(a))
        tx, tz = -az, ax
        cx, cz = px + ax * (R - 1.5), pz + az * (R - 1.5)
        fx, fz = round(cx) + 0.5 - ax * 0.5, round(cz) + 0.5 - az * 0.5           # the panel's face, middle of the alcove, at floor level
        shelves[tower] = {"case": pt(fx, G, fz), "plate": pt(fx - ax * 0.06, G + 5.7, fz - az * 0.06),
                          "facing": round(math.degrees(math.atan2(-ax, -az)), 1)}
    return {"centre": [px, G, pz], "radius": R, "board": columns, "board_facing": round(math.degrees(math.atan2(ux, uz)), 1),
            "statues": statues, "track": track, "shelves": shelves,
            "chest": pt(px + 0.5 + ux * CHEST_R, G + 1, pz + 0.5 + uz * CHEST_R), "chest_facing": round(math.degrees(math.atan2(ux, uz)), 1), "rank": pt(px + 0.5 + ux * (R - 3.5), G + 3, pz + 0.5 + uz * (R - 3.5))}


def records_hall(g: Grid) -> None:
    """The Records Hall (SW diagonal): a dark rotunda with gold and quartz, its arch toward the plaza. Inside, round a stepped rank dais
    (the season's top three as statues, drawn by the mod, and the Season Chest on its front step), a pale ring path with the season track's
    sixteen tier marks inlaid in it; on the back wall, facing the
    door, the leaderboard wall; and four alcoves with each tower's mastery shelves in that tower's stone. A tiled dome with gold ribs and a
    skylight over it all."""
    px, pz = civic_centre((-1, 1), RECORDS_R)
    R = RECORDS_SIZE
    door = math.degrees(math.atan2(C - pz, C - px)) % 360      # toward the plaza
    back = (door + 180) % 360
    WALL, TRIM, DARK = "minecraft:polished_blackstone_bricks", "minecraft:chiseled_quartz_block", "minecraft:polished_deepslate"

    def off(a, b):
        return abs(((a - b + 180) % 360) - 180)

    for x in range(px - R - 3, px + R + 4):
        for z in range(pz - R - 3, pz + R + 4):
            d = math.hypot(x - px, z - pz)
            if d > R + 2.2:
                continue
            ang = math.degrees(math.atan2(z - pz, x - px)) % 360
            g.air(x, G, z, x, G + 30, z)
            # the floor: dark, a pale ring path with the track's tier marks in it (below), a quartz border at the wall
            if d > R + 0.6:
                g.at(KERB, x, G - 1, z)
            elif 7.4 <= d <= 9.4:
                g.at("minecraft:smooth_quartz", x, G - 1, z)
            elif d > R - 1.6:
                g.at("minecraft:quartz_bricks", x, G - 1, z)
            else:
                g.at(DARK if (int(d) + int(ang // 22.5)) % 2 else "minecraft:polished_blackstone", x, G - 1, z)
            # the drum wall, with the door's arch cut through it
            if R - 0.5 <= d <= R + 0.9:
                for y in range(G, G + 13):
                    tang = math.radians(off(ang, door)) * R             # how far along the wall from the door's middle
                    spring = G + 6
                    rr = math.hypot(tang, y - spring) if y >= spring else tang
                    if off(ang, door) < 25 and rr <= 3.6:
                        continue                                          # the doorway
                    if off(ang, door) < 25 and rr <= 4.8:
                        g.at("minecraft:gold_block" if rr <= 4.2 else TRIM, x, y, z)
                        continue
                    g.at(WALL, x, y, z)
                g.at(TRIM, x, G + 13, z)
                g.at("minecraft:gold_block" if d > R + 0.3 else TRIM, x, G + 14, z)
    # pilasters and slit windows round the drum (none at the door, the back wall or the alcoves)
    for k in range(16):
        a = k * 22.5 + 11.25
        cx, cz = px + (R + 1.2) * math.cos(math.radians(a)), pz + (R + 1.2) * math.sin(math.radians(a))
        if off(a, door) > 20:
            g.box("minecraft:quartz_pillar", round(cx), G, round(cz), round(cx), G + 12, round(cz))
            g.at("minecraft:gold_block", round(cx), G + 13, round(cz))
        w = a + 11.25
        if off(w, door) > 30 and off(w, back) > 30 and min(off(w, m) for m in (0, 90, 180, 270)) > 12:
            wx, wz = round(px + R * math.cos(math.radians(w))), round(pz + R * math.sin(math.radians(w)))
            g.box("minecraft:light_gray_stained_glass", wx, G + 5, wz, wx, G + 10, wz)
            g.at("minecraft:sea_lantern", wx, G + 4, wz)                 # a light set into the wall under each window
    # the dome: tiles, filled down to the lowest neighbour so it has no holes, gold ribs, a skylight
    top = {}
    for x in range(px - R - 2, px + R + 3):
        for z in range(pz - R - 2, pz + R + 3):
            d = math.hypot(x - px, z - pz)
            if d <= R + 0.9:
                top[(x, z)] = G + 15 + round(12 * math.sqrt(max(0.0, 1 - (min(d, R) / R) ** 2)))
    for (x, z), y in top.items():
        low = min(top.get((x + dx, z + dz), G + 14) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        d = math.hypot(x - px, z - pz)
        ang = math.degrees(math.atan2(z - pz, x - px)) % 360
        rib = off(ang, round(ang / 45) * 45) * math.radians(1) * d < 0.8
        start = G + 15 if d >= R - 2.5 else min(low, y)         # the rim rests on the drum; each column reaches its lowest neighbour
        for yy in range(start, y + 1):
            if d < 3.2:
                g.at("minecraft:light_blue_stained_glass" if d < 2.4 else "minecraft:gold_block", x, yy, z)
            else:
                g.at("minecraft:gold_block" if rib else "minecraft:deepslate_tiles", x, yy, z)
    g.box("minecraft:gold_block", px, G + 28, pz, px, G + 29, pz)
    # the rank dais: three steps, the top three's plinths (first highest, middle), lit from below
    for x in range(px - 6, px + 7):
        for z in range(pz - 6, pz + 7):
            d = math.hypot(x - px, z - pz)
            if d <= 5.4:
                g.at("minecraft:polished_blackstone_bricks", x, G, z)
            if d <= 3.9:
                g.at("minecraft:quartz_bricks", x, G + 1, z)
            if 5.0 < d <= 5.4:
                g.at("minecraft:ochre_froglight" if (x + z) % 3 == 0 else TRIM, x, G, z)
    ux, uz = math.cos(math.radians(door)), math.sin(math.radians(door))       # toward the door
    vx, vz = -uz, ux                                                          # across
    for side, h, top_block in ((0, 3, "minecraft:gold_block"), (-1, 2, "minecraft:iron_block"), (1, 1, "minecraft:copper_block")):
        sx, sz = round(px + vx * side * 2.2 + ux * 0.5), round(pz + vz * side * 2.2 + uz * 0.5)
        g.box(TRIM, sx, G + 2, sz, sx, G + 1 + h, sz)
        g.at(top_block, sx, G + 2 + h, sz)
    # the season track's tier marks, inlaid in the ring path (the mod lays a gold star over each one the viewer has passed)
    gaps = [door, back]
    k = 0
    for i in range(24):
        a = i * 15 + 7.5
        if min(off(a, gp) for gp in gaps) < 24:
            continue
        if k >= RECORDS_TRACK:
            break
        cx, cz = round(px + TRACK_R * math.cos(math.radians(a))), round(pz + TRACK_R * math.sin(math.radians(a)))
        g.at(INLAY, cx, G - 1, cz)
        k += 1
    # the leaderboard wall on the back, facing the door: a gold frame round a dark board, lit from below
    bx, bz = px - ux * (R - 1.0), pz - uz * (R - 1.0)
    for t in range(-9, 10):
        for y in range(G + 1, G + 12):
            x, z = round(bx + vx * t), round(bz + vz * t)
            edge = abs(t) == 9 or y in (G + 1, G + 11)
            g.at("minecraft:gold_block" if edge else "minecraft:black_concrete", x, y, z)
        x, z = round(bx + ux * 1 + vx * t), round(bz + uz * 1 + vz * t)
        g.at("minecraft:ochre_froglight" if t % 3 == 0 else TRIM, x, G, z)
    # the mastery alcoves: each tower's stone panel, its banner over it; the trophy case in front is the mod's (trophy_export.py)
    for tower, a in zip(MASTERY_ORDER, (270, 0, 90, 180)):
        ax, az = math.cos(math.radians(a)), math.sin(math.radians(a))
        tx, tz = -az, ax
        cx, cz = px + ax * (R - 1.5), pz + az * (R - 1.5)
        trophy = {"neutral": "minecraft:gold_block", "tideforge": "minecraft:prismarine_bricks", "duskvale": "minecraft:amethyst_block",
                  "rootvale": "minecraft:moss_block"}[tower]
        for t in range(-4, 5):
            for y in range(G, G + 9):
                x, z = round(cx + tx * t), round(cz + tz * t)
                if abs(t) == 4 or y in (G, G + 8):
                    g.at(role(tower, "trim"), x, y, z)
                elif y in (G + 2, G + 4, G + 6):
                    g.at(role(tower, "wall"), x, y, z)
                else:                                                        # the trophy case stands against it (the mod draws it)
                    g.at(role(tower, "wall_low"), x, y, z)
            x, z = round(cx + tx * t - ax), round(cz + tz * t - az)
            if abs(t) <= 1:
                g.box(role(tower, "hanging"), x, G + 9, z, x, G + 12, z)
        g.at(role(tower, "light"), round(cx - ax * 5), G - 1, round(cz - az * 5))      # just in front of the case
    civic_path(g, (-1, 1), RECORDS_R, RECORDS_SIZE)


def build(veil: bool = False) -> Grid:
    g = Grid({}, (SX, SY, SZ))
    rnd = random.Random(19)
    ground(g)
    placed, footprint = [], np.zeros((SX, SZ), dtype=bool)
    for tower, out, colour in BAYS:
        arr, (fx, fz) = turned(bay_grid(g, tower, colour if veil else None), out, g)
        x0, z0 = C + out[0] * GATE_R - fx, C + out[1] * GATE_R - fz
        placed.append((arr, x0, z0))
        footprint[x0:x0 + arr.shape[0], z0:z0 + arr.shape[2]] |= arr.any(axis=1)
    near = footprint.copy()
    for _ in range(3):
        p = np.pad(near, 1)
        near = p[2:, 1:-1] | p[:-2, 1:-1] | p[1:-1, 2:] | p[1:-1, :-2] | near
    xs, zs, _, _ = plan()
    paths = np.zeros_like(near)
    for _, (ox, oz), _ in BAYS:
        paths |= (np.abs((xs - C) * -oz + (zs - C) * ox) <= 5) & ((xs - C) * ox + (zs - C) * oz > 0)
    curtain_wall(g)
    trees = big_tree_spots()
    diag = np.zeros_like(near)
    for _, tx, tz in trees:
        diag |= np.hypot(xs - tx, zs - tz) <= 10
    for a, b in ((1, 2), (3, 0)):                                            # the pylons on the two diagonals the civic buildings leave
        (ta, oa, _), (tb, ob, _) = BAYS[a], BAYS[b]
        px, pz = C + (oa[0] + ob[0]) * 44, C + (oa[1] + ob[1]) * 44
        diag |= np.hypot(xs - px, zs - pz) <= 6
        pylon(g, px, pz, ta, tb)
    civic = civic_mask(xs, zs)
    for tower, out, _ in BAYS:
        keep = ~near & ~paths & ~diag & ~civic
        garden(g, tower, sector(out) & keep, sector(out, slack=BLEND) & keep, out, rnd)
    for tower, tx, tz in trees:
        big_tree(g, tower, tx, tz, rnd)
    for arr, x0, z0 in placed:                                              # the bays over the gardens
        region = g.grid[x0:x0 + arr.shape[0], :, z0:z0 + arr.shape[2]]
        region[arr != 0] = arr[arr != 0]
    trials_pavilion(g, veil)
    records_hall(g)
    plaza(g)
    fountain(g)
    party_fixtures(g)
    tg.connect(g.grid, g.palette, g.id)
    spawn_proof(g)
    return g


def problems(g: Grid) -> list[str]:
    """No water runs out: still water never beside or over air, falling water lands on water (boss_kit.spills)."""
    import boss_kit as K
    return K.spills(g)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=tg.ROOT / "validation" / "schematics" / "hub")
    ap.add_argument("--veil", action="store_true", help="fill the portals with the towers' colours (concept pictures only)")
    ap.add_argument("--install", action="store_true", help="also copy the structure and its layout into the mod")
    args = ap.parse_args()
    g = build(args.veil)
    found = problems(g)
    for p in found:
        print("FAIL", p)
    if found:
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    stem = "tower_hub_concept" if args.veil else "tower_hub"
    n = tg.write_nbt(g, args.out / f"{stem}.nbt")
    if not args.veil:
        import json
        (args.out / "tower_hub.layout.json").write_text(json.dumps(layout(), indent=2) + "\n")
        print(f"wrote {args.out / 'tower_hub.layout.json'}")
        if args.install:
            import shutil
            shutil.copyfile(args.out / f"{stem}.nbt", tg.ROOT / "src/main/resources/data/cobbletowers/structure/tower_hub.nbt")
            dest = tg.ROOT / "src/main/resources/data/cobbletowers/cobbletowers/hub"
            dest.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(args.out / "tower_hub.layout.json", dest / "tower_hub.json")
            print("installed the hub structure and its layout")
    print(f"wrote {args.out / (stem + '.nbt')} ({n:,} blocks, {SX}x{SY}x{SZ})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
