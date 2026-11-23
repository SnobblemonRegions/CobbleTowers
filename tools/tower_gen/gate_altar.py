"""The Gate Altar (owner, 20 Nov 2026): one object in front of each Tower Hub portal that does the whole job of picking a run.

* a ring of seven Threat runes round the altar's table: the leader turns it, the runes light one by one from calm to red;
* two crystals on the flanks, First floor (left) and Best (right): touching one is a vote, each vote a mote circling it;
* the base's four ready strips: a member standing on the base ring is ready, a strip lights per ready member;
* a focus object over the table in the tower's own idea (Neutral's sun, Tideforge's water orb, Duskvale's eclipse, Rootvale's seed).

The altar is Blockbench-style elements in 1/16-block units (x, z from -24 to 24, y from 0 up: a 3 x 3 footprint), each element one
material, and every material a 16 px texture made here (lit from the top left, hue-shifted: warm light, cool shade). The same data
drives the concept renders now and the item models later. Parts that change at run time carry a tag (rune0-6, ready0-3, first, best,
focus) so the mod can swap their texture or show and hide them.
"""
from __future__ import annotations

import colorsys
import math
import random
from dataclasses import dataclass, field

import numpy as np


@dataclass
class Element:
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    mat: str
    tag: str = ""


@dataclass
class Altar:
    tower: str
    elements: list[Element] = field(default_factory=list)
    materials: dict[str, dict] = field(default_factory=dict)

    def box(self, x0, y0, z0, x1, y1, z1, mat, tag=""):
        self.elements.append(Element((min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1)), mat, tag))


# ---- materials: a pattern, a base colour, and whether it glows ------------------------------------------------------------------------

def mat(kind: str, rgb: str, glow: bool = False, **kw) -> dict:
    return {"kind": kind, "rgb": rgb, "glow": glow, **kw}


THEMES = {
    "neutral": {
        "base": mat("brick", "#d8d2c4"), "base2": mat("polished", "#ebe6da"), "core": mat("fluted", "#f0ece2"),
        "trim": mat("metal", "#e0b23a"), "top": mat("polished", "#f4f1ea"), "accent": mat("metal", "#b8362e"),
        "floor": mat("brick", "#a9a49a"),
    },
    "tideforge": {
        "base": mat("brick", "#3f8a86"), "base2": mat("polished", "#5aa8a0"), "core": mat("fluted", "#2f6f78"),
        "trim": mat("metal", "#d9e6e8"), "top": mat("polished", "#6cc0b6"), "accent": mat("stone", "#e8806e"),
        "floor": mat("brick", "#4e7d86"),
    },
    "duskvale": {
        "base": mat("brick", "#3a3446"), "base2": mat("polished", "#4a4258"), "core": mat("fluted", "#2c2736"),
        "trim": mat("metal", "#9a6ad8"), "top": mat("polished", "#524a62"), "accent": mat("crystal", "#b57bea", glow=True),
        "floor": mat("brick", "#34303e"),
    },
    "rootvale": {
        "base": mat("stone", "#7d7464"), "base2": mat("moss", "#5f8a3c"), "core": mat("wood", "#6b4a2e"),
        "trim": mat("wood", "#8f6a40"), "top": mat("wood", "#9c7a4e"), "accent": mat("moss", "#4f7a32"),
        "floor": mat("stone", "#6e6a5c"),
    },
}
COMMON = {
    "rune_off": mat("polished", "#2a2a33"), "ready_off": mat("polished", "#26262e"), "ready_on": mat("glow", "#f2c744", glow=True),
    "first": mat("crystal", "#dfe6f0", glow=True), "best": mat("crystal", "#ffd34d", glow=True),
    "mote_first": mat("glow", "#f4f8ff", glow=True), "mote_best": mat("glow", "#ffe48a", glow=True), "dark": mat("polished", "#121016"),
}
# the Threat runes, calm to danger: none lit is no band; I and II cyan-green, III and IV gold, V and VI red
RUNE_ON = ["#6ae4ff", "#55ff99", "#55ff99", "#ffd34d", "#ffb03a", "#ff6a4d", "#ff3b5c"]
FOCUS = {
    "neutral": {"focus": mat("metal", "#f2c744"), "focus_glow": mat("glow", "#fff4c8", glow=True)},
    "tideforge": {"focus": mat("crystal", "#3fa9f5", glow=True), "focus_glow": mat("glow", "#bfe9ff", glow=True)},
    "duskvale": {"focus": mat("polished", "#0d0b12"), "focus_glow": mat("glow", "#c58cff", glow=True)},
    "rootvale": {"focus": mat("glow", "#9be86a", glow=True), "focus_glow": mat("moss", "#3f6f2a")},
}


def build(tower: str) -> Altar:
    a = Altar(tower)
    a.materials = {**THEMES[tower], **COMMON, **FOCUS[tower]}
    for i, c in enumerate(RUNE_ON):
        a.materials[f"rune_on{i}"] = mat("glow", c, glow=True)
    # the base: two steps; on the lower step's rim, four ready strips (one per member)
    a.box(-24, 0, -24, 24, 3, 24, "base")
    a.box(-21, 3, -21, 21, 6, 21, "base2")
    for k, (x0, z0, x1, z1) in enumerate(((-17, -23.6, 17, -21.4), (21.4, -17, 23.6, 17), (-17, 21.4, 17, 23.6), (-23.6, -17, -21.4, 17))):
        a.box(x0, 3, z0, x1, 3.6, z1, "ready_off", f"ready{k}")
    # the core: a fluted block with two trim bands
    a.box(-12, 6, -12, 12, 22, 12, "core")
    a.box(-13, 9, -13, 13, 10.5, 13, "trim")
    a.box(-13, 20, -13, 13, 22, 13, "trim")
    for sx in (-1, 1):                                      # buttresses on the core's corners
        for sz in (-1, 1):
            a.box(sx * 11, 6, sz * 11, sx * 15, 20, sz * 15, "base2")
    # the table and the rune ring
    a.box(-17, 22, -17, 17, 25, 17, "top")
    for x0, z0, x1, z1 in ((-18, -18, 18, -15.5), (-18, 15.5, 18, 18), (-18, -15.5, -15.5, 15.5), (15.5, -15.5, 18, 15.5)):
        a.box(x0, 24, z0, x1, 25.6, z1, "trim")                   # the table's rim: a frame, so the top shows
    for i in range(7):
        t = math.radians(90 + i * 360 / 7)                  # rune 0 faces the player (south, +z)
        cx, cz = 12.5 * math.cos(t), 12.5 * math.sin(t)
        a.box(cx - 1.8, 25.5, cz - 1.8, cx + 1.8, 26.4, cz + 1.8, "rune_off", f"rune{i}")
    a.box(-3.5, 25.5, -3.5, 3.5, 28, 3.5, "trim")           # the focus's stand
    # the crystal arms and crystals: First floor on the left (-x), Best on the right
    for side, tag in ((-1, "first"), (1, "best")):
        a.box(side * 12, 14, -3, side * 19.5, 17, 3, "trim")
        x = side * 20.5
        a.box(x - 3.5, 15, -3.5, x + 3.5, 18, 3.5, "base2")
        for y0, y1, h in ((18, 24, 3), (24, 29, 2.3), (29, 32, 1.4), (32, 34, 0.6)):
            a.box(x - h, y0, -h, x + h, y1, h, tag, tag)
    focus(a, tower)
    return a


def focus(a: Altar, tower: str) -> None:
    if tower == "neutral":                                  # a sun: a gold disc with a bright heart and eight rays
        a.box(-6, 31, -1, 6, 43, 1, "focus", "focus")
        a.box(-3.5, 33.5, -1.6, 3.5, 40.5, 1.6, "focus_glow", "focus")
        for k in range(8):
            t = math.radians(k * 45)
            cx, cy = 9 * math.cos(t), 37 + 9 * math.sin(t)
            a.box(cx - 1, cy - 1, -0.6, cx + 1, cy + 1, 0.6, "focus", "focus")
    elif tower == "tideforge":                              # a water orb in a ring of sea-glass, coral on the base's corners
        a.box(-5, 31, -5, 5, 41, 5, "focus", "focus")
        a.box(-6, 35, -6, 6, 37, 6, "trim", "focus")
        for sx in (-1, 1):
            for sz in (-1, 1):
                a.box(sx * 19, 6, sz * 19, sx * 22, 10, sz * 22, "accent")
                a.box(sx * 20, 10, sz * 20, sx * 21, 13, sz * 21, "accent")
    elif tower == "duskvale":                               # an eclipse: a black disc on a violet corona, amethyst on the corners
        a.box(-7.5, 29.5, -2, 7.5, 44.5, -1, "focus_glow", "focus")     # the corona, behind the disc
        a.box(-6, 31, -1, 6, 43, 1, "focus", "focus")
        for sx in (-1, 1):
            for sz in (-1, 1):
                a.box(sx * 18, 6, sz * 18, sx * 22, 12, sz * 22, "accent")
                a.box(sx * 19, 12, sz * 19, sx * 21, 16, sz * 21, "accent")
    else:                                                   # a glowing seed held in leaves, roots down the core, moss on the base
        a.box(-3.5, 30, -3.5, 3.5, 39, 3.5, "focus", "focus")
        for x0, z0, x1, z1 in ((-7, -1.5, -3, 1.5), (3, -1.5, 7, 1.5), (-1.5, -7, 1.5, -3), (-1.5, 3, 1.5, 7)):
            a.box(x0, 28, z0, x1, 32, z1, "focus_glow", "focus")      # four leaves cupping the seed
        for sx in (-1, 1):
            for sz in (-1, 1):
                a.box(sx * 12, 3, sz * 12, sx * 14, 20, sz * 14, "core")
                a.box(sx * 14, 3, sz * 12, sx * 18, 5, sz * 14, "core")
        a.box(-20, 6, -20, 20, 6.6, 20, "accent")


# ---- textures: 16 x 16, lit from the top left, hue-shifted -------------------------------------------------------------------------

def hexrgb(h: str) -> np.ndarray:
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float64) / 255


def shift(rgb: np.ndarray, light: float) -> np.ndarray:
    """Lighter toward warm (hue to yellow), darker toward cool (hue to blue), saturation peaking in the mid tones: pixel-art hue shifting."""
    h, l, s = colorsys.rgb_to_hls(*rgb)
    h = (h - 0.03 * light) % 1.0
    l = min(0.97, max(0.03, l + 0.17 * light))
    s = min(1, max(0, s * (1.08 - 0.16 * abs(light))))
    return np.array(colorsys.hls_to_rgb(h, l, s))


RAMP = (-1.5, -0.8, -0.2, 0.35, 0.95)          # five tones: deep shade, shade, mid, light, highlight


def _ramp(base: np.ndarray) -> list[np.ndarray]:
    return [shift(base, v) for v in RAMP]


def _cells(rnd: random.Random, n: int) -> list[tuple[float, float]]:
    return [(rnd.uniform(0, 16), rnd.uniform(0, 16)) for _ in range(n)]


def _voronoi(x: int, y: int, pts) -> tuple[int, float]:
    """Nearest cell (wrapping, so the texture tiles) and how close this texel is to that cell's edge (0 at the edge)."""
    best, second, idx = 99.0, 99.0, 0
    for i, (px, py) in enumerate(pts):
        for ox in (-16, 0, 16):
            for oy in (-16, 0, 16):
                d = math.hypot(x + 0.5 - px - ox, y + 0.5 - py - oy)
                if d < best:
                    best, second, idx = d, best, i
                elif d < second:
                    second = d
    return idx, second - best


def texture(m: dict, seed: int = 0) -> np.ndarray:
    """A 16 x 16 tile, quantised to a five-tone hue-shifted ramp of the material's colour, lit from the top left."""
    rnd = random.Random(seed)
    base = hexrgb(m["rgb"])
    ramp = _ramp(base)
    tone = np.full((16, 16), 2.0)
    kind = m["kind"]
    if kind == "brick":                                     # 8 x 4 bricks, offset rows, each brick its own tone, bevelled, chipped
        shade = {}
        for y in range(16):
            for x in range(16):
                row = y // 4
                bx = (x + (4 if row % 2 else 0)) % 16 // 8
                key = (row, bx)
                shade.setdefault(key, rnd.choice((1.6, 2.0, 2.0, 2.4)))
                lx, ly = (x + (4 if row % 2 else 0)) % 8, y % 4
                if ly == 3 or lx == 7:
                    t = 0
                elif ly == 0 or lx == 0:
                    t = shade[key] + 1.2
                elif ly == 2 or lx == 6:
                    t = shade[key] - 0.7
                else:
                    t = shade[key] + rnd.choice((-0.4, 0, 0, 0.3))
                if (lx in (0, 6) and ly in (0, 2)) and rnd.random() < 0.35:
                    t = 1                                   # a chipped corner
                tone[y, x] = t
    elif kind == "polished":                                # a smooth panel: bevelled border, an inset line, a soft sheen, specks
        for y in range(16):
            for x in range(16):
                if x == 0 or y == 0:
                    t = 4
                elif x == 15 or y == 15:
                    t = 0.6
                elif x in (2, 13) and 2 <= y <= 13 or y in (2, 13) and 2 <= x <= 13:
                    t = 1.6 if (x == 13 or y == 13) else 3
                else:
                    t = 2.5 - (x + y) / 30 + (0.9 if abs(x - y) < 2 and x < 9 else 0)
                tone[y, x] = t + (rnd.choice((-0.6, 0.5)) if rnd.random() < 0.05 else 0)
    elif kind == "fluted":                                  # vertical flutes: lit edge, face, shade, deep groove; worn at top and foot
        for y in range(16):
            for x in range(16):
                t = (3.6, 2.6, 1.7, 0.4)[x % 4] + rnd.uniform(-0.25, 0.25)
                if y in (0, 15):
                    t = 3.0 if y == 0 else 0.8
                tone[y, x] = t
    elif kind == "metal":                                   # gold: a top-lit gradient, a diagonal streak, an engraved line, rivets
        for y in range(16):
            for x in range(16):
                t = 3.2 - y / 7 + rnd.uniform(-0.2, 0.2)
                if abs((x + y) - 13) < 2:
                    t = 4.4
                if y == 7:
                    t = 0.8
                if y == 8:
                    t = 3.4
                tone[y, x] = t
        for cx, cy in ((2, 2), (13, 2), (2, 12), (13, 12)):
            tone[cy, cx] = 4.6
            tone[cy + 1, cx] = 0.6
            tone[cy, cx + 1] = 1.2
    elif kind == "stone":                                   # chunky cells, darker toward their edges, lit on their upper faces
        pts = _cells(rnd, 7)
        lvl = [rnd.choice((1.6, 2.0, 2.4, 2.8)) for _ in pts]
        for y in range(16):
            for x in range(16):
                i, edge = _voronoi(x, y, pts)
                px, py = pts[i]
                t = lvl[i] + (0.7 if (y + 0.5) < py and (x + 0.5) < px else -0.2)
                tone[y, x] = 0 if edge < 0.55 else t if edge > 1.6 else t - 0.9
    elif kind == "wood":                                    # four planks: grain lines, a knot, dark gaps, lit top edges
        for y in range(16):
            for x in range(16):
                plank = y // 4
                ly = y % 4
                g = math.sin(x * 0.9 + plank * 2.1 + math.sin(x * 0.35 + plank) * 2.2)
                t = 2.2 + 0.8 * g + rnd.uniform(-0.2, 0.2)
                if ly == 3:
                    t = 0.3
                elif ly == 0:
                    t += 0.9
                tone[y, x] = t
        kx, ky = rnd.randint(3, 12), rnd.choice((1, 5, 9, 13))
        tone[ky, kx] = 0.5
        tone[ky, kx + 1] = 1.2
        tone[ky - 1 if ky > 0 else ky, kx] = 3.4
    elif kind == "moss":                                    # clumps: dark hollows, lit tufts, a few bright specks
        pts = _cells(rnd, 9)
        for y in range(16):
            for x in range(16):
                i, edge = _voronoi(x, y, pts)
                px, py = pts[i]
                d = math.hypot(x + 0.5 - px, y + 0.5 - py)
                t = 3.4 - d * 0.55 + rnd.uniform(-0.4, 0.4)
                tone[y, x] = max(0, t) if edge > 0.5 else 0.6
                if rnd.random() < 0.03:
                    tone[y, x] = 4.6
    elif kind == "crystal":                                 # diagonal facets with bright ridges, a darker heart, sparkles
        for y in range(16):
            for x in range(16):
                f = ((x - y + 16) // 5) % 3
                t = (3.8, 2.6, 1.6)[f]
                if (x - y + 16) % 5 == 0:
                    t = 4.6
                if 5 <= x <= 10 and 5 <= y <= 10:
                    t -= 0.7
                tone[y, x] = t
        for _ in range(3):
            tone[rnd.randint(1, 14), rnd.randint(1, 14)] = 4.8
    elif kind == "glow":                                    # a bright heart in rings, fading out
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                tone[y, x] = 4.6 - d * 0.42 + (0.5 if int(d) % 3 == 0 else 0) + rnd.uniform(-0.2, 0.2)
    else:
        tone += np.array([[rnd.uniform(-0.6, 0.6) for _ in range(16)] for _ in range(16)])
    out = np.zeros((16, 16, 3))
    for y in range(16):
        for x in range(16):
            t = max(0.0, min(4.0, tone[y, x]))
            i = int(t)
            f = t - i
            if i >= 4:
                out[y, x] = ramp[4]
            else:                                           # between two ramp tones, ordered-dithered rather than blended
                dither = ((x & 1) * 2 + (y & 1) * 3) % 4 / 4 + 0.125
                out[y, x] = ramp[i + 1] if f > dither else ramp[i]
    return np.clip(out, 0, 1)


def textures(a: Altar) -> dict[str, np.ndarray]:
    return {name: texture(m, seed=hash((a.tower, name)) & 0xFFFF) for name, m in a.materials.items()}
