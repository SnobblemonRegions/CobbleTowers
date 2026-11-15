#!/usr/bin/env python3
"""Checks the installed structures of a tower for blocks that do not belong: another theme's blocks (the Halloween palette) and pieces that float,
touching nothing.

    py -3.14 -I tools/tower_gen/stray_check.py [--theme neutral]

Reads the .nbt files the game pastes (src/main/resources/data/cobbletowers/structure): <theme>_act*, <theme>_hall*, <theme>_prep and
room_<theme>_<kind>. A piece floats when its blocks (6-way connected) touch no other block: in an act or the prep room, anything not joined to the
building's main mass; in a stop room, anything not joined to the room's own box faces (the room is pasted into a chamber whose walls, floor and
ceiling hold it).
"""
from __future__ import annotations

import argparse
import gzip
import sys
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validation"))
from schem_to_structure import Reader  # noqa: E402

STRUCTURES = ROOT / "src/main/resources/data/cobbletowers/structure"
BUILDING = 1000          # a piece at least this big is part of the building
FLUIDS = ("minecraft:water", "minecraft:lava")

# the Hollow's Eve palette and other blocks no regional tower should carry
FOREIGN = ("blackstone", "pumpkin", "jack_o_lantern", "soul_lantern", "soul_torch", "soul_campfire", "soul_fire", "cobweb", "crying_obsidian",
           "purple_", "orange_wool", "orange_concrete", "nether_brick", "warped_", "crimson_", "skeleton", "wither", "spider", "mangrove_roots",
           "black_wool", "dead_bush", "obsidian")


def load(path: Path):
    raw = path.read_bytes()
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    _, root = Reader(data).root()
    # a fluid keeps its level, so a waterfall ("minecraft:water[level=8]") is told apart from still water
    names = [e["Name"] + (f"[level={e['Properties']['level']}]" if e["Name"] in FLUIDS and e.get("Properties", {}).get("level", "0") != "0" else "")
             for e in root["palette"]]
    size = tuple(int(v) for v in root["size"])
    blocks = {}
    for b in root["blocks"]:
        name = names[b["state"]]
        if name == "minecraft:air":
            continue
        blocks[tuple(int(v) for v in b["pos"])] = name
    return size, blocks


def components(blocks: dict):
    seen, out = set(), []
    for start in blocks:
        if start in seen:
            continue
        comp, queue = [], deque([start])
        seen.add(start)
        while queue:
            p = queue.popleft()
            comp.append(p)
            x, y, z = p
            for n in ((x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)):
                if n in blocks and n not in seen:
                    seen.add(n)
                    queue.append(n)
        out.append(comp)
    return out


FALLING = "minecraft:water[level=8]"


def falling_problems(stem: str, blocks: dict) -> list[str]:
    """Waterfalls (falling water, level 8) in an installed structure that would misbehave once pasted. `blocks` maps (x, y, z) to a block name;
    a position missing from it is air. Still water is checked by the spill rule above; this is only for FALLING.

    In game, falling water only flows down while the block below it accepts water, so a waterfall may have air beside it. But the column must
    be fed from above (a still source or more falling water) or it dries up, and where it ends it must land in water: landing on a solid block
    or on air makes it spread sideways across that floor, up to seven blocks."""
    # Only still water or more falling water counts as a feed: a waterlogged block does feed a fall in game, but this reader keeps no
    # properties for anything but fluids, so it cannot tell a waterlogged stair from a dry one. Basins are still water (cathedral.py).
    wet = ("minecraft:water", FALLING)
    falls = [p for p, name in blocks.items() if name == FALLING]
    unfed = [p for p in falls if blocks.get((p[0], p[1] + 1, p[2])) not in wet]
    dry = [p for p in falls if blocks.get((p[0], p[1] - 1, p[2])) not in wet]
    problems = []
    if unfed:
        problems.append(f"{stem}: {len(unfed)} waterfall block(s) with nothing feeding them from above, they dry up (first at {min(unfed)})")
    if dry:
        problems.append(f"{stem}: {len(dry)} waterfall block(s) not landing in water, they spread over the floor (first at {min(dry)})")
    return problems


def check(path: Path, room: bool) -> list[str]:
    size, blocks = load(path)
    problems = []
    foreign = {}
    for pos, name in blocks.items():
        if any(f in name for f in FOREIGN):
            foreign.setdefault(name, []).append(pos)
    for name, where in sorted(foreign.items()):
        problems.append(f"{path.stem}: {len(where)} x {name} (first at {where[0]})")
    # a fluid spreads sideways and down into air the moment the structure is pasted (a missing position counts as air: whatever the world
    # holds there is not ours to rely on)
    spills = [p for p, name in blocks.items() if name in FLUIDS and any(n not in blocks for n in
              ((p[0] + 1, p[1], p[2]), (p[0] - 1, p[1], p[2]), (p[0], p[1], p[2] + 1), (p[0], p[1], p[2] - 1), (p[0], p[1] - 1, p[2])))]
    if spills:
        problems.append(f"{path.stem}: {len(spills)} fluid block(s) with air beside or below, they will spill (first at {min(spills)})")
    problems += falling_problems(path.stem, blocks)
    comps = components(blocks)
    if room:
        def anchored(c):
            return any(x in (0, size[0] - 1) or y in (0, size[1] - 1) or z in (0, size[2] - 1) for x, y, z in c)
        floating = [c for c in comps if not anchored(c)]
    else:
        # every floor of an act is its own shell (stacked 24 apart, the solid between them trimmed away, joined only by the stair's teleport),
        # so a big piece is a floor; a small one touching nothing is a stray
        floating = [c for c in comps if len(c) < BUILDING]
    for c in floating:
        names = sorted({blocks[p] for p in c})
        problems.append(f"{path.stem}: {len(c)} floating block(s) at {min(c)}: {', '.join(n.removeprefix('minecraft:') for n in names)[:160]}")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="neutral")
    args = ap.parse_args()
    t = args.theme
    buildings = sorted(STRUCTURES.glob(f"{t}_act*.nbt")) + sorted(STRUCTURES.glob(f"{t}_hall*.nbt"))     # Neutral's acts, Tideforge's halls
    targets = [(p, False) for p in buildings] + [(STRUCTURES / f"{t}_prep.nbt", False)]
    targets += [(p, True) for p in sorted(STRUCTURES.glob(f"room_{t}_*.nbt"))]
    problems = []
    for path, room in targets:
        if not path.exists():
            problems.append(f"missing {path.name}")
            continue
        found = check(path, room)
        print(f"{path.stem}: {'clean' if not found else str(len(found)) + ' problem(s)'}")
        problems += found
    for p in problems:
        print("  ", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
