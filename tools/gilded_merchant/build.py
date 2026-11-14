#!/usr/bin/env python3
"""Builds the Gilded Merchant: a hooded general-shop vendor rigged for animation (GeckoLib / Bedrock entity format).

    python tools/gilded_merchant/build.py [--previews] [--gif]

Look taken from a reference skin (near-black robes with gold filigree, a tattered crimson mantle, a gold crest on the hood, a face lost in
shadow); every cube and pixel here is our own. Vendor props added on top: a lantern staff, a balance scale, a coin purse, a ledger and a
trader's pack.

Writes (deterministic), all under art/gilded_merchant/:
  gilded_merchant.bbmodel                       Blockbench project (Bedrock entity format), bones + rest pose + animations
  gilded_merchant.png / _glowmask.png           atlas, and the emissive pixels for GeckoLib's AutoGlowingGeoLayer
  export/geo/gilded_merchant.geo.json           GeckoLib geometry
  export/animations/gilded_merchant.animation.json
  previews/                                     software renders (with --previews / --gif)

Space (Blockbench units, 16 per block): x east, y up, z south; the merchant FACES NORTH (-z), feet at y = 0, centred on x = z = 0.
The character's right hand is +x. Bedrock export negates x (origin = -to.x; pivot x and rotation x/y flipped), as Blockbench does.
Animation values are written in Bedrock convention: +x rotation pitches a bone's lower end forward... except where a bone hangs down, where
+x swings its bottom BACKWARD (south). See bedrock_to_bb().
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import math
import random
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "art/gilded_merchant"
NAME = "gilded_merchant"
FACES = ("north", "east", "south", "west", "up", "down")
D = 2  # texels per model unit (Grizelda used 1): twice the detail per face

# ---- palette: every material is a dark -> light ramp; painting picks a ramp index, never a free colour ------------------------------
PAL = {
    "robe": [(16, 15, 22), (25, 25, 34), (35, 36, 48), (48, 50, 64), (64, 68, 84)],
    "gold": [(82, 56, 22), (128, 92, 34), (176, 134, 52), (214, 174, 82), (244, 214, 132)],
    "crimson": [(46, 12, 17), (72, 21, 26), (100, 31, 34), (128, 44, 42), (156, 60, 52)],
    "leather": [(36, 24, 19), (54, 36, 27), (76, 52, 37), (100, 70, 49), (124, 90, 62)],
    "canvas": [(58, 48, 38), (78, 66, 52), (100, 86, 68), (122, 106, 84), (144, 126, 100)],
    "wood": [(46, 30, 21), (66, 44, 29), (88, 60, 39), (112, 78, 51), (134, 96, 63)],
    "iron": [(38, 38, 44), (60, 60, 68), (86, 86, 96), (116, 116, 126), (150, 150, 160)],
    "shadow": [(4, 4, 7), (9, 9, 13), (15, 15, 21), (22, 22, 30)],
    "eye": [(140, 82, 18), (226, 156, 46), (255, 212, 108), (255, 244, 200)],
    "glow": [(190, 104, 28), (238, 156, 52), (255, 200, 96), (255, 234, 166), (255, 252, 230)],
    "parch": [(146, 126, 90), (180, 160, 116), (208, 190, 146), (232, 218, 176)],
    "teal": [(18, 66, 70), (28, 104, 106), (52, 150, 140), (116, 206, 186)],
    "violet": [(46, 22, 70), (78, 38, 116), (116, 64, 166), (168, 118, 212)],
    "cork": [(92, 62, 36), (124, 88, 52), (152, 112, 68)],
    "gem": [(84, 6, 18), (156, 18, 34), (214, 46, 58), (255, 136, 136)],
}
BASE = {"robe": 0.56, "gold": 0.58, "crimson": 0.52, "shadow": 0.3, "glow": 0.62, "eye": 0.6, "iron": 0.5}
NOISE = {"robe": 0.07, "crimson": 0.08, "canvas": 0.09, "leather": 0.08, "wood": 0.06, "gold": 0.035, "shadow": 0.05, "glow": 0.04}
CLOTH = {"robe", "crimson", "canvas"}
METAL = {"gold", "iron"}
GLOWING = {"eye", "glow"}
BAYER = ((-0.5, 0.25), (0.5, -0.25))


# ---- model description ---------------------------------------------------------------------------------------------------------------
@dataclass
class Bone:
    name: str
    parent: str | None
    pivot: tuple
    rest: tuple = (0, 0, 0)  # Blockbench-space rest rotation (degrees, Euler ZYX like three.js)


@dataclass
class El:
    name: str
    bone: str
    frm: tuple
    to: tuple
    mat: str
    rot: tuple | None = None       # Blockbench-space (rx, ry, rz) about `origin`
    origin: tuple | None = None
    decor: dict = field(default_factory=dict)   # face key ("north", "sides", "*", ...) -> [decor, ...]
    fmat: dict = field(default_factory=dict)    # per-face material override


BONES: dict[str, Bone] = {}
ELS: list[El] = []


def bone(name, parent, pivot, rest=(0, 0, 0)):
    BONES[name] = Bone(name, parent, tuple(pivot), tuple(rest))


def add(name, b, frm, to, mat, rot=None, origin=None, decor=None, fmat=None):
    assert all(t > f for f, t in zip(frm, to)), (name, frm, to)
    ELS.append(El(name, b, tuple(frm), tuple(to), mat, tuple(rot) if rot else None, tuple(origin) if origin else None,
                  dict(decor or {}), dict(fmat or {})))


_SWAP = {"east": "west", "west": "east", "trim_l": "trim_r", "trim_r": "trim_l"}


def _swap_decor(dlist):
    return [(_SWAP.get(d[0], d[0]),) + tuple(d[1:]) for d in dlist]


def mirror(name, b, frm, to, mat, rot=None, origin=None, decor=None, fmat=None):
    """The same cube on the other side (x -> -x): rotations about y and z flip, east/west faces and left/right trims swap."""
    lo = (-to[0], frm[1], frm[2])
    hi = (-frm[0], to[1], to[2])
    mrot = (rot[0], -rot[1], -rot[2]) if rot else None
    morg = (-origin[0], origin[1], origin[2]) if origin else None
    md = {_SWAP.get(k, k): _swap_decor(v) for k, v in (decor or {}).items()}
    mf = {_SWAP.get(k, k): v for k, v in (fmat or {}).items()}
    add(name, b, lo, hi, mat, mrot, morg, md, mf)


def pair(name, b, frm, to, mat, **kw):
    """A cube and its mirror; `name` gets _l (the -x side, the character's left) and _r."""
    add(name + "_l", b, frm, to, mat, **kw)
    mirror(name + "_r", b, frm, to, mat, **kw)


def build_merchant():
    BONES.clear()
    ELS.clear()
    bone("root", None, (0, 0, 0))
    bone("legs", "root", (0, 0, 0))
    bone("body", "root", (0, 12, 0))
    bone("purse", "body", (-3.3, 12, -3.9))
    bone("torso", "body", (0, 13, 0))
    bone("head", "torso", (0, 24, 0))
    bone("mantle", "torso", (0, 24.5, 0))
    bone("cape", "mantle", (0, 22.5, 3.7))
    bone("pack", "torso", (0, 17, 6))
    bone("arm_r", "torso", (5.75, 23, 0))
    bone("forearm_r", "arm_r", (6, 16, 0), rest=(45, 0, 0))
    bone("arm_l", "torso", (-5.75, 23, 0))
    bone("forearm_l", "arm_l", (-6, 16, 0), rest=(80, 0, 0))
    bone("scale", "forearm_l", (-6, 8.25, 0), rest=(-80, 0, 0))  # cancels the forearm's bend so the scale hangs plumb
    bone("staff", "root", (6, 0, -4.6))
    bone("lantern", "staff", (9.9, 36.0, -4.6))

    # ---- boots peeking under the hem, gold toe caps
    pair("boot", "legs", (-3.25, 0, -2.25), (-0.75, 2.5, 1.25), "leather")
    pair("toe", "legs", (-3.25, 0, -3.75), (-0.75, 1.5, -2.25), "leather", decor={"up": [("stitch",)]})
    pair("toecap", "legs", (-3.35, 0, -4.05), (-0.65, 1.0, -3.6), "gold")

    # ---- robe skirt: a core, a flared hem, two front panels parting over a crimson under-robe
    add("skirt_core", "body", (-4.5, 2, -2.5), (4.5, 12.5, 2.75), "robe")
    add("skirt_hem", "body", (-5.25, 0.5, -3.0), (5.25, 4.5, 3.25), "robe", decor={"sides": [("trim_b", 2), ("bands", (0.2,))]})
    panel = {"north": [("filigree",), ("trim_l", 2), ("trim_b", 2)], "west": [("trim_b", 2)], "east": [("trim_b", 2)]}
    pair("front", "body", (-5.0, 0.75, -3.4), (-0.9, 12.5, -2.5), "robe", decor=panel)
    add("underrobe", "body", (-1.0, 0.75, -3.05), (1.0, 12.0, -2.5), "crimson", decor={"north": [("tatter", 3), ("chevrons",)]})
    # belt, buckle, gem
    add("belt", "body", (-4.85, 11.75, -2.9), (4.85, 13.75, 3.0), "leather", decor={"sides": [("stitch",)]})
    add("buckle", "body", (-1.25, 11.5, -3.25), (1.25, 14.0, -2.85), "gold", decor={"north": [("buckle",)]})
    add("buckle_gem", "body", (-0.5, 12.25, -3.45), (0.5, 13.25, -3.2), "gem", decor={"north": [("gem",)]})
    # crimson sash knotted at the right hip, two tattered tails
    add("sash_knot", "body", (2.25, 11.0, -3.75), (4.0, 13.4, -2.85), "crimson")
    add("sash_tail_a", "body", (2.5, 4.5, -3.75), (3.5, 11.25, -3.45), "crimson", rot=None, origin=None,
        decor={"north": [("tatter", 3)], "south": [("tatter", 3)]})
    add("sash_tail_b", "body", (3.35, 6.0, -3.7), (4.25, 11.25, -3.42), "crimson", rot=None, origin=None,
        decor={"north": [("tatter", 2)], "south": [("tatter", 2)]})
    # scroll case on the left hip, ledger chained on the right
    add("scroll_case", "body", (-5.85, 5.5, 0.0), (-4.85, 11.5, 1.0), "leather", decor={"sides": [("bands", (0.5,))]})
    add("scroll_cap", "body", (-5.95, 11.25, -0.1), (-4.75, 12.0, 1.1), "gold")
    add("scroll_foot", "body", (-5.95, 5.25, -0.1), (-4.75, 5.75, 1.1), "gold")
    add("ledger", "body", (4.85, 7.0, -1.75), (6.0, 11.25, 1.5), "crimson",
        decor={"east": [("book",)], "north": [("pages",)], "south": [("pages",)], "down": [("pages",)]},
        fmat={"north": "parch", "south": "parch", "down": "parch"})
    add("ledger_spine", "body", (4.8, 7.0, -1.85), (5.1, 11.25, 1.6), "crimson")
    add("ledger_chain", "body", (5.25, 11.0, -0.3), (5.75, 12.25, 0.3), "iron")
    # coin purse, its own bone so it can sway
    add("purse_bag", "purse", (-4.5, 8.25, -4.45), (-2.1, 11.0, -3.4), "crimson", decor={"north": [("coin_emblem",)], "sides": [("stitch",)]})
    add("purse_bottom", "purse", (-4.2, 7.75, -4.2), (-2.4, 8.25, -3.6), "crimson")
    add("purse_tie", "purse", (-4.05, 10.75, -4.6), (-2.55, 11.5, -3.5), "gold")
    add("purse_neck", "purse", (-3.8, 11.5, -4.3), (-2.8, 12.25, -3.6), "crimson")
    add("purse_tassel", "purse", (-2.35, 9.0, -4.7), (-2.0, 11.0, -4.4), "gold")

    # ---- torso: robe with gold bands, a filigree chest plate, pack straps with buckles, collar
    add("chest", "torso", (-4.5, 13, -2.5), (4.5, 23.5, 2.5), "robe", decor={"north": [("bands", (0.1, 0.92))], "sides": [("bands", (0.92,))]})
    add("chest_plate", "torso", (-3.5, 14.5, -2.8), (3.5, 22.75, -2.5), "robe", decor={"north": [("trim_all", 1), ("filigree",)]})
    pair("strap", "torso", (-3.4, 13.75, -3.05), (-2.4, 23.6, -2.8), "leather", decor={"north": [("stitch",)]})
    pair("strap_buckle", "torso", (-3.6, 17.5, -3.2), (-2.2, 18.75, -3.0), "gold", decor={"north": [("buckle",)]})
    add("collar", "torso", (-3.25, 23.25, -2.25), (3.25, 24.5, 2.25), "robe")

    # ---- head lost in the hood's shadow: two ember eyes above a crimson face scarf
    add("head", "head", (-3.75, 24.25, -3.75), (3.75, 31.75, 3.75), "shadow", decor={"north": [("face",)]})
    # the eyes again as two thin cubes over the face, so the server can draw them full-bright (a display's light is per part, not per pixel)
    add("eye_glow_l", "head", (1.25, 28.75, -3.8), (2.25, 29.25, -3.75), "eye", decor={"north": [("eye_px",)]})
    add("eye_glow_r", "head", (-2.25, 28.75, -3.8), (-1.25, 29.25, -3.75), "eye", decor={"north": [("eye_px",)]})
    add("scarf", "head", (-4.0, 24.0, -4.05), (4.0, 27.25, 3.9), "crimson", decor={"north": [("scarf_folds",)]})
    add("scarf_tail", "head", (1.0, 20.0, -4.35), (2.75, 24.25, -3.95), "crimson", rot=None, origin=None,
        decor={"north": [("tatter", 2)], "south": [("tatter", 2)]})
    add("hood_top", "head", (-4.75, 31.5, -4.75), (4.75, 33.0, 5.25), "robe")
    pair("hood_side", "head", (-4.75, 24.0, -4.75), (-3.75, 31.5, 5.25), "robe")
    add("hood_back", "head", (-3.75, 24.0, 3.75), (3.75, 31.5, 5.25), "robe", decor={"south": [("bands", (0.85,))]})
    pair("rim", "head", (-4.95, 23.75, -5.15), (-3.55, 32.25, -4.6), "gold", decor={"north": [("studs",)]})
    add("rim_top", "head", (-4.95, 31.25, -5.15), (4.95, 33.25, -4.6), "gold", decor={"north": [("studs",)]})
    add("peak_a", "head", (-2.75, 32.5, -0.5), (2.75, 34.25, 4.75), "robe", rot=(22.5, 0, 0), origin=(0, 32.5, 2.0))
    add("peak_b", "head", (-1.5, 33.5, 2.0), (1.5, 35.0, 6.25), "robe", rot=(22.5, 0, 0), origin=(0, 33.5, 3.5))
    add("peak_tip", "head", (-0.6, 34.5, 5.5), (0.6, 35.6, 6.9), "gold", rot=(22.5, 0, 0), origin=(0, 33.5, 3.5))
    add("diadem", "head", (-1.0, 32.75, -5.4), (1.0, 35.5, -4.6), "gold", decor={"north": [("gem_center",)]})
    add("diadem_spike", "head", (-0.45, 35.5, -5.25), (0.45, 36.75, -4.75), "gold")
    pair("diadem_wing", "head", (-3.4, 32.6, -5.3), (-0.75, 33.7, -4.65), "gold", rot=(0, 0, -22.5), origin=(-1.0, 32.9, -4.95))

    # ---- mantle: dark yoke with gold hem over a tattered crimson capelet; a long cape behind
    add("yoke", "mantle", (-6.5, 22.5, -3.15), (6.5, 25.25, 3.15), "robe", decor={"sides": [("trim_b", 1)], "up": [("bands", (0.5,))]})
    add("capelet", "mantle", (-7.75, 18.75, -3.5), (7.75, 23.25, 3.5), "crimson", decor={"sides": [("tatter", 3)]})
    add("clasp", "mantle", (-1.1, 21.75, -3.85), (1.1, 23.75, -3.45), "gold", decor={"north": [("gem_center",)]})
    add("cape", "cape", (-5.5, 3.5, 3.5), (5.5, 22.5, 3.95), "crimson", decor={"south": [("tatter", 4), ("cape_trim",)], "north": [("tatter", 4)]})

    # ---- the trader's pack
    add("bag", "pack", (-4.0, 10.5, 3.95), (4.0, 21.0, 8.75), "canvas", decor={"sides": [("stitch",)]})
    add("bag_flap", "pack", (-4.25, 17.5, 3.85), (4.25, 21.5, 9.1), "leather", decor={"south": [("trim_b", 1)], "east": [("trim_b", 1)], "west": [("trim_b", 1)]})
    add("flap_buckle", "pack", (-0.65, 17.0, 9.1), (0.65, 18.4, 9.3), "gold", decor={"south": [("buckle",)]})
    add("pocket", "pack", (-2.75, 12.0, 8.75), (2.75, 16.5, 9.75), "leather", decor={"south": [("stitch",), ("trim_t", 1)]})
    add("pocket_buckle", "pack", (-0.6, 15.25, 9.75), (0.6, 16.75, 9.95), "gold")
    pair("side_pocket", "pack", (-5.25, 11.5, 4.75), (-4.0, 16.0, 8.0), "leather", decor={"sides": [("stitch",)]})
    add("bedroll", "pack", (-5.25, 21.0, 4.25), (5.25, 23.75, 7.75), "crimson", decor={"sides": [("rug",)]})
    pair("bedroll_tie", "pack", (-3.25, 20.9, 4.1), (-2.5, 23.9, 7.9), "leather")
    add("map_tube", "pack", (1.75, 21.0, 7.0), (3.25, 28.0, 8.5), "leather", decor={"sides": [("bands", (0.3, 0.7))]})
    add("map_cap", "pack", (1.6, 27.5, 6.85), (3.4, 28.5, 8.65), "gold")
    add("map_scroll", "pack", (2.0, 28.5, 7.25), (3.0, 30.0, 8.25), "parch")
    add("pot", "pack", (-1.75, 8.5, 6.0), (1.75, 10.5, 8.5), "iron")
    add("pot_rim", "pack", (-2.0, 10.25, 5.75), (2.0, 10.75, 8.75), "iron")
    # bottles hanging under the side pockets (teal on the left, violet on the right)
    for side, mat in (("l", "teal"), ("r", "violet")):
        f = add if side == "l" else mirror
        f(f"bottle_{side}", "pack", (-5.1, 8.5, 5.5), (-3.9, 10.5, 6.7), mat, decor={"sides": [("glass",)]})
        f(f"bottle_neck_{side}", "pack", (-4.75, 10.5, 5.85), (-4.25, 11.25, 6.35), mat)
        f(f"bottle_cork_{side}", "pack", (-4.8, 11.25, 5.8), (-4.2, 11.75, 6.4), "cork")

    # ---- arms: bell sleeves with gold hems, crimson lining, leather gloves with gold cuffs
    for side in ("r", "l"):
        f = mirror if side == "l" else add
        f(f"sleeve_{side}", f"arm_{side}", (4.5, 15.5, -2.0), (7.5, 23.5, 2.0), "robe", decor={"sides": [("bands", (0.25,))]})
        f(f"bell_{side}", f"forearm_{side}", (4.25, 11.0, -2.5), (7.75, 16.25, 2.5), "robe", decor={"sides": [("trim_b", 2)]})
        f(f"lining_{side}", f"forearm_{side}", (4.5, 10.75, -2.25), (7.5, 11.25, 2.25), "crimson")
        f(f"glove_{side}", f"forearm_{side}", (5.0, 8.25, -1.4), (7.0, 11.0, 1.4), "leather", decor={"sides": [("stitch",)]})
        f(f"glove_cuff_{side}", f"forearm_{side}", (4.9, 10.5, -1.5), (7.1, 11.0, 1.5), "gold")

    # ---- the balance scale, hanging from the left hand (authored hanging straight down from the grip, in the unbent frame)
    add("scale_loop", "scale", (-6.35, 7.25, -0.35), (-5.65, 8.5, 0.35), "gold")
    add("scale_post", "scale", (-6.2, 4.5, -0.2), (-5.8, 7.25, 0.2), "gold")
    add("scale_beam", "scale", (-9.75, 5.75, -0.25), (-2.25, 6.25, 0.25), "gold", decor={"sides": [("studs",)]})
    add("scale_needle", "scale", (-6.15, 6.25, -0.4), (-5.85, 7.5, -0.25), "iron")
    for nm, cx in (("l", -9.5), ("r", -2.5)):
        add(f"scale_chain_{nm}", "scale", (cx - 0.1, 2.25, -0.1), (cx + 0.1, 5.75, 0.1), "iron")
        add(f"scale_pan_{nm}", "scale", (cx - 1.4, 1.75, -1.4), (cx + 1.4, 2.25, 1.4), "gold")
        add(f"scale_lip_{nm}", "scale", (cx - 1.55, 2.25, -1.55), (cx + 1.55, 2.5, 1.55), "gold")
    add("scale_coins", "scale", (-3.1, 2.25, -0.6), (-1.9, 3.5, 0.6), "gold", decor={"sides": [("coin_stack",)]})
    add("scale_gem", "scale", (-9.9, 2.25, -0.5), (-9.1, 3.25, 0.5), "violet", decor={"sides": [("glass",)]})

    # ---- lantern staff, standing in the right hand
    add("staff_pole", "staff", (5.5, 0.5, -5.1), (6.5, 37.5, -4.1), "wood", decor={"sides": [("grain",)]})
    add("staff_ferrule", "staff", (5.35, 0, -5.25), (6.65, 1.25, -3.95), "iron")
    add("staff_grip", "staff", (5.35, 9.5, -5.25), (6.65, 13.5, -3.95), "leather", decor={"sides": [("wrap",)]})
    add("staff_band_a", "staff", (5.3, 14.5, -5.3), (6.7, 15.25, -3.9), "gold")
    add("staff_band_b", "staff", (5.3, 29.0, -5.3), (6.7, 29.75, -3.9), "gold")
    add("staff_knob", "staff", (5.15, 37.25, -5.45), (6.85, 39.0, -3.75), "gold", decor={"sides": [("studs",)]})
    add("staff_finial", "staff", (5.6, 39.0, -5.0), (6.4, 40.25, -4.2), "gold")
    add("hook_arm", "staff", (6.5, 37.5, -4.85), (10.25, 38.25, -4.35), "gold")
    add("hook_drop", "staff", (9.6, 36.0, -4.85), (10.25, 37.5, -4.35), "gold")
    add("lantern_chain", "lantern", (9.75, 33.75, -4.7), (10.05, 36.0, -4.5), "iron")
    add("lantern_cap_top", "lantern", (9.15, 32.75, -5.35), (10.65, 33.75, -3.85), "gold")
    add("lantern_cap", "lantern", (8.5, 32.0, -6.0), (11.3, 33.0, -3.2), "gold", decor={"sides": [("studs",)]})
    add("lantern_glass", "lantern", (8.85, 28.0, -5.65), (10.95, 32.0, -3.55), "glow", decor={"sides": [("pane",)]})
    for i, (x0, z0) in enumerate(((8.6, -5.9), (10.7, -5.9), (8.6, -3.8), (10.7, -3.8))):
        add(f"lantern_post_{i}", "lantern", (x0, 28.0, z0), (x0 + 0.5, 32.0, z0 + 0.5), "gold")
    add("lantern_base", "lantern", (8.5, 27.0, -6.0), (11.3, 28.0, -3.2), "gold", decor={"sides": [("studs",)]})
    add("lantern_drip", "lantern", (9.5, 26.25, -5.0), (10.3, 27.0, -4.2), "gold")


# ---- painting -------------------------------------------------------------------------------------------------------------------------
def face_dims(e: El, f: str):
    sx, sy, sz = (e.to[i] - e.frm[i] for i in range(3))
    return {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy), "up": (sx, sz), "down": (sx, sz)}[f]


def face_px(e: El, f: str):
    w, h = face_dims(e, f)
    return max(1, round(w * D)), max(1, round(h * D))


def decor_for(e: El, f: str):
    out = list(e.decor.get("*", []))
    if f in ("north", "east", "south", "west"):
        out += e.decor.get("sides", [])
    return out + e.decor.get(f, [])


class Canvas:
    """A face being painted: per pixel a material, a ramp index and an alpha."""

    def __init__(self, w, h, mat):
        self.w, self.h = w, h
        self.mat = [[mat] * w for _ in range(h)]
        self.idx = [[0] * w for _ in range(h)]
        self.a = [[255] * w for _ in range(h)]

    def set(self, x, y, mat, idx):
        if 0 <= x < self.w and 0 <= y < self.h:
            n = len(PAL[mat])
            self.mat[y][x] = mat
            self.idx[y][x] = max(0, min(n - 1, idx))

    def shift(self, x, y, d):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.set(x, y, self.mat[y][x], self.idx[y][x] + d)


def paint_face(e: El, f: str, seed: int) -> Canvas:
    mat = e.fmat.get(f, e.mat)
    ramp = PAL[mat]
    n = len(ramp)
    w, h = face_px(e, f)
    rng = random.Random(seed)
    cv = Canvas(w, h, mat)
    vertical = f in ("north", "east", "south", "west")
    phase, period = rng.random() * 6.283, rng.uniform(4.5, 7.5)
    for y in range(h):
        for x in range(w):
            v = BASE.get(mat, 0.55) + rng.gauss(0, NOISE.get(mat, 0.05))
            if vertical and h > 1:
                v += 0.16 * (0.5 - (y + 0.5) / h)          # baked light: tops catch it, hems sit in shadow
            v += {"up": 0.12, "down": -0.24}.get(f, 0)
            if mat in CLOTH and vertical and w >= 4:      # vertical folds, deepening toward the hem
                v += 0.1 * math.sin(2 * math.pi * x / period + phase) * (0.3 + 0.7 * (y + 0.5) / h)
            if mat in METAL and w >= 3 and h >= 3 and (x - y * 0.6) % 9 < 1.0:
                v += 0.13                                  # a diagonal glint
            if mat == "wood":
                v += 0.07 * math.sin(x * 2.3 + phase + y * 0.15)
            if mat == "canvas" and (x % 2 == 0) and (y % 2 == 0):
                v -= 0.06                                  # weave
            if w >= 4 and h >= 4 and (x in (0, w - 1) or y in (0, h - 1)):
                v -= 0.12                                  # a crisp, darker edge reads the shape
            if vertical and y == 0 and h >= 4:
                v += 0.1
            v += BAYER[y % 2][x % 2] * 0.07
            cv.idx[y][x] = max(0, min(n - 1, int(round(v * (n - 1)))))
    for d in decor_for(e, f):
        DECOR[d[0]](cv, rng, *d[1:])
    return cv


# -- decor painters
def _trim_rows(k):
    return {1: [2], 2: [3, 1], 3: [3, 2, 1]}.get(k, [3] + [2] * (k - 2) + [1])


def d_trim_b(cv, rng, k=2):
    rows = _trim_rows(k)
    for i, gi in enumerate(rows):
        y = cv.h - k + i
        for x in range(cv.w):
            cv.set(x, y, "gold", gi + (1 if (k >= 2 and i == 0 and x % 4 == 1) else 0))
    for x in range(cv.w):
        cv.shift(x, cv.h - k - 1, -2)


def d_trim_t(cv, rng, k=1):
    rows = _trim_rows(k)
    for i, gi in enumerate(rows):
        for x in range(cv.w):
            cv.set(x, i, "gold", gi)
    for x in range(cv.w):
        cv.shift(x, k, -2)


def _trim_col(cv, k, left):
    rows = _trim_rows(k)
    for i, gi in enumerate(rows):
        x = i if left else cv.w - 1 - i
        for y in range(cv.h):
            cv.set(x, y, "gold", gi + (1 if (i == 0 and y % 4 == 1) else 0))
    for y in range(cv.h):
        cv.shift(k if left else cv.w - 1 - k, y, -2)


def d_trim_l(cv, rng, k=2):
    _trim_col(cv, k, True)


def d_trim_r(cv, rng, k=2):
    _trim_col(cv, k, False)


def d_trim_all(cv, rng, k=1):
    for x in range(cv.w):
        cv.set(x, 0, "gold", 3)
        cv.set(x, cv.h - 1, "gold", 1)
    for y in range(cv.h):
        cv.set(0, y, "gold", 2)
        cv.set(cv.w - 1, y, "gold", 2)


def d_bands(cv, rng, fracs=(0.5,)):
    for fr in fracs:
        y = int(fr * cv.h)
        for x in range(cv.w):
            cv.set(x, y, "gold", 2 + (1 if x % 3 == 0 else 0))
            if y + 1 < cv.h and cv.mat[y + 1][x] != "gold":
                cv.shift(x, y + 1, -2)


def d_filigree(cv, rng):
    """The reference's motif: a gold diamond with a bright core and two small diamonds flanking it."""
    cx, cy = (cv.w - 1) / 2, (cv.h - 1) / 2
    r = max(2.0, min(cv.w, cv.h) * 0.3)

    def diamond(ox, oy, rad, hi):
        for y in range(cv.h):
            for x in range(cv.w):
                dd = abs(x - ox) + abs(y - oy)
                if abs(dd - rad) < 0.55:
                    cv.set(x, y, "gold", 3 if (y < oy and hi) else 2)
                elif dd < rad - 0.6 and hi:
                    cv.shift(x, y, -1)

    diamond(cx, cy, r, True)
    cv.set(int(round(cx)), int(round(cy)), "gold", 4)
    if cv.w > 2 * r + 5:
        for sgn in (-1, 1):
            diamond(cx + sgn * (r + 2.2), cy, max(1.0, r * 0.35), False)
    # curled whiskers above and below the diamond
    for sgn in (-1, 1):
        y = int(round(cy + sgn * (r + 1.5)))
        for dx in (-1, 0, 1):
            cv.set(int(round(cx)) + dx, y, "gold", 1 if dx else 2)


def d_studs(cv, rng):
    if cv.w >= cv.h:
        y = cv.h // 2
        for x in range(1, cv.w - 1, 3):
            cv.set(x, y, "gold", 4)
            cv.set(x, min(cv.h - 1, y + 1), "gold", 1)
    else:
        x = cv.w // 2
        for y in range(1, cv.h - 1, 3):
            cv.set(x, y, "gold", 4)
            cv.set(min(cv.w - 1, x + 1), y, "gold", 1)


def d_stitch(cv, rng):
    if cv.w < 5 or cv.h < 5:
        return
    for x in range(1, cv.w - 1):
        if x % 2:
            cv.shift(x, 1, 2)
            cv.shift(x, cv.h - 2, 2)
    for y in range(1, cv.h - 1):
        if y % 2:
            cv.shift(1, y, 2)
            cv.shift(cv.w - 2, y, 2)


def d_tatter(cv, rng, k=3):
    """Ragged hem: columns cut away by 0..k pixels (alpha 0), the pixel above each cut frayed darker."""
    cut, prev = [], rng.randint(0, k)
    for x in range(cv.w):
        prev = max(0, min(k, prev + rng.choice((-2, -1, 0, 1, 1, 2))))
        cut.append(prev)
    for x, c in enumerate(cut):
        for i in range(c):
            cv.a[cv.h - 1 - i][x] = 0
        if cv.h - 1 - c >= 0:
            cv.shift(x, cv.h - 1 - c, -2)


def d_face(cv, rng):
    """Deep hood shadow, two ember eyes; the rows below sit behind the scarf."""
    for y in range(cv.h):
        for x in range(cv.w):
            edge = min(x, cv.w - 1 - x)
            cv.set(x, y, "shadow", (0 if y < 3 or edge < 2 else 1) + (1 if (y > 6 and 3 < x < cv.w - 4) else 0))
    ey = 5
    for ex in (3, cv.w - 5):
        cv.set(ex, ey, "eye", 2)
        cv.set(ex + 1, ey, "eye", 3)
        cv.set(ex - 1, ey, "eye", 0)
        cv.set(ex, ey + 1, "eye", 0)
        cv.set(ex + 1, ey + 1, "eye", 1)


def d_eye_px(cv, rng):
    for x in range(cv.w):
        for y in range(cv.h):
            cv.set(x, y, "eye", 2 if x == 0 else 3)


def d_scarf_folds(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            if (x + y // 2) % 5 == 0:
                cv.shift(x, y, -1)
    for x in range(cv.w):
        cv.shift(x, 0, 1)


def d_gem(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            cv.set(x, y, "gem", 2)
    cv.set(0, 0, "gem", 3)
    cv.set(cv.w - 1, cv.h - 1, "gem", 0)


def d_gem_center(cv, rng):
    cx, cy = cv.w // 2 - 1, cv.h // 2 - 1
    for dy in range(-1, 3):
        for dx in range(-1, 3):
            cv.set(cx + dx, cy + dy, "gold", 0)
    for dy in range(2):
        for dx in range(2):
            cv.set(cx + dx, cy + dy, "gem", 2)
    cv.set(cx, cy, "gem", 3)
    cv.set(cx + 1, cy + 1, "gem", 1)


def d_buckle(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            border = x in (0, cv.w - 1) or y in (0, cv.h - 1)
            cv.set(x, y, "gold", (3 if y == 0 else 2) if border else 0)
            if not border:
                cv.set(x, y, "leather", 0)
    for y in range(1, cv.h - 1):
        cv.set(cv.w // 2, y, "gold", 1)


def d_book(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            if (x + y < 2) or (cv.w - 1 - x + y < 2) or (x + cv.h - 1 - y < 2) or (cv.w - 1 - x + cv.h - 1 - y < 2):
                cv.set(x, y, "gold", 3)
    cx, cy = (cv.w - 1) / 2, (cv.h - 1) / 2
    for y in range(cv.h):
        for x in range(cv.w):
            d = math.hypot(x - cx, y - cy)
            if d < 1.6:
                cv.set(x, y, "gold", 3 if d < 0.8 else 2)


def d_pages(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            cv.set(x, y, "parch", 2 if y % 2 else 1)
    for y in range(cv.h):
        cv.set(0, y, "crimson", 1)
        cv.set(cv.w - 1, y, "crimson", 1)


def d_coin_emblem(cv, rng):
    cx, cy = (cv.w - 1) / 2, (cv.h - 1) / 2 + 0.5
    for y in range(cv.h):
        for x in range(cv.w):
            d = math.hypot(x - cx, y - cy)
            if 0.9 < d < 1.9:
                cv.set(x, y, "gold", 2 if y < cy else 1)


def d_coin_stack(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            cv.set(x, y, "gold", 3 if y % 2 == 0 else 1)


def d_glass(cv, rng):
    mat = cv.mat[0][0]
    n = len(PAL[mat])
    for y in range(cv.h):
        for x in range(cv.w):
            cv.set(x, y, mat, n - 2 if x == 0 and y < cv.h - 1 else (1 if y > cv.h * 0.4 else 2))
    if cv.w > 1:
        cv.set(0, 0, mat, n - 1)


def d_pane(cv, rng):
    """Lantern glass: a hot core fading out, a small flame, gold mullions."""
    cx, cy = (cv.w - 1) / 2, cv.h * 0.6
    for y in range(cv.h):
        for x in range(cv.w):
            d = math.hypot((x - cx) * 1.3, (y - cy) * 0.8)
            cv.set(x, y, "glow", max(0, 4 - int(d)))
    fx = int(round(cx))
    for y, (gi) in ((int(cy) - 2, 3), (int(cy) - 1, 4), (int(cy), 4)):
        cv.set(fx, y, "glow", gi)
    for y in range(cv.h):
        if cv.w >= 5:
            cv.set(fx, y, "gold", 1) if y < int(cy) - 2 else None
    for x in range(cv.w):
        cv.set(x, cv.h // 4, "gold", 1)


def d_grain(cv, rng):
    for y in range(cv.h):
        if rng.random() < 0.12:
            for x in range(cv.w):
                cv.shift(x, y, -1)


def d_wrap(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            if (x + y) % 3 == 0:
                cv.shift(x, y, 1)
            elif (x + y) % 3 == 2:
                cv.shift(x, y, -1)


def d_rug(cv, rng):
    for y in range(cv.h):
        for x in range(cv.w):
            if y == (2 + int(abs((x % 4) - 2))) % cv.h:
                cv.set(x, y, "gold", 2)


def d_chevrons(cv, rng):
    for y in range(0, cv.h - 3):
        for x in range(cv.w):
            if (y + abs(x - (cv.w - 1) / 2)) % 4 < 1:
                cv.set(x, y, "gold", 1)


def d_cape_trim(cv, rng):
    for y in range(cv.h):
        cv.set(1, y, "gold", 1)
        cv.set(cv.w - 2, y, "gold", 1)
    d_filigree_small = (cv.w // 2, cv.h // 3)
    cx, cy = d_filigree_small
    for y in range(cv.h):
        for x in range(cv.w):
            if abs(abs(x - cx) + abs(y - cy) - 4) < 0.55:
                cv.set(x, y, "gold", 2)


DECOR = {
    "trim_b": d_trim_b, "trim_t": d_trim_t, "trim_l": d_trim_l, "trim_r": d_trim_r, "trim_all": d_trim_all, "bands": d_bands,
    "filigree": d_filigree, "studs": d_studs, "stitch": d_stitch, "tatter": d_tatter, "face": d_face, "scarf_folds": d_scarf_folds,
    "gem": d_gem, "gem_center": d_gem_center, "buckle": d_buckle, "book": d_book, "pages": d_pages, "coin_emblem": d_coin_emblem,
    "coin_stack": d_coin_stack, "glass": d_glass, "pane": d_pane, "grain": d_grain, "wrap": d_wrap, "rug": d_rug,
    "chevrons": d_chevrons, "cape_trim": d_cape_trim, "eye_px": d_eye_px,
}


def canvas_rgba(cv: Canvas, glow_only=False) -> Image.Image:
    im = Image.new("RGBA", (cv.w, cv.h), (0, 0, 0, 0))
    px = im.load()
    for y in range(cv.h):
        for x in range(cv.w):
            if cv.a[y][x] == 0:
                continue
            m = cv.mat[y][x]
            if glow_only and m not in GLOWING:
                continue
            px[x, y] = PAL[m][cv.idx[y][x]] + (255,)
    return im


def build_atlas():
    """Paint every face, then shelf-pack them (tallest first) into a power-of-two atlas."""
    faces = []
    for i, e in enumerate(ELS):
        for k, f in enumerate(FACES):
            cv = paint_face(e, f, 7919 * i + 31 * k + 17)
            faces.append(((i, f), cv))
    width = 256
    order = sorted(faces, key=lambda t: (-t[1].h, -t[1].w))
    x = y = row_h = 0
    pos = {}
    for key, cv in order:
        if x + cv.w > width:
            x, y, row_h = 0, y + row_h, 0
        pos[key] = (x, y)
        x += cv.w
        row_h = max(row_h, cv.h)
    height = 1
    while height < y + row_h:
        height *= 2
    atlas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    rects = {}
    for key, cv in faces:
        px, py = pos[key]
        atlas.paste(canvas_rgba(cv), (px, py))
        glow.paste(canvas_rgba(cv, glow_only=True), (px, py))
        rects[key] = (px, py, cv.w, cv.h)
    return atlas, glow, rects


# ---- transforms ---------------------------------------------------------------------------------------------------------------------
def rx(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_zyx(r):
    """three.js Euler order 'ZYX' (what Blockbench uses for cubes and bones): M = Rz . Ry . Rx."""
    return rz(r[2]) @ ry(r[1]) @ rx(r[0])


def affine(m3, pivot):
    p = np.asarray(pivot, float)
    m = np.eye(4)
    m[:3, :3] = m3
    m[:3, 3] = p - m3 @ p
    return m


def bedrock_to_bb(rot):
    """A Bedrock keyframe rotation as Blockbench displays it (BoneAnimator.displayRotation: x and y are subtracted, z added)."""
    return (-rot[0], -rot[1], rot[2])


def bone_world(pose: dict) -> dict:
    """World matrix of every bone, rest rotation plus an optional Bedrock-convention pose {bone: {"rotation": .., "position": ..}}."""
    out = {}

    def world(name):
        if name in out:
            return out[name]
        b = BONES[name]
        parent = world(b.parent) if b.parent else np.eye(4)
        p = pose.get(name, {})
        r = p.get("rotation", (0, 0, 0))
        br = bedrock_to_bb(r)
        m = affine(euler_zyx(b.rest), b.pivot) @ affine(euler_zyx(br), b.pivot)
        pos = p.get("position", (0, 0, 0))
        t = np.eye(4)
        t[:3, 3] = (-pos[0], pos[1], pos[2])
        out[name] = parent @ t @ m
        return out[name]

    for n in BONES:
        world(n)
    return out


# ---- animation ----------------------------------------------------------------------------------------------------------------------
IDLE_LEN = 4.0


def idle_pose(t: float) -> dict:
    """A 4 s loop: slow breath, the hood drifting as he watches the room, cape and lantern swinging a beat behind the body."""
    w = 2 * math.pi * t / IDLE_LEN
    s, c = math.sin(w), math.cos(w)
    return {
        "torso": {"rotation": (1.2 * s, 0, 0), "position": (0, 0.18 * (1 - c) / 2, 0)},
        "head": {"rotation": (-2 + 2.5 * math.sin(2 * w), 9 * s, 2 * c)},
        "mantle": {"rotation": (-0.8 * s, 0, 0)},
        "cape": {"rotation": (3 + 2.5 * math.sin(w - 0.9), 0, 1.5 * c)},
        "arm_l": {"rotation": (-1.5 * s, 0, 0)},
        "scale": {"rotation": (2 * math.sin(w - 1.2), 0, 5 * math.sin(w + 0.6))},
        "purse": {"rotation": (0, 0, 6 * math.sin(w - 0.7))},
        "lantern": {"rotation": (3 * math.sin(w + 1.6), 0, 7 * s)},
        "pack": {"rotation": (-0.8 * math.sin(w - 0.5), 0, 0)},
    }


GREET_LEN = 2.0


def greet_pose(t: float) -> dict:
    """Played once when a player opens the shop (t runs 0 -> GREET_LEN; the pose must return to rest at both ends).

    Return {bone: {"rotation": (x, y, z), "position": (x, y, z)}} in Bedrock convention, like idle_pose().
    Bones: torso, head, arm_l, forearm_l, scale, arm_r, mantle, cape, lantern, purse, pack.
    +x on torso/head bows forward; +x on forearm_l raises the scale hand up toward the player? (it starts bent 80 deg forward).
    """
    # a courtly bow: ease down (0-0.6 s), hold (to 1.1 s), rise (to 2 s); smoothstep keeps both ends at rest for a clean blend into idle
    def smooth(a):
        a = max(0.0, min(1.0, a))
        return a * a * (3 - 2 * a)

    k = smooth(t / 0.6) if t < 1.1 else 1 - smooth((t - 1.1) / 0.9)
    lag = smooth((t - 0.15) / 0.6) if t < 1.25 else 1 - smooth((t - 1.25) / 0.75)   # cloth and hanging things trail the body
    bow = 22 * k
    return {
        "torso": {"rotation": (bow, 0, 0)},
        "head": {"rotation": (10 * k, 0, 0)},                              # the hood dips a little further than the back
        "arm_r": {"rotation": (-bow * 0.8, 0, 0)},                         # keeps the staff hand roughly where it was
        "staff": {"rotation": (7 * k, 0, 0)},                              # the staff leans in with him
        "lantern": {"rotation": (-7 * lag - 4 * math.sin(math.pi * lag), 0, 0)},  # swings back to plumb, overshooting a touch
        "arm_l": {"rotation": (-bow * 0.6, 0, -8 * k)},                    # the scale hand sweeps out to the side
        "forearm_l": {"rotation": (-20 * k, 0, 0)},
        "scale": {"rotation": (bow * 0.6 + 20 * k - 6 * math.sin(math.pi * lag), 0, 8 * k)},  # stays hanging, with a sway
        "cape": {"rotation": (-bow * 0.5 + 6 * lag, 0, 0)},                # the cape falls away from the back, then settles
        "mantle": {"rotation": (-3 * lag, 0, 0)},
        "purse": {"rotation": (-bow * 0.7, 0, 4 * math.sin(math.pi * lag))},
    }


def sample(fn, length, step=0.125):
    times = [round(i * step, 4) for i in range(int(round(length / step)) + 1)]
    channels: dict = {}
    for t in times:
        for b, ch in fn(t).items():
            for k, v in ch.items():
                channels.setdefault(b, {}).setdefault(k, {})[t] = [round(float(x), 3) for x in v]
    return channels


# ---- exports ------------------------------------------------------------------------------------------------------------------------
def num(v):
    v = round(float(v), 4)
    return int(v) if v == int(v) else v


def geo_json(rects, tw, th):
    bones = []
    for b in BONES.values():
        cubes = []
        for i, e in enumerate(ELS):
            if e.bone != b.name:
                continue
            size = [num(e.to[k] - e.frm[k]) for k in range(3)]
            cube = {"origin": [num(-e.to[0]), num(e.frm[1]), num(e.frm[2])], "size": size, "uv": {}}
            if e.rot:
                org = e.origin
                cube["pivot"] = [num(-org[0]), num(org[1]), num(org[2])]
                cube["rotation"] = [num(-e.rot[0]), num(-e.rot[1]), num(e.rot[2])]
            for f in FACES:
                x, y, w, h = rects[(i, f)]
                if f in ("up", "down"):  # Blockbench writes these two flipped
                    cube["uv"][f] = {"uv": [x + w, y + h], "uv_size": [-w, -h]}
                else:
                    cube["uv"][f] = {"uv": [x, y], "uv_size": [w, h]}
            cubes.append(cube)
        d = {"name": b.name, "pivot": [num(-b.pivot[0]), num(b.pivot[1]), num(b.pivot[2])]}
        if b.parent:
            d["parent"] = b.parent
        if any(b.rest):
            d["rotation"] = [num(-b.rest[0]), num(-b.rest[1]), num(b.rest[2])]
        if cubes:
            d["cubes"] = cubes
        bones.append(d)
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{NAME}", "texture_width": tw, "texture_height": th,
                        "visible_bounds_width": 3, "visible_bounds_height": 3.5, "visible_bounds_offset": [0, 1.25, 0]},
        "bones": bones}]}


def animation_json(anims):
    out = {"format_version": "1.8.0", "animations": {}, "geckolib_format_version": 2}
    for name, (fn, length, loop) in anims.items():
        ch = sample(fn, length)
        if not ch:
            continue
        bones = {b: {k: {f"{t:.4g}" if t else "0.0": v for t, v in keys.items()} for k, keys in chans.items()} for b, chans in ch.items()}
        out["animations"][f"animation.{NAME}.{name}"] = {"loop": loop, "animation_length": length, "bones": bones}
    return out


NS = uuid.UUID("5b0d6f6e-3e1d-4d55-9a8e-0f5a3c2b7d41")


def _u(s):
    return str(uuid.uuid5(NS, s))


def bbmodel(rects, png, tw, th, anims):
    elements = []
    for i, e in enumerate(ELS):
        faces = {}
        for f in FACES:
            x, y, w, h = rects[(i, f)]
            faces[f] = {"uv": [x, y, x + w, y + h], "texture": 0}
        d = {"name": e.name, "box_uv": False, "rescale": False, "locked": False, "render_order": "default", "allow_mirror_modeling": True,
             "from": list(e.frm), "to": list(e.to), "autouv": 0, "color": i % 8, "inflate": 0,
             "origin": list(e.origin) if e.origin else list(BONES[e.bone].pivot), "faces": faces, "type": "cube", "uuid": _u(f"el:{i}:{e.name}")}
        if e.rot:
            d["rotation"] = list(e.rot)
        elements.append(d)

    def group(name):
        b = BONES[name]
        kids = [_u(f"el:{i}:{e.name}") for i, e in enumerate(ELS) if e.bone == name]
        kids += [group(c) for c in BONES if BONES[c].parent == name]
        g = {"name": name, "origin": list(b.pivot), "color": 0, "uuid": _u("bone:" + name), "export": True, "mirror_uv": False,
             "isOpen": name in ("root", "body", "torso"), "locked": False, "visibility": True, "autouv": 0, "children": kids}
        if any(b.rest):
            g["rotation"] = list(b.rest)
        return g

    animations = []
    for name, (fn, length, loop) in anims.items():
        ch = sample(fn, length)
        if not ch:
            continue
        animators = {}
        for b, chans in ch.items():
            kfs = []
            for k, keys in chans.items():
                for t, v in keys.items():
                    kfs.append({"channel": k, "data_points": [{"x": str(v[0]), "y": str(v[1]), "z": str(v[2])}],
                                "uuid": _u(f"kf:{name}:{b}:{k}:{t}"), "time": t, "color": -1, "interpolation": "linear"})
            animators[_u("bone:" + b)] = {"name": b, "type": "bone", "keyframes": kfs}
        animations.append({"uuid": _u("anim:" + name), "name": f"animation.{NAME}.{name}", "loop": "loop" if loop else "once",
                           "override": False, "length": length, "snapping": 24, "selected": False, "anim_time_update": "",
                           "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators})

    tex = {"path": f"{NAME}.png", "name": f"{NAME}.png", "folder": "", "namespace": "", "id": "0", "particle": False,
           "render_mode": "default", "render_sides": "auto", "frame_time": 1, "frame_order_type": "loop", "frame_order": "",
           "frame_interpolate": False, "visible": True, "internal": True, "saved": True, "uuid": _u("texture"),
           "width": tw, "height": th, "uv_width": tw, "uv_height": th,
           "source": "data:image/png;base64," + base64.b64encode(png).decode()}
    return {"meta": {"format_version": "4.10", "model_format": "bedrock", "box_uv": False}, "name": NAME,
            "model_identifier": NAME, "visible_box": [3, 3.5, 1.25], "variable_placeholders": "", "variable_placeholder_buttons": [],
            "timeline_setups": [], "unhandled_root_fields": {}, "resolution": {"width": tw, "height": th},
            "elements": elements, "outliner": [group("root")], "textures": [tex], "animations": animations}


# ---- Polymer export: one vanilla item model per bone, and the rig the server animates them with --------------------------------------
# CobbleTowers is server-only, so the merchant is drawn the way Grizelda is: item displays carrying our own item models (Polymer's pack).
# Each bone is one display (two when it has full-bright cubes); the server sends every display a transformation each tick pair and the
# client interpolates. A Java item model may only span -16..32 and turn a cube about one axis by 0/+-22.5/+-45, so each bone's cubes are
# written about the bone pivot at HALF scale (model point 8,8,8 = pivot), and the server scales them back up.
MODEL_DIR = "gilded_merchant"
TEX_REF = "cobbletowers:item/gilded_merchant/gilded_merchant"
PART_SCALE = 0.5


def parts():
    """(part name, bone, glow, [element indices]) for every bone with cubes; glowing cubes get their own part."""
    out = []
    for b in BONES:
        for glow in (False, True):
            idx = [i for i, e in enumerate(ELS) if e.bone == b and (e.mat in GLOWING) == glow]
            if idx:
                out.append((b + ("_glow" if glow else ""), b, glow, idx))
    return out


def part_model(bone_name, idx, rects, tw):
    piv = BONES[bone_name].pivot
    k = 16.0 / tw

    def m(p):
        return [num(8 + (p[a] - piv[a]) * PART_SCALE) for a in range(3)]

    els = []
    for i in idx:
        e = ELS[i]
        d = {"name": e.name, "from": m(e.frm), "to": m(e.to)}
        if e.rot:
            axes = [a for a in range(3) if e.rot[a]]
            assert len(axes) == 1 and e.rot[axes[0]] in (-45, -22.5, 22.5, 45), (e.name, "vanilla item models turn one axis by 22.5/45", e.rot)
            d["rotation"] = {"angle": e.rot[axes[0]], "axis": "xyz"[axes[0]], "origin": m(e.origin)}
        for v in (*d["from"], *d["to"]):
            assert -16 <= v <= 32, (e.name, "outside the -16..32 item model range", v)
        d["faces"] = {}
        for f in FACES:
            x, y, w, h = rects[(i, f)]
            d["faces"][f] = {"uv": [round(x * k, 5), round(y * k, 5), round((x + w) * k, 5), round((y + h) * k, 5)], "texture": "#0"}
        els.append(d)
    return {"credit": "The Gilded Merchant (tools/gilded_merchant/build.py), original work for CobbleTowers", "texture_size": [tw, tw],
            "textures": {"0": TEX_REF, "particle": TEX_REF}, "elements": els}


def write_polymer(rects, atlas, tw, th, anims):
    assert tw == th, "item model UVs assume a square atlas"
    assets = ROOT / "src/main/resources/assets/cobbletowers"
    mdir = assets / "models" / MODEL_DIR
    mdir.mkdir(parents=True, exist_ok=True)
    for old in mdir.glob("*.json"):
        old.unlink()
    plist = parts()
    for name, b, glow, idx in plist:
        (mdir / f"{name}.json").write_text(json.dumps(part_model(b, idx, rects, tw), indent=1) + "\n")
    (assets / "textures/item/gilded_merchant").mkdir(parents=True, exist_ok=True)
    atlas.save(assets / "textures/item/gilded_merchant/gilded_merchant.png")
    step = 0.05  # one sample a tick
    rig = {
        "comment": "Generated by tools/gilded_merchant/build.py. Blockbench units, model faces north; rotations are degrees, bone rest in "
                   "Blockbench space (Euler ZYX), animation samples in Bedrock convention (x and y negated when applied).",
        "part_scale": PART_SCALE,
        "parts": [{"name": n, "bone": b, "glow": g, "model": f"{MODEL_DIR}/{n}"} for n, b, g, _ in plist],
        "bones": [{"name": bn.name, "parent": bn.parent, "pivot": list(bn.pivot), "rest": list(bn.rest)} for bn in BONES.values()],
        "animations": {},
    }
    for name, (fn, length, loop) in anims.items():
        n = int(round(length / step))
        samples = [fn(i * step) for i in range(n + 1)]
        bones = sorted({b for smp in samples for b in smp})
        if not bones:
            continue
        rig["animations"][name] = {"length": length, "loop": loop, "step": step, "bones": {
            b: {ch: [[round(float(v), 3) for v in smp.get(b, {}).get(ch, (0, 0, 0))] for smp in samples]
                for ch in ("rotation", "position") if any(ch in smp.get(b, {}) for smp in samples)}
            for b in bones}}
    out = ROOT / "src/main/resources/cobbletowers/gilded_merchant/rig.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rig, separators=(",", ":")) + "\n")
    print(f"polymer: {len(plist)} parts -> {mdir.relative_to(ROOT)}; rig {out.relative_to(ROOT)}")


# ---- software preview (alpha-tested, emissive pixels unlit) ---------------------------------------------------------------------------
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}


def quad(e, f):
    x0, y0, z0 = e.frm; x1, y1, z1 = e.to
    return {"north": [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)], "south": [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
            "east": [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)], "west": [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
            "up": [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], "down": [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)]}[f]


def render(atlas, glow, rects, az, el=12, px=14.0, out=(560, 720), centre=(0, 19.5, 0), pose=None, ss=2, bg=(30, 27, 38, 255)):
    W, H = out[0] * ss, out[1] * ss
    zbuf = np.full((H, W), -1e9)
    img = np.zeros((H, W, 4), np.uint8)
    img[:] = bg
    # soft floor vignette so the dark robe separates from the background
    yy, xx = np.mgrid[0:H, 0:W]
    v = np.clip(1 - np.hypot((xx - W / 2) / (W * 0.55), (yy - H * 0.55) / (H * 0.6)), 0, 1)
    for k in range(3):
        img[..., k] = np.clip(bg[k] + 26 * v, 0, 255)
    a, p = math.radians(az), math.radians(el)
    cam = np.array([math.sin(a) * math.cos(p), math.sin(p), -math.cos(a) * math.cos(p)])
    right = np.cross(-cam, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, -cam)
    light = np.array([0.45, 0.85, -0.55]); light /= np.linalg.norm(light)
    at, gl = np.asarray(atlas), np.asarray(glow)
    worlds = bone_world(pose or {})
    c0 = np.array(centre, float)
    for i, e in enumerate(ELS):
        m = worlds[e.bone]
        for f in FACES:
            q = np.array(quad(e, f), float)
            n = np.array(NORMAL[f], float)
            if e.rot:
                r = euler_zyx(e.rot)
                q = (q - e.origin) @ r.T + e.origin
                n = r @ n
            q = q @ m[:3, :3].T + m[:3, 3]
            n = m[:3, :3] @ n
            if np.dot(n, cam) <= 1e-6:
                continue
            shade = 0.38 + 0.42 * max(0.0, float(n @ light)) + 0.22 * max(0.0, float(n @ cam))
            rel = q - c0
            sx = (rel @ right) * px * ss + W / 2
            sy = -(rel @ up) * px * ss + H / 2
            depth = rel @ cam
            uv = [(0, 0), (0, 1), (1, 1), (1, 0)]
            for tri in ((0, 1, 2), (0, 2, 3)):
                _tri(img, zbuf, at, gl, rects[(i, f)], [sx[k] for k in tri], [sy[k] for k in tri], [depth[k] for k in tri],
                     [uv[k] for k in tri], shade)
    return Image.fromarray(img, "RGBA").resize(out, Image.LANCZOS)


def _tri(img, zbuf, at, gl, rect, xs, ys, zs, uvs, shade):
    rx_, ry_, rw, rh = rect
    H, W = zbuf.shape
    x0, x1 = int(max(0, math.floor(min(xs)))), int(min(W - 1, math.ceil(max(xs))))
    y0, y1 = int(max(0, math.floor(min(ys)))), int(min(H - 1, math.ceil(max(ys))))
    if x1 < x0 or y1 < y0:
        return
    gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
    if abs(d) < 1e-9:
        return
    l0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / d
    l1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / d
    l2 = 1 - l0 - l1
    msk = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
    if not msk.any():
        return
    z = l0 * zs[0] + l1 * zs[1] + l2 * zs[2]
    u = l0 * uvs[0][0] + l1 * uvs[1][0] + l2 * uvs[2][0]
    v = l0 * uvs[0][1] + l1 * uvs[1][1] + l2 * uvs[2][1]
    sub = zbuf[y0:y1 + 1, x0:x1 + 1]
    tx = np.clip(rx_ + (u * rw).astype(int), rx_, rx_ + rw - 1)
    ty = np.clip(ry_ + (v * rh).astype(int), ry_, ry_ + rh - 1)
    col = at[ty, tx].astype(float)
    msk &= (col[..., 3] >= 128) & (z > sub)
    if not msk.any():
        return
    lit = gl[ty, tx][..., 3] >= 128
    col[..., :3] *= np.where(lit, 1.0, shade)[..., None]
    region = img[y0:y1 + 1, x0:x1 + 1]
    region[msk] = np.clip(col[msk], 0, 255).astype(np.uint8)
    sub[msk] = z[msk]


# ---- main ---------------------------------------------------------------------------------------------------------------------------
def check():
    names = [e.name for e in ELS]
    dup = {n for n in names if names.count(n) > 1}
    assert not dup, dup
    for e in ELS:
        assert e.bone in BONES, (e.name, e.bone)
        assert (e.rot is None) == (e.origin is None), e.name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--previews", action="store_true")
    ap.add_argument("--gif", action="store_true")
    args = ap.parse_args()
    build_merchant()
    check()
    atlas, glow, rects = build_atlas()
    tw, th = atlas.size
    buf = io.BytesIO(); atlas.save(buf, "PNG"); png = buf.getvalue()
    anims = {"idle": (idle_pose, IDLE_LEN, True), "greet": (greet_pose, GREET_LEN, False)}
    (ART / "export/geo").mkdir(parents=True, exist_ok=True)
    (ART / "export/animations").mkdir(parents=True, exist_ok=True)
    (ART / "export/textures").mkdir(parents=True, exist_ok=True)
    atlas.save(ART / f"{NAME}.png")
    glow.save(ART / f"{NAME}_glowmask.png")
    atlas.save(ART / f"export/textures/{NAME}.png")
    glow.save(ART / f"export/textures/{NAME}_glowmask.png")
    (ART / f"export/geo/{NAME}.geo.json").write_text(json.dumps(geo_json(rects, tw, th), indent=1) + "\n")
    (ART / f"export/animations/{NAME}.animation.json").write_text(json.dumps(animation_json(anims), indent=1) + "\n")
    (ART / f"{NAME}.bbmodel").write_text(json.dumps(bbmodel(rects, png, tw, th, anims)))
    write_polymer(rects, atlas, tw, th, anims)
    print(f"{len(ELS)} cubes in {len(BONES)} bones; atlas {tw}x{th}; -> {ART.relative_to(ROOT)}")
    if args.previews:
        (ART / "previews").mkdir(exist_ok=True)
        angles = (-35, 0, 35, 90, 160, 215)
        sheet = Image.new("RGBA", (560 * len(angles), 720))
        for k, az in enumerate(angles):
            im = render(atlas, glow, rects, az)
            im.save(ART / "previews" / f"az{az}.png")
            sheet.paste(im, (560 * k, 0))
        sheet.save(ART / "previews" / "sheet.png")
        close = render(atlas, glow, rects, -25, el=8, px=34.0, out=(720, 560), centre=(2.5, 29.5, -3))
        close.save(ART / "previews" / "closeup_head.png")
        print("previews:", ART / "previews")
    if args.gif:
        frames = []
        for k in range(48):
            t = IDLE_LEN * k / 24 % IDLE_LEN
            frames.append(render(atlas, glow, rects, -35 + 360 * k / 48, pose=idle_pose(t), ss=1, out=(420, 540), px=10.5).convert("P", palette=Image.ADAPTIVE))
        frames[0].save(ART / "previews" / "idle_turntable.gif", save_all=True, append_images=frames[1:], duration=83, loop=0, disposal=2)
        print("gif:", ART / "previews" / "idle_turntable.gif")


if __name__ == "__main__":
    main()
