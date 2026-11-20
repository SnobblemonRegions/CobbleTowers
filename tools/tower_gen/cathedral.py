#!/usr/bin/env python3
"""Tideforge's own buildings (P59, owner direction 15 Nov 2026: a glass-domed sea temple, replacing the first sunken-cathedral draft).

    py -3.14 -I tools/tower_gen/cathedral.py                # build, validate, write .nbt and the layout JSON to validation/schematics/tideforge/
    py -3.14 -I tools/tower_gen/cathedral.py --check        # build and validate only
    py -3.14 -I tools/tower_gen/cathedral.py --install      # also copy the .nbt files into the mod's structure folder

THE SEA TEMPLE. One round hall 92 across under a single glass dome with the sea lying on it: the glass is ribbed in packed ice along sixteen
meridians and two rings, an oculus ringed in diamond at the crown, the water over it four deep with sea lanterns and coral in its far side.
The arena is a round dais in the middle of a lit ring pool. A ring of twelve water columns (glass tubes, a lantern glowing at the foot of each)
stands in the pool and carries a circular entablature; between the columns eight waterfalls pour from basins in it into the pool. Round the pool
an amphitheatre steps up in five tiers to the drum, a balustrade along its front; the drum is pierced by sixteen arched aquarium windows with
coral behind them. The party walks in from the passage through a vaulted tunnel under the tiers and across a causeway to the dais, and leaves
by the causeway and tunnel on the far side to gate A. A ring chandelier hangs from the oculus over the arena.

All the water is closed in by construction: the dome's glass is every cell beside the open hall, so the sea is never beside air; the window
water stands one block behind the window glass. `spills` proves it on every build.

The north part, the passage and the arrival hall, the buildings, the layout and the walk check are shared with the other themed towers
(grand_hall.py). A temple floor needs about 70 blocks of height, so the ten floors are four buildings (BUILDINGS): the crowns (5, 10) each top
one.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
from grand_hall import HALL_X, HALL_Z, MID, ellipse, hang  # noqa: E402,F401
from tower_gen import Grid  # noqa: E402
from themes import THEMES  # noqa: E402,F401
from boss_kit import CORALS, spills  # noqa: E402,F401  (rootvale and the boss halls use them from here too)

THEME = "tideforge"
BUILDINGS = ((1, 2), (3, 4), (5,), (6, 7), (8, 9), (10,))     # the boss floors (5, 10) are buildings of their own: boss_tideforge.py
CROWNS = (5, 10)
PITCH = 72                              # a floor runs from under its pool bed (feet - 7) to the cap over its sea (feet + 63; the crown + 71)
BASE_FEET = 9
CENTRE_Z = 94

R = 46                                  # the drum's inner radius: the hall is round, 92 across
ARENA = 19.5                            # the dais
POOL_OUT = 29.5                         # the ring pool between ARENA and here; the amphitheatre from here to the drum
COL_R = 26                              # the colonnade's radius, its columns at 15 + 30k degrees, the falls at 30k
BEAM = (23.5, 28.5)                     # the entablature ring the columns carry
BEAM_UP = 22                            # its underside at feet + 22, two thick
TIER_UP, TIER_STEP, TIERS = 6, 2, 5     # the front tier at feet + 6, each next one 2 higher and 3 deeper
POOL_BED = 6                            # the bed at feet - 6, water feet - 5 .. feet - 2
HALL = (30, 26)                         # the dome's spring above the feet, its rise
CROWN = (34, 30)
SEA = 4                                 # water over the glass
WIN_UP = 17                             # the drum windows from feet + 17 to three under the spring
TUNNEL_HALF = gh.PASSAGE_HALF
TUNNEL_H = 6

WATER = "minecraft:water"
FALLING = "minecraft:water[level=8]"
CLEAR = "minecraft:glass"


def spring_rise(crown: bool) -> tuple[int, int]:
    return CROWN if crown else HALL


def tier_of(r: float) -> int:
    return min(TIERS - 1, max(0, int((r - 31) // 3)))


def column_centres() -> list[tuple[int, int]]:
    return [(round(MID + COL_R * math.cos(math.radians(a))), round(CENTRE_Z + COL_R * math.sin(math.radians(a)))) for a in range(15, 360, 30)]


def fall_centres() -> list[tuple[int, int]]:
    """At 30k degrees, except where the causeways cross the pool (90, 270) and in front of the spectators east and west (0, 180)."""
    return [(round(MID + COL_R * math.cos(math.radians(a))), round(CENTRE_Z + COL_R * math.sin(math.radians(a))))
            for a in range(0, 360, 30) if a not in (0, 90, 180, 270)]


def _dilate(m: np.ndarray) -> np.ndarray:
    out = m.copy()
    out[1:] |= m[:-1]
    out[:-1] |= m[1:]
    out[:, 1:] |= m[:, :-1]
    out[:, :-1] |= m[:, 1:]
    out[:, :, 1:] |= m[:, :, :-1]
    out[:, :, :-1] |= m[:, :, 1:]
    return out


def plan(shape: tuple[int, int, int]) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """x, z, distance from the hall's centre and angle (degrees, 0 east, 90 south) for every column of the grid."""
    xs, zs = np.meshgrid(np.arange(shape[0]), np.arange(shape[2]), indexing="ij")
    r = np.hypot(xs - MID, zs - CENTRE_Z)
    ang = (np.degrees(np.arctan2(zs - CENTRE_Z, xs - MID)) + 360) % 360
    return xs, zs, r, ang


def dome(shape: tuple[int, int, int], f: int, crown: bool) -> tuple[int, np.ndarray, np.ndarray, np.ndarray]:
    """The dome from the row under its spring up: (y0, the open hall, the glass, the sea), boolean volumes starting at y0. The glass is every
    cell six-way beside the open hall, so no water can ever stand beside the air."""
    spring, rise = spring_rise(crown)
    s = f + spring
    y0 = s - 1
    ny = rise + SEA + 3
    _, _, r, _ = plan(shape)
    ys = np.arange(ny)[None, :, None] + y0
    inside = np.zeros((shape[0], ny, shape[2]), dtype=bool)
    rr = r[:, None, :]
    inside |= (ys == y0) & (rr < R)
    inside |= (ys >= s) & ((rr / R) ** 2 + ((ys - s) / rise) ** 2 < 1)
    glass = _dilate(inside) & ~inside
    reach = inside
    for _ in range(SEA + 1):
        reach = _dilate(reach)
    sea = reach & ~inside & ~glass
    glass[:, 0, :] = False
    sea[:, 0, :] = False
    return y0, inside, glass, sea


def hall(g: Grid, floor: int, f: int, crown: bool) -> None:
    """The sea temple's battle hall for one floor (feet f)."""
    ground = f - 1
    spring, rise = spring_rise(crown)
    s = f + spring
    rnd = random.Random(500 + floor)
    xs, zs, r, ang = plan(g.grid.shape)
    tier_k = np.vectorize(tier_of)(r)
    tier_top = f + TIER_UP + TIER_STEP * tier_k

    # the open hall: the pool and dais from the bed up, the amphitheatre from over each tier up, to the spring
    for x, z in np.argwhere(r < R):
        x, z = int(x), int(z)
        rv = r[x, z]
        if rv < POOL_OUT:
            g.air(x, f - POOL_BED + 1, z, x, s - 1, z)
        else:
            g.air(x, int(tier_top[x, z]) + 1, z, x, s - 1, z)

    # ---- the dome and the sea over it -----------------------------------------------------------------------------------------------
    y0, inside, glass, sea = dome(g.grid.shape, f, crown)
    ny = inside.shape[1]
    sub = g.grid[:, y0:y0 + ny, :]
    sub[inside] = 0
    rr = r[:, None, :]
    aa = ang[:, None, :]
    ys = np.arange(ny)[None, :, None] + y0
    off = np.abs((aa + 11.25) % 22.5 - 11.25)                          # degrees from the nearest of sixteen meridians
    meridian = rr * np.radians(off) < 1.0
    rings = np.zeros_like(glass)
    for frac in (0.0, 0.38, 0.72):
        rings |= ys == s + round(rise * frac)
    oculus = rr < 7
    oculus_rim = (rr >= 7) & (rr < 10)
    sub[glass] = g.id(CLEAR)
    sub[glass & (meridian | rings) & ~oculus] = g.id("rib")
    sub[glass & meridian & rings & ~oculus] = g.id("light")              # a lantern where a meridian crosses a ring
    sub[glass & oculus_rim] = g.id("accent")
    sub[glass & oculus] = g.id(CLEAR)
    sub[sea] = g.id(WATER)
    far = sea & _dilate(~(sea | glass | inside))                        # the far side of the sea, against the solid
    noise = np.random.default_rng(700 + floor).random(far.shape)
    sub[far & (noise < 0.03)] = g.id("light")                         # sparse: a dense field read as noise, not as the sea
    coral_ids = [g.id(c) for c in CORALS]
    for i, cid in enumerate(coral_ids):
        sub[far & (noise >= 0.03 + 0.006 * i) & (noise < 0.03 + 0.006 * (i + 1))] = cid

    # ---- the drum: a cornice under the spring, pilasters between sixteen arched aquarium windows -----------------------------------
    ring = (r >= R - 1) & (r < R)
    g.grid[:, s - 1, :][ring] = g.id("trim")
    for k in range(16):
        a = math.radians(22.5 * k)
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                x, z = round(MID + (R - 0.5) * math.cos(a)) + dx, round(CENTRE_Z + (R - 0.5) * math.sin(a)) + dz
                if not ring[x, z]:
                    continue
                u = abs(r[x, z] * math.radians((ang[x, z] - 22.5 * k + 180) % 360 - 180))
                if u <= 1.0:
                    g.box("pier", x, f + TIER_UP + TIER_STEP * (TIERS - 1) + 1, z, x, s - 2, z)
                    g.at("light", x, f + 24, z)
    for k in range(16):
        centre = 22.5 * k + 11.25
        for x, z in np.argwhere((r >= R) & (r < R + 5)):
            x, z = int(x), int(z)
            u = r[x, z] * math.radians((ang[x, z] - centre + 180) % 360 - 180)
            if abs(u) > 4.5 * r[x, z] / R:
                continue
            u = u * R / r[x, z]
            top = s - 7 + int(math.sqrt(max(0.0, 4.5 ** 2 - u * u)))
            depth = r[x, z] - R
            if depth < 1:
                g.box(CLEAR, x, f + WIN_UP, z, x, top, z)
            elif depth < 4:
                g.box(WATER, x, f + WIN_UP, z, x, top, z)
                if depth >= 2.5 and rnd.random() < 0.55:
                    g.box(rnd.choice(CORALS), x, f + WIN_UP, z, x, f + WIN_UP + rnd.randint(0, 6), z)
            else:
                for y in range(f + WIN_UP + 1, top + 1, 3):
                    if (x + z + y) % 2 == 0:
                        g.at("light", x, y, z)
        # a sill under the window and a keystone over it, on the drum face
        a = math.radians(centre)
        cx, cz = round(MID + (R - 0.5) * math.cos(a)), round(CENTRE_Z + (R - 0.5) * math.sin(a))
        g.at("accent", cx, s - 2, cz)

    # ---- the amphitheatre: five tiers, a lapis edge on each, lanterns in the risers, aisles every 45 degrees -------------------------
    tiers = (r >= POOL_OUT) & (r < R)
    for x, z in np.argwhere(tiers):
        x, z = int(x), int(z)
        k = int(tier_k[x, z])
        top = int(tier_top[x, z])
        front = any(0 <= x + dx < g.grid.shape[0] and 0 <= z + dz < g.grid.shape[2] and
                    (r[x + dx, z + dz] < POOL_OUT or tier_k[x + dx, z + dz] < k) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        aisle = abs(ang[x, z] % 45 - 22.5) * math.radians(1) * r[x, z] < 1.2       # at 22.5 + 45k: clear of the tunnels north and south
        g.at("inlay" if front or aisle else "floor", x, top, z)
        if front:
            low = f - POOL_BED if k == 0 else top - TIER_STEP
            g.box("wall_low", x, low, z, x, top - 1, z)
            if (k > 0 and (aisle or int(ang[x, z]) % 10 == 0)) or (k == 0 and int(ang[x, z]) % 8 == 0):
                g.at("light", x, top - 1, z)
    # the balustrade along the front of the lowest tier: every cell of it beside the drop, corners too
    for x, z in np.argwhere(tiers & (tier_k == 0)):
        x, z = int(x), int(z)
        if any(r[x + dx, z + dz] < POOL_OUT for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
            g.at("rail", x, f + TIER_UP + 1, z)

    # ---- the ring pool and the dais ---------------------------------------------------------------------------------------------------
    pool = (r > ARENA) & (r < POOL_OUT)
    for x, z in np.argwhere(pool):
        x, z = int(x), int(z)
        g.at("wall_low", x, f - POOL_BED, z)
        g.box(WATER, x, f - POOL_BED + 1, z, x, f - 2, z)
        rv, av = r[x, z], ang[x, z]
        if abs(rv - 24.5) < 0.5:
            g.at("inlay", x, f - POOL_BED, z)
        if (abs(rv - 21.5) < 0.5 or abs(rv - 28) < 0.5) and int(av) % 12 == 0:
            g.at("light", x, f - POOL_BED, z)
    dais = r <= ARENA
    for x, z in np.argwhere(dais):
        x, z = int(x), int(z)
        rv = r[x, z]
        g.box("wall", x, f - POOL_BED, z, x, ground, z)
        g.at("floor", x, ground, z)
        if rv > ARENA - 1:
            g.box("wall_low", x, f - POOL_BED + 1, z, x, ground - 1, z)
            if int(ang[x, z]) % 10 == 0:
                g.at("light", x, f - 3, z)
            g.at("trim", x, ground, z)
        elif abs(rv - 17.5) < 0.5:
            g.at("inlay", x, ground, z)
        elif abs(rv - 6.5) < 0.5:
            g.at("inlay", x, ground, z)
    tg.medallion(g, MID, CENTRE_Z, ground, 15 if crown else 14)
    for a in range(0, 360, 45):
        g.at("accent", round(MID + 18.5 * math.cos(math.radians(a + 22.5))), ground, round(CENTRE_Z + 18.5 * math.sin(math.radians(a + 22.5))))
    g.at("accent", MID, ground, CENTRE_Z)

    # coral growing on the pool bed, clear of the causeways, the columns and the falls
    keep_clear = column_centres() + fall_centres()
    for _ in range(140):
        x, z = rnd.randint(MID - 29, MID + 29), rnd.randint(CENTRE_Z - 29, CENTRE_Z + 29)
        if not pool[x, z] or r[x, z] > POOL_OUT - 1.5 or abs(x - MID) <= TUNNEL_HALF + 1:
            continue
        if any(abs(x - cx) <= 3 and abs(z - cz) <= 3 for cx, cz in keep_clear):
            continue
        g.box(rnd.choice(CORALS), x, f - POOL_BED + 1, z, x, f - POOL_BED + rnd.randint(1, 3), z)

    # ---- the causeways over the pool: a deck two thick, rails along both sides, piers down to the bed --------------------------------
    for x, z in np.argwhere(pool & (np.abs(xs - MID) <= TUNNEL_HALF)):
        x, z = int(x), int(z)
        edge = abs(x - MID) == TUNNEL_HALF
        g.at("wall_low", x, ground - 1, z)
        g.at("wall_low" if edge else "inlay" if x == MID else "floor", x, ground, z)
        if edge:
            g.at("rail", x, f, z)
            if z % 4 == 0:
                g.box("wall_low", x, f - POOL_BED + 1, z, x, ground - 2, z)
    # the dais's own balustrade, open where the causeways land
    for x, z in np.argwhere(dais):
        x, z = int(x), int(z)
        if abs(x - MID) < TUNNEL_HALF:
            continue
        if any(pool[x + dx, z + dz] for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
            g.at("rail", x, f, z)

    # ---- the colonnade: twelve water columns standing in the pool, a lantern glowing at the foot of each --------------------------------
    for cx, cz in column_centres():
        g.box(CLEAR, cx - 2, f - POOL_BED + 1, cz - 2, cx + 2, f + BEAM_UP - 1, cz + 2)
        g.box("wall_low", cx - 2, f - 2, cz - 2, cx + 2, ground, cz + 2)                # the base, where the column leaves the water
        g.box("trim", cx - 2, f + BEAM_UP - 2, cz - 2, cx + 2, f + BEAM_UP - 1, cz + 2)  # the capital
        g.box(WATER, cx - 1, f - POOL_BED + 1, cz - 1, cx + 1, f + BEAM_UP - 3, cz + 1)
        for y in (f - POOL_BED + 1, f, f + BEAM_UP - 3):                              # lanterns in the water: the column glows
            g.at("light", cx, y, cz)

    # ---- the entablature: a ring the columns carry, lit under its inner edge, lanterns and diamonds along its top ----------------------
    beam = (r >= BEAM[0]) & (r < BEAM[1])
    for x, z in np.argwhere(beam):
        x, z = int(x), int(z)
        g.box("trim", x, f + BEAM_UP, z, x, f + BEAM_UP + 1, z)
        av = int(ang[x, z])
        if r[x, z] < BEAM[0] + 1 and av % 6 == 0:
            g.at("light", x, f + BEAM_UP, z)
        if r[x, z] >= BEAM[1] - 1:
            g.at("rib", x, f + BEAM_UP + 2, z)
            if (av + 15) % 30 == 0:
                g.box("light", x, f + BEAM_UP + 2, z, x, f + BEAM_UP + 3, z)
            elif av % 30 == 0:
                g.at("accent", x, f + BEAM_UP + 3, z)

    # ---- the falls: a basin in the entablature's underside pours down a glass tube into the pool (owner 15 Nov: open falls read as
    # columns missing their glass; in a tube the moving water still tells them from the still columns) --------------------------------
    for fx, fz in fall_centres():
        g.box(CLEAR, fx - 2, f - POOL_BED + 1, fz - 2, fx + 2, f + BEAM_UP - 1, fz + 2)
        g.box(WATER, fx - 1, f - POOL_BED + 1, fz - 1, fx + 1, f - 2, fz + 1)           # the pool inside the tube, where the fall lands
        g.box(FALLING, fx - 1, ground, fz - 1, fx + 1, f + BEAM_UP - 1, fz + 1)
        for y in (f - 2, ground, f + BEAM_UP - 2, f + BEAM_UP - 1):                      # a base at the waterline and a capital, as rings
            for dx in range(-2, 3):
                for dz in range(-2, 3):
                    if max(abs(dx), abs(dz)) == 2:
                        g.at("wall_low" if y < f else "trim", fx + dx, y, fz + dz)
        g.box("trim", fx - 2, f + BEAM_UP, fz - 2, fx + 2, f + BEAM_UP + 1, fz + 2)       # the ring is curved: square the basin's walls
        g.box(WATER, fx - 1, f + BEAM_UP, fz - 1, fx + 1, f + BEAM_UP, fz + 1)

    # ---- the tunnels under the tiers: in from the passage, out to gate A --------------------------------------------------------------
    h = TUNNEL_HALF
    for z in range(HALL_Z[0], HALL_Z[1] + 1):
        if abs(z - CENTRE_Z) < POOL_OUT:
            continue
        g.air(MID - h, f, z, MID + h, f + TUNNEL_H - 1, z)
        g.box("floor", MID - h, ground, z, MID + h, ground, z)
        g.at("inlay", MID, ground, z)
        for x in (MID - h - 1, MID + h + 1):
            g.box("wall_low", x, f, z, x, f + 1, z)
        if abs(z - CENTRE_Z) > POOL_OUT + 6 and z % 4 == 0:
            g.box("trim", MID - h - 1, f, z, MID - h - 1, f + TUNNEL_H, z)
            g.box("trim", MID + h + 1, f, z, MID + h + 1, f + TUNNEL_H, z)
            g.box("trim", MID - h, f + TUNNEL_H, z, MID + h, f + TUNNEL_H, z)
        if z % 4 == 2:
            for x in (MID - h - 1, MID + h + 1):
                g.at("light", x, f + 3, z)
    for z in (round(CENTRE_Z - POOL_OUT), round(CENTRE_Z + POOL_OUT)):                 # the portals onto the causeways
        g.box("trim", MID - h - 1, f, z, MID - h - 1, f + TUNNEL_H - 1, z)
        g.box("trim", MID + h + 1, f, z, MID + h + 1, f + TUNNEL_H - 1, z)

    # ---- the chandelier: a great ring hung from the oculus over the dais ----------------------------------------------------------------
    hang(g, MID, CENTRE_Z, s + rise - 1, f + (36 if crown else 32), 8)


def surfaces(floor: int, shape: tuple[int, int, int]) -> dict[str, np.ndarray]:
    """The cells of a floor that must never be air (integrity_check): the dome's glass and ribs, the dais, the causeway decks, the pool bed."""
    f = feet(floor)
    crown = floor in CROWNS
    xs, zs, r, _ = plan(shape)
    y0, _, glass, _ = dome(shape, f, crown)
    out = {"dome glass": np.argwhere(glass) + np.array([0, y0, 0])}
    ground = f - 1
    dais = np.argwhere(r <= ARENA)
    out["dais floor"] = np.column_stack([dais[:, 0], np.full(len(dais), ground), dais[:, 1]])
    pool = (r > ARENA) & (r < POOL_OUT)
    deck = np.argwhere(pool & (np.abs(xs - MID) < TUNNEL_HALF))
    out["causeway decks"] = np.column_stack([deck[:, 0], np.full(len(deck), ground), deck[:, 1]])
    bed = np.argwhere(pool)
    out["pool bed"] = np.column_stack([bed[:, 0], np.full(len(bed), f - POOL_BED), bed[:, 1]])
    return out




def fenced(g: Grid) -> list[str]:
    """Nobody steps off into the pool: every standable cell on the dais, the causeways and the lowest tier has no open drop beside it, corners
    included (a diagonal gap in a balustrade lets a player squeeze through)."""
    problems = []
    w = tg.Walker(g)
    _, _, r, _ = plan(g.grid.shape)
    # the building's own floors: the ones whose dais centre is here (buildings 1 and 3 share their feet, so a feet value is checked once)
    mine = sorted({feet(k) for b in BUILDINGS for k in b if feet(k) + TIER_UP + 2 < g.grid.shape[1]
                   and g.name(MID, feet(k) - 1, CENTRE_Z) == g.theme["accent"]})
    for f in mine:
        for y, zone in ((f, r < POOL_OUT), (f + TIER_UP + 1, (r >= POOL_OUT) & (r < POOL_OUT + 3))):
            for x, z in np.argwhere(zone):
                x, z = int(x), int(z)
                if not w.standable(x, y, z):
                    continue
                if any(w.passable(x + dx, y, z + dz) and w.passable(x + dx, y - 1, z + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
                    problems.append(f"feet {f}: an open drop beside ({x}, {y}, {z})")
    return problems[:8] + ([f"... {len(problems) - 8} more"] if len(problems) > 8 else [])


def checks(g: Grid) -> list[str]:
    return spills(g) + fenced(g)


import boss_tideforge as boss_halls  # noqa: E402  (after this module's names: it uses some of them)

SPEC = gh.Spec(
    theme=THEME, buildings=BUILDINGS, boss={5: boss_halls.BOSS5, 10: boss_halls.BOSS10}, crowns=CROWNS, pitch=PITCH, base_feet=BASE_FEET,
    top=lambda crown: sum(spring_rise(crown)) + SEA + 3,
    hall=hall,
    entry_z=round(CENTRE_Z + ARENA) - 5, presentation_z=round(CENTRE_Z - ARENA) + 6,
    spectator=(MID - 38, TIER_UP + TIER_STEP * 2 + 1, CENTRE_Z),
    checks=checks,
    summary="arrival -> tunnel -> causeway -> dais -> causeway -> tunnel -> gate A -> door -> chamber -> its own stair -> threshold; "
            "no water can run out; no open drop into the pool",
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
