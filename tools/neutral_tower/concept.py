#!/usr/bin/env python3
"""Concept model of the P59 Neutral makeover, for pictures only: one standard floor and the preparation room.

    py -3.14 -I tools/neutral_tower/concept.py <out_dir> [shot ...]

Not the generator. It builds rough voxel grids with the planned room sizes and the Neutral palette, and renders them with the Halloween tower's
eye-level ray caster (tools/halloween_tower/view.py: real 1.21.1 block textures, the game's block light). No entities: the vendor, the
pedestal holograms and the route-map icons are shown as blocks or not at all.

Coordinates: x east, y up (y 0 is the floor block, feet stand at y 1), z south. Yaw as in the game: 0 looks south, 90 west, 180 north, -90 east.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "halloween_tower"))
import view as V  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tower_gen"))
from themes import NEUTRAL as PALETTE  # noqa: E402  (the palette lives with the generator: tools/tower_gen/themes.py)


class Grid:
    def __init__(self, sx: int, sy: int, sz: int) -> None:
        self.g = np.zeros((sx, sy, sz), dtype=np.uint16)
        self.palette = ["minecraft:air"]
        self.index = {"minecraft:air": 0}

    def id(self, block: str) -> int:
        block = PALETTE.get(block, block)
        if block not in self.index:
            self.index[block] = len(self.palette)
            self.palette.append(block)
        return self.index[block]

    def box(self, block: str, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
        """Fills x0..x1, y0..y1, z0..z1 inclusive (clipped to the grid)."""
        sx, sy, sz = self.g.shape
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        self.g[max(xa, 0):min(xb + 1, sx), max(ya, 0):min(yb + 1, sy), max(za, 0):min(zb + 1, sz)] = self.id(block)

    def at(self, block: str, x: int, y: int, z: int) -> None:
        self.box(block, x, y, z, x, y, z)

    def air(self, x0, y0, z0, x1, y1, z1) -> None:
        self.box("minecraft:air", x0, y0, z0, x1, y1, z1)


def room(G: Grid, x0: int, z0: int, x1: int, z1: int, height: int) -> None:
    """A rectangular room: interior x0..x1, z0..z1, feet 1..height; walls are already solid around it (the building starts solid)."""
    G.air(x0, 1, z0, x1, height, z1)
    G.box("floor", x0, 0, z0, x1, 0, z1)
    for z in (z0 - 1, z1 + 1):
        G.box("wall_low", x0, 1, z, x1, 2, z)
    for x in (x0 - 1, x1 + 1):
        G.box("wall_low", x, 1, z0, x, 2, z1)


def barrel_vault(G: Grid, x0: int, z0: int, x1: int, z1: int, spring: int, rise: int, rib_every: int = 6) -> None:
    """Carves a barrel vault running along z above a room: the ceiling climbs from `spring` at the walls to spring + rise at the ridge.
    Every `rib_every` blocks a rib stands one block proud of the vault, and froglight coves run along both springing lines."""
    cx, half = (x0 + x1) / 2, (x1 - x0) / 2 + 0.5
    for x in range(x0, x1 + 1):
        h = spring + int(round(rise * math.sqrt(max(0.0, 1 - ((x - cx) / half) ** 2))))
        G.air(x, 1, z0, x, h, z1)
        G.box("vault", x, h + 1, z0, x, h + 1, z1)
        for z in range(z0, z1 + 1, rib_every):
            G.at("rib", x, h, z)
    for x in (x0 - 1, x1 + 1):
        for z in range(z0 + 1, z1, 3):
            G.at("light", x, spring - 1, z)
        G.box("trim", x, spring, z0, x, spring, z1)


def piers(G: Grid, xs: list[int], z0: int, z1: int, height: int, every: int = 6) -> None:
    for x in xs:
        for z in range(z0, z1 + 1, every):
            G.box("pier", x, 1, z, x, height - 1, z)
            G.at("trim", x, height, z)
            G.at("trim", x, 1, z)


def medallion(G: Grid, cx: int, cz: int, r: int) -> None:
    """A round inlay: an inlay ring, a quartz ring, eight spokes, an accent centre."""
    for x in range(cx - r, cx + r + 1):
        for z in range(cz - r, cz + r + 1):
            d = math.hypot(x - cx, z - cz)
            if r - 1 <= d <= r:
                G.at("inlay", x, 0, z)
            elif r - 3 <= d < r - 2:
                G.at("light" if (x == cx or z == cz) else "trim", x, 0, z)
            elif d < 2:
                G.at("accent", x, 0, z)
            elif d < r - 3 and (x == cx or z == cz or abs(x - cx) == abs(z - cz)):
                G.at("inlay", x, 0, z)


def grand_door(G: Grid, x: int, z: int, wall_dz: int, w: int = 5, h: int = 7) -> None:
    """A doorway centred on x through a wall at z..z+wall_dz-1, with a trim frame and an accent keystone (the open door: no leaf)."""
    a, b = x - w // 2, x + w // 2
    G.air(a, 1, z, b, h, z + wall_dz - 1)
    for zz in (z - 1, z + wall_dz):
        G.box("trim", a - 1, 1, zz, a - 1, h + 1, zz)
        G.box("trim", b + 1, 1, zz, b + 1, h + 1, zz)
        G.box("trim", a - 1, h + 1, zz, b + 1, h + 1, zz)
        G.at("accent", x, h + 1, zz)
        G.box("light", a - 1, h + 2, zz, b + 1, h + 2, zz) if zz == z - 1 else None


def hang_lanterns(G: Grid, xs: list[int], zs: list[int], ceiling_of, drop: int) -> None:
    for x in xs:
        for z in zs:
            top = ceiling_of(x, z)
            G.box("minecraft:chain[axis=y]", x, top - drop + 1, z, x, top, z)
            G.at("minecraft:lantern[hanging=true]", x, top - drop, z)


def chandelier(G: Grid, x: int, z: int, low: int) -> None:
    """A chain from the ceiling down to `low`, a light block there with a lantern hanging off each side and one below. Block light loses one
    level per block, so in a hall taller than about 10 the light has to hang down to the people, not sit in the vault."""
    top = ceiling_at(G, x, z)
    G.box("minecraft:chain[axis=y]", x, low + 1, z, x, top, z)
    G.at("light", x, low, z)
    G.at("trim", x, low + 1, z)
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        G.at("minecraft:lantern[hanging=true]", x + dx, low - 1, z + dz)
    G.at("minecraft:lantern[hanging=true]", x, low - 1, z)


def ceiling_at(G: Grid, x: int, z: int) -> int:
    col = G.g[x, :, z]
    solid = np.nonzero(col[1:])[0]
    return int(solid[0]) if solid.size else G.g.shape[1] - 1


def banner(G: Grid, x: int, y_top: int, z: int, length: int, dz: int) -> None:
    """A wool hanging two wide on the wall face at z (dz = +1 hangs on the north face of a south wall... it just fills the column)."""
    G.box("hanging", x, y_top - length + 1, z, x + 1, y_top, z)
    G.box("accent", x, y_top - length, z, x + 1, y_top - length, z)


# ---- the standard floor ----------------------------------------------------------------------------------------------------------------
SX, SY, SZ = 112, 24, 112
HALL = (26, 22, 85, 69)          # battle hall interior x0, z0, x1, z1: 60 x 48
ATRIUM = (26, 72, 85, 87)        # 60 x 16
ARRIVAL = (34, 6, 77, 19)        # 44 x 14
LANES_X = (40, 56, 72)           # door centres, 16 apart
LANE_Z = (91, 106)


def standard_floor(roofless: bool = False, headroom: int = 0) -> Grid:
    G = Grid(SX, SY + headroom, SZ)
    G.box("wall", 0, 0, 0, SX - 1, SY - 1, SZ - 1)

    # Arrival hall: low and long (8 clear), three landing arches on the north wall, one lane-dressed each.
    x0, z0, x1, z1 = ARRIVAL
    room(G, x0, z0, x1, z1, 8)
    G.box("rib", x0, 9, z0, x1, 9, z1)
    for x in range(x0, x1 + 1, 4):
        G.box("rib", x, 8, z0, x, 8, z1)
    for lx in LANES_X:
        G.air(lx - 2, 1, z0 - 2, lx + 2, 5, z0 - 1)
        G.box("trim", lx - 3, 1, z0 - 1, lx - 3, 6, z0 - 1); G.box("trim", lx + 3, 1, z0 - 1, lx + 3, 6, z0 - 1)
        G.box("trim", lx - 3, 6, z0 - 1, lx + 3, 6, z0 - 1); G.at("accent", lx, 6, z0 - 1)
        G.box("glass", lx - 2, 1, z0 - 2, lx + 2, 5, z0 - 2)
        G.box("light", lx - 2, 0, z0 - 1, lx + 2, 0, z0 - 1)
    for x in range(x0 + 2, x1, 6):
        G.at("light", x, 8, (z0 + z1) // 2)

    # Battle hall: 60 x 48, a barrel vault springing at 11 and rising to 19, side aisles behind pier lines with a spectator gallery at 6.
    x0, z0, x1, z1 = HALL
    room(G, x0, z0, x1, z1, 10)
    barrel_vault(G, x0, z0, x1, z1, spring=11, rise=8)
    piers(G, [x0 + 7, x1 - 7], z0 + 3, z1 - 3, 11)
    for xa, xb in ((x0, x0 + 6), (x1 - 6, x1)):
        G.box("rib", xa, 6, z0, xb, 6, z1)                                 # gallery deck
        rail = x0 + 6 if xa == x0 else x1 - 6
        G.box("minecraft:polished_andesite_wall", rail, 7, z0, rail, 7, z1)
    G.air(x0, 1, z0, x1, 5, z1)
    G.box("floor", x0, 0, z0, x1, 0, z1)
    piers(G, [x0 + 7, x1 - 7], z0 + 3, z1 - 3, 11)
    medallion(G, (x0 + x1) // 2, (z0 + z1) // 2, 12)
    G.box("inlay", (x0 + x1) // 2 - 1, 0, z0, (x0 + x1) // 2 + 1, 0, z0 + 9)  # the runner in from the arrival hall
    G.box("inlay", (x0 + x1) // 2 - 1, 0, z1 - 9, (x0 + x1) // 2 + 1, 0, z1)
    G.air((x0 + x1) // 2 - 4, 1, z0 - 3, (x0 + x1) // 2 + 4, 7, z0 - 1)       # the way in from the arrival hall
    G.box("floor", (x0 + x1) // 2 - 4, 0, z0 - 3, (x0 + x1) // 2 + 4, 0, z0 - 1)
    for x in range(x0 + 10, x1 - 9, 8):
        banner(G, x, 10, z1 + 1, 6, -1)
    for z in range(z0 + 9, z1 - 3, 12):
        for x in (x0 + 17, (x0 + x1) // 2, x1 - 17):
            chandelier(G, x, z, 7 if x != (x0 + x1) // 2 else 9)
    for x in range(x0 + 1, x0 + 6, 2):                                       # gallery lamps under the deck edge
        for z in range(z0 + 2, z1, 6):
            G.at("minecraft:lantern[hanging=true]", x, 5, z); G.at("minecraft:lantern[hanging=true]", x1 - (x - x0), 5, z)

    # Gate A, then the atrium: a 12-high hall with a ribbed flat-arched ceiling, the vendor stand at its east end, three grand doors south.
    cx = (x0 + x1) // 2
    G.air(cx - 3, 1, z1 + 1, cx + 3, 7, z1 + 2)
    G.box("floor", cx - 3, 0, z1 + 1, cx + 3, 0, z1 + 2)
    ax0, az0, ax1, az1 = ATRIUM
    room(G, ax0, az0, ax1, az1, 9)
    barrel_vault(G, ax0, az0, ax1, az1, spring=9, rise=3, rib_every=4)
    for lx in LANES_X:
        grand_door(G, lx, az1 + 1, 3)
    # the shared pair of pedestals, standing in front of the chosen (middle) door: accept on the player's right (west), refuse on the left
    for px, top in ((LANES_X[1] - 4, "accent"), (LANES_X[1] + 4, "wall_low")):
        G.box("trim", px, 1, az1 - 3, px, 1, az1 - 3); G.at("pier", px, 2, az1 - 3); G.at(top, px, 3, az1 - 3)
    vendor_stand(G, ax1 - 9, az0 + 3)
    for x in range(ax0 + 6, ax1 - 12, 12):
        chandelier(G, x, (az0 + az1) // 2, 7)
    G.box("inlay", ax0, 0, (az0 + az1) // 2, ax1, 0, (az0 + az1) // 2)

    # Lane rooms behind the doors: 13 wide, 9 high (low on purpose, after the atrium), each with its own five-step stair to a threshold arch.
    lz0, lz1 = LANE_Z
    for lx in LANES_X:
        room(G, lx - 6, lz0, lx + 6, lz1, 9)
        barrel_vault(G, lx - 6, lz0, lx + 6, lz1, spring=7, rise=4, rib_every=3)
        for s in range(5):                                                  # 5 steps up, climbing south, then the landing
            G.box("minecraft:polished_andesite_stairs[facing=south]", lx - 2, 1 + s, lz1 - 9 + s, lx + 2, 1 + s, lz1 - 9 + s)
            G.box("wall_low", lx - 2, 0, lz1 - 9 + s, lx + 2, s, lz1 - 9 + s)
        G.box("floor", lx - 2, 5, lz1 - 4, lx + 2, 5, lz1)
        G.box("wall_low", lx - 2, 1, lz1 - 4, lx + 2, 4, lz1)
        G.box("glass", lx - 1, 6, lz1, lx + 1, 8, lz1)                      # the threshold: walking in fades and teleports
        G.box("light", lx - 2, 6, lz1, lx - 2, 8, lz1); G.box("light", lx + 2, 6, lz1, lx + 2, 8, lz1)
        G.box("accent", lx - 2, 9, lz1, lx + 2, 9, lz1)
        G.at("minecraft:lantern", lx - 5, 1, lz0 + 1); G.at("minecraft:lantern", lx + 5, 1, lz0 + 1)

    if roofless:
        G.g[:, 8:, :] = 0                                                   # a section cut at feet + 6: the plan, not the vaults
    return G


def vendor_stand(G: Grid, x: int, z: int) -> None:
    """The vendor's stall, about 7 x 5: a wooden counter on an andesite plinth, four posts, a striped awning, goods on the counter and behind."""
    G.box("wall_low", x, 1, z, x + 6, 1, z)                                 # counter front
    G.box("wood", x, 2, z, x + 6, 2, z)
    G.box("wood", x, 1, z + 1, x, 2, z + 3); G.box("wood", x + 6, 1, z + 1, x + 6, 2, z + 3)
    for px, pz in ((x, z), (x + 6, z), (x, z + 4), (x + 6, z + 4)):
        G.box("minecraft:polished_andesite_wall", px, 3 if pz == z else 1, pz, px, 5, pz)
    for i, xx in enumerate(range(x, x + 7)):
        G.box("hanging" if i % 2 == 0 else "minecraft:white_wool", xx, 6, z - 1, xx, 6, z + 4)
    G.box("accent", x + 2, 7, z + 1, x + 4, 7, z + 1)                       # the sign board
    for xx, item in ((x + 1, "minecraft:barrel"), (x + 3, "minecraft:lantern"), (x + 5, "minecraft:flower_pot")):
        G.at(item, xx, 3, z)
    G.box("minecraft:barrel", x + 1, 1, z + 4, x + 5, 2, z + 4)
    G.at("minecraft:lantern[hanging=true]", x + 3, 5, z + 2)


# ---- the preparation room -------------------------------------------------------------------------------------------------------------
PX, PY, PZ = 50, 22, 42


def prep_room(headroom: int = 0) -> Grid:
    """About 40 x 32 under a shallow dome (16 at the walls, 19 at the crown). West: the way in from the lobby. North: the route-map wall.
    South: the vendor stand. East: the great door to floor 1, with the ready dais in front of it."""
    G = Grid(PX, PY + headroom, PZ)
    G.box("wall", 0, 0, 0, PX - 1, PY - 1, PZ - 1)
    x0, z0, x1, z1 = 5, 5, 44, 36
    room(G, x0, z0, x1, z1, 12)
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            r = math.hypot((x - cx) / ((x1 - x0) / 2 + 1), (z - cz) / ((z1 - z0) / 2 + 1))
            h = 13 + int(round(6 * math.sqrt(max(0.0, 1 - min(r, 1) ** 2))))
            G.air(x, 1, z, x, h, z)
            G.at("vault", x, h + 1, z)
            ang = math.atan2(z - cz, x - cx)
            off = abs((ang + math.pi / 8) % (math.pi / 4) - math.pi / 8) * math.hypot(x - cx, z - cz)
            if off < 0.75 or abs(r - 0.55) < 0.03:
                G.at("rib", x, h, z)                                        # eight radial ribs and one ring
    for x in (x0 - 1, x1 + 1):
        G.box("trim", x, 12, z0, x, 12, z1)
        for z in range(z0 + 2, z1, 4):
            G.at("light", x, 11, z)
    for z in (z0 - 1, z1 + 1):
        G.box("trim", x0, 12, z, x1, 12, z)
        for x in range(x0 + 2, x1, 4):
            G.at("light", x, 11, z)
    piers(G, [x0 + 4, x1 - 4], z0 + 4, z1 - 4, 12, every=8)
    medallion(G, int(cx), int(cz), 9)

    # West: the way in, an arch from the lobby (the teleport lands the party here, facing east).
    G.air(x0 - 3, 1, int(cz) - 2, x0 - 1, 6, int(cz) + 2)
    G.box("trim", x0 - 1, 7, int(cz) - 3, x0 - 1, 7, int(cz) + 3)
    # North: the route-map wall, a dark panel 24 x 7 with the stop icons as accent and inlay blocks (the real one is display entities).
    G.box("minecraft:black_concrete", 13, 3, z0 - 1, 36, 9, z0 - 1)
    G.box("trim", 12, 2, z0 - 1, 37, 2, z0 - 1); G.box("trim", 12, 10, z0 - 1, 37, 10, z0 - 1)
    for lane, y in enumerate((8, 6, 4)):
        for i, x in enumerate(range(14, 36, 2)):
            G.at("accent" if i in (4, 10) else "inlay" if (i + lane) % 3 else "trim", x, y, z0 - 1)
    # East: the great door to floor 1, 7 x 10, and the ready dais: three steps up to a gold ring the whole party stands in.
    G.air(x1 + 1, 1, int(cz) - 3, x1 + 3, 10, int(cz) + 3)
    G.box("glass", x1 + 3, 1, int(cz) - 3, x1 + 3, 10, int(cz) + 3)
    G.box("trim", x1 + 1, 11, int(cz) - 4, x1 + 1, 11, int(cz) + 4); G.at("accent", x1 + 1, 11, int(cz))
    for s, r in enumerate((5, 4)):
        G.box("wall_low", x1 - 1 - 2 * r, 1 + s, int(cz) - r, x1 - 1, 1 + s, int(cz) + r)
    G.box("accent", x1 - 7, 2, int(cz) - 3, x1 - 2, 2, int(cz) + 3)
    G.box("floor", x1 - 6, 2, int(cz) - 2, x1 - 3, 2, int(cz) + 2)
    # South: the vendor stand (run supplies only). No free healing font: healing is a paid supply at the stand.
    vendor_stand(G, 26, z1 - 6)
    for x in range(x0 + 6, x1 - 5, 8):
        banner(G, x, 11, z1 + 1, 5, -1)
    chandelier(G, int(cx), int(cz), 9)
    for x, z in ((int(cx) - 10, int(cz) - 7), (int(cx) + 10, int(cz) - 7), (int(cx) - 10, int(cz) + 7), (int(cx) + 10, int(cz) + 7)):
        chandelier(G, x, z, 8)
    return G


SHOTS = {
    # name: (scene, eye x, eye y (feet), eye z, yaw, pitch, fov)
    "floor_overview": ("floor_top", 56, 62, 1, 0, 56, 88),
    "battle_hall": ("floor", 56, 1, 20, 0, -4, 90),
    "battle_hall_gallery": ("floor", 29, 7, 25, -30, 8, 90),
    "atrium_doors": ("floor", 56, 1, 75, 0, 2, 90),
    "atrium_stand": ("floor", 62, 1, 82, -110, 4, 80),
    "lane_stair": ("floor", 56, 1, 92, 0, -12, 90),
    "prep_room": ("prep", 7, 1, 21, -90, -2, 95),
    "prep_dais": ("prep", 20, 1, 30, -115, 0, 90),
    "prep_map_wall": ("prep", 25, 1, 22, 180, -6, 95),
}


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "concept_out")
    out.mkdir(parents=True, exist_ok=True)
    wanted = sys.argv[2:] or list(SHOTS)
    scenes: dict[str, V.Scene] = {}
    for name in wanted:
        kind, x, y, z, yaw, pitch, fov = SHOTS[name]
        if kind not in scenes:
            G = {"floor": lambda: standard_floor(), "floor_top": lambda: standard_floor(roofless=True, headroom=60), "prep": lambda: prep_room()}[kind]()
            print(kind, "volume:", G.g.shape, G.g.size)
            scenes[kind] = V.Scene(G.g, G.palette, sky=(kind == "floor_top"))
        scenes[kind].render((x + 0.5, y + 1.62, z + 0.5), yaw, pitch, 960, 540, fov, exposure=1.15).save(out / f"{name}.png")
        print("wrote", out / f"{name}.png")
    for s in scenes.values():
        if s.missing:
            print("no texture for:", sorted(s.missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
