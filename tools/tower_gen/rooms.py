#!/usr/bin/env python3
"""The stop rooms behind a floor's route doors, in a tower's own palette (P59): battle, rest, shrine, gambler, forge, library, vault, pool.

    py -3.14 -I tools/tower_gen/rooms.py [--theme neutral] [--check] [--install] [--sheet out.png]

A room is 11 wide (x), 9 high (y: the floor row, then eight clear rows) and 15 deep (z): RoomDresser clears the chamber north of a route door and
pastes room_<tower>_<kind> there (falling back to the shared room_<kind>, the Halloween set). Local coordinates as in the Halloween rooms: x 0..10
west to east, the aisle x 4..6; z 14 is the door, z 0 the back doorway to the lane stair; y 0 the floor, feet at y 1. Every room keeps the aisle open
three high from the door to the back doorway (checked by the same flood fill), and uses no block entity (no chests, beds, banners, signs, campfires,
lecterns, barrels), so a paste never leaves anything to clean up.

The theme roles (themes.py) give each tower its look: wall, trim, pier, floor, inlay, accent, hanging, light, glass, wood.
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import deque
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(ROOT / "validation"))
from themes import THEMES  # noqa: E402

THEME_NAMES = {id(t): name for name, t in THEMES.items()}

W, H, D = 11, 9, 15
DATA_VERSION = 3955
AIR = "minecraft:air"
LANTERN = "minecraft:lantern[hanging=true]"
CHAIN = "minecraft:chain[axis=y]"
CANDLE = "minecraft:candle[candles=3,lit=true]"
CANDLE_W = "minecraft:white_candle[candles=2,lit=true]"
STRUCTURES = ROOT / "src/main/resources/data/cobbletowers/structure"


class Room:
    def __init__(self, theme: dict[str, str]) -> None:
        self.t = theme
        self.grid = np.zeros((W, H, D), dtype=np.uint16)
        self.palette = [AIR]
        self.index = {AIR: 0}

    def b(self, block: str) -> str:
        """A theme role ("wall", "accent"...) or a block id."""
        return self.t.get(block, block)

    def bid(self, block: str) -> int:
        name = self.b(block)
        found = self.index.get(name)
        if found is None:
            found = len(self.palette)
            self.palette.append(name)
            self.index[name] = found
        return found

    def fill(self, x0, x1, y0, y1, z0, z1, block) -> None:
        self.grid[min(x0, x1):max(x0, x1) + 1, y0:y1 + 1, min(z0, z1):max(z0, z1) + 1] = self.bid(block)

    def set(self, x, y, z, block) -> None:
        self.fill(x, x, y, y, z, z, block)

    def both(self, x0, x1, y0, y1, z0, z1, block) -> None:
        """The same box on both sides of the aisle."""
        self.fill(x0, x1, y0, y1, z0, z1, block)
        self.fill(W - 1 - x1, W - 1 - x0, y0, y1, z0, z1, block)


# ---- the shared bones of every room -----------------------------------------------------------------------------------------------

def base(r: Room, field: str = "floor", alt: str = "minecraft:polished_diorite") -> None:
    """A floor of large panels with a red runner down the aisle, gold studs at its edges every fourth block."""
    for x in range(W):
        for z in range(D):
            r.set(x, 0, z, field if ((x // 3) + (z // 3)) % 2 == 0 else alt)
    r.fill(4, 6, 0, 0, 0, D - 1, "inlay")
    for z in range(D):
        for x in (3, 7):
            r.set(x, 0, z, "accent" if z % 4 == 2 else "trim")


def hang(r: Room, x: int, z: int, light: str = LANTERN, drop: int = 2) -> None:
    for y in range(8, 8 - drop, -1):
        r.set(x, y, z, CHAIN)
    r.set(x, 8 - drop, z, light)


def ribs(r: Room, zs=(3, 7, 11), lamps=(1, 5, 9, 13), drop: int = 2) -> None:
    """Transverse ribs: a pier up each wall with a trim capital, an arch across at the top; lanterns in the bays between them; a cove of light."""
    for z in zs:
        r.both(0, 0, 1, 6, z, z, "pier")
        r.both(0, 0, 7, 7, z, z, "trim")
        r.both(1, 1, 7, 8, z, z, "rib")
        r.both(0, 0, 8, 8, z, z, "rib")                    # the arch's shoulder meets the wall: without it a notch showed at the crown
        r.fill(2, 8, 8, 8, z, z, "rib")
        r.set(5, 8, z, "accent")
    for z in lamps:
        hang(r, 5, z, LANTERN, drop)
    for z in range(D):                                     # a light cove along the top of each wall, between the ribs
        if z not in zs:
            r.both(0, 0, 7, 7, z, z, "light")


def statue(r: Room, x: int, z: int) -> None:
    """A guardian on a plinth: an andesite base, a quartz body, a gold head."""
    r.set(x, 1, z, "wall_low")
    r.set(x, 2, z, "pier")
    r.set(x, 3, z, "pier")
    r.set(x, 4, z, "accent")


# ---- the eight rooms ----------------------------------------------------------------------------------------------------------------

def battle(r: Room) -> None:
    """A hall of honour: gold-crowned guardians on both walls between the ribs, red pennants hung above them, the runner leading on."""
    base(r)
    ribs(r)
    for z in (1, 5, 9, 13):
        statue(r, 1, z)
        statue(r, 9, z)
    for z in (5, 9):
        for x in (0, 10):
            r.fill(x, x, 4, 6, z - 1, z + 1, "hanging")
            r.set(x, 4, z, "accent")


def rest(r: Room) -> None:
    """A quiet lodge: wool bedrolls with a candle each down one wall, a quartz hearth with a glowing fire and cauldrons on the other."""
    base(r, "wood", "minecraft:stripped_spruce_wood[axis=y]")
    ribs(r, drop=1)
    for z in (1, 5, 9):
        r.fill(1, 2, 1, 1, z, z + 1, "hanging")
        r.fill(1, 1, 1, 1, z, z + 1, "minecraft:white_wool")
        r.set(3, 1, z, "wood")
        r.set(3, 2, z, CANDLE)
    r.fill(9, 10, 1, 6, 5, 9, "wall")                          # the hearth: a quartz mass with a mouth, a mantel and a chimney
    r.fill(9, 9, 1, 3, 6, 8, AIR)
    r.fill(9, 9, 1, 1, 6, 8, "minecraft:magma_block")
    r.fill(9, 9, 2, 3, 6, 8, "minecraft:orange_stained_glass")
    r.set(9, 2, 7, "minecraft:shroomlight")
    r.fill(9, 9, 4, 4, 5, 9, "trim")
    r.fill(8, 8, 4, 4, 5, 9, "accent")
    r.set(8, 1, 5, "minecraft:cauldron")
    r.set(8, 1, 9, "minecraft:cauldron")
    r.both(1, 1, 1, 2, 12, 13, "minecraft:hay_block[axis=y]")


def shrine(r: Room) -> None:
    """Twin altars of quartz and gold under tall windows of light, amethyst on the altars, candles beside them."""
    base(r)
    ribs(r)
    for z in (5, 9):
        for x0, x1, inner in ((1, 2, 2), (8, 9, 8)):
            r.fill(x0, x1, 1, 1, z - 1, z + 1, "trim")
            r.fill(x0, x1, 2, 2, z - 1, z + 1, "accent")
            r.set(inner, 3, z, "minecraft:amethyst_block")
            r.set(inner, 4, z, "minecraft:amethyst_cluster[facing=up]")
            r.set(inner, 3, z - 1, CANDLE_W)
            r.set(inner, 3, z + 1, CANDLE_W)
        for x, side in ((0, 0), (10, 10)):                     # a tall backlit window behind each altar
            r.fill(side, side, 2, 6, z - 1, z + 1, "glass")
            r.fill(side, side, 1, 1, z - 1, z + 1, "trim")     # a sill: the glass stood on air, a slot along the floor under it
    r.fill(4, 6, 1, 1, 7, 7, "minecraft:red_carpet")


def gambler(r: Room) -> None:
    """A wheel inlaid in the floor (red and white sectors round a gold hub), a chandelier over it, card tables of green felt, two great dice."""
    base(r)
    cx, cz = 5, 7
    for x in range(W):
        for z in range(D):
            d = math.hypot(x - cx, z - cz)
            if d <= 4.6:
                sector = int((math.atan2(z - cz, x - cx) + math.pi) / (2 * math.pi) * 12) % 2
                r.set(x, 0, z, "inlay" if sector == 0 else "minecraft:white_concrete")
            if d <= 1.5:
                r.set(x, 0, z, "accent")
    ribs(r, zs=(4, 10), lamps=(1, 13), drop=1)
    hang(r, 5, 7, "light", 2)
    for z0 in (1, 11):
        r.fill(1, 2, 1, 1, z0, z0 + 1, "wall_low")
        r.fill(1, 2, 2, 2, z0, z0 + 1, "minecraft:green_wool")
        r.set(1, 3, z0, CANDLE)
        r.set(2, 3, z0 + 1, CANDLE)
    for dz0, dx0 in ((6, 8), (6, 1)):                          # the great dice
        r.fill(dx0, dx0 + 1, 1, 2, dz0, dz0 + 1, "minecraft:white_concrete")
        r.set(dx0, 2, dz0, "minecraft:black_concrete")
        r.set(dx0 + 1, 2, dz0 + 1, "minecraft:black_concrete")
        r.set(dx0 + 1, 1, dz0, "minecraft:black_concrete")
    for x in (0, 10):
        r.fill(x, x, 3, 6, 5, 9, "hanging")


def forge(r: Room) -> None:
    """A smithy: anvils and a grindstone along one wall, a glowing pit under an andesite hood on the other, lava pots, racks of bars."""
    base(r, "minecraft:polished_andesite", "minecraft:smooth_stone")
    ribs(r)
    for z in (3, 6, 9):
        r.set(1, 1, z, "minecraft:anvil[facing=east]")
    r.set(2, 1, 12, "minecraft:smithing_table")
    r.set(1, 1, 1, "minecraft:grindstone[face=floor,facing=south]")
    r.fill(7, 9, 0, 0, 5, 7, "minecraft:magma_block")
    r.fill(8, 9, 1, 1, 4, 8, "wall_low")
    r.fill(8, 9, 2, 2, 5, 7, "minecraft:orange_stained_glass")
    r.set(8, 2, 6, "minecraft:shroomlight")
    r.fill(8, 9, 3, 3, 4, 8, "trim")
    r.fill(9, 9, 4, 8, 5, 7, "wall_low")
    r.fill(10, 10, 8, 8, 4, 6, "wall_low")                  # the flue meets the wall under the ceiling, shoulder to shoulder: pockets showed
    r.set(8, 1, 10, "minecraft:lava_cauldron")
    r.set(9, 1, 11, "minecraft:lava_cauldron")
    r.set(8, 1, 2, "minecraft:lava_cauldron")
    r.both(1, 2, 1, 2, 0, 0, "minecraft:iron_block")
    for z in (1, 5, 9, 13):
        r.fill(0, 0, 3, 6, z - 1, z + 1, "minecraft:iron_bars")


def library(r: Room) -> None:
    """Bookcases floor to cove on both walls, low shelves and ladders, a reading desk with a candle in two bays."""
    base(r, "wood", "minecraft:stripped_spruce_wood[axis=y]")
    ribs(r)
    for x in (0, 10):
        for z in range(1, 14):
            if z not in (3, 7, 11):
                r.fill(x, x, 1, 6, z, z, "minecraft:bookshelf")
    for z in (2, 4, 6, 8, 10, 12):
        r.fill(1, 1, 1, 2, z, z, "minecraft:bookshelf")
        r.fill(9, 9, 1, 2, z, z, "minecraft:bookshelf")
    for z in (5, 9):
        r.set(2, 1, z, "minecraft:spruce_slab[type=top]")
        r.set(2, 2, z, CANDLE)
        r.set(8, 1, z, "minecraft:spruce_slab[type=top]")
        r.set(8, 2, z, CANDLE)
        r.fill(1, 1, 1, 5, z, z, "minecraft:ladder[facing=east]")
        r.fill(9, 9, 1, 5, z, z, "minecraft:ladder[facing=west]")


def vault(r: Room) -> None:
    """A treasury: gold stacked behind bars down both walls, a gilded floor edge, light panels high on the walls."""
    base(r)
    ribs(r, drop=1)
    for x0, x1 in ((1, 2), (8, 9)):
        r.fill(x0, x1, 1, 1, 1, 13, "accent")
        r.fill(x0, x1, 2, 2, 2, 12, "accent")
        r.fill(x0, x1, 3, 3, 4, 10, "accent")
    for x in (3, 7):
        r.fill(x, x, 1, 3, 1, 13, "minecraft:iron_bars")
    for x in (1, 9):
        r.set(x, 4, 7, "minecraft:lodestone")
        for z in (2, 12):
            r.set(x, 3, z, CANDLE)
    for z in (1, 5, 9, 13):
        for x in (0, 10):
            r.fill(x, x, 4, 6, z, z, "light")


def pool(r: Room) -> None:
    """Two quartz basins of still water lit from beneath, lily pads on the surface, cool light above."""
    base(r)
    ribs(r)
    for x0, x1 in ((1, 3), (7, 9)):
        r.fill(x0, x1, 0, 0, 3, 11, "wall")
        r.fill(x0, x1, 1, 1, 3, 3, "trim")
        r.fill(x0, x1, 1, 1, 11, 11, "trim")
        r.fill(x0, x0, 1, 1, 3, 11, "trim")
        r.fill(x1, x1, 1, 1, 3, 11, "trim")
        r.fill(x0 + 1 if x0 == 1 else x0, x1 - 1 if x1 == 9 else x1, 1, 1, 4, 10, "minecraft:water")
        r.fill(x0 + 1, x1 - 1, 0, 0, 4, 10, "minecraft:sea_lantern")
    r.both(3, 3, 1, 1, 3, 11, "trim")
    for x, z in ((2, 5), (2, 9), (8, 5), (8, 9)):
        r.set(x, 2, z, "minecraft:lily_pad")


ROOMS = {"battle": battle, "rest": rest, "shrine": shrine, "gambler": gambler, "forge": forge, "library": library, "vault": vault, "pool": pool}


def build(kind: str, theme: dict[str, str]) -> Room:
    r = Room(theme)
    ROOMS[kind](r)
    from tower_gen import UNGLOW, connect, unglow          # joined up as pasted (no neighbour updates); past the box is the chamber's wall
    if THEME_NAMES.get(id(theme)) in UNGLOW:
        unglow(r)                                          # Rootvale's rooms are lit by hidden lights, as its halls are
    connect(r.grid, r.palette, r.bid, edge_solid=True)
    return r


# ---- checking: the aisle stays open and walkable, as in the Halloween rooms -----------------------------------------------------------

def passable(r: Room, x: int, y: int, z: int) -> bool:
    if not (0 <= x < W and 0 <= y < H and 0 <= z < D):
        return False
    name = r.palette[int(r.grid[x, y, z])]
    return name == AIR or name.startswith(("minecraft:lantern", "minecraft:chain", "minecraft:red_carpet", "minecraft:light[", "minecraft:cave_vines"))         or "candle" in name


def check(kind: str, r: Room) -> list[str]:
    for z in range(D):
        for x in (4, 5, 6):
            for y in (1, 2, 3):
                if not passable(r, x, y, z):
                    return [f"{kind}: the aisle is blocked at ({x}, {y}, {z}) by {r.palette[int(r.grid[x, y, z])]}"]
            below = r.palette[int(r.grid[x, 0, z])]
            if below == AIR or "water" in below or "lava" in below:
                return [f"{kind}: the aisle has no floor at ({x}, 0, {z})"]

    def standing(x, y, z):
        if not (passable(r, x, y, z) and passable(r, x, y + 1, z)):
            return False
        below = r.palette[int(r.grid[x, y - 1, z])] if y >= 1 else AIR
        return below != AIR and not passable(r, x, y - 1, z) and "water" not in below
    start, goal = (5, 1, 14), (5, 1, 0)
    seen, queue = {start}, deque([start])
    while queue:
        x, y, z = queue.popleft()
        if (x, y, z) == goal:
            return []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for ny in (y, y + 1, y - 1):
                n = (x + dx, ny, z + dz)
                if n not in seen and standing(*n):
                    seen.add(n)
                    queue.append(n)
                    break
    return [f"{kind}: no way on foot from the door to the back doorway"]


BLOCK_ENTITIES = ("chest", "barrel", "bed", "banner", "sign", "campfire", "lectern", "furnace", "hopper", "shulker", "spawner")


def no_block_entities(kind: str, r: Room) -> list[str]:
    return [f"{kind}: {n} is a block entity" for n in r.palette if any(be in n for be in BLOCK_ENTITIES)]


def write_nbt(r: Room, path: Path) -> int:
    from schem_to_structure import write_structure
    xs, ys, zs = np.nonzero(r.grid)
    placed = [((int(x), int(y), int(z)), r.palette[int(r.grid[x, y, z])]) for x, y, z in zip(xs, ys, zs)]
    write_structure(path, r.grid.shape, placed, DATA_VERSION)
    return len(placed)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="neutral")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--install", action="store_true", help="write room_<theme>_<kind>.nbt into the mod's structures")
    ap.add_argument("--sheet", type=Path, help="a contact sheet of every room, for review")
    args = ap.parse_args()
    theme = THEMES[args.theme]
    rooms = {kind: build(kind, theme) for kind in ROOMS}
    problems = []
    for kind, r in rooms.items():
        problems += check(kind, r) + no_block_entities(kind, r)
        print(f"room {args.theme}/{kind}: {int(np.count_nonzero(r.grid))} blocks, {len(r.palette)} states")
    for p in problems:
        print("FAIL", p)
    if problems:
        return 1
    print("rooms: PASS (every aisle open and walkable, no block entities)")
    if args.sheet:
        from room_view import sheet
        sheet(rooms, args.sheet, args.theme)
        print("sheet", args.sheet)
    if args.install and not args.check:
        for kind, r in rooms.items():
            write_nbt(r, STRUCTURES / f"room_{args.theme}_{kind}.nbt")
        print(f"installed {len(rooms)} rooms as room_{args.theme}_<kind>.nbt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
