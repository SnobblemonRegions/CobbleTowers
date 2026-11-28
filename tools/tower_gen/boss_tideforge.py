"""Tideforge's boss halls (owner, 15 Nov 2026: floors 5 and 10 fully rebuilt, unlike each other and every other floor).

FLOOR 5, THE BUBBLE. A glass sphere 80 across standing on the seabed with the sea all round it: kelp forests, seagrass, coral reefs, sea
pickles glowing on the sand. The party walks out along a glass tube from the passage into the sphere, whose floor is a stone dais ringed by
a glass floor with the reef under it; spectators stand on a ring balcony round the sphere's waist.

FLOOR 10, THE LEVIATHAN. A dark abyssal cavern, black water below, the bones of a colossal sea beast overhead: a spine down the length of
the hall, ribs arching down into the abyss, its skull at the north end with gate A in its throat and glowing eyes. The arena is a platform
on its breastbone; a glowing lure hangs over it like an anglerfish's. Bioluminescence everywhere: sea pickles, pale lights along the bones.
"""
from __future__ import annotations

import math
import random

import numpy as np

import boss_kit as K
import grand_hall as gh
import tower_gen as tg
from boss_kit import CZ, MID, X0, X1, Z0, Z1, CLEAR, WATER
from tower_gen import CHAIN, Grid

SAND, GRAVEL = "minecraft:sand", "minecraft:gravel"
KELP, KELP_TOP, SEAGRASS = "minecraft:kelp_plant", "minecraft:kelp[age=25]", "minecraft:seagrass"
PICKLE = "minecraft:sea_pickle[pickles=4,waterlogged=true]"
FANS = ("minecraft:tube_coral_fan[waterlogged=true]", "minecraft:brain_coral_fan[waterlogged=true]",
        "minecraft:fire_coral_fan[waterlogged=true]", "minecraft:horn_coral_fan[waterlogged=true]")
BONE = "minecraft:bone_block[axis=y]"
BONE_X, BONE_Z = "minecraft:bone_block[axis=x]", "minecraft:bone_block[axis=z]"
GLOW = "minecraft:pearlescent_froglight"
BIO = "minecraft:verdant_froglight"
DARK = "minecraft:dark_prismarine"

# ---- floor 5: the bubble ----------------------------------------------------------------------------------------------------------------
SPHERE_R = 40
SPHERE_UP = 14                                  # the centre above the feet
SEA = 16
BED = 10                                        # the seabed this far under the feet
BALCONY_UP = 12
DAIS_R = 19


def bubble(g: Grid, floor: int, f: int) -> None:
    rnd = random.Random(5005)
    ground = f - 1
    y0 = f - BED - 1
    ny = SPHERE_UP + SPHERE_R + SEA + BED + 3
    sx, _, sz = g.grid.shape
    centre = (MID, f + SPHERE_UP, CZ)
    inside = K.sphere_mask(g.grid.shape, y0, ny, centre, SPHERE_R)
    ys = np.arange(ny)[None, :, None] + y0
    inside &= ys >= f                                                       # the floor is a chord through the sphere
    xs = np.arange(sx)[:, None, None]
    zs = np.arange(sz)[None, None, :]
    for za, zb in ((Z0, CZ - 30), (CZ + 30, Z1)):                          # the glass tubes in from the passage and out to gate A
        tube = (abs(xs - MID) <= 3) & (ys >= f) & (ys <= f + 5) & (zs >= za) & (zs <= zb)
        inside |= tube
    glass = K.dilate(inside) & ~inside
    reach = inside
    for _ in range(SEA):
        reach = K.dilate(reach)
    sea = reach & ~inside & ~glass & (ys > f - BED) & (zs >= Z0 + 4) & (zs <= Z1 - 4)
    sea &= (xs >= X0) & (xs <= X1)
    glass &= (zs >= Z0) & (zs <= Z1)
    sub = g.grid[:, y0:y0 + ny, :]
    sub[inside] = 0
    sub[glass & (ys >= f)] = g.id(CLEAR)
    sub[sea] = g.id(WATER)
    # the floor inside: a stone dais, a glass ring over the reef, the tubes' floors
    _, _, r, ang = K.plan(g.grid.shape)
    floor_ring = K.sphere_mask(g.grid.shape, ground, 1, centre, SPHERE_R)[:, 0, :]
    for x, z in np.argwhere(floor_ring):
        x, z = int(x), int(z)
        d = r[x, z]
        if d <= DAIS_R:
            g.at("floor", x, ground, z)
            if d > DAIS_R - 1:
                g.at("trim", x, ground, z)
        else:
            g.at(CLEAR, x, ground, z)                                       # look down through it at the reef
            g.box(WATER, x, f - BED + 1, z, x, ground - 1, z)
    for x, z in np.argwhere(r <= DAIS_R):
        g.box("wall", int(x), f - BED, int(z), int(x), ground - 1, int(z))  # the dais stands on a stone drum down to the bed
    tg.medallion(g, MID, CZ, ground, 15)
    g.at("accent", MID, ground, CZ)
    for a in range(0, 360, 30):                                              # lights set in the dais's rim
        g.at("light", round(MID + (DAIS_R - 0.5) * math.cos(math.radians(a))), ground, round(CZ + (DAIS_R - 0.5) * math.sin(math.radians(a))))
    for za, zb in ((Z0, CZ - 30), (CZ + 30, Z1)):
        g.box("floor", MID - 3, ground, za, MID + 3, ground, zb)
        g.box("inlay", MID, ground, za, MID, ground, zb)
        for z in range(za, zb + 1, 6):
            for x in (MID - 4, MID + 4):
                g.at("light", x, f + 3, z) if g.name(x, f + 3, z) == CLEAR else None
    # the seabed: sand and gravel, kelp, seagrass, coral and pickles
    bed_y = f - BED
    for x in range(X0, X1 + 1):
        for z in range(Z0 + 4, Z1 - 3):
            if g.name(x, bed_y + 1, z) != WATER:
                continue
            g.at(GRAVEL if (x * 3 + z * 7) % 11 == 0 else SAND, x, bed_y, z)
            roll = rnd.random()
            if roll < 0.05:                                                  # kelp up to just under the surface or the glass
                h = rnd.randint(6, 22)
                y = bed_y + 1
                while y < bed_y + h and g.name(x, y + 1, z) == WATER:
                    g.at(KELP, x, y, z)
                    y += 1
                g.at(KELP_TOP, x, y, z)
            elif roll < 0.12:
                g.at(SEAGRASS, x, bed_y + 1, z)
            elif roll < 0.15:
                g.at(rnd.choice(K.CORALS), x, bed_y + 1, z)
                if g.name(x, bed_y + 2, z) == WATER:
                    g.at(rnd.choice(FANS), x, bed_y + 2, z)
            elif roll < 0.165:
                g.at(PICKLE, x, bed_y + 1, z)
    for _ in range(26):                                                       # reefs: coral mounds
        cx, cz = rnd.randint(X0 + 6, X1 - 6), rnd.randint(Z0 + 8, Z1 - 8)
        for dx in range(-4, 5):
            for dz in range(-4, 5):
                h = 4 - int(math.hypot(dx, dz))
                for dy in range(1, h + 1):
                    if g.name(cx + dx, bed_y + dy, cz + dz) == WATER:
                        g.at(rnd.choice(K.CORALS), cx + dx, bed_y + dy, cz + dz)
    far = sea & K.dilate(~(sea | glass | inside))                             # the sea's far side against the rock: dim lights
    noise = np.random.default_rng(55).random(far.shape)
    sub[far & (noise < 0.02)] = g.id("light")
    # the balcony round the sphere's waist for the spectators
    by = f + BALCONY_UP
    rr = math.sqrt(SPHERE_R ** 2 - (BALCONY_UP - SPHERE_UP) ** 2)
    deck = (r > rr - 7) & (r < rr) & (np.abs(K.plan(g.grid.shape)[0] - MID) > 6)
    for x, z in np.argwhere(deck):
        if g.grid[int(x), by, int(z)] == 0:
            g.at("rib", int(x), by, int(z))
    K.rail_edge(g, by + 1, deck, (r <= rr - 7))
    for x, z in np.argwhere(deck & (np.abs(K.plan(g.grid.shape)[0] - MID) <= 8)):
        g.at("rail", int(x), by + 1, int(z))                                  # the deck's ends, over the tubes
    gh.hang(g, MID, CZ, f + SPHERE_UP + SPHERE_R - 1, f + 28, 7)          # from the last air under the glass, so it hangs from it


def bubble_checks(g: Grid) -> list[str]:
    f = BOSS5.base_feet
    _, _, r, _ = K.plan(g.grid.shape)
    rr = math.sqrt(SPHERE_R ** 2 - (BALCONY_UP - SPHERE_UP) ** 2)
    return K.spills(g) + K.drops(g, f, [(f + BALCONY_UP + 1, (r > rr - 7) & (r < rr))])


BOSS5 = gh.Boss(hall=bubble, top=SPHERE_UP + SPHERE_R + SEA + 3, base_feet=BED + 3,
                entry_z=CZ + 14, presentation_z=CZ - 13, spectator=(MID - 36, BALCONY_UP + 1, CZ),
                checks=bubble_checks)

# ---- floor 10: the leviathan ------------------------------------------------------------------------------------------------------------
CAVE = (64, 46)
ROOF_UP = 56
ABYSS = 26
SPINE_UP = 44
ARENA = (MID - 22, MID + 22, CZ - 16, CZ + 16)
PATH_HALF = 3
LEDGE_UP = 14


def leviathan(g: Grid, floor: int, f: int) -> None:
    rnd = random.Random(1010)
    ground = f - 1
    e = K.ell(g.grid.shape, *CAVE)
    xs, zs, r, ang = K.plan(g.grid.shape)
    cave = e < 1
    # the cavern: a dome of rough dark stone over a black abyss of water
    y0 = f - ABYSS - 1
    ny = ABYSS + ROOF_UP + 4
    ys = np.arange(ny)[None, :, None] + y0
    roof = f + ROOF_UP * np.sqrt(np.clip(1 - e ** 2, 0, 1))[:, None, :] * 0.35 + ROOF_UP * 0.65
    inside = cave[:, None, :] & (ys >= f - ABYSS) & (ys <= roof)
    sub = g.grid[:, y0:y0 + ny, :]
    sub[inside] = 0
    shell = K.dilate(inside) & ~inside
    noise = np.random.default_rng(1011).random(shell.shape)
    sub[shell & (sub != 0)] = g.id(DARK)
    sub[shell & (noise < 0.18)] = g.id("wall_low")
    sub[shell & (noise > 0.985) & (ys > f + 4)] = g.id(BIO)                 # glowing spots in the cavern's skin
    sub[inside & (ys <= ground - 1)] = g.id(WATER)                          # the abyss, its surface two under the feet
    for x, z in np.argwhere(cave):
        x, z = int(x), int(z)
        g.at("minecraft:gravel", x, f - ABYSS, z)
        if rnd.random() < 0.02:
            g.at(PICKLE, x, ground - 1 - rnd.randint(0, 2), z) if g.name(x, ground - 1, z) == WATER else None
    # the walk: a path down the middle on vertebra plates, the arena on the breastbone
    ax0, ax1, az0, az1 = ARENA
    walk = ((np.abs(xs - MID) <= PATH_HALF) & cave) | ((xs >= ax0) & (xs <= ax1) & (zs >= az0) & (zs <= az1))
    for x, z in np.argwhere(walk):
        x, z = int(x), int(z)
        g.box(BONE, x, f - 5, z, x, ground - 1, z)
        g.at("floor" if (x - MID) % 4 else BONE_Z, x, ground, z)
    for x, z in np.argwhere((xs >= ax0) & (xs <= ax1) & (zs >= az0) & (zs <= az1)):
        x, z = int(x), int(z)
        g.at(DARK, x, ground, z)
        if x in (ax0, ax1) or z in (az0, az1):
            g.at("light", x, ground, z) if (x + z) % 3 == 0 else g.at("inlay", x, ground, z)
    tg.medallion(g, MID, CZ, ground, 12)
    g.at(GLOW, MID, ground, CZ)
    for x, z in np.argwhere((xs >= ax0 - 2) & (xs <= ax1 + 2) & (zs >= az0 - 2) & (zs <= az1 + 2) & ~walk):
        if (int(x) + int(z)) % 6 == 0:
            g.box(BONE, int(x), f - ABYSS + 1, int(z), int(x), ground - 1, int(z))  # the sternum's struts down into the dark
    K.rail_edge(g, f, walk, cave & ~walk)
    # the skeleton: a spine overhead, ribs arching down into the abyss
    spine = f + SPINE_UP
    for z in range(Z0 + 12, Z1 - 6):
        g.box(BONE_Z, MID - 1, spine, z, MID + 1, spine + 1, z)
        if z % 4 == 0:
            g.box(BONE, MID - 2, spine - 1, z, MID + 2, spine + 3, z)            # a vertebra
            g.box(BONE, MID, spine + 4, z, MID, spine + 6, z)                    # its spur
            g.at(BIO, MID, spine - 2, z)
    for z in range(Z0 + 18, Z1 - 10, 8):
        half_span = CAVE[0] * math.sqrt(max(0.0, 1 - ((z - CZ) / CAVE[1]) ** 2)) - 6
        for side in (-1, 1):
            for t in np.linspace(0, 1, 160):
                a = t * math.pi / 2
                x = MID + side * half_span * math.sin(a)
                y = spine - (spine - (f - 4)) * (1 - math.cos(a))
                th = 2                                                   # two thick all the way: a thinner curve breaks at its diagonal steps
                for dx in range(-th + 1, th):
                    g.box(BONE, round(x) + dx, round(y) - th + 1, z, round(x) + dx, round(y), z + 1)
                if 0.15 < t < 0.95 and rnd.random() < 0.05:
                    g.at(GLOW, round(x), round(y) - th, z)
    # the skull at the north end: an upper jaw over the way out, teeth, two glowing eyes
    sz0 = Z0
    for x in range(MID - 16, MID + 17):
        for y in range(f + 6, f + 30):
            dx = abs(x - MID)
            if (dx / 16) ** 2 + ((y - f - 6) / 24) ** 2 <= 1:
                g.box(BONE, x, y, sz0, x, y, sz0 + 9)
    for x in range(MID - 12, MID + 13, 3):
        g.box(BONE, x, f + 4, sz0 + 8, x, f + 5, sz0 + 9)                         # teeth, clear of the way under them
    for ex in (MID - 8, MID + 8):
        g.box(GLOW, ex - 2, f + 15, sz0 + 9, ex + 2, f + 18, sz0 + 9)
    g.air(MID - PATH_HALF, f, sz0, MID + PATH_HALF, f + 5, sz0 + 10)
    K.close_short(g, MID - 2, Z0 + 12, MID + 2, Z1 - 6, spine - 1, f + ROOF_UP + 1)    # spurs that all but touch the roof reach it
    K.fill_dents(g, MID - 8, f + ROOF_UP - 4, CZ - 8, MID + 8, f + ROOF_UP + 2, CZ + 8, DARK)
    # the lure: a glowing orb hanging on a long stalk from the spine over the arena
    g.box(CHAIN, MID, f + 22, CZ, MID, spine - 1, CZ)
    for x in range(MID - 4, MID + 5):
        for y in range(f + 14, f + 23):
            for z in range(CZ - 4, CZ + 5):
                if math.hypot(x - MID, y - f - 18, z - CZ) <= 4.2:
                    g.at(GLOW, x, y, z)
    # the spectators' ledge on the west wall of the cavern, railed
    ledge = (e >= 0.84) & (e < 1.0) & (xs < MID - 40) & (np.abs(zs - CZ) <= 14)
    for x, z in np.argwhere(ledge):
        x, z = int(x), int(z)
        if g.grid[x, f + LEDGE_UP, z] == 0:
            g.at("wall_low", x, f + LEDGE_UP, z)
            g.at(BIO if (x + z) % 9 == 0 else "wall_low", x, f + LEDGE_UP - 1, z)
    K.rail_edge(g, f + LEDGE_UP + 1, ledge, cave & ~ledge)
    # the ways in and out along the path
    K.rail_edge(g, f, walk, cave & ~walk)                                  # again: the skull's way cut the rails under it
    K.ways(g, f, Z0 + 1, Z1 - 2, half=PATH_HALF, rib_every=0)


def leviathan_checks(g: Grid) -> list[str]:
    f = BOSS10.base_feet
    e = K.ell(g.grid.shape, *CAVE)
    xs, zs, _, _ = K.plan(g.grid.shape)
    ledge = (e >= 0.84) & (e < 1.0) & (xs < MID - 40) & (np.abs(zs - CZ) <= 14)
    ax0, ax1, az0, az1 = ARENA
    walk = ((np.abs(xs - MID) <= PATH_HALF) & (e < 1)) | ((xs >= ax0) & (xs <= ax1) & (zs >= az0) & (zs <= az1))
    return K.spills(g) + K.drops(g, f, [(f, walk), (f + LEDGE_UP + 1, ledge)])


def leviathan_floating() -> list[tuple[int, int, int]]:
    f = BOSS10.base_feet
    return [(MID, f + 18, CZ)]


BOSS10 = gh.Boss(hall=leviathan, top=ROOF_UP + 5, base_feet=ABYSS + 3,
                 entry_z=CZ + 12, presentation_z=CZ - 10, spectator=(MID - 58, LEDGE_UP + 1, CZ),
                 checks=leviathan_checks, floating=[])
