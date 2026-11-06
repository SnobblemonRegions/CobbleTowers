"""Builds the route-map icon models from tools/vendor/route_icons.json (the approved chunky set, 2026-11-06).

  python tools/vendor/make_route_icons.py

Writes, under src/main/resources/assets/cobbletowers/:
  models/item/route/<name>.json    one box model per stop (the boxes are the data, so a change is a diff, not a binary)
  textures/item/route/palette.png  a 32 x 32 swatch sheet: every colour used is a 2 x 2 px cell, and each box face points at its cell

A box is [x, y, z, w, d, h, colour, flag] in the preview's axes (z up; the preview looks at the +x and +y faces); flag "e" marks a glowing part, which
Minecraft cannot light per part, so it is only kept for reference. ORIENTATION maps the preview's axes onto the model's, so the part of the icon that faces
the viewer in the preview is the part that does in game; it is the one thing to change if a rig test shows an icon turned the wrong way.
"""
import json
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "cobbletowers")
CELLS = 16                    # 16 x 16 cells of 2 x 2 px = 32 x 32 px; model uv runs 0..16 over the whole texture, so one cell is one uv unit
TEXTURE = "cobbletowers:item/route/palette"

# preview axes (x, y, z) -> model axes: model x east, y up, z south. Preview x faces the viewer's right, y the viewer's left.
ORIENTATION = "A"   # A: preview x -> east, preview y -> south


def to_model(x, y, z):
    if ORIENTATION == "A":
        return (x, z, y)
    if ORIENTATION == "B":   # preview x -> north, preview y -> east
        return (y, z, 16 - x)
    raise ValueError(ORIENTATION)


def box_corners(x, y, z, w, d, h):
    a = to_model(x, y, z)
    b = to_model(x + w, y + d, z + h)
    return [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]


def hexc(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def png(w, h, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def main():
    models = json.load(open(os.path.join(HERE, "route_icons.json"), encoding="utf-8"))
    colours = sorted({b[6] for boxes in models.values() for b in boxes})
    if len(colours) > CELLS * CELLS:
        raise SystemExit("too many colours for one palette: %d" % len(colours))
    cell = {c: (i % CELLS, i // CELLS) for i, c in enumerate(colours)}

    rows = [[(0, 0, 0, 0)] * (CELLS * 2) for _ in range(CELLS * 2)]
    for c, (cx, cy) in cell.items():
        r, g, b = hexc(c)
        for dy in range(2):
            for dx in range(2):
                rows[cy * 2 + dy][cx * 2 + dx] = (r, g, b, 255)
    tex_dir = os.path.join(ASSETS, "textures", "item", "route")
    os.makedirs(tex_dir, exist_ok=True)
    with open(os.path.join(tex_dir, "palette.png"), "wb") as f:
        f.write(png(CELLS * 2, CELLS * 2, rows))

    model_dir = os.path.join(ASSETS, "models", "item", "route")
    os.makedirs(model_dir, exist_ok=True)
    for name, boxes in models.items():
        elements = []
        for x, y, z, w, d, h, colour, flag in boxes:
            frm, to = box_corners(x, y, z, w, d, h)
            cx, cy = cell[colour]
            face = {"uv": [cx, cy, cx + 1, cy + 1], "texture": "#p"}
            elements.append({"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to],
                             "faces": {side: dict(face) for side in ("north", "east", "south", "west", "up", "down")}})
        model = {
            "textures": {"p": TEXTURE, "particle": TEXTURE},
            "elements": elements,
            "gui_light": "side",
            "display": {
                "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
                "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
            },
        }
        with open(os.path.join(model_dir, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(model, f, separators=(",", ":"))
            f.write("\n")
    print("wrote %d models and a %d-colour palette" % (len(models), len(colours)))


if __name__ == "__main__":
    main()
