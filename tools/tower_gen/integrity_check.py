#!/usr/bin/env python3
"""Integrity scan of the structures the game pastes: missing blocks, disconnected blocks and columns that do not reach what they stand on or
hang from. Complements stray_check.py (foreign blocks, spills) and route_check.py (anchors, arrivals, walking), reading the same installed .nbt files.

    py -3.14 -I tools/tower_gen/integrity_check.py [--theme tideforge] [--limit 8]

Checks, on each building (a missing position is air):
  floating   a piece (6-way connected, any size) joined to nothing: in a building of stacked floors every floor is its own shell, so pieces under
             BUILDING blocks are reported
  dent       an air cell with five solid faces round it: one block missing from a wall, floor or ceiling, a hole the eye catches
  pinhole    an air cell boxed in on all four sides within one plane (a floor, a wall, a glass sheet) with the sheet carrying on past it
  short      a free-standing vertical run (a pillar, post, chain, pendant or waterfall: air on two opposite sides at every height) whose bottom
             or top stops one to three blocks short of a solid surface, so it neither touches it nor is clearly free
For the Tideforge halls (cathedral.py) it also checks, floor by floor, the surfaces that must be whole (cathedral.surfaces): the dome glass,
the dais, the causeway decks and the pool bed.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import stray_check as sc  # noqa: E402

WET = ("minecraft:water",)


def dense(path: Path) -> tuple[np.ndarray, list[str]]:
    size, blocks = sc.load(path)
    names = ["minecraft:air"] + sorted({n for n in blocks.values()})
    index = {n: i for i, n in enumerate(names)}
    grid = np.zeros(size, dtype=np.uint16)
    for (x, y, z), n in blocks.items():
        grid[x, y, z] = index[n]
    return grid, names


def masks(grid: np.ndarray, names: list[str]) -> tuple[np.ndarray, np.ndarray]:
    """air, and solid (anything but air, water and thin hanging things: a chain or lantern beside a gap does not wall it in)."""
    thin = np.array([n.startswith(WET) or any(k in n for k in ("chain", "minecraft:lantern", "candle")) for n in names])
    air = grid == 0
    return air, ~air & ~thin[grid]


def shifted(a: np.ndarray, axis: int, d: int, fill: bool) -> np.ndarray:
    """a seen from one step along axis: out[p] = a[p + d], `fill` beyond the edge."""
    out = np.full_like(a, fill)
    src = [slice(None)] * 3
    dst = [slice(None)] * 3
    if d > 0:
        src[axis], dst[axis] = slice(d, None), slice(None, -d)
    else:
        src[axis], dst[axis] = slice(None, d), slice(-d, None)
    out[tuple(dst)] = a[tuple(src)]
    return out


def first(mask: np.ndarray, limit: int) -> list[tuple[int, int, int]]:
    return [tuple(int(v) for v in p) for p in np.argwhere(mask)[:limit]]


def seeds_of(path: Path) -> list[tuple[int, int, int]]:
    """Where players stand in this building, from the layout its generator wrote (validation/schematics/<theme>/<theme>.layout.json): each
    floor's entry, opponent and spectator spots, or the prep room's entry. Empty when there is no layout (a stop room)."""
    import json
    theme = path.stem.split("_")[0]
    layout_file = sc.ROOT / "validation" / "schematics" / theme / f"{theme}.layout.json"
    if not layout_file.exists():
        return []
    lay = json.loads(layout_file.read_text())
    if path.stem == f"{theme}_prep":
        e = lay["prep"]["entry"]
        return [(e["x"], e["y"], e["z"])]
    return [(a["x"], a["y"] + 1, a["z"]) for floor in lay["floors"].values() if floor["structure"] == f"cobbletowers:{path.stem}"
            for a in (floor["entry"], floor["presentation"], floor["spectator"])]


def visible(air: np.ndarray, see: np.ndarray, seeds: list[tuple[int, int, int]] = ()) -> np.ndarray:
    """The air a player can look into: grown through air and see-through blocks (water, glass, leaves) from where players stand when the
    layout gives it, else from every cell sitting in open room (all six neighbours open). From the anchors, the hollows the trim leaves inside
    thick work (a pendant, the island, a root) are never reached; from open room they could be, where they join up into large voids."""
    open_ = air | see
    if seeds:
        seen = np.zeros_like(open_)
        for x, y, z in seeds:
            if 0 <= x < seen.shape[0] and 0 <= y < seen.shape[1] and 0 <= z < seen.shape[2]:
                seen[x, y, z] = open_[x, y, z]
    else:
        seen = open_.copy()
        for axis in range(3):
            for d in (1, -1):
                seen &= shifted(open_, axis, d, False)
    while True:
        grown = seen.copy()
        for axis in range(3):
            for d in (1, -1):
                grown |= shifted(seen, axis, d, False)
        grown &= open_
        if (grown == seen).all():
            return seen & air
        seen = grown


def dents(air: np.ndarray, solid: np.ndarray) -> np.ndarray:
    count = sum(shifted(solid, axis, d, True).astype(np.uint8) for axis in range(3) for d in (1, -1))
    return air & (count >= 5)


def pinholes(air: np.ndarray, solid: np.ndarray) -> np.ndarray:
    """Boxed in within a plane, and the plane carries on: the two cells beyond each in-plane neighbour are solid too (so a narrow slot between
    two pillars, or a room corner, is not a pinhole)."""
    out = np.zeros_like(air)
    for a, b in ((0, 2), (0, 1), (1, 2)):           # the horizontal plane, then the two vertical ones
        ring = np.ones_like(air)
        for axis in (a, b):
            for d in (1, -1):
                ring &= shifted(solid, axis, d, True) & shifted(solid, axis, 2 * d, True)
        out |= air & ring
    return out


def held(solid: np.ndarray, x: int, z: int, lo: int, hi: int) -> bool:
    """Whether a run is fixed along its side to something that carries on across the gap: a wall beside it from the run to the surface (a lamp
    on a wall, a pier against one), or the edge of the box (a stop room's chamber wall). Then the gap is a niche, not a column stopping short."""
    sx, _, sz = solid.shape
    for nx, nz in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
        if not (0 <= nx < sx and 0 <= nz < sz) or solid[nx, lo:hi + 1, nz].all():
            return True
    return False


def shorts(grid: np.ndarray, air: np.ndarray, solid: np.ndarray, limit: int, designed: set = frozenset()) -> list[str]:
    """Free-standing vertical runs whose ends stop 1..3 short of a solid surface."""
    sx, sy, sz = grid.shape
    # beyond the box is not open air: a stop room's chamber walls, or a building's own trimmed-away solid
    free = ~air & ((shifted(air, 0, 1, False) & shifted(air, 0, -1, False)) | (shifted(air, 2, 1, False) & shifted(air, 2, -1, False)))
    found = []
    for x, z in zip(*np.nonzero(free.any(axis=1))):
        col = free[x, :, z]
        y = 0
        while y < sy:
            if not col[y]:
                y += 1
                continue
            y0 = y
            while y < sy and col[y]:
                y += 1
            y1 = y - 1                                # a run y0..y1 of free-standing blocks in this column
            if y1 - y0 < 2 or any((int(x), yy, int(z)) in designed for yy in range(y0, y1 + 1)):
                continue                              # too short to read as a column, or a piece meant to hang free
            for end, step in ((y0, -1), (y1, 1)):
                nxt = end + step
                if not (0 <= nxt < sy) or not air[x, nxt, z]:
                    continue                          # it touches something there
                gap = 0
                k = nxt
                while 0 <= k < sy and air[x, k, z] and gap < 4:
                    gap += 1
                    k += step
                lo, hi = sorted((end, k))
                if gap <= 3 and 0 <= k < sy and solid[x, k, z] and not held(solid, x, z, lo, hi):
                    found.append(f"run at x {x}, z {z}, y {y0}..{y1} stops {gap} short of the {'floor' if step < 0 else 'ceiling'} "
                                 f"(at y {k})")
    return found[:limit] + ([f"... {len(found) - limit} more"] if len(found) > limit else [])


def cathedral_surfaces(grid: np.ndarray, names: list[str], building: int, limit: int) -> list[str]:
    """The surfaces of a Tideforge hall that must be whole, floor by floor, as cathedral.py lays them out."""
    import cathedral as C
    problems = []
    air = grid == 0
    for floor in C.BUILDINGS[building - 1]:
        if floor in C.SPEC.boss:
            continue                                           # a boss hall has its own checks (boss_tideforge.py)
        for label, cells in C.surfaces(floor, grid.shape).items():
            holes = air[cells[:, 0], cells[:, 1], cells[:, 2]]
            if holes.any():
                first = tuple(int(v) for v in cells[np.argmax(holes)])
                problems.append(f"floor {floor}: {int(holes.sum())} missing block(s) in the {label}, first at {first}")
    return problems


def scan(path: Path, limit: int) -> list[str]:
    grid, names = dense(path)
    air, solid = masks(grid, names)
    # what a player sees past: water, glass and leaves, and bars, rails and fences (so the flood reaches the atrium past gate A's bars and the
    # chambers past their doors)
    see = np.array([n.startswith(WET) or any(k in n for k in ("glass", "leaves", "iron_bars", "_pane", "_wall", "fence")) for n in names])[grid]
    looked_at = visible(air, see, seeds_of(path))
    problems = []
    size, blocks = sc.load(path)
    room = path.stem.startswith("room_")
    for msg in sc.check(path, room):
        if "floating" in msg:
            problems.append(msg.replace(f"{path.stem}: ", "floating: "))
    for label, mask in (("dent", dents(air, solid) & looked_at), ("pinhole", pinholes(air, solid) & looked_at)):
        if mask.any():
            problems.append(f"{label}: {int(mask.sum())} cell(s), first at {first(mask, limit)}")
    problems += [f"short: {s}" for s in shorts(grid, air, solid, limit, sc.meant_to_float(path))]
    if path.stem.startswith("tideforge_hall"):
        problems += cathedral_surfaces(grid, names, int(path.stem.removeprefix("tideforge_hall")), limit)
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="tideforge")
    ap.add_argument("--limit", type=int, default=8)
    args = ap.parse_args()
    t = args.theme
    paths = sorted(sc.STRUCTURES.glob(f"{t}_act*.nbt")) + sorted(sc.STRUCTURES.glob(f"{t}_hall*.nbt")) + sorted(sc.STRUCTURES.glob(f"{t}_boss*.nbt")) + [sc.STRUCTURES / f"{t}_prep.nbt"]
    paths += sorted(sc.STRUCTURES.glob(f"room_{t}_*.nbt"))
    total = 0
    for p in paths:
        found = scan(p, args.limit)
        total += len(found)
        print(f"{p.stem}: {'clean' if not found else str(len(found)) + ' finding(s)'}")
        for line in found:
            print("   ", line)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
