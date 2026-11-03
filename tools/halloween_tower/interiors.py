#!/usr/bin/env python3
"""Hollow's Eve interiors: the gothic dressing of the tower's rooms.

Each function dresses one space of the Tower grid in unmirrored floor-local coordinates (the Tower mirrors even floors). They add dressing
only: walls, doors, gates, stairs and the zone constants stay in hollow_tower.py, and its validator still proves every walk.

The look: black stone and deepslate, a purple vein of crying obsidian, orange fire (pumpkins, shroomlight, lanterns) and a cold soul-lantern
accent. Light is white in vanilla, so colour comes from the blocks and from the glass in front of a lamp. Rules kept from the generator:
no block entities, no cobweb below head height, nothing in the walking lanes, nothing taller than three blocks on a lane.
"""
from __future__ import annotations

OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def stairs(kind: str, facing: str, half: str = "bottom") -> str:
    return f"minecraft:{kind}_stairs[facing={facing},half={half},shape=straight]"


def slab(kind: str, where: str = "bottom") -> str:
    return f"minecraft:{kind}_slab[type={where}]"


BRICK = "polished_blackstone_brick"
BRICKS = "minecraft:polished_blackstone_bricks"
CRACKED = "minecraft:cracked_polished_blackstone_bricks"
POLISHED = "minecraft:polished_blackstone"
GILDED = "minecraft:gilded_blackstone"
CHISELED = "minecraft:chiseled_polished_blackstone"
TILES = "minecraft:deepslate_tiles"
TRIM = "minecraft:crying_obsidian"
PURPLE = "minecraft:purple_concrete"
TERRA = "minecraft:purple_terracotta"
VIOLET = "minecraft:purple_stained_glass"
AMBER = "minecraft:orange_stained_glass"
SHROOM = "minecraft:shroomlight"
CHAIN = "minecraft:chain[axis=y]"
SOUL = "minecraft:soul_lantern[hanging=true]"
FIRE = "minecraft:lantern[hanging=true]"
CANDLES = "minecraft:candle[candles=4,lit=true]"
FROG_LILAC = "minecraft:pearlescent_froglight[axis=y]"
FROG_AMBER = "minecraft:ochre_froglight[axis=y]"
MAGENTA = "minecraft:magenta_stained_glass"
PURPLE_WOOL = "minecraft:purple_wool"
ORANGE_WOOL = "minecraft:orange_wool"
BLACK_WOOL = "minecraft:black_wool"


def jack(facing: str) -> str:
    return f"minecraft:jack_o_lantern[facing={facing}]"


def noise(x: int, z: int, salt: int = 0) -> int:
    """A stable scatter from a position: the same tower on every build."""
    return ((x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)) % 1000


# ---- pieces ------------------------------------------------------------------------------------------------------------------

def pier(t, x: int, z: int, f: int, top: int, face: str) -> None:
    """A one-block pier on a wall, floor to ceiling, looking `face` into the room: slab feet either side and a chiseled band."""
    t.fill(x, x, f, top, z, z, BRICKS)
    t.set(x, f + 4, z, CHISELED)
    t.set(x, f + 2, z, jack(face))                          # a lit face at eye height, looking into the room
    side = ((1, 0), (-1, 0)) if face in ("north", "south") else ((0, 1), (0, -1))
    for dx, dz in side:
        t.set(x + dx, f, z + dz, slab(BRICK))


def rib(t, x: int, z0: int, z1: int, f: int, top: int) -> None:
    """A rib across the ceiling between two piers (z0 and z1 are the pier rows): a two-block band that steps down to the piers."""
    t.fill(x, x, f + 6, top, z0 + 1, z1 - 1, BRICKS)
    t.set(x, f + 5, z0 + 1, stairs(BRICK, "north", "top"))
    t.set(x, f + 5, z1 - 1, stairs(BRICK, "south", "top"))
    t.set(x, f + 5, z0, BRICKS)
    t.set(x, f + 5, z1, BRICKS)


def hang(t, x: int, y_top: int, z: int, light: str, drop: int = 2) -> None:
    """A chain from the ceiling and a lantern at its end: y_top is the highest chain block."""
    for i in range(drop):
        t.set(x, y_top - i, z, CHAIN)
    t.set(x, y_top - drop, z, light)


GLASS_ROWS = (VIOLET, VIOLET, MAGENTA, MAGENTA, AMBER, AMBER, AMBER)   # a sunset climbing the window, bottom to head


def _pane(t, x: int, y: int, z: int, row: int, along_x: bool, behind: int) -> None:
    lamp = FROG_LILAC if row < 4 else FROG_AMBER
    t.set(x, y, z, GLASS_ROWS[row])
    t.set(x + (0 if along_x else behind), y, z + (behind if along_x else 0), lamp)


def lancet(t, xc: int, y0: int, z: int, behind: int, half_w: int) -> None:
    """A tall pointed window in a wall that runs along x: stained glass at plane z, a lamp at z + behind. Five rows then a head one block
    narrower per row; the glass climbs from violet to amber and the lamp behind it follows."""
    for dx in range(-half_w, half_w + 1):
        for r in range(5):
            _pane(t, xc + dx, y0 + r, z, r, True, behind)
    for r in range(1, half_w + 1):
        for dx in range(-(half_w - r), half_w - r + 1):
            _pane(t, xc + dx, y0 + 4 + r, z, 4 + r, True, behind)


def lancet_x(t, x: int, y0: int, zc: int, behind: int, half_w: int, rows: int = 5) -> None:
    """The same window in a wall that runs along z: glass at plane x, the lamp at x + behind. `rows` is the height of the straight part;
    a low window skips the bottom of the gradient so it still ends in amber."""
    lift = max(0, 5 - rows)
    for dz in range(-half_w, half_w + 1):
        for r in range(rows):
            _pane(t, x, y0 + r, zc + dz, r + lift, False, behind)
    for r in range(1, half_w + 1):
        for dz in range(-(half_w - r), half_w - r + 1):
            _pane(t, x, y0 + rows - 1 + r, zc + dz, rows - 1 + r + lift, False, behind)


def weather(t, x0: int, x1: int, y0: int, y1: int, z0: int, z1: int, salt: int) -> None:
    """Age a wall face of polished blackstone bricks: cracked bricks, plain stone and the odd gold fleck, scattered by position."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                # only bricks age: a gate's or a door's bars in the same face must stay bars (the walk test found them replaced)
                if t.at(t.grid.shape[0] - 1 - x if t.mirror else x, y, z) != BRICKS:
                    continue
                n = noise(x * 7 + y, z * 5 + y * 3, salt)
                if n < 110:
                    t.set(x, y, z, CRACKED)
                elif n < 170:
                    t.set(x, y, z, POLISHED)
                elif n < 182:
                    t.set(x, y, z, GILDED)


def tapestry(t, xc: int, z: int, f: int) -> None:
    """A banner on a wall that runs along x: purple cloth, a pumpkin's two eyes, nose and grin in orange, a black swallow-tail."""
    t.fill(xc - 1, xc + 1, f + 3, f + 6, z, z, PURPLE_WOOL)
    t.set(xc, f + 2, z, PURPLE_WOOL)
    t.set(xc - 1, f + 2, z, BLACK_WOOL)
    t.set(xc + 1, f + 2, z, BLACK_WOOL)
    t.fill(xc - 1, xc + 1, f + 7, f + 7, z, z, BLACK_WOOL)
    for ex in (xc - 1, xc + 1):
        t.set(ex, f + 6, z, ORANGE_WOOL)                    # eyes
    t.set(xc, f + 5, z, ORANGE_WOOL)                        # nose
    t.fill(xc - 1, xc + 1, f + 4, f + 4, z, z, ORANGE_WOOL)  # grin
    t.set(xc, f + 4, z, PURPLE_WOOL)


# ---- the intermission room ---------------------------------------------------------------------------------------------------

def decorate_inter(t, f: int, top: int, accent: str, c: dict) -> None:
    """The room behind gate A: vendor and the three pedestals in front of three doors. Clear volume x 20..76, z 23..34, y f..top.
    Piers and ribs every six blocks (x = 21, 27, 33 ... 75), lit lancets in the door wall, banners and plinths on the gate wall, hanging lamps,
    a patterned floor with a purple spine from the gate and an orange spur under each pedestal."""
    ground = f - 1
    X0, X1, Z0, Z1 = c["x0"], c["x1"], c["z0"], c["z1"]
    north, south = Z0 - 1, Z1 + 1                         # the faces of the two long walls
    centers, vendor = c["centers"], c["vendor"]
    ribs = list(range(X0 + 1, X1, 6))                     # 21, 27 ... 75

    # -- the walls, aged before anything is set into them
    weather(t, X0, X1, f, top, north, north, 11)
    weather(t, X0, X1, f, top, south, south, 12)
    weather(t, X0 - 1, X0 - 1, f, top, Z0, Z1, 13)
    weather(t, X1 + 1, X1 + 1, f, top, Z0, Z1, 14)

    # -- floor
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            block = POLISHED if ((x // 3) + (z // 3)) % 2 == 0 else TILES
            if noise(x, z, 3) < 60:
                block = CRACKED
            t.set(x, ground, z, block)
        t.set(x, ground, Z0, TRIM if x % 4 else GILDED)
        t.set(x, ground, Z1, TRIM if x % 4 else GILDED)
    for x in range(46, 51):                                # the spine from gate A to the cross runner
        for z in range(Z1 - 5, Z1):
            t.set(x, ground, z, PURPLE if 47 <= x <= 49 else TRIM)
    for x in range(centers[0] - 4, centers[-1] + 5):       # the cross runner behind the pedestals' aisle
        t.set(x, ground, Z1 - 6, accent)
        t.set(x, ground, Z1 - 7, PURPLE)
    for cx in centers:                                     # an orange spur under each pedestal
        for z in range(Z0 + 1, Z1 - 7):
            t.set(cx, ground, z, accent)
    sx, sz = vendor                                        # the vendor's dais: nested squares, a lit core
    for r, block in ((3, TRIM), (2, TERRA), (1, accent)):
        for x in range(sx - r, sx + r + 1):
            for z in range(sz - r, sz + r + 1):
                if max(abs(x - sx), abs(z - sz)) == r:
                    t.set(x, ground, z, block)
    t.set(sx, ground, sz, GILDED)

    # -- ribs and piers
    for x in ribs:
        rib(t, x, Z0, Z1, f, top)
        pier(t, x, Z0, f, top, "south")
        if not 45 <= x <= 51:                              # gate A takes the middle of the south wall
            pier(t, x, Z1, f, top, "north")

    # -- a canopy over every door: a beam of obsidian and brick between its two piers, with a soul lantern under each end
    for cx in centers:
        t.fill(cx - 2, cx + 2, f + 6, f + 6, Z0, Z0, TRIM)
        t.fill(cx - 2, cx + 2, f + 7, f + 7, Z0, Z0, BRICKS)
        t.set(cx - 2, f + 5, Z0, SOUL)
        t.set(cx + 2, f + 5, Z0, SOUL)

    # -- the door wall: a lit lancet in every gap between the door frames (the frames and doors are the caller's)
    for xc, hw in ((24, 1), (29, 1), (42, 0), (54, 0), (67, 1), (72, 1)):
        lancet(t, xc, f + 1, north, -1, hw)

    # -- the gate wall: banners in every bay but the gate's, and a candle plinth under each
    for xc in (24, 30, 36, 42, 54, 60, 66, 72):
        tapestry(t, xc, south, f)
        t.set(xc, f, Z1, BRICKS)
        t.set(xc, f + 1, Z1, CANDLES)
    for x in (44, 52):                                     # the gate's frame, with a watching pumpkin on each side
        t.fill(x, x, f, f + 7, south, south, TRIM)
    t.fill(44, 52, f + 6, f + 7, south, south, TRIM)
    for x in (43, 53):
        t.fill(x, x, f, f + 1, Z1, Z1, BRICKS)
        t.set(x, f + 2, Z1, jack("south"))

    # -- the end walls: three lit lancets each, between them a plinth with candles
    for zc in (25, 32):
        lancet_x(t, X0 - 1, f + 1, zc, -1, 1)
        lancet_x(t, X1 + 1, f + 1, zc, 1, 1)

    # -- the sills: a plinth and candles under each window, and webs high in the corners
    for xc in (24, 29, 42, 54, 67, 72):
        t.set(xc, f, Z0, BRICKS)
        t.set(xc, f + 1, Z0, CANDLES)
    for x, z in ((X0, Z0), (X1, Z0), (X0, Z1), (X1, Z1), (ribs[0] + 3, Z0), (ribs[-1] - 3, Z0)):
        t.set(x, top, z, "minecraft:cobweb")
        t.set(x, top - 1, z, "minecraft:cobweb")

    # -- hanging lamps between the ribs: warm over the aisle, soul-blue over the vendor and the far end
    for i, x in enumerate(range(X0 + 4, X1, 6)):
        cold = x <= 30 or x >= 66
        hang(t, x, top, 31, SOUL if cold else FIRE)
    # -- the ceiling: a lit boss at the centre of every bay
    for x in range(X0 + 4, X1, 6):
        t.set(x, top + 1, 29, SHROOM)


# ---- the halls: arrival and stair ---------------------------------------------------------------------------------------------------

def tapestry_x(t, x: int, zc: int, f: int) -> None:
    """The banner for a wall that runs along z: the same pumpkin face as tapestry()."""
    t.fill(x, x, f + 3, f + 6, zc - 1, zc + 1, PURPLE_WOOL)
    t.set(x, f + 2, zc, PURPLE_WOOL)
    t.set(x, f + 2, zc - 1, BLACK_WOOL)
    t.set(x, f + 2, zc + 1, BLACK_WOOL)
    t.fill(x, x, f + 7, f + 7, zc - 1, zc + 1, BLACK_WOOL)
    for ez in (zc - 1, zc + 1):
        t.set(x, f + 6, ez, ORANGE_WOOL)
    t.set(x, f + 5, zc, ORANGE_WOOL)
    t.fill(x, x, f + 4, f + 4, zc - 1, zc + 1, ORANGE_WOOL)
    t.set(x, f + 4, zc, PURPLE_WOOL)


def nave_rib(t, z: int, x0: int, x1: int, f: int, top: int, across_z: bool = False) -> None:
    """A rib across a space: a pilaster at each wall, then an underside that climbs half a block per step from f + 3 to a crown at f + 6.5
    (a top slab where the underside is a half step, a full block where it is whole). By default it spans x at row z; with across_z it spans
    z (x0..x1 are then z values) at column `z`."""
    def put(a: int, y0: int, y1: int, block: str) -> None:
        if across_z:
            t.fill(z, z, y0, y1, a, a, block)
        else:
            t.fill(a, a, y0, y1, z, z, block)

    for k in range((x1 - x0) // 2 + 1):
        half_steps = min(6 + k, 13)
        level, odd = f + half_steps // 2, half_steps % 2
        for a in (x0 + k, x1 - k):
            if odd:
                put(a, level, level, slab(BRICK, "top"))
                put(a, level + 1, top, BRICKS)
            else:
                put(a, level, top, BRICKS)
    for a in (x0, x1):
        put(a, f, f + 2, BRICKS)
        put(a, f + 1, f + 1, CHISELED)


def decorate_hall(t, f: int, top: int, accent: str, hall: tuple, outer_west: bool, z0: int, z1: int, ground: int) -> None:
    """An arrival or stair hall: a ribbed nave, a purple runner, a lit pilaster at every rib, hanging lamps over the runner, banners on the
    partition wall. `outer_west` says which side holds the tower's outer wall (and its windows). The stair zone at z < 18 is left alone."""
    x0, x1 = hall
    outer_x, inner_x = (x0, x1) if outer_west else (x1, x0)
    into_outer = "west" if not outer_west else "east"      # the way a pilaster on the partition looks is toward the outer wall
    ribs = list(range(21, z1 - 2, 8))                       # 21, 29 ... 69
    for z in range(z0, z1 + 1):                             # floor: a checker, a runner and its border
        for x in range(x0, x1 + 1):
            block = POLISHED if ((x // 3) + (z // 3)) % 2 == 0 else TILES
            if noise(x, z, 5) < 60:
                block = CRACKED
            t.set(x, ground, z, block)
        for x in range(x0 + 6, x0 + 10):
            t.set(x, ground, z, PURPLE)
        for x in (x0 + 5, x0 + 10):
            t.set(x, ground, z, TRIM if z % 4 else GILDED)
    for z in ribs:
        nave_rib(t, z, x0, x1, f, top)
        t.set(outer_x, f + 2, z, jack("east" if outer_west else "west"))
        t.set(inner_x, f + 2, z, jack("west" if outer_west else "east"))
    mid = (x0 + x1) // 2
    for z in range(z0 + 4, z1 - 1, 8):
        if z < 25:
            continue
        hang(t, x0 + 5, top, z + 4, SOUL if (z // 8) % 2 else FIRE, 3)
        hang(t, x1 - 5, top, z + 4, SOUL if (z // 8) % 2 else FIRE, 3)
    for z in ribs[:-1]:                                    # banners on the partition between the ribs (not over the door, z 46..50)
        zc = z + 4
        if 44 <= zc <= 52:
            continue
        tapestry_x(t, inner_x, zc, f)
    for z in ribs[:-1]:                                    # plinths and candles under the outer windows
        t.set(outer_x, f, z + 4, BRICKS)
        t.set(outer_x, f + 1, z + 4, CANDLES)
    for z in (z0 + 20, z1):
        for x in (x0, x1):
            t.set(x, top, z, "minecraft:cobweb")
            t.set(x, top - 1, z, "minecraft:cobweb")


# ---- the corridor behind the chambers -----------------------------------------------------------------------------------------------

def decorate_corridor(t, f: int, top: int, accent: str, x0: int, x1: int, z0: int, z1: int, ground: int, doorways: list) -> None:
    """The three-wide passage behind the chambers (x x0..x1, z z0..z1): a pointed vault of ribs every six blocks, a purple runner between
    obsidian edges, candle niches cut into the outer wall, hanging lamps over the runner and bone-white studs along the sill."""
    mid = (z0 + z1) // 2
    for x in range(x0, x1 + 1):
        t.set(x, ground, z0, TRIM if x % 4 else GILDED)
        t.set(x, ground, mid, PURPLE)
        t.set(x, ground, z1, TRIM if x % 4 else GILDED)
    ribs = list(range(x0 + 3, x1 - 1, 6))
    for x in ribs:
        t.fill(x, x, f + 4, f + 4, z0, z0, slab(BRICK, "top"))      # the haunches
        t.fill(x, x, f + 4, f + 4, z1, z1, slab(BRICK, "top"))
        t.fill(x, x, f + 5, top, z0, z0, BRICKS)
        t.fill(x, x, f + 5, top, z1, z1, BRICKS)
        t.fill(x, x, f + 6, top, mid, mid, BRICKS)                   # the crown
        t.set(x, f + 5, mid, slab(BRICK, "top"))
        t.set(x, f + 3, z0 - 1, jack("south"))                       # a lit face in the outer wall at every rib
    for x in range(x0 + 6, x1 - 2, 6):                               # hanging lamps between the ribs
        hang(t, x, top, mid, SOUL if (x // 6) % 2 else FIRE, 2)
    for x in range(x0 + 6, x1 - 2, 6):                               # candle niches in the outer wall between the ribs
        t.set(x, f + 1, z0 - 1, "minecraft:air")
        t.set(x, f + 2, z0 - 1, "minecraft:air")
        t.set(x, f + 1, z0 - 1, "minecraft:candle[candles=4,lit=true]")
    for x in range(x0, x1 + 1, 9):
        if not any(abs(x - d) <= 2 for d in doorways):
            t.set(x, top, z1, "minecraft:cobweb")
            t.set(x, top - 1, z1, "minecraft:cobweb")


# ---- the spectator gallery ----------------------------------------------------------------------------------------------------------

def decorate_gallery(t, f: int, top: int, accent: str, x0: int, x1: int, z0: int, z1: int, ground: int, aisle: tuple, window: tuple) -> None:
    """The long hall behind the arena window (x x0..x1, z z0..z1): ribs across its depth every eight blocks, a runner along the glass, rows of
    benches with a clear aisle for the spectator anchor, candles at the ends of the rows and a lit pilaster at every rib."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            block = POLISHED if ((x // 3) + (z // 3)) % 2 == 0 else TILES
            if noise(x, z, 9) < 60:
                block = CRACKED
            t.set(x, ground, z, block)
    for x in range(x0, x1 + 1):                                      # the runner along the window, with its border
        t.set(x, ground, z0, TRIM if x % 4 else GILDED)
        t.set(x, ground, z0 + 1, PURPLE)
        t.set(x, ground, z0 + 2, PURPLE)
        t.set(x, ground, z0 + 3, TRIM if x % 4 else GILDED)
    ribs = list(range(x0 + 8, x1 - 4, 8))
    for x in ribs:
        nave_rib(t, x, z0, z1, f, top, across_z=True)
        t.set(x, f + 2, z0, jack("south"))
        t.set(x, f + 2, z1, jack("north"))
    for row_z, face in ((z0 + 6, "south"), (z0 + 9, "south"), (z0 + 12, "south")):   # benches: the tall back to the south, so they face the window
        for x in range(x0 + 4, x1 - 3):
            if aisle[0] <= x <= aisle[1] or any(abs(x - r) <= 0 for r in ribs):
                continue
            if (x - x0) % 8 in (0, 7):                              # a gap at each rib for a way through
                continue
            t.set(x, f, row_z, stairs(BRICK, face))
        for x in (x0 + 3, x1 - 3):
            t.set(x, f, row_z, BRICKS)
            t.set(x, f + 1, row_z, CANDLES)
    for x in range(x0 + 12, x1 - 8, 16):                             # hanging lamps over the benches
        for z in (z0 + 8, z0 + 14):
            hang(t, x, top, z, SOUL if (x // 16) % 2 else FIRE, 3)
    for x, z in ((x0, z0), (x1, z0), (x0, z1), (x1, z1), (window[0], z0), (window[1], z0)):
        t.set(x, top, z, "minecraft:cobweb")
        t.set(x, top - 1, z, "minecraft:cobweb")


# ---- the arena's walls ---------------------------------------------------------------------------------------------------------------

def decorate_arena_walls(t, f: int, top: int) -> None:
    """Piers with a lit pumpkin under the arena's glowing ceiling lines, on the north and south walls and the west wall where the wall is free
    (the corner outcrops, the dais and the gate keep their place)."""
    def free(x: int, z: int) -> bool:
        return all(t.free(x, y, z) for y in range(f, f + 3))

    for x in (24, 36, 60, 72):
        if free(x, 37):
            pier(t, x, 37, f, top, "south")
        if free(x, 72):
            pier(t, x, 72, f, top, "north")
    for z in (41, 53, 65):
        if free(20, z):
            pier(t, 20, z, f, top, "east")
    for x, z in ((20, 37), (76, 37), (20, 72), (76, 72)):
        if t.free(x, top, z):
            t.set(x, top, z, "minecraft:cobweb")
