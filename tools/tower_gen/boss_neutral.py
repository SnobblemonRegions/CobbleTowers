"""Neutral's boss halls (owner, 15 Nov 2026: floors 5 and 10 fully rebuilt, unlike each other and every other floor).

FLOOR 5, THE COLOSSEUM. An elliptical amphitheatre 128 x 92 under a glass velarium with the real noon sky over it, a red and white awning
round its rim. A sand arena ringed by a podium hung with red banners; seven tiers of seating rise to a three-storey arcade of quartz arches,
gold keystones, backlit like openings onto the day. The party comes in through tunnels under the tiers.

FLOOR 10, THE THRONE HALL. A nave 36 wide and 40 high under a coffered barrel vault, aisles behind arcades of quartz columns with gold
capitals, a spectators' gallery over each aisle, clerestory windows, red banners and gold chandeliers. At the north end the throne of the
tower stands on a dais the party walks under to reach gate A: gold, red, a halo of light behind it, stairs up either side.
"""
from __future__ import annotations

import math

import numpy as np

import boss_kit as K
import grand_hall as gh
import tower_gen as tg
from boss_kit import CZ, MID, X0, X1, Z0, Z1
from tower_gen import Grid

SAND = "minecraft:smooth_sandstone"
WHITE = "minecraft:white_wool"

# ---- floor 5: the colosseum -------------------------------------------------------------------------------------------------------------
RX, RZ = 64, 46
ARENA_E = 0.5
TIER_E, TIERS = 0.0629, 7
ROOF_UP = 48
OPEN_E = 0.72


def colosseum(g: Grid, floor: int, f: int) -> None:
    ground = f - 1
    e = K.ell(g.grid.shape, RX, RZ)
    _, _, _, ang = K.plan(g.grid.shape)
    tier = np.clip(((e - ARENA_E) // TIER_E).astype(int), 0, TIERS - 1)
    top = f + 6 + 2 * tier
    inner = e < 0.94
    for x, z in np.argwhere(inner):
        x, z = int(x), int(z)
        if e[x, z] < ARENA_E:
            g.air(x, f, z, x, f + ROOF_UP - 1, z)
            g.at(SAND, x, ground, z)
            if e[x, z] > ARENA_E - 0.035:
                g.at("inlay", x, ground, z)
        else:
            t = int(top[x, z])
            g.air(x, t + 1, z, x, f + ROOF_UP - 1, z)
            k = int(tier[x, z])
            front = any(e[x + dx, z + dz] < ARENA_E + k * TIER_E for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            g.at("inlay" if front else "floor", x, t, z)
            if front:
                g.box("wall_low", x, t - (TIER_STEP_FACE if k else 7), z, x, t - 1, z)
                if k == 0:
                    stripe = int(ang[x, z] * 55 / 57.3) % 7
                    if stripe < 3 and abs(x - MID) > 6:
                        g.box("hanging", x, f, z, x, f + 4, z)        # red banners hung on the podium
                        g.at("accent", x, f + 5, z)
                elif int(ang[x, z]) % 9 == 0:
                    g.at("light", x, t - 1, z)
    # the arcade: three storeys of arches round the rim, backlit like openings onto the day
    face = (e >= 0.94) & (e < 0.975)
    behind = (e >= 0.975) & (e < 1.01)
    for x, z in np.argwhere(face):
        x, z = int(x), int(z)
        u = math.radians(ang[x, z]) * 55
        for s in range(3):
            yb = f + 19 + 10 * s
            pier = u % 8 < 2
            for y in range(yb, yb + 10):
                local = y - yb
                if local == 9:
                    g.at("trim", x, y, z)                              # the cornice
                elif pier:
                    g.at("pier", x, y, z)
                else:
                    c = (u % 8) - 5                                    # -3 .. +3 across the opening
                    arch_top = 5 + int(math.sqrt(max(0.0, 9 - c * c)))
                    if local < arch_top:
                        g.at("glass", x, y, z)
                    elif local == arch_top and abs(c) < 0.6:
                        g.at("accent", x, y, z)                        # the keystone
                    else:
                        g.at("wall", x, y, z)
    for x, z in np.argwhere(behind):
        g.box("light", int(x), f + 19, int(z), int(x), f + 46, int(z))
    # the velarium: glass over the middle with the sky above it, a striped awning round the rim
    for x, z in np.argwhere(inner):
        x, z = int(x), int(z)
        if e[x, z] < OPEN_E:
            g.at(K.CLEAR, x, f + ROOF_UP, z)
            g.air(x, f + ROOF_UP + 1, z, x, g.grid.shape[1] - 1, z)
        else:
            g.at("hanging" if int(ang[x, z] / 4) % 2 else WHITE, x, f + ROOF_UP, z)
    # the podium's balustrade, the arena's centre
    rail_zone = (e >= ARENA_E) & (tier == 0)
    K.rail_edge(g, f + 7, rail_zone, e < ARENA_E)
    tg.medallion(g, MID, CZ, ground, 10)
    g.at("accent", MID, ground, CZ)
    # the ways in and out, under the tiers
    K.ways(g, f, int(CZ - RZ * ARENA_E), int(CZ + RZ * ARENA_E))


TIER_STEP_FACE = 2


def colosseum_checks(g: Grid) -> list[str]:
    f = BOSS5.base_feet
    e = K.ell(g.grid.shape, RX, RZ)
    return K.drops(g, f, [(f + 7, (e >= ARENA_E) & (e < ARENA_E + TIER_E))])


BOSS5 = gh.Boss(hall=colosseum, top=ROOF_UP + 4, base_feet=9,
                entry_z=CZ + 16, presentation_z=CZ - 14, spectator=(MID - 46, 13, CZ),
                checks=colosseum_checks, open_sky=True)

# ---- floor 10: the throne hall ----------------------------------------------------------------------------------------------------------
NAVE = 18                      # half width
AISLE = (36, 112)              # outer walls of the aisles
SPRING, RISE = 26, 14
GALLERY_UP = 12
COLS = range(Z1 - 8, 64, -10)  # arcade columns, south to north, clear of the dais


def throne_hall(g: Grid, floor: int, f: int) -> None:
    ground = f - 1
    nx0, nx1 = MID - NAVE, MID + NAVE
    # the nave under a barrel vault, coffered
    for x in range(nx0, nx1 + 1):
        t = f + SPRING + gh.ellipse(x, nx0 - 1, nx1 + 1, RISE)
        g.air(x, f, Z0, x, t, Z1)
        g.box("vault", x, t + 1, Z0, x, t + 1, Z1)
        for z in range(Z0, Z1 + 1):
            if z % 6 == 0 or (x - MID) % 6 == 0:
                g.at("rib", x, t, z)
                if z % 6 == 0 and (x - MID) % 6 == 0:
                    g.at("accent", x, t, z)
    for x in (nx0 - 1, nx1 + 1):                                          # light coves along the spring
        g.box("trim", x, f + SPRING - 1, Z0, x, f + SPRING - 1, Z1)
        for z in range(Z0 + 1, Z1, 3):
            g.at("light", x, f + SPRING - 2, z)
    # the aisles, a flat coffered ceiling; the arcade between them and the nave
    for xa, xb in ((AISLE[0], nx0 - 1), (nx1 + 1, AISLE[1])):
        g.air(xa, f, Z0 + 18, xb, f + 20, Z1)
        g.box("vault", xa, f + 21, Z0 + 18, xb, f + 21, Z1)
        g.box("floor", xa, ground, Z0 + 18, xb, ground, Z1)
        for z in range(Z0 + 18, Z1 + 1, 5):
            g.box("rib", xa, f + 20, z, xb, f + 20, z)
    for side in (-1, 1):
        cx = MID + side * NAVE
        g.box("wall", cx - 1, f, Z0 + 18, cx + 1, f + 25, Z1)                 # the arcade wall, then its arches cut
        for za, zb in zip(list(COLS)[1:], list(COLS)[:-1]):
            mid_z = (za + zb) / 2
            half = (zb - za) / 2 - 2
            for z in range(za + 2, zb - 1):
                c = abs(z - mid_z)
                top = f + 14 + int(math.sqrt(max(0.0, half * half - c * c)) * 1.7)      # tall enough to watch through from the gallery
                g.air(cx - 1, f, z, cx + 1, top, z)
        for z in COLS:
            g.box("pier", cx - 1, f, z - 1, cx + 1, f + 18, z + 1)
            g.box("trim", cx - 1, f + 19, z - 1, cx + 1, f + 20, z + 1)
            g.at("accent", cx, f + 20, z)
            # clerestory windows and red banners on the nave wall above the arcade
            wx = cx + side
            g.box("glass", cx, f + 27, z - 2, cx, f + 34, z + 2)
            g.box("light", wx, f + 27, z - 2, wx, f + 34, z + 2)
            bz = z + 5
            g.box("hanging", cx - side, f + 22, bz - 1, cx - side, f + 31, bz + 1)
            g.at("accent", cx - side, f + 32, bz)
    # the galleries over the aisles, for the spectators
    for xa, xb, rail in ((AISLE[0], nx0 - 2, nx0 - 2), (nx1 + 2, AISLE[1], nx1 + 2)):
        g.box("rib", xa, f + GALLERY_UP, Z0 + 18, xb, f + GALLERY_UP, Z1)
        g.box("rail", rail, f + GALLERY_UP + 1, Z0 + 18, rail, f + GALLERY_UP + 1, Z1)
        for z in range(Z0 + 20, Z1, 6):
            g.at("light", rail, f + GALLERY_UP, z)
    # the floor: andesite, a red runner with gold borders
    g.box("floor", nx0, ground, Z0, nx1, ground, Z1)
    g.box("inlay", MID - 3, ground, Z0, MID + 3, ground, Z1)
    for x in (MID - 4, MID + 4):
        g.box("accent", x, ground, 64, x, ground, Z1)
    # the throne: a dais across the north end the party walks under, stairs up either side, the throne on it
    g.box("wall_low", MID - 14, f, Z0, MID + 14, f + 7, Z0 + 12)
    g.box("trim", MID - 14, f + 7, Z0, MID + 14, f + 7, Z0 + 12)
    for k in range(8):
        for sx in (MID - 13, MID + 9):
            g.box("trim", sx, f, Z0 + 13 + k, sx + 4, f + 6 - k, Z0 + 13 + k)
    g.air(MID - 4, f, Z0, MID + 4, f + 5, Z0 + 12)                          # the way to gate A under it, framed in gold
    g.box("floor", MID - 4, ground, Z0, MID + 4, ground, Z0 + 12)
    g.box("accent", MID - 5, f, Z0 + 12, MID - 5, f + 6, Z0 + 12)
    g.box("accent", MID + 5, f, Z0 + 12, MID + 5, f + 6, Z0 + 12)
    g.box("accent", MID - 5, f + 6, Z0 + 12, MID + 5, f + 6, Z0 + 12)
    seat = f + 8
    g.box("accent", MID - 5, seat, Z0 + 2, MID + 5, seat + 2, Z0 + 7)           # the seat
    g.box("hanging", MID - 3, seat + 3, Z0 + 3, MID + 3, seat + 3, Z0 + 7)      # its cushion
    g.box("accent", MID - 6, seat, Z0 + 1, MID - 5, seat + 6, Z0 + 7)           # the arms
    g.box("accent", MID + 5, seat, Z0 + 1, MID + 6, seat + 6, Z0 + 7)
    g.box("accent", MID - 5, seat + 3, Z0 + 1, MID + 5, seat + 20, Z0 + 2)      # the back, tall, red in a gold frame
    g.box("hanging", MID - 3, seat + 4, Z0 + 2, MID + 3, seat + 18, Z0 + 2)
    for dy in range(-12, 13):                                                  # a halo of light behind it
        for dx in range(-12, 13):
            d = math.hypot(dx, dy)
            if 10 <= d < 12:
                g.at("light", MID + dx, seat + 18 + dy, Z0)
    for z in (80, 104, 128):
        top = f + SPRING + RISE - 1
        gh.hang(g, MID, z, top, f + 24, 6, frame_role="accent")
    K.corridor(g, f, Z0 - 2, Z0, rib_every=0)


def throne_checks(g: Grid) -> list[str]:
    f = BOSS10.base_feet
    xs, zs, _, _ = K.plan(g.grid.shape)
    zone = ((xs >= AISLE[0]) & (xs <= MID - NAVE - 2)) | ((xs >= MID + NAVE + 2) & (xs <= AISLE[1]))
    zone &= (zs >= Z0 + 18) & (zs <= Z1)                                  # the galleries run the aisles' length, no further
    return K.drops(g, f, [(f + GALLERY_UP + 1, zone)])


BOSS10 = gh.Boss(hall=throne_hall, top=SPRING + RISE + 4, base_feet=9,
                 entry_z=Z1 - 16, presentation_z=74, spectator=(MID - 28, GALLERY_UP + 1, CZ),
                 checks=throne_checks)                               # the galleries' rails: throne_checks (they stop short of the dais)
