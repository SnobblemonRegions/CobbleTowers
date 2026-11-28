"""Shared pieces of the boss halls (floors 5 and 10 of every tower, owner 15 Nov 2026): plan geometry, the ways in and out, glass-held seas,
a night sky lining, and the checks every boss hall runs.

The plan is grand_hall's: the hall is x 9-140, z 47-142, centre (74, 94). The party comes in from the passage at z 143 and leaves through
gate A at z 45-46, both 9 wide at the feet (x 70-78), six high.
"""
from __future__ import annotations

import math
import random

import numpy as np

import grand_hall as gh
import tower_gen as tg
from grand_hall import HALL_X, HALL_Z, MID
from tower_gen import Grid

CZ = 94
X0, X1 = HALL_X
Z0, Z1 = HALL_Z
WATER = "minecraft:water"
FALLING = "minecraft:water[level=8]"
CLEAR = "minecraft:glass"
STAR = "minecraft:end_rod[facing=up]"
NIGHT = "minecraft:black_concrete"
CORALS = ("minecraft:tube_coral_block", "minecraft:brain_coral_block", "minecraft:bubble_coral_block", "minecraft:fire_coral_block",
          "minecraft:horn_coral_block")


def plan(shape: tuple[int, int, int], cz: int = CZ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    xs, zs = np.meshgrid(np.arange(shape[0]), np.arange(shape[2]), indexing="ij")
    r = np.hypot(xs - MID, zs - cz)
    ang = (np.degrees(np.arctan2(zs - cz, xs - MID)) + 360) % 360
    return xs, zs, r, ang


def ell(shape: tuple[int, int, int], rx: float, rz: float, cz: int = CZ) -> np.ndarray:
    """Normalised elliptical distance from the hall's centre (1 on the ellipse rx, rz)."""
    xs, zs, _, _ = plan(shape, cz)
    return np.sqrt(((xs - MID) / rx) ** 2 + ((zs - cz) / rz) ** 2)


def dilate(m: np.ndarray) -> np.ndarray:
    out = m.copy()
    out[1:] |= m[:-1]
    out[:-1] |= m[1:]
    out[:, 1:] |= m[:, :-1]
    out[:, :-1] |= m[:, 1:]
    out[:, :, 1:] |= m[:, :, :-1]
    out[:, :, :-1] |= m[:, :, 1:]
    return out


def corridor(g: Grid, f: int, za: int, zb: int, half: int = 4, h: int = 6, floor: str = "floor", wall: str = "wall_low",
             rib_every: int = 4, light: bool = True) -> None:
    """A straight way along z at the feet, `half` either side of MID: floor, a plinth, trim ribs and wall lights."""
    lo, hi = min(za, zb), max(za, zb)
    g.air(MID - half, f, lo, MID + half, f + h - 1, hi)
    g.box(floor, MID - half, f - 1, lo, MID + half, f - 1, hi)
    g.box("inlay", MID, f - 1, lo, MID, f - 1, hi)
    for z in range(lo, hi + 1):
        for x in (MID - half - 1, MID + half + 1):
            g.box(wall, x, f, z, x, f + 1, z)
        if rib_every and z % rib_every == 0:
            g.box("trim", MID - half - 1, f, z, MID - half - 1, f + h, z)
            g.box("trim", MID + half + 1, f, z, MID + half + 1, f + h, z)
            g.box("trim", MID - half, f + h, z, MID + half, f + h, z)
        if light and rib_every and z % rib_every == rib_every // 2:
            for x in (MID - half - 1, MID + half + 1):
                g.at("light", x, f + 3, z)


def ways(g: Grid, f: int, north_to: int, south_from: int, **kw) -> None:
    """The way in from the passage (z 142 down to south_from) and out to gate A (z 47 up to north_to)."""
    corridor(g, f, Z0, north_to, **kw)
    corridor(g, f, south_from, Z1, **kw)


def rail_edge(g: Grid, y: int, walk: np.ndarray, drop: np.ndarray, rail: str = "rail") -> None:
    """A rail on every cell of `walk` (a plan mask) beside `drop` (eight-way, so no corner gap) at height y."""
    near = np.zeros_like(drop)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            near |= np.roll(np.roll(drop, dx, axis=0), dz, axis=1)
    for x, z in np.argwhere(walk & near):
        g.at(rail, int(x), y, int(z))


def drops(g: Grid, f: int, zones: list[tuple[int, np.ndarray]]) -> list[str]:
    """Nobody can step off an edge: every standable cell at height y inside a zone has no eight-way neighbour that is open at its feet and
    below them (an open drop), corners included."""
    w = tg.Walker(g)
    found = []

    def open_(x: int, y: int, z: int) -> bool:                       # air, or water to fall into
        return w.passable(x, y, z) or g.name(x, y, z).startswith("minecraft:water")

    for y, zone in zones:
        for x, z in np.argwhere(zone):
            x, z = int(x), int(z)
            if not w.standable(x, y, z):
                continue
            if any(w.passable(x + dx, y, z + dz) and open_(x + dx, y - 1, z + dz) and open_(x + dx, y - 2, z + dz)
                   for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
                found.append(f"feet {f}: an open drop beside ({x}, {y}, {z})")
    return found[:6] + ([f"... {len(found) - 6} more"] if len(found) > 6 else [])


def night_lining(g: Grid, inside: np.ndarray, y0: int, seed: int, density: float = 0.012) -> None:
    """Line every solid cell beside the open volume `inside` (starting at row y0) with the night: black, a star here and there."""
    shell = dilate(inside) & ~inside
    sub = g.grid[:, y0:y0 + inside.shape[1], :]
    keep = sub != 0
    rng = np.random.default_rng(seed)
    noise = rng.random(shell.shape)
    paint = shell & keep
    sub[paint] = g.id(NIGHT)
    sub[paint & (noise < density)] = g.id("light")
    sub[paint & (noise >= density) & (noise < density * 2.2)] = g.id("minecraft:sea_lantern")


def sphere_mask(shape: tuple[int, int, int], y0: int, ny: int, c: tuple[float, float, float], r: float) -> np.ndarray:
    xs = np.arange(shape[0])[:, None, None]
    ys = np.arange(ny)[None, :, None] + y0
    zs = np.arange(shape[2])[None, None, :]
    return (xs - c[0]) ** 2 + (ys - c[1]) ** 2 + (zs - c[2]) ** 2 < r * r


def scatter(rnd: random.Random, n: int, keep) -> list[tuple[int, int]]:
    out = []
    for _ in range(n * 20):
        if len(out) >= n:
            break
        x, z = rnd.randint(X0, X1), rnd.randint(Z0, Z1)
        if keep(x, z) and all(math.hypot(x - a, z - b) > 9 for a, b in out):
            out.append((x, z))
    return out


def gh_feet(spec: gh.Spec, floor: int) -> int:
    return gh.feet(spec, floor)

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


def close_short(g: Grid, x0: int, z0: int, x1: int, z1: int, y0: int, y1: int, reach: int = 3) -> None:
    """Carry every free-standing column top in the box up to the ceiling when it stops `reach` or less under it: a pillar or spur that
    almost touches reads as a missing block (integrity_check's short runs)."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1):
                if g.grid[x, y, z] != 0 and g.grid[x, y + 1, z] == 0:
                    gap = 1                                                  # y + gap is the first block above the air
                    while gap <= reach + 1 and y + gap < g.grid.shape[1] and g.grid[x, y + gap, z] == 0:
                        gap += 1
                    if gap <= reach + 1 and y + gap < g.grid.shape[1] and g.grid[x, y + gap, z] != 0:
                        name = g.palette[int(g.grid[x, y, z])]
                        g.box(name, x, y + 1, z, x, y + gap - 1, z)


def fill_dents(g: Grid, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, name: str) -> None:
    """Fill, inside the box, every air cell with five solid faces round it (a dent) or boxed in on its four sides (a pinhole): at full size
    they read as missing blocks (integrity_check). Only for boxes nobody walks in."""
    sub = g.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1]
    for _ in range(3):
        solid = sub != 0
        count = np.zeros(sub.shape, dtype=np.int8)
        ring = np.ones(sub.shape, dtype=bool)
        for axis in range(3):
            for d in (1, -1):
                s = np.roll(solid, d, axis=axis)
                count += s
                if axis != 1:
                    ring &= s
        hole = (sub == 0) & ((count >= 5) | ring)
        hole[0, :, :] = hole[-1, :, :] = False
        hole[:, 0, :] = hole[:, -1, :] = False
        hole[:, :, 0] = hole[:, :, -1] = False
        if not hole.any():
            break
        sub[hole] = g.id(name)
    g.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1] = sub
