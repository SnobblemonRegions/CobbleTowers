#!/usr/bin/env python3
"""Logic and connection check for the regional towers, read from the installed .nbt structures and the floor/tower JSON. Complements
integrity_check.py (missing blocks) and stray_check.py (foreign blocks): this one asks whether a player can be where the layout says and walk
where the run needs them to go.

    py -3.14 -I tools/tower_gen/route_check.py [--theme neutral]      (default: all four)

Per tower:
  data      every floor's and the prep room's structure exists; milestone floors (5, 10) use a structure no ordinary floor uses; every door
            but the last floor's has a stair top and an arrival
  anchors   entry / presentation / spectator / exit / pedestals / prep entry+vendor: inside the structure, something sturdy underfoot, room
            for feet and head (the same rule CellAnchors applies when a floor is pasted)
  boxes     gate, door openings and frames, stair tops, prep dais/door/threshold: inside the structure; door openings clear once opened
  arrival   each door's arrival spot, read in the NEXT floor's structure (LaneStairs.transfer does this; nothing validates it at paste time)
  walk      from the entry, on foot (jumps of one block, drops of up to four, gates and doors open): presentation, exit, every pedestal and
            every stair top; and from each arrival on the next floor to that floor's presentation
"""
import argparse
import gzip
import json
import sys
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validation"))
from schem_to_structure import Reader  # noqa: E402

DATA = ROOT / "src/main/resources/data/cobbletowers/cobbletowers"
STRUCT = ROOT / "src/main/resources/data/cobbletowers/structure"
TOWERS = ("neutral", "tideforge", "duskvale", "rootvale")

NOCOLLIDE = ("air", "water", "light", "torch", "_sign", "banner", "button", "pressure_plate", "short_grass", "tall_grass", "fern", "vine",
             "glow_lichen", "sculk_vein", "redstone_wire", "rail", "lever", "tripwire", "sapling", "pink_petals", "seagrass", "kelp",
             "coral_fan", "hanging_roots", "spore_blossom", "structure_void", "poppy", "dandelion", "tulip", "orchid", "allium", "bluet",
             "daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "dead_bush", "nether_sprouts", "roots",
             "small_dripleaf", "big_dripleaf_stem", "sweet_berry", "lava", "fire", "bubble_column", "moss_carpet_none", "cave_vines",
             "weeping_vines", "twisting_vines", "glow_berries", "frogspawn", "leaf_litter", "firefly_bush", "bush", "cactus_flower")
PARTIAL = ("carpet", "_slab", "stairs", "snow", "dirt_path", "farmland", "_trapdoor", "daylight_detector", "lily_pad", "moss_carpet")
TALL = ("fence", "_wall", "iron_bars", "glass_pane", "chain")        # cannot be jumped onto
OPENABLE = ("_door", "fence_gate")


def load(stem):
    raw = (STRUCT / f"{stem}.nbt").read_bytes()
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    _, root = Reader(data).root()
    pal = []
    for e in root["palette"]:
        props = e.get("Properties", {})
        pal.append((e["Name"].split(":")[-1], props))
    size = tuple(int(v) for v in root["size"])
    blocks = {}
    for b in root["blocks"]:
        n, p = pal[b["state"]]
        if n in ("air", "cave_air", "void_air"):
            continue
        blocks[tuple(int(v) for v in b["pos"])] = (n, p)
    return size, blocks


class S:
    def __init__(self, stem):
        self.stem = stem
        self.size, self.blocks = load(stem)

    def get(self, p):
        return self.blocks.get(p)

    def inside(self, p):
        return all(0 <= p[i] < self.size[i] for i in range(3))

    def passable(self, p, open_boxes=()):
        b = self.get(p)
        if b is None:
            return True
        n, props = b
        if any(_in(p, box) for box in open_boxes):
            return True
        if any(k in n for k in OPENABLE):
            return True
        return any(k in n for k in NOCOLLIDE) and not n.endswith("carpet")

    def partial(self, p):
        b = self.get(p)
        if b is None:
            return False
        n, props = b
        if n.endswith("_slab"):
            return props.get("type", "bottom") == "bottom"
        if n.endswith("stairs"):
            return props.get("half", "bottom") == "bottom"
        return any(k in n for k in PARTIAL) and not n.endswith("_slab") and not n.endswith("stairs")

    def solid(self, p):
        b = self.get(p)
        if b is None:
            return False
        return not self.passable(p) and not self.partial(p)

    def sturdy_top(self, p):
        """isFaceSturdy(UP), roughly: a full collision top."""
        b = self.get(p)
        if b is None:
            return False
        n, props = b
        if self.passable(p) or self.partial(p):
            return False
        if any(k in n for k in ("fence", "_wall", "pane", "iron_bars", "chain", "lantern", "candle", "flower_pot", "head", "skull",
                                "button", "end_rod", "lightning_rod", "bell", "campfire", "anvil", "brewing", "cauldron", "hopper",
                                "chest", "enchanting", "lectern", "grindstone", "stonecutter", "bed", "cake", "amethyst_cluster",
                                "_bud", "pointed_dripstone", "scaffolding", "ladder", "decorated_pot", "conduit", "sea_pickle", "turtle_egg")):
            return False
        return True

    def anchor_problems(self, a):
        p = (a["x"], a["y"], a["z"])
        out = []
        if not self.inside(p):
            return [f"outside the structure {self.size}"]
        if not self.sturdy_top((p[0], p[1] - 1, p[2])):
            out.append(f"nothing sturdy underfoot ({self.get((p[0], p[1] - 1, p[2]))})")
        if not self.passable(p):
            out.append(f"inside a block ({self.get(p)})")
        if not self.passable((p[0], p[1] + 1, p[2])):
            out.append(f"no headroom ({self.get((p[0], p[1] + 1, p[2]))})")
        return out

    # ---- walking ----
    def standable(self, p, opens):
        x, y, z = p
        if not self.inside(p) or y < 1:
            return False
        if not self.passable(p, opens) and not self.partial(p):
            return False
        if not self.passable((x, y + 1, z), opens):
            return False
        below = (x, y - 1, z)
        water = (self.get(p) or ("",))[0] == "water"
        return water or self.partial(p) or (self.get(below) is not None and not self.passable(below, opens))

    def settle(self, p, opens):
        """The standing spot at or just under p (a teleport lands and falls)."""
        x, y, z = p
        for dy in range(0, 6):
            q = (x, y - dy, z)
            if self.standable(q, opens):
                return q
        return None

    def reach(self, start, opens, limit=2_000_000):
        seen = {start}
        q = deque([start])
        while q and len(seen) < limit:
            x, y, z = q.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                cands = []
                # same level, a step / jump up (not onto a fence or wall), drops of up to 4
                cands.append((nx, y, nz))
                up_floor = self.get((nx, y, nz))
                if not (up_floor and any(k in up_floor[0] for k in TALL)) and self.passable((x, y + 2, z), opens):
                    cands.append((nx, y + 1, nz))
                for d in range(1, 5):
                    cands.append((nx, y - d, nz))
                for c in cands:
                    if c in seen:
                        continue
                    if c[1] < y:
                        # dropping: the column above the landing must be open down to it
                        if not all(self.passable((nx, yy, nz), opens) for yy in range(c[1] + 1, y + 2)):
                            continue
                    if self.standable(c, opens):
                        seen.add(c)
                        q.append(c)
                        break
            # swimming up and down in water
            for dy in (1, -1):
                c = (x, y + dy, z)
                if c not in seen and (self.get((x, y, z)) or ("",))[0] == "water" and self.standable(c, opens):
                    seen.add(c)
                    q.append(c)
        return seen


def _in(p, box):
    lo, hi = box["min"], box["max"]
    return all(lo[i] <= p[i] <= hi[i] for i in range(3))


def box_cells(box):
    lo, hi = box["min"], box["max"]
    for x in range(lo[0], hi[0] + 1):
        for y in range(lo[1], hi[1] + 1):
            for z in range(lo[2], hi[2] + 1):
                yield (x, y, z)


def box_inside(s, box):
    return s.inside(tuple(box["min"])) and s.inside(tuple(box["max"]))


def pt(a):
    return (a["x"], a["y"], a["z"])


CACHE = {}


def struct(rid):
    stem = rid.split(":")[-1]
    if stem not in CACHE:
        CACHE[stem] = S(stem) if (STRUCT / f"{stem}.nbt").exists() else None
    return CACHE[stem]


def check_tower(t):
    issues = []
    tower = json.loads((DATA / "towers" / f"{t}.json").read_text())
    floors = []
    for fid in tower["floors"]:
        ns, path = fid.split(":")
        f = json.loads((DATA / "floors" / f"{path}.json").read_text())
        floors.append(f)
    # data
    for f in floors:
        if struct(f["layout"]["structure"]) is None:
            issues.append(("data", f"floor {f['index']}: structure {f['layout']['structure']} is not installed"))
    for f in floors:
        if f.get("milestone") and f["layout"]["structure"] in [g["layout"]["structure"] for g in floors if not g.get("milestone")]:
            issues.append(("data", f"floor {f['index']} ({f['milestone']}) shares its structure with ordinary floors"))
    # per floor
    walked = {}
    for i, f in enumerate(floors):
        L = f["layout"]
        s = struct(L["structure"])
        if s is None:
            continue
        tag = f"floor {f['index']} ({s.stem})"
        anchors = {k: L[k] for k in ("entry", "presentation", "spectator", "exit")}
        for di, d in enumerate(L.get("doors", [])):
            anchors[f"door {di} pedestal"] = d["pedestal"]
        for name, a in anchors.items():
            for pr in s.anchor_problems(a):
                issues.append(("anchor", f"{tag}: {name} {pt(a)} {pr}"))
        boxes = {"gate_a": L["gate_a"]}
        for di, d in enumerate(L.get("doors", [])):
            boxes[f"door {di} open"] = d["open"]
            boxes[f"door {di} frame"] = d["frame"]
            last = i + 1 == len(floors)
            if "stair_top" in d:
                boxes[f"door {di} stair_top"] = d["stair_top"]
            elif not last:
                issues.append(("data", f"{tag}: door {di} has no stair_top"))
            if "arrival" not in d and not last:
                issues.append(("data", f"{tag}: door {di} has no arrival"))
            if last and ("stair_top" in d or "arrival" in d):
                issues.append(("data", f"{tag}: the last floor's door {di} has a stair or arrival that leads nowhere"))
        for name, b in boxes.items():
            if not box_inside(s, b):
                issues.append(("box", f"{tag}: {name} {b} reaches outside the structure {s.size}"))
        opens = [L["gate_a"]] + [d["open"] for d in L.get("doors", [])]
        key = (s.stem, json.dumps(L, sort_keys=True))
        if key not in walked:
            start = s.settle(pt(L["entry"]), opens)
            if start is None:
                issues.append(("walk", f"{tag}: nowhere to stand at the entry"))
                walked[key] = set()
            else:
                walked[key] = s.reach(start, opens)
        seen = walked[key]
        cols = {(x, z) for x, _, z in seen}
        def reached(p):
            q = s.settle(p, opens)
            return q is not None and q in seen
        for name in ("presentation", "exit"):
            if not reached(pt(L[name])):
                issues.append(("walk", f"{tag}: {name} {pt(L[name])} cannot be walked to from the entry"))
        for di, d in enumerate(L.get("doors", [])):
            if not reached(pt(d["pedestal"])):
                issues.append(("walk", f"{tag}: door {di} pedestal {pt(d['pedestal'])} cannot be walked to from the entry"))
            if "stair_top" in d:
                if not any(c in seen for c in box_cells(d["stair_top"])):
                    issues.append(("walk", f"{tag}: door {di} stair_top {d['stair_top']['min']}..{d['stair_top']['max']} cannot be walked to from the entry"))
        # arrivals into the next floor
        if i + 1 < len(floors):
            nf = floors[i + 1]
            ns_ = struct(nf["layout"]["structure"])
            if ns_ is None:
                continue
            nopens = [nf["layout"]["gate_a"]] + [d["open"] for d in nf["layout"].get("doors", [])]
            for di, d in enumerate(L.get("doors", [])):
                if "arrival" not in d:
                    continue
                a = d["arrival"]
                for pr in ns_.anchor_problems(a):
                    issues.append(("arrival", f"{tag} door {di} -> floor {nf['index']} ({ns_.stem}): arrival {pt(a)} {pr}"))
                # the team is spread sideways along z: 0, +1, -1, +2, -2
                for off in (1, -1, 2, -2):
                    q = (a["x"], a["y"], a["z"] + off)
                    if not ns_.inside(q) or not ns_.passable(q) or not ns_.passable((q[0], q[1] + 1, q[2])):
                        issues.append(("arrival", f"{tag} door {di} -> floor {nf['index']}: teammate spot {q} is blocked ({ns_.get(q)})"))
                        break
                st = ns_.settle(pt(a), nopens)
                if st is None:
                    continue
                k2 = ("arr", ns_.stem, st)
                if k2 not in walked:
                    walked[k2] = ns_.reach(st, nopens)
                ps = ns_.settle(pt(nf["layout"]["presentation"]), nopens)
                if ps not in walked[k2]:
                    issues.append(("walk", f"{tag} door {di} -> floor {nf['index']} ({ns_.stem}): from the arrival {pt(a)} the presentation cannot be reached on foot"))
    # prep
    prep = tower.get("prep", {})
    ps = struct(prep.get("structure", "x:none"))
    if ps is None:
        issues.append(("data", f"prep structure {prep.get('structure')} is not installed"))
    else:
        for name in ("entry", "vendor"):
            for pr in ps.anchor_problems(prep[name]):
                issues.append(("anchor", f"prep ({ps.stem}): {name} {pt(prep[name])} {pr}"))
        for name in ("dais", "door", "threshold"):
            if not box_inside(ps, prep[name]):
                issues.append(("box", f"prep: {name} reaches outside {ps.size}"))
        opens = [prep["door"]]
        st = ps.settle(pt(prep["entry"]), opens)
        seen = ps.reach(st, opens) if st else set()
        for name in ("dais", "threshold"):
            if not any(c in seen for c in box_cells(prep[name])):
                issues.append(("walk", f"prep: the {name} cannot be walked to from the entry"))
        if not (ps.settle(pt(prep["vendor"]), opens) in seen or any((prep["vendor"]["x"] + dx, prep["vendor"]["y"], prep["vendor"]["z"] + dz) in seen
                                                                for dx in (-1, 0, 1) for dz in (-1, 0, 1))):
            issues.append(("walk", "prep: the vendor cannot be walked up to from the entry"))
    return issues


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", choices=TOWERS, help="one tower (default: all four)")
    args = ap.parse_args()
    total = 0
    for t in ([args.theme] if args.theme else TOWERS):
        out = check_tower(t)
        total += len(out)
        print(f"{t}: {'clean' if not out else str(len(out)) + ' finding(s)'}")
        for kind, msg in out:
            print(f"    [{kind}] {msg}")
        sys.stdout.flush()
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
