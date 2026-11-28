"""Exports the Records Hall trophy cases (owner, 23 Nov 2026) as item models for the mod, from the concept the owner approved
(validation/concepts/trophy_case): each tower's case is a stepped stand in that tower's stone against its alcove panel, with six ladder
trophies (one per achievement family, upgrading stone -> bronze -> silver -> gold -> ascended) on the two front tiers and the six one-off
feats as medals on the back rail.

The case's frame, in 1/16 block: the origin is the panel's face at floor level, middle of the alcove; +z runs out into the hall, +x along the
panel, y up. Written:
  models/trophy/<tower>/case_*.json      the still case, cut into item-model-sized chunks (and _glow chunks for the light strips)
  models/trophy/<tower>/<family>_<tier>.json (+ _glow)   each ladder trophy, its foot at (8, -4, 8) so it spins about its own axis
  models/trophy/common/medal_<a|b|dark>.json, pool.json  the medals (centred) and the gold light pool under a gold trophy
  textures/item/trophy/<tower>.png, common.png
  data/.../hub/trophy.json               where every chunk, trophy foot and medal sits, in blocks in the case's frame

    python tools/tower_gen/trophy_export.py
"""
from __future__ import annotations

import json
from pathlib import Path

import gate_altar as GA
from altar_export import Atlas, chunked, element, model, write

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "src/main/resources/assets/cobbletowers/models/trophy"
TEXTURES = ROOT / "src/main/resources/assets/cobbletowers/textures/item/trophy"
DATA = ROOT / "src/main/resources/data/cobbletowers/cobbletowers/hub/trophy.json"
TOWERS = ("neutral", "tideforge", "duskvale", "rootvale")
TIERS = ("stone", "bronze", "silver", "gold", "ascended")
FAMILIES = ("depth", "flawless", "speed", "solo", "challenger", "score")
M = GA.mat
TOWER_GLOW = {"neutral": "#f2c744", "tideforge": "#3fa9f5", "duskvale": "#b57bea", "rootvale": "#7bd35a"}
SCALE = 1.35
FOOT = (8, -4, 8)                                   # a trophy's foot in its model; the mod puts the display 0.75 block over the foot


def bx(cx, cy, cz, a, b, c, d, e, f, mat):
    return ((cx + a, cy + b, cz + c), (cx + d, cy + e, cz + f), mat)


# ---- the six ladder trophies, each built round its foot at (0, 0, 0) -------------------------------------------------------------------

def spire(t):
    m = "t_" + t
    return [bx(0, 0, 0, -4, 0, -4, 4, 4, 4, m), bx(0, 0, 0, -3, 4, -3, 3, 8, 3, m), bx(0, 0, 0, -2, 8, -2, 2, 12, 2, m),
            bx(0, 0, 0, -1, 12, -1, 1, 17, 1, m), bx(0, 0, 0, -0.75, 17, -0.75, 0.75, 20, 0.75, "tip" if t == "ascended" else m)]


def gem(t):
    m = "g_" + t
    return [bx(0, 0, 0, -1, 0, -1, 1, 3, 1, "t_" + t), bx(0, 0, 0, -1.5, 3, -1.5, 1.5, 4, 1.5, m), bx(0, 0, 0, -2.5, 4, -2.5, 2.5, 6, 2.5, m),
            bx(0, 0, 0, -3.5, 6, -3.5, 3.5, 9, 3.5, m), bx(0, 0, 0, -3, 9, -3, 3, 10, 3, m), bx(0, 0, 0, -2, 10, -2, 2, 11, 2, m)]


def hourglass(t):
    m = "t_" + t
    out = [bx(0, 0, 0, -4, 0, -4, 4, 1.5, 4, m), bx(0, 0, 0, -4, 14.5, -4, 4, 16, 4, m)]
    for sx in (-1, 1):
        for sz in (-1, 1):
            out.append(bx(0, 0, 0, sx * 3.5 - 0.5, 1.5, sz * 3.5 - 0.5, sx * 3.5 + 0.5, 14.5, sz * 3.5 + 0.5, m))
    for lo, hi, r in ((1.5, 5.5, 2.5), (5.5, 7, 1.5), (7, 9, 0.6), (9, 10.5, 1.5), (10.5, 14.5, 2.5)):
        out.append(bx(0, 0, 0, -r, lo, -r, r, hi, r, "sand" if lo < 6 else "glass"))
    return out


def banner(t):
    m = "t_" + t
    cl = "dark" if t == "stone" else "cloth"
    return [bx(0, 0, 0, -0.6, 0, -0.6, 0.6, 19, 0.6, m), bx(0, 0, 0, -4.5, 17, -0.6, 4.5, 18, 0.6, m), bx(0, 0, 0, -1.2, 19, -1.2, 1.2, 21, 1.2, m),
            bx(0, 0, 0, -4, 9, 0.6, 4, 17, 1.2, cl), bx(0, 0, 0, -4, 6.5, 0.6, -1, 9, 1.2, cl), bx(0, 0, 0, 1, 6.5, 0.6, 4, 9, 1.2, cl),
            bx(0, 0, 0, -1.2, 11.5, 1.2, 1.2, 14.5, 1.5, m)]


def blades(t):
    m = "t_" + t
    out = [bx(0, 0, 0, -4, 0, -2, 4, 1.5, 2, m)]
    for i in range(11):                                    # two blades crossing, stepped along their diagonals
        out.append(bx(0, 0, 0, -5 + i - 0.7, 2 + i * 1.5, -0.5, -5 + i + 0.7, 3.6 + i * 1.5, 0.5, "steel_" + t))
        out.append(bx(0, 0, 0, 5 - i - 0.7, 2 + i * 1.5, -0.6, 5 - i + 0.7, 3.6 + i * 1.5, 0.6, "steel_" + t))
    for sx in (-1, 1):
        out.append(bx(0, 0, 0, sx * 3 - 2, 4, -0.8, sx * 3 + 2, 5, 0.8, m))
        out.append(bx(0, 0, 0, sx * 5 - 1, 1.5, -1, sx * 5 + 1, 3, 1, m))
    return out


def cup(t):
    m = "t_" + t
    out = [bx(0, 0, 0, -4, 0, -4, 4, 1.5, 4, m), bx(0, 0, 0, -1, 1.5, -1, 1, 5, 1, m), bx(0, 0, 0, -2, 5, -2, 2, 6, 2, m),
           bx(0, 0, 0, -3, 6, -3, 3, 8, 3, m), bx(0, 0, 0, -4, 8, -4, 4, 13, 4, m), bx(0, 0, 0, -4.6, 12.5, -4.6, 4.6, 13.5, 4.6, m)]
    for sx in (-1, 1):
        out += [bx(0, 0, 0, sx * 5.5 - 0.6, 8.5, -0.6, sx * 5.5 + 0.6, 12, 0.6, m),
                bx(0, 0, 0, min(sx * 4, sx * 5.5), 11.4, -0.6, max(sx * 4, sx * 5.5), 12.4, 0.6, m),
                bx(0, 0, 0, min(sx * 4, sx * 5.5), 8.5, -0.6, max(sx * 4, sx * 5.5), 9.5, 0.6, m)]
    return out


LADDERS = {"depth": spire, "flawless": gem, "speed": hourglass, "solo": banner, "challenger": blades, "score": cup}


def medal(kind):
    """A medal disc and its ribbon, centred on itself; kind a / b (earned, two ribbon colours) or dark (not yet)."""
    d = "dark" if kind == "dark" else "gold"
    inner = "dark" if kind == "dark" else "silver"
    ribbon = "dark" if kind == "dark" else "ribbon_" + kind
    return [((-3, -5.5, -0.6), (3, -1.5, 0.6), d), ((-2, -6.5, -0.6), (2, -0.5, 0.6), d), ((-1, -4.5, -0.9), (1, -2.5, 0.9), inner),
            ((-1.8, -0.5, -0.4), (1.8, 4.5, 0.4), ribbon)]


# ---- the case ------------------------------------------------------------------------------------------------------------------------

TIER_BOXES = ((-58, 58, 0, 14, 64), (-56, 56, 14, 30, 32), (-52, 52, 30, 46, 16))     # back (medals), middle, front: x0, x1, z0, z1, top
SPOTS = {"depth": (-36, 32, 22), "flawless": (0, 32, 22), "speed": (36, 32, 22), "solo": (-36, 16, 38), "challenger": (0, 16, 38),
         "score": (36, 16, 38)}
MEDALS = ("first_ascent", "regular", "veteran", "warden", "paragon", "eternal")
MEDAL_Y, MEDAL_Z = 64, 7


def case() -> list[tuple]:
    els = [((-60, 0, 0), (60, 3, 48), "base")]   # 3 blocks deep: a clear block to the track plinths in front
    for x0, x1, z0, z1, h in TIER_BOXES:
        els += [((x0, 3, z0), (x1, h - 2, z1), "core"), ((x0 - 1, h - 2, z0), (x1 + 1, h, z1 + 1), "top"),
                ((x0 - 1, h - 3, z1), (x1 + 1, h - 2, z1 + 1.2), "trim"), ((x0 + 2, h - 6, z1), (x1 - 2, h - 5, z1 + 0.4), "strip")]
    for cx, y, cz in SPOTS.values():                    # a plinth under every ladder trophy
        els += [((cx - 6.5, y, cz - 6.5), (cx + 6.5, y + 2, cz + 6.5), "top"), ((cx - 7, y, cz - 7), (cx + 7, y + 0.6, cz + 7), "trim")]
    for i in range(len(MEDALS)):                        # a small stand under every medal
        cx = -40 + 16 * i
        els += [((cx - 2.5, MEDAL_Y, MEDAL_Z - 1.5), (cx + 2.5, MEDAL_Y + 1.5, MEDAL_Z + 1.5), "top"),
                ((cx - 0.5, MEDAL_Y + 1.5, MEDAL_Z - 0.5), (cx + 0.5, MEDAL_Y + 3, MEDAL_Z + 0.5), "trim")]
    return [(lo, hi, m, None) for lo, hi, m in els]


def tower_materials(tower: str) -> dict:
    mats = dict(GA.THEMES[tower])
    for t, rgb in (("stone", "#4a4652"), ("bronze", "#c27a3e"), ("silver", "#cdd3dd"), ("gold", "#f2c744"), ("ascended", "#ffd75a")):
        mats["t_" + t] = M("polished" if t == "stone" else "metal", rgb)
        mats["g_" + t] = M("crystal", {"stone": "#5a5662", "bronze": "#e0985a", "silver": "#e8f0ff", "gold": "#ffe27a",
                                       "ascended": TOWER_GLOW[tower]}[t], glow=t == "ascended")
        mats["steel_" + t] = M("metal", {"stone": "#55515c", "bronze": "#d09060", "silver": "#e4e8f0", "gold": "#ffe08a",
                                         "ascended": "#fff0b0"}[t])
    mats["tip"] = M("crystal", TOWER_GLOW[tower], glow=True)
    mats["strip"] = M("glow", TOWER_GLOW[tower], glow=True)
    mats["sand"] = M("polished", "#e8cf8a")
    mats["glass"] = M("crystal", "#e6f2ff")
    mats["cloth"] = M("polished", TOWER_GLOW[tower])
    mats["dark"] = M("polished", "#17151b")
    return mats


def trophy_models(tower: str, atlas: Atlas, tex: str) -> None:
    for fam, build in LADDERS.items():
        for t in TIERS:
            plain, glow = [], []
            for lo, hi, mat in build(t):
                lo = tuple(v * SCALE + FOOT[i] for i, v in enumerate(lo))
                hi = tuple(v * SCALE + FOOT[i] for i, v in enumerate(hi))
                (glow if atlas.materials[mat]["glow"] else plain).extend(element(lo, hi, mat, atlas, (0, 0, 0), mat))
            write(MODELS / tower / f"{fam}_{t}.json", model(plain, tex))
            if glow:
                write(MODELS / tower / f"{fam}_{t}_glow.json", model(glow, tex))


def blocks(p) -> list[float]:
    return [round(v / 16, 4) for v in p]


def main() -> int:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    layout = {"case": {}, "glow": {}}
    for tower in TOWERS:
        atlas = Atlas(tower_materials(tower), "trophy_" + tower)
        atlas.img.save(TEXTURES / f"{tower}.png")
        tex = f"cobbletowers:item/trophy/{tower}"
        layout["case"][tower] = chunked(case(), atlas, tex, MODELS / tower, "case", (-80, 0, -16))
        trophy_models(tower, atlas, tex)
        layout["glow"][tower] = sorted(p.stem[:-5] for p in (MODELS / tower).glob("*_glow.json") if not p.stem.startswith("case"))
    common = {"gold": M("metal", "#f2c744"), "silver": M("metal", "#cdd3dd"), "dark": M("polished", "#17151b"),
              "ribbon_a": M("polished", "#b8362e"), "ribbon_b": M("polished", "#2f5fae"), "pool": M("glow", "#fff1c0", glow=True)}
    atlas = Atlas(common, "trophy_common")
    atlas.img.save(TEXTURES / "common.png")
    tex = "cobbletowers:item/trophy/common"
    for kind in ("a", "b", "dark"):
        els = [x for lo, hi, mat in medal(kind) for x in element(lo, hi, mat, atlas, (8, 8, 8), mat)]
        write(MODELS / "common" / f"medal_{kind}.json", model(els, tex))
    write(MODELS / "common" / "pool.json", model(element((-7.5, -0.08, -7.5), (7.5, 0.08, 7.5), "pool", atlas, (8, 8, 8), "pool"), tex))
    layout["ladders"] = {fam: blocks((x, y + 2, z)) for fam, (x, y, z) in SPOTS.items()}           # each trophy's foot, on its plinth
    layout["medals"] = {m: blocks((-40 + 16 * i, MEDAL_Y + 3 + 6.5, MEDAL_Z)) for i, m in enumerate(MEDALS)}   # each medal's centre
    layout["foot_up"] = round((8 - FOOT[1]) / 16, 4)
    DATA.write_text(json.dumps(layout, indent=2) + "\n")
    print("trophy models and", DATA.name, "written;", sum(len(v) for v in layout["case"].values()), "case chunks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
