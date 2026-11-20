"""Generates the Split Seal route door (P59): per-tower textures and models, and the lane icon cut into halves.

The door is drawn by the concept board's own code (concept_board.Kit), so what the board shows is what the pack ships. Models use the Halloween
grand door's model space so DoorPose.grand drives them unchanged: 4 model units a block, x 0-20 across the 5-wide opening, y 0-24 floor to the
top of the lintel, front towards -Z. Model z 0 is the middle of the two-block wall; its hall face is z -4.

Seen from the hall, model x runs right to left (the pose turns the model half a turn), and a north face's texture runs from x max at the
viewer's left. So a north face over model x [a, b] samples image columns (20 - b) * 4 to (20 - a) * 4 of the door image, unmirrored.

Run: py -3.14 -I tools/gate_door/gen_seal_door.py
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from PIL import Image, ImageDraw  # noqa: E402

import concept_board as cb  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/cobbletowers"
MODELS = ASSETS / "models/gate_door"
TEXTURES = ASSETS / "textures/item/gate_door"

# Model ids by tower; the mod picks one with SealDoorModels.trimOf. Keep in step with SealDoorModels.TRIMS.
TRIM_IDS = {"neutral": "Neutral", "duskvale": "Duskvale", "rootvale": "Rootvale", "tideforge": "Tideforge", "poke": "Poke Tower",
            "hollows_eve": "Hollow's Eve"}
cb.TRIMS.setdefault("Hollow's Eve", dict(leaf="#2b2730", band="#e07a1f", frame="#3a3540", accent="#e07a1f", glow="#ffb347",
                                         note="blackstone, ember bands, lantern glow"))

# The route stops, in RouteMap.Stop order (the icon sheet's order too).
STOPS = ["start", "battle", "elite", "rest", "shrine", "forge", "library", "vault", "pool", "merchant", "trick_or_treat", "boss"]

ATLAS = 128                      # the trim texture: the door image at (0, 0), 80 x 96, then solid swatches
IMG_W, IMG_H = 80, 96
SW = {"frame_edge": (80, 0), "leaf_edge": (88, 0), "band": (96, 0), "seal_edge": (104, 0), "glow": (112, 0)}

SEAL_X, SEAL_Y, SEAL_R = 10.0, 11.5, 3.75      # the seal's centre and radius in model units
ICON_HALF = 2.25                               # the icon is 18 texels: 4.5 units square
GLOW_R = 4.15


def door_image(trim: dict) -> Image.Image:
    """The shut door as the hall sees it, 80 x 96 texels: the board's frame, leaves, bands, seam and seal, without the icon."""
    img = Image.new("RGBA", (cb.TW, cb.TH))
    glow = Image.new("RGB", (cb.TW, cb.TH))
    import random
    k = cb.Kit(img, glow, trim, Image.new("RGBA", (1, 1)), random.Random(7))
    k.frame_parts()
    k.lintel_slit(False)
    k.leaf_fill(cb.GX0, cb.GX1)
    k.bands(cb.GX0, cb.GX1)
    mid = (cb.GX0 + cb.GX1) // 2
    k.d.line((mid, cb.GY0, mid, cb.DY1 - 1), fill=cb.shade(k.leaf, 0.45))
    cy = cb.GY0 + 34
    k.disc(mid, cy, 15, k.band if not k.poke else cb.hexc("#1b1b1f"), cb.shade(k.accent, 0.5) if not k.poke else cb.hexc("#f2f2f0"), False)
    return img.crop((cb.DX0, cb.DY0, cb.DX1, cb.DY1))


def atlas(trim: dict) -> Image.Image:
    out = Image.new("RGBA", (ATLAS, ATLAS))
    out.alpha_composite(door_image(trim), (0, 0))
    d = ImageDraw.Draw(out)
    colours = {"frame_edge": cb.shade(cb.hexc(trim["frame"]), 0.8), "leaf_edge": cb.shade(cb.hexc(trim["leaf"]), 0.7),
               "band": cb.hexc(trim["band"]) if not trim.get("pokeball") else cb.hexc("#1b1b1f"), "seal_edge": cb.shade(cb.hexc(trim["band"]), 0.7),
               "glow": cb.hexc(trim["glow"])}
    for name, (x, y) in SW.items():
        d.rectangle((x, y, x + 7, y + 7), fill=colours[name] + (255,))
    return out


# ---------------------------------------------------------------- model building

def uv(px0, py0, px1, py1, size=(ATLAS, ATLAS)):
    return [round(px0 * 16 / size[0], 5), round(py0 * 16 / size[1], 5), round(px1 * 16 / size[0], 5), round(py1 * 16 / size[1], 5)]


def swatch(name):
    x, y = SW[name]
    return uv(x + 2, y + 2, x + 5, y + 5)


def front_uv(a, b, y0, y1):
    """The door-image region a north face over model x [a, b], y [y0, y1] shows."""
    return uv((20 - b) * 4, (24 - y1) * 4, (20 - a) * 4, (24 - y0) * 4)


def box(name, frm, to, front, edge, tex="#t"):
    faces = {"north": {"uv": front, "texture": tex}}
    for f in ("south", "east", "west", "up", "down"):
        faces[f] = {"uv": edge, "texture": tex}
    return {"name": name, "from": [round(v, 4) for v in frm], "to": [round(v, 4) for v in to], "faces": faces}


def disc_rows(cx, cy, r, steps=9):
    """A disc as horizontal slabs: (y0, y1, half width) from the bottom up."""
    rows, h = [], 2 * r / steps
    for i in range(steps):
        y0 = cy - r + i * h
        mid = y0 + h / 2 - cy
        rows.append((y0, y0 + h, math.sqrt(max(0.0, r * r - mid * mid))))
    return rows


def frame_model():
    els = [
        box("jamb.left", (0, 0, -4.25), (4, 20, 4), front_uv(0, 4, 0, 20), swatch("frame_edge")),
        box("jamb.right", (16, 0, -4.25), (20, 20, 4), front_uv(16, 20, 0, 20), swatch("frame_edge")),
        box("lintel", (0, 20, -4.25), (20, 24, 4), front_uv(0, 20, 20, 24), swatch("frame_edge")),
    ]
    return els


def frame_glow_model():
    # the slit in the lintel: the board draws it 2 texels high at 7 texels below the lintel top, between the jambs' inner edges plus 4 texels
    y1 = 24 - 7 / 4
    return [box("lintel.slit", (5, y1 - 0.5, -4.4), (15, y1, -4.25), swatch("glow"), swatch("glow"))]


def leaf_model(side: int):
    """side -1 is model x 4-10, +1 is 10-16. The seal half sits proud of the leaf, the seam glow of the other model lights its edge."""
    a, b = (4, 10) if side < 0 else (10, 16)
    els = [box("leaf", (a, 0, -0.5), (b, 20, 0.5), front_uv(a, b, 0, 20), swatch("leaf_edge"))]
    for i, (y0, y1, w) in enumerate(disc_rows(SEAL_X, SEAL_Y, SEAL_R)):
        xa, xb = (SEAL_X - w, SEAL_X) if side < 0 else (SEAL_X, SEAL_X + w)
        if xb - xa < 0.05:
            continue
        els.append(box(f"seal.{i}", (xa, y0, -0.9), (xb, y1, -0.5), front_uv(xa, xb, y0, y1), swatch("seal_edge")))
    return els


def leaf_glow_model(side: int):
    seam = (9.75, 10) if side < 0 else (10, 10.25)
    g = swatch("glow")
    els = [box("seam.low", (seam[0], 0, -0.58), (seam[1], SEAL_Y - GLOW_R, -0.5), g, g),
           box("seam.high", (seam[0], SEAL_Y + GLOW_R, -0.58), (seam[1], 20, -0.5), g, g)]
    for i, (y0, y1, w) in enumerate(disc_rows(SEAL_X, SEAL_Y, GLOW_R, 11)):
        xa, xb = (SEAL_X - w, SEAL_X) if side < 0 else (SEAL_X, SEAL_X + w)
        if xb - xa < 0.05:
            continue
        els.append(box(f"ring.{i}", (xa, y0, -0.8), (xb, y1, -0.5), g, g))
    return els


def icon_model(index: int, side: int):
    """Half of the lane icon on the seal: a thin plate over model x [SEAL_X - 2.25, SEAL_X] (side -1) or [SEAL_X, SEAL_X + 2.25]."""
    a, b = (SEAL_X - ICON_HALF, SEAL_X) if side < 0 else (SEAL_X, SEAL_X + ICON_HALF)
    left_edge = SEAL_X + ICON_HALF                      # model x at the icon's viewer-left column
    c0, c1 = (left_edge - b) * 4, (left_edge - a) * 4   # icon columns, 0-18
    sheet = (234, 18)
    front = uv(index * 18 + c0, 0, index * 18 + c1, 18, sheet)
    clear = uv(index * 18, 0, index * 18 + 0.01, 0.01, sheet)
    el = {"name": "icon", "from": [a, SEAL_Y - ICON_HALF, -0.97], "to": [b, SEAL_Y + ICON_HALF, -0.92],
          "faces": {"north": {"uv": front, "texture": "#i"}, "south": {"uv": clear, "texture": "#i"}}}
    return {"textures": {"i": "cobbletowers:item/gate_door/icons", "particle": "cobbletowers:item/gate_door/icons"}, "elements": [el]}


def model(texture: str, elements):
    return {"textures": {"t": texture, "particle": texture}, "elements": elements}


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8")


def main() -> int:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    parts = {"frame": frame_model(), "frame_glow": frame_glow_model(), "left": leaf_model(-1), "left_glow": leaf_glow_model(-1),
             "right": leaf_model(1), "right_glow": leaf_glow_model(1)}
    for trim_id, name in TRIM_IDS.items():
        atlas(cb.TRIMS[name]).save(TEXTURES / f"{trim_id}.png")
        for part, els in parts.items():
            write(MODELS / trim_id / f"{part}.json", model(f"cobbletowers:item/gate_door/{trim_id}", els))
    Image.open(cb.ICONS).convert("RGBA").save(TEXTURES / "icons.png")
    for i, stop in enumerate(STOPS):
        for side, tag in ((-1, "left"), (1, "right")):
            write(MODELS / "icon" / f"{stop}_{tag}.json", icon_model(i, side))
    print(f"{len(TRIM_IDS)} trims x {len(parts)} parts, {len(STOPS) * 2} icon halves -> {MODELS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
