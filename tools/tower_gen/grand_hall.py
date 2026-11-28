#!/usr/bin/env python3
"""What every themed tower's own buildings share (P59, owner direction 14 Nov 2026: each themed tower gets its own geometry).

A themed tower is a `Spec`: its theme, how its ten floors are grouped into buildings, how far apart the floors of a building stand, and a
`hall` function that builds the battle hall of one floor. Everything else is the same for every themed tower and lives here:

    z 3-46    the north part, exactly Neutral's moved OX east (tower_gen.py has the plan): each lane's stair, RoomDresser's 11 x 15 x 8
              chambers, the three doors, the atrium with the vendor stand and the pedestals, gate A in the wall at z 45-46
    z 47-142  the battle hall (the tower's own: `Spec.hall`), 150 wide in all, its open width HALL_X
    z 143-158 the passage, the arrival hall the lane stairs of the floor below land in, a landing arch per lane

Leaving the top floor of a building is the lane stair into the next building (WalkRules.climbsAcross); the next building is built in its
own cell while the party walks. `main` builds, validates (the walk, the shells, the held window, the spec's own checks), writes and installs.

    tools/tower_gen/cathedral.py    Tideforge, the sea temple
    tools/tower_gen/duskvale.py     Duskvale, the shattered cathedral
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tower_gen as tg  # noqa: E402
from tower_gen import BARS, CHAIN, Grid, room, vault  # noqa: E402
from themes import THEMES  # noqa: E402

# ---- the plan (x): 150 wide, the shared north part moved OX east so it stays centred ------------------------------------------------
SX = 150
OX = (SX - 66) // 2                     # 42
MID = tg.MID_X + OX                     # 74
LANES = tuple(c + OX for c in tg.LANES)
GATE_X = (tg.GATE_X[0] + OX, tg.GATE_X[1] + OX)
ATRIUM_X = (tg.HALL_X[0] + OX, tg.HALL_X[1] + OX)
STAND = (tg.STAND[0] + OX, tg.STAND[1])
ARRIVAL_X = (tg.ARRIVAL_X[0] + OX, tg.ARRIVAL_X[1] + OX)
HALL_X = (9, 140)
PASSAGE_HALF = 4

# ---- the plan (z) --------------------------------------------------------------------------------------------------------------------
HALL_Z = (47, 142)
PASSAGE_Z = (143, 144)
ARRIVAL_Z = (145, 156)
ARCH_Z = (157, 158)
ARRIVAL_ANCHOR_Z = ARRIVAL_Z[0] + 8
SZ = ARCH_Z[1] + 2                      # the arches' backlight is the last row

# water is seen through: the generator must neither trim it (a hollow pool would drain into the gap in game) nor treat it as a wall
if "water" not in tg.SEE_THROUGH:
    tg.SEE_THROUGH = tg.SEE_THROUGH + ("water",)


@dataclass
class Boss:
    """A boss floor's own hall (owner, 15 Nov 2026: floors 5 and 10 are fully rebuilt halls, unlike each other and every other floor). A boss
    floor is a building of its own, so its hall may be as tall as the cell allows and it brings its own anchors and checks."""
    hall: Callable[[Grid, int, int], None]                  # builds the hall of (g, floor, feet)
    top: int                                                # how far above its feet its highest block may stand
    base_feet: int                                          # its feet in its own building (room below for pools, abysses, islands)
    entry_z: int
    presentation_z: int
    spectator: tuple[int, int, int]
    rails: tuple[tuple[int, int], ...] = ()
    checks: Callable[[Grid], list[str]] = lambda g: []
    floating: list[tuple[int, int, int]] = field(default_factory=list)
    open_sky: bool = False                                  # a glass roof with the sky over it: the room may look out at the top


@dataclass
class Spec:
    theme: str
    buildings: tuple[tuple[int, ...], ...]
    crowns: tuple[int, ...]
    pitch: int                                              # feet to feet between the floors of a building
    base_feet: int                                          # the feet of a building's first floor
    top: Callable[[bool], int]                              # how far above its feet a floor's highest block may stand (crown or not)
    hall: Callable[[Grid, int, int, bool], None]            # builds the battle hall of (g, floor, feet, crown)
    entry_z: int                                            # where the party is set down in the hall, facing north
    presentation_z: int                                     # where the opponent stands, facing south
    spectator: tuple[int, int, int]                         # (x, height above the feet, z) of the spectator anchor, facing east
    rails: tuple[tuple[int, int], ...] = ()                 # (x, height above the feet) of gallery rails that must run the hall's length
    checks: Callable[[Grid], list[str]] = lambda g: []      # the spec's own checks, run before and after the trim
    floating: Callable[[int], list[tuple[int, int, int]]] = lambda b: []   # one block of each piece meant to hang free, per building
    summary: str = "arrival -> hall -> gate A -> door -> chamber -> its own stair -> threshold"
    extra: dict = field(default_factory=dict)
    boss: dict[int, Boss] = field(default_factory=dict)     # floor -> its own hall; a boss floor must be a building on its own


def boss_of(spec: Spec, floor: int) -> Boss | None:
    return spec.boss.get(floor)


def anchors(spec: Spec, floor: int) -> tuple[int, int, tuple[int, int, int], tuple[tuple[int, int], ...]]:
    """(entry z, presentation z, spectator, rails) of a floor."""
    b = boss_of(spec, floor)
    if b:
        return b.entry_z, b.presentation_z, b.spectator, b.rails
    return spec.entry_z, spec.presentation_z, spec.spectator, spec.rails


def building_boss(spec: Spec, b: int) -> Boss | None:
    floors = spec.buildings[b - 1]
    return boss_of(spec, floors[0]) if len(floors) == 1 else None


def ellipse(x: int, x0: int, x1: int, rise: int) -> int:
    t = (x - (x0 + x1) / 2) / ((x1 - x0) / 2)
    return int(round(rise * math.sqrt(max(0.0, 1 - t * t))))


def hang(g: Grid, x: int, z: int, top: int, low: int, ring: int, frame_role: str = "rib") -> None:
    """A chain from the vault at `top` to `low`, a light block there, and six lights hung on short chains from a frame: a ring at low + 4 joined
    to the central chain by four straight spokes, so every piece hangs from the vault through something (stray_check finds floaters)."""
    g.box(CHAIN, x, low + 1, z, x, top, z)
    g.box("light", x, low - 1, z, x, low, z)
    frame = low + 4
    for dx in range(-ring - 1, ring + 2):                           # the ring: a band wide enough that it joins face to face, not corner to corner
        for dz in range(-ring - 1, ring + 2):
            if abs(math.hypot(dx, dz) - ring) <= 0.71:
                g.at(frame_role, x + dx, frame, z + dz)
    g.box(frame_role, x - ring, frame, z, x + ring, frame, z)        # four straight spokes from the central chain (a diagonal one joins only by
    g.box(frame_role, x, frame, z - ring, x, frame, z + ring)        # corners)
    for a in range(0, 360, 60):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        px, pz = x + round(ring * ca), z + round(ring * sa)
        g.box(CHAIN, px, low + 1, pz, px, frame - 1, pz)
        g.at("light", px, low, pz)


def rose(g: Grid, cx: int, cy: int, z: int, back: int, r: int) -> None:
    """A rose window in a wall face at z, backlit from the row behind it: an inlay rim and hub, rib tracery, coloured glass between."""
    for x in range(cx - r - 1, cx + r + 2):
        for y in range(cy - r - 1, cy + r + 2):
            d = math.hypot(x - cx, y - cy)
            if d > r:
                continue
            a = math.degrees(math.atan2(y - cy, x - cx)) % 30
            g.at("inlay" if d >= r - 1 or d <= 2 else "rib" if a < 2.5 or abs(d - r * 0.45) < 0.6 else "glass", x, y, z)
            g.at("light", x, y, back)


# ---- floors and buildings --------------------------------------------------------------------------------------------------------

def building_of(spec: Spec, floor: int) -> int:
    return next(i for i, b in enumerate(spec.buildings, 1) if floor in b)


def feet(spec: Spec, floor: int) -> int:
    boss = boss_of(spec, floor)
    if boss:
        return boss.base_feet
    return spec.base_feet + spec.pitch * spec.buildings[building_of(spec, floor) - 1].index(floor)


def height(spec: Spec, b: int) -> int:
    last = spec.buildings[b - 1][-1]
    boss = boss_of(spec, last)
    if boss:
        return feet(spec, last) + boss.top
    return feet(spec, last) + spec.top(last in spec.crowns)


def passage_and_arrival(g: Grid, f: int) -> None:
    """The passage south out of the hall into the arrival hall: low, ribbed, a landing arch per lane on its south wall."""
    ground = f - 1
    h = PASSAGE_HALF
    g.air(MID - h, f, PASSAGE_Z[0], MID + h, f + 6, PASSAGE_Z[1])
    g.box("floor", MID - h, ground, PASSAGE_Z[0], MID + h, ground, PASSAGE_Z[1])
    g.box("trim", MID - h - 1, f + 7, PASSAGE_Z[0], MID + h + 1, f + 7, PASSAGE_Z[0])
    room(g, *ARRIVAL_X, *ARRIVAL_Z, f, 8)
    g.air(MID - h, f, PASSAGE_Z[1], MID + h, f + 6, PASSAGE_Z[1] + 1)       # the arrival's plinth would close the passage
    g.box("rib", ARRIVAL_X[0], f + 8, ARRIVAL_Z[0], ARRIVAL_X[1], f + 8, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0], ARRIVAL_X[1] + 1, 4):
        g.box("wall_low", x, f + 7, ARRIVAL_Z[0], x, f + 7, ARRIVAL_Z[1])
    for x in range(ARRIVAL_X[0] + 3, ARRIVAL_X[1], 6):
        g.at("light", x, f + 8, (ARRIVAL_Z[0] + ARRIVAL_Z[1]) // 2)
    g.box("inlay", MID, ground, ARRIVAL_Z[0], MID, ground, ARRIVAL_Z[1])
    for c in LANES:
        g.air(c - 2, f, ARCH_Z[0], c + 2, f + 4, ARCH_Z[1])
        g.box("glass", c - 2, f, ARCH_Z[1], c + 2, f + 4, ARCH_Z[1])
        g.box("light", c - 2, f, ARCH_Z[1] + 1, c + 2, f + 4, ARCH_Z[1] + 1)
        g.box("floor", c - 2, ground, ARCH_Z[0], c + 2, ground, ARCH_Z[1])
        g.box("trim", c - 3, f, ARCH_Z[0], c - 3, f + 5, ARCH_Z[0])
        g.box("trim", c + 3, f, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.box("trim", c - 3, f + 5, ARCH_Z[0], c + 3, f + 5, ARCH_Z[0])
        g.at("accent", c, f + 5, ARCH_Z[0])


def north_part(g: Grid, f: int, stairs: bool) -> None:
    """The atrium, gate A, the doors, the chambers and the lane stairs, exactly Neutral's moved OX east."""
    ground = f - 1
    room(g, *ATRIUM_X, *tg.ATRIUM_Z, f, 9)
    vault(g, *ATRIUM_X, *tg.ATRIUM_Z, f, 9, 3, 4)
    tg.vendor_stand(g, STAND[0], STAND[1], f, "south")
    for x in LANES:
        tg.chandelier(g, x, 38, f, 6)
    g.box("inlay", ATRIUM_X[0], ground, 38, STAND[0] - 2, ground, 38)
    g.air(GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1])
    g.box(BARS, GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1])
    g.box("trim", GATE_X[0] - 1, f + 6, tg.GATE_Z[0], GATE_X[1] + 1, f + 6, tg.GATE_Z[1])
    g.at("accent", MID, f + 6, tg.GATE_Z[1])
    for c in LANES:
        room(g, c - tg.CHAMBER_HALF, c + tg.CHAMBER_HALF, *tg.CHAMBER_Z, f, tg.CHAMBER_H)
        vault(g, c - tg.CHAMBER_HALF, c + tg.CHAMBER_HALF, *tg.CHAMBER_Z, f, tg.CHAMBER_H, 3, 3)
        g.box("inlay", c - 1, ground, tg.CHAMBER_Z[0], c + 1, ground, tg.CHAMBER_Z[1])
        if stairs:
            tg.cut_stair(g, c, f)
    for c in LANES:
        h = tg.DOOR_HALF
        g.air(c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1])
        g.box(BARS, c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c - h - 1, f, tg.DOOR_WALL_Z[0], c - h - 1, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c + h + 1, f, tg.DOOR_WALL_Z[0], c + h + 1, f + 5, tg.DOOR_WALL_Z[1])
        g.box("trim", c - h - 1, f + 6, tg.DOOR_WALL_Z[0], c + h + 1, f + 7, tg.DOOR_WALL_Z[1])
        g.box("light", c - h - 1, f + 8, tg.DOOR_WALL_Z[1], c + h + 1, f + 8, tg.DOOR_WALL_Z[1])
        g.at("accent", c, f + 7, tg.DOOR_WALL_Z[1])


def build_floor(spec: Spec, g: Grid, floor: int) -> None:
    f = feet(spec, floor)
    boss = boss_of(spec, floor)
    if boss:
        boss.hall(g, floor, f)
    else:
        spec.hall(g, floor, f, floor in spec.crowns)
    passage_and_arrival(g, f)
    north_part(g, f, floor < tg.FLOORS)


def build_building(spec: Spec, theme: dict[str, str], b: int) -> Grid:
    g = Grid(theme, (SX, height(spec, b), SZ))
    g.grid[:] = g.id("wall")
    for floor in spec.buildings[b - 1]:
        build_floor(spec, g, floor)
    if spec.theme in tg.UNGLOW:                            # lit by hidden lights, none in the rooms RoomDresser pastes behind the doors
        tg.unglow(g, [tg.dresser_box(d) for k in spec.buildings[b - 1] for d in layout(spec, k)["doors"]])
    tg.connect(g.grid, g.palette, g.id)                    # rails and bars joined up: the paste does no neighbour updates
    return g


# ---- the layout the floor definitions use -------------------------------------------------------------------------------------

def layout(spec: Spec, floor: int) -> dict:
    f = feet(spec, floor)
    doors = []
    for lane, c in enumerate(LANES):
        h = tg.DOOR_HALF
        door = {
            "open": tg.box(c - h, f, tg.DOOR_WALL_Z[0], c + h, f + 5, tg.DOOR_WALL_Z[1]),
            "frame": tg.box(c - h - 1, f + 6, tg.DOOR_WALL_Z[0], c + h + 1, f + 7, tg.DOOR_WALL_Z[1]),
            "pedestal": tg.anchor(c, f, tg.PEDESTAL_Z, 0),
        }
        if floor < tg.FLOORS:
            door["stair_top"] = tg.box(c - tg.STUB_HALF, f + tg.STEPS, tg.LANDING_Z[0], c + tg.STUB_HALF, f + tg.STEPS + 1, tg.LANDING_Z[0] + 1)
            door["arrival"] = tg.anchor(LANES[lane], feet(spec, floor + 1), ARRIVAL_ANCHOR_Z, 180)
        doors.append(door)
    entry_z, presentation_z, (sx, sy, sz), _ = anchors(spec, floor)
    return {
        "structure": f"cobbletowers:{spec.theme}_hall{building_of(spec, floor)}",
        "entry": tg.anchor(MID, f, entry_z, 180),
        "presentation": tg.anchor(MID, f, presentation_z, 0),
        "spectator": tg.anchor(sx, f + sy, sz, 270),
        "exit": tg.anchor(STAND[0] + 3, f, STAND[1] + 2, 0),
        "gate_a": tg.box(GATE_X[0], f, tg.GATE_Z[0], GATE_X[1], f + 5, tg.GATE_Z[1]),
        "doors": doors,
    }


# ---- validation: the same walk as Neutral's, over these buildings -----------------------------------------------------------------

def validate(spec: Spec, buildings: dict[int, Grid]) -> list[str]:
    problems: list[str] = []
    walkers = {b: tg.Walker(g) for b, g in buildings.items()}
    for floor in range(1, tg.FLOORS + 1):
        w = walkers[building_of(spec, floor)]
        lay = layout(spec, floor)
        f = feet(spec, floor)
        for name in ("entry", "presentation", "spectator", "exit"):
            if not w.standable(*tg._cell(lay[name])):
                problems.append(f"floor {floor}: the {name} anchor {lay[name]} is not standable")
        for i, door in enumerate(lay["doors"]):
            if not w.standable(*tg._cell(door["pedestal"])):
                problems.append(f"floor {floor}: the pedestal of door {i} is not standable")
            x0, y0, z0, x1, y1, z1 = tg.dresser_box(door)
            if w.g.grid[x0:x1 + 1, y0:y1 + 1, z0:z1 + 1].any():
                problems.append(f"floor {floor}: RoomDresser's box behind door {i} is not empty air")
        for rail, up in anchors(spec, floor)[3]:
            gaps = [z for z in range(*HALL_Z) if w.g.name(rail, f + up, z).split("[")[0] != w.g.theme["rail"]]
            if gaps:
                problems.append(f"floor {floor}: the gallery rail at x {rail} has gaps (z {gaps[:4]})")
        entry, pres, spot_spec = tg._cell(lay["entry"]), tg._cell(lay["presentation"]), tg._cell(lay["spectator"])
        vendor = (lay["exit"]["x"], f, lay["exit"]["z"] + 2)
        if not w.standable(*vendor):
            problems.append(f"floor {floor}: the spot in front of the vendor's counter {vendor} is not standable")
        chambers = [(c, f, (tg.CHAMBER_Z[0] + tg.CHAMBER_Z[1]) // 2) for c in LANES]
        tops = [tg._mid(d["stair_top"], f + tg.STEPS) for d in lay["doors"]] if floor < tg.FLOORS else []
        gate = lay["gate_a"]
        closed = w.reach(entry, [])
        if pres not in closed:
            problems.append(f"floor {floor}: the opponent's spot is not reachable from the entry")
        if vendor in closed:
            problems.append(f"floor {floor}: the vendor is reachable with gate A shut")
        if spot_spec in closed or pres in w.reach(spot_spec, []):
            problems.append(f"floor {floor}: the gallery and the arena are joined on foot")
        after_a = w.reach(entry, [gate])
        if vendor not in after_a:
            problems.append(f"floor {floor}: the vendor is not reachable with gate A open")
        for i, spot in enumerate(chambers):
            if spot in after_a:
                problems.append(f"floor {floor}: chamber {i} is reachable with its door shut")
        for i, door in enumerate(lay["doors"]):
            through = w.reach(entry, [gate, door["open"]])
            if chambers[i] not in through:
                problems.append(f"floor {floor}: chamber {i} is not reachable through its door")
            if any(spot in through for j, spot in enumerate(chambers) if j != i):
                problems.append(f"floor {floor}: another chamber is reachable through door {i}")
            if floor < tg.FLOORS:
                if tops[i] not in through:
                    problems.append(f"floor {floor}: the threshold of lane {i} is not reachable through door {i}")
                if any(spot in through for j, spot in enumerate(tops) if j != i):
                    problems.append(f"floor {floor}: another lane's threshold is reachable through door {i}")
                ys = {c[1] for c in through}
                if max(ys) > f + tg.STEPS or min(ys) < f:
                    problems.append(f"floor {floor}: the walk through door {i} leaves its floor, y {min(ys)}..{max(ys)}")
                nw = walkers[building_of(spec, floor + 1)]
                spot = tg._cell(door["arrival"])
                if not nw.standable(*spot):
                    problems.append(f"floor {floor}: the arrival of lane {i} {door['arrival']} is not standable")
                elif tg._cell(layout(spec, floor + 1)["entry"]) not in nw.reach(spot, []):
                    problems.append(f"floor {floor}: from the arrival of lane {i} the next battle hall is not reachable")
    return problems


def shell_problems(g: Grid, starts: list[tuple[int, int, int]], open_sky: bool) -> list[str]:
    """tower_gen.shell_closed, except that a hall under a glass roof may look out through the top of its box at the sky: the box's top rows
    are air there, so the open space reaches the top edge and nowhere else."""
    if not open_sky:
        return tg.shell_closed(g, starts)
    sy = g.grid.shape[1]
    lid = g.grid[:, sy - 1, :].copy()
    removed = g.removed[:, sy - 1, :].copy()
    g.grid[:, sy - 1, :] = g.id("minecraft:bedrock")                # a pretend lid, for the check only
    g.removed[:, sy - 1, :] = False
    try:
        return tg.shell_closed(g, starts)
    finally:
        g.grid[:, sy - 1, :] = lid
        g.removed[:, sy - 1, :] = removed


def main(spec: Spec) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="build and validate only")
    parser.add_argument("--install", action="store_true", help="also copy the .nbt files into the mod's structure folder")
    parser.add_argument("--out", type=Path, default=tg.ROOT / "validation" / "schematics")
    args = parser.parse_args()
    theme = THEMES[spec.theme]
    name = spec.theme

    buildings = {b: build_building(spec, theme, b) for b in range(1, len(spec.buildings) + 1)}
    prep = tg.build_prep(theme)
    if name in tg.UNGLOW:
        tg.unglow(prep)
        tg.connect(prep.grid, prep.palette, prep.id)
    problems = validate(spec, buildings) + tg.validate_prep(prep)
    for b, g in buildings.items():
        boss = building_boss(spec, b)
        own_checks = boss.checks if boss else spec.checks
        problems += [f"hall{b}: {p}" for p in own_checks(g)]
        solid = int(np.count_nonzero(g.grid))
        kept = solid - tg.trim(g)
        print(f"{name}_hall{b}: {g.grid.shape[0]}x{g.grid.shape[1]}x{g.grid.shape[2]}, {solid:,} blocks before the trim, {kept:,} kept")
        starts = [s for k in spec.buildings[b - 1] for s in (tg._cell(layout(spec, k)["entry"]), tg._cell(layout(spec, k)["spectator"]))]
        problems += [f"hall{b}: {p}" for p in shell_problems(g, starts, boss is not None and boss.open_sky)]
        problems += tg.held_window_problems(g.grid, f"{name}_hall{b}")
        problems += [f"hall{b} after the trim: {p}" for p in own_checks(g)]
    tg.trim(prep)
    problems += [f"prep: {p}" for p in tg.shell_closed(prep, [tg._cell(tg.prep_layout()["entry"])])]
    problems += tg.held_window_problems(prep.grid, f"{name}_prep")
    for p in problems:
        print("FAIL", p)
    if problems:
        return 1
    print(f"validation: PASS (every floor: {spec.summary}; lanes never meet; each arrival leads to the next hall; galleries cut off from the "
          "arena; shells closed; the tower's own checks)")
    if args.check:
        return 0
    out = args.out / name
    out.mkdir(parents=True, exist_ok=True)
    files = {f"{name}_hall{b}": g for b, g in buildings.items()} | {f"{name}_prep": prep}
    for stem, g in files.items():
        n = tg.write_nbt(g, out / f"{stem}.nbt")
        print(f"wrote {out / (stem + '.nbt')} ({n:,} blocks)")
    for b in buildings:
        boss = building_boss(spec, b)
        free = list(boss.floating) if boss else spec.floating(b)
        sidecar = out / f"{name}_hall{b}.floating.json"
        if free:
            sidecar.write_text(json.dumps([list(p) for p in free]) + "\n")      # stray_check and integrity_check leave these pieces be
        else:
            sidecar.unlink(missing_ok=True)
    lay = {"floors": {str(k): layout(spec, k) for k in range(1, tg.FLOORS + 1)}, "prep": tg.prep_layout()}
    (out / f"{name}.layout.json").write_text(json.dumps(lay, indent=2) + "\n")
    print(f"wrote {out / (name + '.layout.json')}")
    if args.install:
        dest = tg.ROOT / "src/main/resources/data/cobbletowers/structure"
        for stem in files:
            shutil.copyfile(out / f"{stem}.nbt", dest / f"{stem}.nbt")
            print("installed", dest / f"{stem}.nbt")
        for old in ("act1", "act2"):                      # the re-skins of Neutral's acts these replace
            (dest / f"{name}_{old}.nbt").unlink(missing_ok=True)
    return 0
