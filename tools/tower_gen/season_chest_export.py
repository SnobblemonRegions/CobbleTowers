"""Exports the Records Hall's Season Chest (owner, 23 Nov 2026) as item models for the mod: a dark wood chest with gold bands on the front
step of the rank dais, whose lid swings open as a player walks up, and the gold tier marks lit in the floor ring as the season goes on.

Frames, in 1/16 block:
  body, glow  the chest's foot: the middle of its underside, +z its front (toward the door), y up
  lid         its hinge: the back top edge of the body (body y 13, z -8), +z toward the front; it opens by turning about x
  inlay       a flat mark, centred on itself
Each model's point (8, 8, 8) is its display's origin. Written:
  models/season_chest/body.json, lid.json, glow.json, inlay.json
  textures/item/season_chest.png

    python tools/tower_gen/season_chest_export.py
"""
from __future__ import annotations

from pathlib import Path

import gate_altar as GA
from altar_export import Atlas, element, model, write

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "src/main/resources/assets/cobbletowers/models/season_chest"
TEXTURE = ROOT / "src/main/resources/assets/cobbletowers/textures/item/season_chest.png"
M = GA.mat

W, D, H = 15, 8, 13                     # the body's half width, half depth, height
CENTRE = (8, 8, 8)


def ring(x0, x1, y0, y1, z0, z1, t, mat):
    """Four boxes round a rectangle, t thick: a rim or a band that leaves the middle open."""
    return [((x0, y0, z0), (x1, y1, z0 + t), mat), ((x0, y0, z1 - t), (x1, y1, z1), mat),
            ((x0, y0, z0 + t), (x0 + t, y1, z1 - t), mat), ((x1 - t, y0, z0 + t), (x1, y1, z1 - t), mat)]


def body() -> list[tuple]:
    els = [((-W, 2, -D), (W, 10, D), "wood")]                           # the solid chest up to the cavity's floor
    els += ring(-W, W, 10, H, -D, D, 1.5, "wood")                       # the cavity's walls
    els.append(((-W + 1.5, 9.9, -D + 1.5), (W - 1.5, 10, D - 1.5), "inside"))
    els += ring(-W - 0.4, W + 0.4, 2, 3.5, -D - 0.4, D + 0.4, 0.4, "band")          # the foot band
    els += ring(-W - 0.4, W + 0.4, H - 1.2, H, -D - 0.4, D + 0.4, 1.9, "band")      # the rim, gold all round the opening
    for sx in (-1, 1):
        x = sx * 10
        els += [((x - 1, 3.5, D), (x + 1, H - 1.2, D + 0.4), "band"), ((x - 1, 3.5, -D - 0.4), (x + 1, H - 1.2, -D), "band")]
        for sz in (-1, 1):
            cx, cz = sx * (W - 0.5), sz * (D - 0.5)                     # corner straps and feet
            els.append(((cx - 1, 3.5, cz - 1), (cx + 1, H - 1.2, cz + 1), "band"))
            els.append(((cx - 1.8, 0, cz - 1.8), (cx + 1.8, 2, cz + 1.8), "foot"))
    els.append(((-3, 6.5, D), (3, H - 1.2, D + 0.8), "band"))           # the lock plate
    els.append(((-1.6, 7.5, D + 0.8), (1.6, 10.7, D + 1.1), "crest"))   # its gold crest, lit
    els.append(((-0.5, 8, D + 1.1), (0.5, 9.5, D + 1.2), "inside"))     # the keyhole
    return els


def lid() -> list[tuple]:
    els = [((-W, 0, 0), (W, 3, 2 * D), "lid"), ((-W, 3, 1), (W, 5, 2 * D - 1), "lid"), ((-W, 5, 3), (W, 6.5, 2 * D - 3), "lid")]
    els.append(((-W + 1.5, -0.1, 1.5), (W - 1.5, 0, 2 * D - 1.5), "inside"))      # the lid's underside, seen when it is open
    els += ring(-W - 0.4, W + 0.4, 0, 1.2, -0.4, 2 * D + 0.4, 0.4, "band")         # the lid's edge band
    for sx in (-1, 1):                                                   # the two bands over the top, stepping with it
        x = sx * 10
        els += [((x - 1, 0, -0.4), (x + 1, 3.3, 2 * D + 0.4), "band"), ((x - 1, 3, 0.6), (x + 1, 5.3, 2 * D - 0.6), "band"),
                ((x - 1, 5, 2.6), (x + 1, 6.8, 2 * D - 2.6), "band")]
        for sz in (0.3, 2 * D - 0.3):                                    # the lid's corner caps
            els.append(((sx * (W - 0.5) - 1, 0, sz - 1), (sx * (W - 0.5) + 1, 3.2, sz + 1), "band"))
    els.append(((-2.2, -2.5, 2 * D), (2.2, 1.5, 2 * D + 0.9), "band"))   # the hasp, hanging over the lock plate
    return els


def glow() -> list[tuple]:
    """The light inside, shown while the lid is open: the cavity's floor, bright (the mod sends motes rising off it)."""
    return [((-W + 1.6, 10.05, -D + 1.6), (W - 1.6, 10.3, D - 1.6), "light")]


def inlay() -> list[tuple]:
    """A tier mark lit in the floor: an eight-point star of gold."""
    y0, y1 = -0.1, 0.1
    return [((-6, y0, -1), (6, y1, 1), "mark"), ((-1, y0, -6), (1, y1, 6), "mark"), ((-3, y0, -3), (3, y1, 3), "mark"),
            ((-4, y0, -0.5), (4, y1, 0.5), "mark_hot"), ((-0.5, y0, -4), (0.5, y1, 4), "mark_hot"), ((-1.6, y0, -1.6), (1.6, y1, 1.6), "mark_hot")]


def main() -> int:
    mats = {
        "wood": M("wood", "#4a2f1c"), "lid": M("wood", "#5a3a22"), "band": M("metal", "#e0b23a"), "foot": M("metal", "#b8902c"),
        "inside": M("polished", "#140e0a"), "crest": M("crystal", "#ffd75a", glow=True),
        "light": M("glow", "#fff1c0", glow=True),
        "mark": M("glow", "#e0b23a", glow=True), "mark_hot": M("glow", "#fff1c0", glow=True),
    }
    atlas = Atlas(mats, "season_chest")
    TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    atlas.img.save(TEXTURE)
    tex = "cobbletowers:item/season_chest"
    for name, build in (("body", body), ("lid", lid), ("glow", glow), ("inlay", inlay)):
        els = [x for lo, hi, mat in build() for x in element(lo, hi, mat, atlas, CENTRE, mat)]
        write(MODELS / f"{name}.json", model(els, tex))
    for name, rows, pal in (("locked", LOCKED, LOCKED_PAL), ("claimed", CLAIMED, CLAIMED_PAL)):
        icon(rows, pal).save(TEXTURE.parent / f"season_chest_{name}.png")
        write(MODELS / f"{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"cobbletowers:item/season_chest_{name}"}})
    print("season chest models written to", MODELS)
    return 0


# ---- the screen's two icons, drawn pixel by pixel: a tier still ahead (a dark tile with a gold padlock) and one claimed (a green medal with a
# check). The screen's background is empty slots, so the path reads as a line of icons.

LOCKED = (
    "................",
    ".oooooooooooooo.",
    "ohhhhhhhhhhhhhso",
    "ohdddddgggdddsso",
    "ohddddGdddgddsso",
    "ohddddGdddgddsso",
    "ohddddGdddgddsso",
    "ohdddYYYYYYYddso",
    "ohdddYyyyyyYddso",
    "ohdddYyykyyYddso",
    "ohdddYyykyyYddso",
    "ohdddYyyyyyYddso",
    "ohdddbbbbbbbddso",
    "ohdddddddddddsso",
    "osssssssssssssso",
    ".oooooooooooooo.",
)
LOCKED_PAL = {"o": "#0e0d12", "h": "#4a4656", "d": "#2c2935", "s": "#1b1922", "g": "#8f8a96", "G": "#c9c4d0", "Y": "#b88a2e",
              "y": "#e8c05a", "b": "#7a5a1e", "k": "#1a120a"}

CLAIMED = (
    "................",
    ".....oooooo.....",
    "...ooRRRRRRoo...",
    "..oRRggggggRro..",
    "..oRgggggggwRo..",
    ".oRggggggggwWro.",
    ".oRgggggggWWgro.",
    ".oRgWggggWWggro.",
    ".oRgWWggWWgggro.",
    ".oRggWWWWggggro.",
    ".oRgggWWgggggro.",
    "..oRgggggggGro..",
    "..orRGGGGGGrro..",
    "...oorrrrrroo...",
    ".....oooooo.....",
    "................",
)
CLAIMED_PAL = {"o": "#10140e", "R": "#f2c744", "r": "#9a7420", "g": "#3f9a4a", "G": "#2a6e33", "w": "#d8ffd8", "W": "#f4fff0"}


def icon(rows, pal) -> "Image.Image":
    from PIL import Image
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row)
        for x, ch in enumerate(row):
            if ch != ".":
                h = pal[ch]
                img.putpixel((x, y), (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255))
    return img


if __name__ == "__main__":
    raise SystemExit(main())
