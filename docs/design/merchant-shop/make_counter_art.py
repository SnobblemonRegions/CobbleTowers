#!/usr/bin/env python3
"""Paints the Gilded Merchant counter-shop art for the mockups (and, if chosen, the bridge pack).

    py -3.14 -I docs/design/merchant-shop/make_counter_art.py

Writes to art/: shop_wall.png (160 x 90, shown at 8x = 1280 x 720), shop_counter.png (160 x 34), token.png (16 x 16).
Pixel art in the merchant's own palette: near-black robes, gold trim, crimson mantle, amber lantern light. Fixed seed, so every
run is the same.
"""
import json
import math
import random
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent / "art"
rng = random.Random(60)

NIGHT = (16, 12, 20)
PLANK = (30, 23, 34)
PLANK_HI = (38, 29, 42)
SEAM = (12, 9, 15)
SHELF = (74, 50, 34)
SHELF_HI = (104, 72, 46)
SHELF_LO = (44, 30, 22)
GOLD = (217, 169, 58)
GOLD_HI = (246, 210, 112)
GOLD_LO = (140, 102, 32)
CRIMSON = (142, 31, 42)
CRIMSON_HI = (178, 52, 60)
CRIMSON_LO = (92, 18, 28)
AMBER = (255, 176, 74)
TEAL = (52, 150, 140)
VIOLET = (110, 70, 160)
PAPER = (200, 182, 140)
WOOD = (92, 62, 40)
WOOD_HI = (122, 84, 52)
WOOD_LO = (58, 38, 26)


def jitter(c, a=4):
    d = rng.randint(-a, a)
    return tuple(max(0, min(255, v + d)) for v in c)


def rect(img, x, y, w, h, c, a=0):
    for i in range(x, x + w):
        for j in range(y, y + h):
            if 0 <= i < img.width and 0 <= j < img.height:
                img.putpixel((i, j), jitter(c, a) + (255,) if len(c) == 3 else c)


def glow(img, cx, cy, r, colour, strength):
    for x in range(img.width):
        for y in range(img.height):
            d = math.hypot(x - cx, (y - cy) * 1.2)
            if d < r:
                k = (1 - d / r) ** 2 * strength
                p = img.getpixel((x, y))
                img.putpixel((x, y), tuple(int(p[i] + (colour[i] - p[i]) * k) for i in range(3)) + (255,))


def bottle(img, x, base, h, c):
    rect(img, x, base - h, 3, h, c, 6)
    rect(img, x + 1, base - h - 2, 1, 2, c)
    img.putpixel((x, base - h + 1), jitter(tuple(min(255, v + 60) for v in c)) + (255,))   # glint


def jar(img, x, base, c):
    rect(img, x, base - 4, 4, 4, c, 6)
    rect(img, x, base - 5, 4, 1, GOLD_LO)


def books(img, x, base, n):
    cols = [CRIMSON, (40, 60, 110), (60, 90, 50), (90, 60, 30), CRIMSON_LO]
    for k in range(n):
        h = rng.randint(5, 7)
        rect(img, x + k * 2, base - h, 2, h, cols[(k + x) % len(cols)], 6)
        img.putpixel((x + k * 2, base - h + 1), GOLD + (255,))


def shelf(img, y, x0, x1):
    rect(img, x0, y, x1 - x0, 1, SHELF_HI)
    rect(img, x0, y + 1, x1 - x0, 2, SHELF, 4)
    rect(img, x0, y + 3, x1 - x0, 1, SHELF_LO)
    for bx in (x0 + 2, x1 - 4):                                                   # gold brackets
        rect(img, bx, y + 4, 2, 2, GOLD_LO)


def wall():
    img = Image.new("RGBA", (160, 90), NIGHT + (255,))
    for x in range(0, 160, 8):                                                     # vertical planks
        rect(img, x, 0, 8, 90, PLANK if (x // 8) % 2 else PLANK_HI, 3)
        rect(img, x, 0, 1, 90, SEAM)
    rect(img, 0, 0, 160, 3, (20, 14, 22))                                          # gilded beam at the top
    rect(img, 0, 3, 160, 1, GOLD_LO)
    rect(img, 0, 4, 160, 1, GOLD)
    # left shelves: bottles, jars, books
    for y in (24, 42):
        shelf(img, y, 4, 58)
    for k, (x, h, c) in enumerate([(7, 6, CRIMSON), (12, 8, TEAL), (17, 5, AMBER), (22, 7, VIOLET), (27, 6, CRIMSON_HI)]):
        bottle(img, x, 24, h, c)
    jar(img, 34, 24, (90, 110, 60)); jar(img, 40, 24, PAPER); books(img, 47, 24, 5)
    books(img, 7, 42, 6); jar(img, 21, 42, AMBER); bottle(img, 28, 42, 7, TEAL); bottle(img, 33, 42, 5, CRIMSON); jar(img, 39, 42, VIOLET)
    rect(img, 47, 38, 8, 4, GOLD_LO); rect(img, 48, 37, 6, 1, GOLD)                # a small chest
    # right shelves
    for y in (24, 42):
        shelf(img, y, 102, 156)
    books(img, 105, 24, 7); bottle(img, 121, 24, 8, AMBER); bottle(img, 126, 24, 6, TEAL); jar(img, 132, 24, CRIMSON); bottle(img, 140, 24, 7, VIOLET)
    jar(img, 146, 24, PAPER)
    bottle(img, 106, 42, 6, CRIMSON); bottle(img, 111, 42, 8, VIOLET); jar(img, 117, 42, (90, 110, 60)); books(img, 125, 42, 5); bottle(img, 140, 42, 5, AMBER)
    rect(img, 145, 36, 7, 6, GOLD_LO); rect(img, 146, 37, 5, 4, (60, 44, 20))      # a framed coin case
    rect(img, 148, 38, 1, 1, GOLD_HI); rect(img, 147, 39, 1, 1, GOLD)
    # a hanging crimson banner behind him, gold-edged, with a token
    rect(img, 66, 5, 28, 38, (96, 20, 30), 4)                                      # darker than the cloth, so he stands out
    rect(img, 66, 5, 1, 38, GOLD_LO); rect(img, 93, 5, 1, 38, GOLD_LO)
    for x in range(66, 94):                                                          # swallowtail hem
        if abs(x - 79.5) > 2:
            rect(img, x, 43, 1, 2, CRIMSON_LO)
    rect(img, 77, 16, 6, 6, GOLD); rect(img, 78, 17, 4, 4, GOLD_HI); rect(img, 79, 18, 2, 2, GOLD_LO)
    # wall sconces with candle light
    for x in (61, 98):
        rect(img, x, 30, 2, 4, GOLD_LO); rect(img, x, 28, 2, 2, AMBER)
    glow(img, 62, 28, 26, AMBER, 0.28)
    glow(img, 99, 28, 26, AMBER, 0.28)
    glow(img, 80, 60, 70, (60, 30, 40), 0.35)                                      # warm dark falloff toward the counter
    return img


def counter():
    img = Image.new("RGBA", (160, 34), (0, 0, 0, 0))
    rect(img, 0, 0, 160, 1, WOOD_HI)                                               # the top, a thick slab
    rect(img, 0, 1, 160, 4, WOOD, 4)
    rect(img, 0, 5, 160, 1, WOOD_LO)
    rect(img, 0, 6, 160, 1, GOLD)                                                  # gold trim under the lip
    rect(img, 0, 7, 160, 27, (34, 24, 22), 3)                                      # front panels
    for x in range(0, 160, 20):
        rect(img, x + 2, 10, 16, 21, (44, 31, 27), 3)
        rect(img, x + 2, 10, 16, 1, (58, 42, 34))
        rect(img, x + 2, 30, 16, 1, (24, 16, 15))
    # a crimson cloth over the middle with a gold token
    rect(img, 64, 6, 32, 20, CRIMSON, 5)
    rect(img, 64, 6, 32, 1, CRIMSON_HI)
    for x in range(64, 96, 4):
        rect(img, x, 26, 2, 2, GOLD)                                               # tassels
    rect(img, 77, 12, 6, 6, GOLD); rect(img, 78, 13, 4, 4, GOLD_HI); rect(img, 79, 14, 2, 2, GOLD_LO)
    return img


def token():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(16):
        for y in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d <= 7.4:
                c = GOLD_LO if d > 6.2 else (GOLD_HI if (x + y) < 11 else GOLD)
                img.putpixel((x, y), c + (255,))
    # a little tower: three stacked blocks, darker, in the middle
    for (x, y, w, h) in ((6, 4, 4, 2), (5, 6, 6, 3), (4, 9, 8, 3)):
        rect(img, x, y, w, h, GOLD_LO)
    rect(img, 7, 10, 2, 2, (90, 60, 20))                                           # the door
    img.putpixel((7, 3), GOLD_LO + (255,)); img.putpixel((8, 3), GOLD_LO + (255,))
    return img


def portraits():
    """The merchant standing and bowing, on a clear background, from his own model (tools/gilded_merchant/build.py)."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools" / "gilded_merchant"))
    import build as b
    b.build_merchant()
    b.check()
    atlas, glow_, rects = b.build_atlas()
    clear = (0, 0, 0, 0)
    # Facing the customer square on (azimuth 0), drawn at the size the counter shows him (410 x 568 on the 1280 x 720 page).
    view = dict(el=5, px=10.25, out=(410, 568), centre=(0, 20.5, 0), bg=clear)
    b.render(atlas, glow_, rects, 0, pose=b.idle_pose(0.0), **view).save(OUT / "merchant_full.png")
    b.render(atlas, glow_, rects, 0, pose=b.greet_pose(b.GREET_LEN * 0.45), **view).save(OUT / "merchant_bow.png")
    # He is drawn as a cut-out puppet (P59): one picture per moving part, each turned and lifted by the bridge's keyframes, which the client
    # plays at its own frame rate (a flipbook could not reach 60 fps inside the bridge's limits). A part is hidden from a render by shrinking its
    # cubes to nothing, so every cube keeps its index into the texture atlas. merchant_layers.json says where each part sits and turns.
    import dataclasses
    import numpy as np
    for old in list(OUT.glob("merchant_idle_*.png")) + list(OUT.glob("merchant_bow_*.png")) + list(OUT.glob("merchant_layer_*.png")):
        old.unlink()
    every = list(b.ELS)
    W, H = view["out"]
    el, a0 = math.radians(view["el"]), 0.0
    cam = np.array([math.sin(a0) * math.cos(el), math.sin(el), -math.cos(a0) * math.cos(el)])
    right = np.cross(-cam, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, -cam)
    worlds = b.bone_world({})

    def project(bone):
        m = worlds[bone]
        p = m[:3, :3] @ np.array(b.BONES[bone].pivot, float) + m[:3, 3]
        rel = p - np.array(view["centre"], float)
        return float(rel @ right) * view["px"] + W / 2, -float(rel @ up) * view["px"] + H / 2

    meta = {"spot": [W, H], "layers": []}
    for name, bones, pivot in LAYERS:
        b.ELS[:] = [e if e.bone in bones else dataclasses.replace(e, to=e.frm) for e in every]
        im = b.render(atlas, glow_, rects, 0, **view)
        box = im.getchannel("A").getbbox()
        if pivot:
            # centred on the joint it turns about, so the bridge can turn the picture about its own centre
            px_, py_ = project(pivot)
            half_w = max(px_ - box[0], box[2] - px_) + 2
            half_h = max(py_ - box[1], box[3] - py_) + 2
            box = (round(px_ - half_w), round(py_ - half_h), round(px_ + half_w), round(py_ + half_h))
        else:
            box = (box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1)
        im.crop(box).save(OUT / f"merchant_layer_{name}.png", optimize=True)
        meta["layers"].append({"name": name, "x": box[0], "y": box[1], "w": box[2] - box[0], "h": box[3] - box[1], "turns": bool(pivot)})
    b.ELS[:] = every
    (OUT / "merchant_layers.json").write_text(json.dumps(meta, indent=1) + "\n")


# His parts, back to front, and the joint each turns about (None: it only moves with the breath).
LAYERS = [
    ("body", {"legs", "body", "torso", "mantle", "cape", "pack", "arm_r", "forearm_r", "arm_l", "forearm_l", "root"}, None),
    ("purse", {"purse"}, "purse"),
    ("head", {"head"}, "head"),
    ("scale", {"scale"}, "scale"),
    ("staff", {"staff"}, None),
    ("lantern", {"lantern"}, "lantern"),
]


def main():
    import sys
    OUT.mkdir(exist_ok=True)
    wall().save(OUT / "shop_wall.png")
    counter().save(OUT / "shop_counter.png")
    token().save(OUT / "token.png")
    if "--portraits" in sys.argv:
        portraits()
    print("wrote", OUT)
    # The bridge pack takes them as tex:cobbletowers/merchant_*.png, token.png (SnobbleGUIOverhaul, textures/gui/pages/cobbletowers/).
    if "--bridge" in sys.argv:
        import shutil
        dest = Path(sys.argv[sys.argv.index("--bridge") + 1])
        for src, name in (("shop_wall.png", "merchant_wall.png"), ("shop_counter.png", "merchant_counter.png"), ("token.png", "tower_token.png"),
                          ("mystery_bag.png", "merchant_bag.png")):        # he is drawn from the idle and bow frames below
            shutil.copyfile(OUT / src, dest / name)
        for pattern in ("merchant_idle_*.png", "merchant_bow_*.png", "merchant_layer_*.png"):
            for stale in dest.glob(pattern):
                stale.unlink()
        for layer in OUT.glob("merchant_layer_*.png"):
            shutil.copyfile(layer, dest / layer.name)
        print("copied to", dest)
    # the frame box and counts, for the bridge's layout generator (tools/gen_ui_towers.py reads tools/merchant_frames.json)
    if "--bridge-tools" in sys.argv:
        import shutil
        shutil.copyfile(OUT / "merchant_layers.json", Path(sys.argv[sys.argv.index("--bridge-tools") + 1]) / "merchant_layers.json")


if __name__ == "__main__":
    main()
