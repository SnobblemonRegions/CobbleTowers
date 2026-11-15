#!/usr/bin/env python3
"""Duskvale's own buildings (P59): THE SHATTERED CATHEDRAL.

    py -3.14 -I tools/tower_gen/duskvale.py [--check] [--install]

A gothic nave 132 x 96 under a pointed vault, broken open over the middle: through the holes, a night sky (black, stars hung from it). The
tower dimension stands at noon and its floors are stacked, so the sky is part of the building: a dark ceiling a storey above the vault.
Pieces of the broken roof hang in the air where they came apart (Disruption), some of the clustered piers are snapped off with their tops
floating over them. The arena is an island over a chasm torn through the floor, hanging on an upside-down spire; two bridges cross the
chasm, a balustrade runs round every edge of it (nobody falls in). Over gate A the rose window is shattered and the moon shows through it
from a chamber behind. Lancet windows in moonlight down both walls above the spectator galleries; candelabra down the nave; moonlight leaks up
through cracks in the floor.

Pieces meant to hang free are listed per building in <stem>.floating.json beside the written structures, so stray_check and
integrity_check pass them and still find any other loose block. Shared parts and the walk check: grand_hall.py.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
from grand_hall import HALL_X, HALL_Z, MID, PASSAGE_Z, hang, rose  # noqa: E402
from tower_gen import Grid  # noqa: E402

THEME = "duskvale"
BUILDINGS = ((1, 2), (3, 4, 5), (6, 7), (8, 9, 10))
CROWNS = (5, 10)
HALL = (24, 22)                         # spring, rise of the pointed vault above the feet
CROWN = (28, 26)
SKY = 16                                # the night ceiling stands this far above the vault's peak
CHASM = 14                              # the chasm is open from feet - 14 to the floor; its bed at feet - 15
PITCH = 84                              # a floor runs from its chasm bed (feet - 16) to its sky (feet + 65); the next floor's bed must clear it
BASE_FEET = 17

GALLERIES = ((9, 16, 16), (133, 140, 133))         # deck x0..x1, rail x
GALLERY_UP = 11
BRIDGE_HALF = gh.PASSAGE_HALF
CENTRE_Z = 94
CHASM_R = (36, 31)                      # the chasm's half axes (x, z) round (MID, CENTRE_Z), ragged
ISLAND_R = (21, 19)
PIERS_X = (30, 119)
PIERS_Z = range(53, 140, 12)
WINDOWS_Z = range(59, 140, 12)
HOLES = ((MID - 24, 70, 16, 11), (MID + 26, 62, 11, 8), (MID + 18, 112, 15, 10), (MID - 30, 120, 9, 8), (MID + 40, 96, 7, 9))

ABYSS = "minecraft:sculk"
ROCK = "minecraft:cobbled_deepslate"
CRACKED = "minecraft:cracked_deepslate_tiles"
RUBBLE = "minecraft:cobbled_deepslate_slab[type=bottom]"
NIGHT = "minecraft:black_concrete"
STAR = "minecraft:end_rod[facing=down]"
STAR_WALL = "minecraft:end_rod[facing=south]"
CLUSTER = "minecraft:amethyst_cluster[facing=up]"

FLOATING: dict[int, list[tuple[int, int, int]]] = {}        # floor -> one block of each piece meant to hang free
CHASMS: dict[int, set[tuple[int, int]]] = {}                # floor -> the chasm's cells (x, z), for the fence check
FLOOR_FEET: dict[int, tuple[int, Grid]] = {}                # floor -> its feet and the building it was built in


def noise(x: int, z: int, seed: int) -> float:
    return ((x * 73856093 ^ z * 19349663 ^ seed * 83492791) & 0xFFFF) / 65535.0


def in_chasm(x: int, z: int) -> bool:
    e = ((x - MID) / CHASM_R[0]) ** 2 + ((z - CENTRE_Z) / CHASM_R[1]) ** 2 + 0.07 * math.sin(x * 0.8) * math.cos(z * 0.7)
    return e < 1 and abs(x - MID) > BRIDGE_HALF and not on_island(x, z)


def on_island(x: int, z: int) -> float:
    """How far into the island (0 at its rim, 1 at its centre), or 0 off it."""
    e = ((x - MID) / ISLAND_R[0]) ** 2 + ((z - CENTRE_Z) / ISLAND_R[1]) ** 2
    return max(0.0, 1 - e) if e < 1 else 0.0


def pointed(spring: int, rise: int):
    """The pointed (two-centred) vault: the height of its soffit over the spring at each x across the hall."""
    x0, x1 = HALL_X[0] - 1, HALL_X[1] + 1
    radius = 0.82 * (x1 - x0)

    def raw(x: float) -> float:
        c = x0 + radius if x <= MID else x1 - radius
        return math.sqrt(max(0.0, radius * radius - (x - c) ** 2))

    k = rise / raw(MID)
    return lambda x: spring + int(round(k * raw(min(max(x, x0), x1))))


def free(floor: int, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
    """Notes a box of blocks meant to hang free (every block of it: later carving may leave only part of the piece)."""
    FLOATING.setdefault(floor, []).extend((x, y, z) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1))


def hall(g: Grid, floor: int, f: int, crown: bool) -> None:
    """The shattered cathedral's battle hall for one floor (feet f)."""
    ground = f - 1
    spring, rise = CROWN if crown else HALL
    over = pointed(spring, rise)
    x0, x1 = HALL_X
    z0, z1 = HALL_Z
    rnd = random.Random(1000 + floor)
    sky = f + spring + rise + SKY
    FLOATING.pop(floor, None)

    # the nave and the space over it: open from the floor to the soffit; above the vault's shell, air up to the night ceiling
    for x in range(x0, x1 + 1):
        top = f + over(x)
        g.air(x, f, z0, x, top, z1)
        g.box("vault", x, top + 1, z0, x, top + 2, z1)
        g.air(x, top + 3, z0, x, sky - 1, z1)
        for z in range(z0, z1 + 1):
            if (z - z0) % 8 == 3 or x == MID:
                g.box("trim", x, top, z, x, top + 1, z)               # pale ribs: the vault's bones show against the dark
            if x == MID and (z - z0) % 8 == 3:
                g.at("light", x, top, z)                              # a boss of moonlight where each rib crosses the spine
    g.box(NIGHT, x0, sky, z0, x1, sky + 1, z1)
    for x in range(x0 + 1, x1, 3):                                 # stars hung from the night ceiling
        for z in range(z0 + 1, z1, 3):
            if noise(x, z, floor) < 0.18:
                g.at(STAR, x, sky - 1, z)
    for x in range(x0, x1 + 1):                                    # the space over the vault is closed by the night at its sides too
        for y in range(f + over(x) + 3, sky):
            for z in (z0 - 1, z1 + 1):
                g.at(NIGHT, x, y, z)
    for side in (x0, x1):                                          # from the vault's outer face up: below it the wall shows inside the nave
        g.box(NIGHT, side - 1 if side == x0 else side + 1, f + over(side) + 3, z0, side - 1 if side == x0 else side + 1, sky - 1, z1)

    # the roof broken open: ragged holes through the vault, and what broke off hanging in the air below them and above (placed last)
    pieces: list[tuple[int, int, int, int, int]] = []
    for hx, hz, rx, rz in HOLES:
        for x in range(hx - rx - 1, hx + rx + 2):
            for z in range(hz - rz - 1, hz + rz + 2):
                if not (x0 < x < x1 and z0 < z < z1) or abs(x - MID) <= 1:
                    continue
                e = ((x - hx) / rx) ** 2 + ((z - hz) / rz) ** 2 + 0.35 * (noise(x, z, 7) - 0.5)
                if e < 1:
                    top = f + over(x)
                    g.air(x, top, z, x, top + 2, z)
        # a stub of vault the hole closed round on four sides would hang loose: it goes too
        for x in range(hx - rx - 2, hx + rx + 3):
            for z in range(hz - rz - 2, hz + rz + 3):
                if not (x0 < x < x1 and z0 < z < z1):
                    continue
                shell = f + over(x) + 1
                if g.grid[x, shell, z] != 0 and all(g.grid[x + dx, f + over(x + dx) + 1, z + dz] == 0
                                                     for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    g.air(x, shell - 1, z, x, shell + 1, z)
        for _ in range(max(3, rx * rz // 14)):
            x, z = hx + rnd.randint(-rx + 2, rx - 2), hz + rnd.randint(-rz + 2, rz - 2)
            if not (x0 + 3 < x < x1 - 3 and z0 + 3 < z < z1 - 3) or abs(x - MID) <= 9 and abs(z - CENTRE_Z) <= 9:
                continue
            top = f + over(x)
            # clear of the vault by four or more: nearer, a piece reads as nearly touching, not as hanging free
            y = top - rnd.randint(6, 16) if rnd.random() < 0.7 else top + rnd.randint(7, 12)
            sx, sz = rnd.randint(1, 3), rnd.randint(1, 3)
            if y <= f + 20 or y + 2 >= sky - 2:
                continue
            pieces.append((x, y, z, sx, sz))

    # the floor: tiles cracked in drifts, moonlight leaking up through some of the cracks
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            g.at(CRACKED if noise(x, z, 3) < 0.12 else "floor", x, ground, z)
    for _ in range(22):
        x, z = rnd.randint(x0 + 3, x1 - 3), rnd.randint(z0 + 3, z1 - 3)
        for _ in range(rnd.randint(8, 20)):
            if x0 < x < x1 and z0 < z < z1 and not in_chasm(x, z) and not in_chasm(x + 1, z) and not in_chasm(x - 1, z):
                g.at("light", x, ground, z)
            x, z = x + rnd.choice((-1, 0, 1, 1)), z + rnd.choice((-1, 0, 1))

    # the chasm: torn through the floor round the arena, rough rock sides, sculk at the bottom with amethyst growing out of it
    cells = [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if in_chasm(x, z)]
    chasm = set(cells)
    CHASMS[floor], FLOOR_FEET[floor] = chasm, (f, g)
    for x, z in cells:
        g.air(x, f - CHASM, z, x, ground, z)
        g.at(ABYSS, x, f - CHASM - 1, z)
        if noise(x, z, 5) < 0.05:
            g.at(CLUSTER, x, f - CHASM, z)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, z + dz) not in chasm:
                g.box(ROCK, x + dx, f - CHASM - 1, z + dz, x + dx, ground - 1, z + dz)

    # the island: an arena hanging on an upside-down spire of rock over the chasm
    for x in range(MID - ISLAND_R[0], MID + ISLAND_R[0] + 1):
        for z in range(CENTRE_Z - ISLAND_R[1], CENTRE_Z + ISLAND_R[1] + 1):
            depth = on_island(x, z)
            if depth <= 0:
                continue
            g.box(ROCK, x, ground - 2 - int(10 * depth ** 1.4), z, x, ground - 1, z)
            g.at("floor", x, ground, z)
    tg.medallion(g, MID, CENTRE_Z, ground, 16 if crown else 14)

    # the bridges across the chasm: a polished deck with a tuff runner
    for x in range(MID - BRIDGE_HALF, MID + BRIDGE_HALF + 1):
        for z in range(z0, z1 + 1):
            if not on_island(x, z):
                g.at("inlay" if x == MID else "wall_low", x, ground, z)

    # a balustrade on every edge of the chasm: the rim of the floor, the island's edge and the bridges' sides. A cell touching the chasm only
    # at a corner gets a rail too: with side-neighbours alone the railing broke wherever the ragged edge stepped diagonally, two thin posts
    # meeting at a corner, a gap a player could squeeze through into the pit (rig 2, 15 Nov)
    around = [(dx, dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dz]
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if (x, z) not in chasm and any((x + dx, z + dz) in chasm for dx, dz in around):
                g.at("rail", x, f, z)

    # amethyst growing on the island's edge, inside the balustrade
    for a in range(0, 360, 24):
        x = MID + round((ISLAND_R[0] - 3) * math.cos(math.radians(a)))
        z = CENTRE_Z + round((ISLAND_R[1] - 3) * math.sin(math.radians(a)))
        if g.grid[x, f, z] == 0:
            g.at(CLUSTER, x, f, z)

    # clustered piers down both aisles; some snapped off: each shaft broken at its own height, a jagged stump, and the fall lying round its
    # foot. (Their tops once hung in the air above the stumps: in the game that read as gaps in the columns, not as damage. Rig 2, 15 Nov.)
    for px in PIERS_X:
        for pz in PIERS_Z:
            top = f + over(px)
            snapped = noise(px, pz, floor) < 0.4
            stop = f + rnd.randint(12, 19) if snapped else top
            for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                shaft = stop - (rnd.randint(0, 4) if snapped and (dx or dz) else 0)
                g.box("wall_low", px + dx, f, pz + dz, px + dx, shaft, pz + dz)
                if snapped:
                    g.at(CRACKED if rnd.random() < 0.5 else ROCK, px + dx, shaft, pz + dz)
            g.box("trim", px - 1, f, pz - 1, px + 1, f + 1, pz + 1)
            if snapped:
                for _ in range(7):                                   # rubble: broken stone on the floor round the stump
                    rx, rz = px + rnd.randint(-3, 3), pz + rnd.randint(-3, 3)
                    if abs(rx - px) + abs(rz - pz) > 1 and g.grid[rx, f, rz] == 0 and g.grid[rx, f - 1, rz] != 0 and not in_chasm(rx, rz):
                        g.at(RUBBLE if rnd.random() < 0.6 else ROCK, rx, f, rz)
            else:
                g.box("trim", px - 1, top - 1, pz - 1, px + 1, top, pz + 1)

    # spectator galleries along both walls, on brackets, a rail at the open edge
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

    # lancet windows in moonlight down both walls, above the galleries
    for wx, back in ((x0 - 1, x0 - 2), (x1 + 1, x1 + 2)):
        for wz in WINDOWS_Z:
            for y in range(f + GALLERY_UP + 3, f + spring - 1):
                half = 0 if y >= f + spring - 3 else 1
                g.box("glass", wx, y, wz - half, wx, y, wz + half)
                g.box("light", back, y, wz - half, back, y, wz + half)
            g.box("inlay", wx, f + GALLERY_UP + 2, wz - 2, wx, f + GALLERY_UP + 2, wz + 2)

    # candelabra down the nave, clear of the chasm
    for cx in (36, x1 - 27):
        for cz in range(z0 + 6, z1 - 2, 12):
            if any(in_chasm(cx + dx, cz + dz) for dx in range(-2, 3) for dz in range(-2, 3)):
                continue
            g.box("wall_low", cx, f, cz, cx, f + 3, cz)
            g.at("trim", cx, f + 4, cz)
            g.box("light", cx, f + 5, cz, cx, f + 6, cz)
            g.at("minecraft:end_rod[facing=up]", cx, f + 7, cz)

    # over gate A: the rose window shattered, and in a chamber behind it the moon
    gz, back = tg.GATE_Z[1], tg.GATE_Z[0]
    cy, r = f + 30, 13
    g.box(NIGHT, MID - r - 3, cy - r - 3, 31, MID + r + 3, cy + r + 3, back)
    g.air(MID - r - 2, cy - r - 2, 32, MID + r + 2, cy + r + 2, back)
    for x in range(MID - 9, MID + 10):
        for y in range(cy - 9, cy + 10):
            if math.hypot(x - (MID + 3), y - (cy + 2)) <= 7.5:
                g.at("light", x, y, 31)                              # the moon, a little off the window's centre
    for x in range(MID - r - 2, MID + r + 3, 3):
        for y in range(cy - r - 2, cy + r + 3, 3):
            if noise(x, y, 11) < 0.3 and math.hypot(x - (MID + 3), y - (cy + 2)) > 9:
                g.at(STAR_WALL, x, y, 32)
    for x in range(MID - r - 2, MID + r + 3):
        for y in range(cy - r - 2, cy + r + 3):
            d = math.hypot(x - MID, y - cy)
            if r < d <= r + 1.2:
                g.at("light", x, y, gz)                              # a ring of moonlight round the window
                continue
            if d > r:
                continue
            a = math.degrees(math.atan2(y - cy, x - MID)) % 30
            ang = math.degrees(math.atan2(y - cy, x - MID)) % 360
            ragged = 14 * (noise(x // 2, y // 2, 13) - 0.5)          # two wedges knocked out, their edges jagged
            broken = d < r - 1.5 and (20 < ang + ragged < 115 or 200 < ang + ragged < 255)
            if d >= r - 1:
                g.at("trim", x, y, gz)                               # the rim holds; pale, so it shows against the night behind
                g.at("trim", x, y, back)
            elif broken:
                g.air(x, y, gz, x, y, back)
            else:
                g.at("trim" if a < 3 or abs(d - r * 0.45) < 0.7 else "glass", x, y, gz)
                g.air(x, y, back, x, y, back)
    # what the shattering left must still hang from the rim: tracery and glass joined to nothing would float in the gable
    held, todo = set(), [(x, y) for x in range(MID - r - 1, MID + r + 2) for y in range(cy - r - 1, cy + r + 2)
                         if r - 1 <= math.hypot(x - MID, y - cy) <= r]
    held.update(todo)
    while todo:
        x, y = todo.pop()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if (nx, ny) not in held and math.hypot(nx - MID, ny - cy) < r - 1 and g.grid[nx, ny, gz] != 0:
                held.add((nx, ny))
                todo.append((nx, ny))
    for x in range(MID - r, MID + r + 1):
        for y in range(cy - r, cy + r + 1):
            if math.hypot(x - MID, y - cy) < r - 1 and (x, y) not in held:
                g.air(x, y, gz, x, y, back)
    # the shattered face's jagged edges are meant: every piece of it hangs from the rim (above), so the scans let its runs end where they break
    free(floor, MID - r, cy - r, gz, MID + r, cy + r, gz)
    # and over the passage a small rose, whole, backlit
    rose(g, MID, f + 22, PASSAGE_Z[0], PASSAGE_Z[1], 9)

    # a cove of moonlight along both walls where the vault springs, so the vault is seen
    for z in range(z0, z1 + 1, 3):
        g.at("light", x0 - 1, f + spring - 1, z)
        g.at("light", x1 + 1, f + spring - 1, z)
    # chandeliers down both sides of the nave, hung only where the vault is whole over them
    for cx in (MID - 30, MID + 30):
        for cz in (z0 + 12, z0 + 36, z0 + 60, z0 + 84):
            top = f + over(cx)
            if g.grid[cx, top + 1, cz] != 0 and g.grid[cx, top, cz] == 0 or g.name(cx, top, cz) == g.theme["rib"]:
                hang(g, cx, cz, top - (1 if g.grid[cx, top, cz] else 0), f + 18, 3)
    # the great chandelier over the arena
    peak = f + over(MID)
    hang(g, MID, CENTRE_Z, peak - 1, f + (30 if crown else 26), 7 if crown else 6)

    # last, the broken pieces of roof: only where four blocks of air stand clear round them, so none reads as nearly touching the vault, a
    # pier, a rib or a chandelier
    for x, y, z, sx, sz in pieces:
        if abs(x - MID) > 40:
            continue                                                 # over the middle of the nave only: by the walls they read as stray blocks
        under_hole = all(g.grid[cx, f + over(cx) + 1, cz] == 0 for cx in range(x, x + sx + 1) for cz in range(z, z + sz + 1))
        if not under_hole and y < f + over(x):
            continue                                                 # a piece below the vault hangs only right under the gap it fell from
        if (g.grid[x - 4:x + sx + 5, y - 4:y + 6, z - 4:z + sz + 5] == 0).all():
            g.box("vault", x, y, z, x + sx, y + 1, z + sz)
            g.box("rib", x, y + 1, z, x + sx, y + 1, z)
            free(floor, x, y, z, x + sx, y + 1, z + sz)


def fenced(g: Grid) -> list[str]:
    """No floor anyone can stand on at a floor's height touches the chasm, even corner to corner (a diagonal gap between two rail posts lets
    a player through). Checked on every floor of the building."""
    problems = []
    walker = tg.Walker(g)
    for floor, cells in CHASMS.items():
        if floor not in FLOOR_FEET or FLOOR_FEET[floor][1] is not g:
            continue
        f = FLOOR_FEET[floor][0]
        open_edge = [(x, z) for x, z in cells for dx in (-1, 0, 1) for dz in (-1, 0, 1)
                     if (x + dx, z + dz) not in cells and walker.standable(x + dx, f, z + dz)]
        if open_edge:
            problems.append(f"floor {floor}: {len(open_edge)} chasm cell(s) beside floor with no rail between, first {open_edge[0]}")
    return problems


def floating(b: int) -> list[tuple[int, int, int]]:
    return [p for floor in BUILDINGS[b - 1] for p in FLOATING.get(floor, [])]


SPEC = gh.Spec(
    theme=THEME, buildings=BUILDINGS, crowns=CROWNS, pitch=PITCH, base_feet=BASE_FEET,
    top=lambda crown: sum(CROWN if crown else HALL) + SKY + 3,
    hall=hall,
    entry_z=CENTRE_Z + ISLAND_R[1] - 5, presentation_z=CENTRE_Z - ISLAND_R[1] + 6,
    spectator=((GALLERIES[0][0] + GALLERIES[0][1]) // 2, GALLERY_UP + 1, CENTRE_Z),
    rails=tuple((rail, GALLERY_UP + 1) for _, _, rail in GALLERIES),
    floating=floating,
    checks=fenced,
    summary="arrival -> bridge -> island -> bridge -> gate A -> door -> chamber -> its own stair -> threshold; the chasm fenced all round",
)


def feet(floor: int) -> int:
    return gh.feet(SPEC, floor)


def build_building(theme: dict[str, str], b: int) -> Grid:
    return gh.build_building(SPEC, theme, b)


if __name__ == "__main__":
    sys.exit(gh.main(SPEC))
