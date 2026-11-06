"""Builds the route-map icon models from tools/vendor/route_icons.json (the approved chunky set, 2026-11-06), with real pixel texturing.

  python tools/vendor/make_route_icons.py

Writes, under src/main/resources/assets/cobbletowers/:
  models/item/route/<name>.json    one box model per stop (the boxes are the data, so a change is a diff, not a binary)
  textures/item/route/atlas.png    every visible face of every box gets its own patch of this sheet, painted by material at vanilla's density
                                   (one pixel per model unit, so a 16 unit face is 16 px like any block): wood grain, stone speckle, brushed metal,
                                   bone, gold, cloth, pumpkin skin, leaf, parchment, crystal facets, water, fire and glow, each with a lit top-left edge
                                   and a shaded bottom-right edge so a box reads as a bevelled block and not a flat colour.

A box is [x, y, z, w, d, h, colour, flag] in the preview's axes (z up; the preview looks at the +x and +y faces); flag "e" marks a glowing part, which
Minecraft cannot light per part, so it is painted bright instead. ORIENTATION maps the preview's axes onto the model's, so the part of the icon that faces
the viewer in the preview is the part that does in game; it is the one thing to change if a rig test shows an icon turned the wrong way.
"""
import colorsys
import json
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "cobbletowers")
TEXTURE = "cobbletowers:item/route/atlas"
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


# ------------------------------------------------------------------ painting
def hash01(*vals):
    """A deterministic value in [0, 1) from integers, so a rebuild paints the same pixels."""
    h = 2166136261
    for v in vals:
        h ^= (int(v) + 0x9E3779B9) & 0xFFFFFFFF
        h = (h * 16777619) & 0xFFFFFFFF
        h ^= h >> 13
    return (h & 0xFFFFFF) / 0x1000000


def noise(x, y, seed, scale=1):
    return hash01(x // scale, y // scale, seed) * 2 - 1


def mul(c, f):
    return tuple(max(0, min(255, int(round(v * f)))) for v in c)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def material_of(model, colour, flag):
    r, g, b = hexc(colour)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    h *= 360
    if model == "treat" and 15 <= h <= 40 and s > .5:
        return "pumpkin"
    if model == "treat" and 80 <= h <= 150:
        return "leaf"
    if flag == "e":
        if 170 <= h <= 235 and s > .3:
            return "water" if model == "pool" else "crystal"
        if 235 < h <= 300:
            return "crystal"
        if model == "rest":
            return "fire"
        return "glow"
    if s < .22:
        if model == "library" and v > .85:
            return "parchment"
        if v > .72:
            return "metal" if b > r + 8 else "bone"
        return "stone"
    if 15 <= h <= 48 and v < .74 and s > .3:
        return "wood"
    if 38 <= h <= 62 and v > .6:
        return "gold"
    if h < 18 or h > 330:
        return "cloth"
    if 235 < h <= 300:
        return "stone"
    if 80 <= h <= 150:
        return "leaf"
    return "stone"


def paint(material, base, x, y, pw, ph, seed):
    """One pixel of a face: the material's pattern over its base colour, then the bevel (lit top-left edge, shaded bottom-right edge)."""
    n = noise(x, y, seed)
    c = base
    if material == "wood":
        row = hash01(y, seed, 1) * 0.10 - 0.05
        seam = 0.82 if y % 5 == 4 else 1.0
        grain = 0.05 * math.sin((x + hash01(y, seed, 2) * 9) * 0.9)
        c = mul(base, (1 + row + grain + n * 0.04) * seam)
    elif material == "stone":
        blot = noise(x, y, seed + 7, 3) * 0.07
        crack = 0.78 if hash01(x, y, seed + 3) > 0.93 else 1.0
        c = mul(base, (1 + blot + n * 0.06) * crack)
    elif material == "metal":
        col = hash01(x, seed, 4) * 0.08 - 0.04
        sheen = 0.10 if (x + y) % 7 == 0 else 0.0
        c = mul(base, 1 + col + sheen + n * 0.03)
    elif material == "gold":
        diag = 0.12 * math.sin((x + y) * 0.7)
        spark = 0.28 if hash01(x, y, seed + 5) > 0.95 else 0.0
        c = mul(base, 1 + diag + spark + n * 0.04)
    elif material == "bone":
        speck = -0.10 if hash01(x, y, seed + 6) > 0.9 else 0.0
        c = mul(base, 1 + speck + noise(x, y, seed + 8, 2) * 0.05 + n * 0.03)
    elif material == "parchment":
        line = -0.06 if y % 3 == 1 else 0.0
        c = mul(base, 1 + line + n * 0.03)
    elif material == "cloth":
        weave = 0.05 if (x + y) % 2 == 0 else -0.03
        c = mul(base, 1 + weave + n * 0.03)
    elif material == "pumpkin":
        rib = 0.10 * math.cos((x + 0.5) / max(1, pw) * math.pi * 2 * 2)
        c = mul(base, 1 + rib + n * 0.04 - (0.06 if y > ph * 0.7 else 0))
    elif material == "leaf":
        c = mul(base, 1 + noise(x, y, seed + 9, 2) * 0.12 + n * 0.05)
    elif material == "crystal":
        band = 0.16 * math.sin((x - y) * 1.1 + seed)
        c = mul(base, 1.05 + band + n * 0.03)
        if hash01(x, y, seed + 2) > 0.94:
            c = mix(c, (255, 255, 255), 0.5)
    elif material == "water":
        wave = 0.14 * math.sin(x * 0.9 + math.sin(y * 0.8) * 2)
        c = mul(base, 1.05 + wave + n * 0.03)
    elif material == "fire":
        t = 1.0 - y / max(1, ph - 1)
        c = mul(base, 0.9 + 0.3 * t + n * 0.07)
    elif material == "glow":
        cx, cy = (pw - 1) / 2, (ph - 1) / 2
        d = math.hypot((x - cx) / max(1, pw / 2), (y - cy) / max(1, ph / 2))
        c = mix(mul(base, 1.18), mul(base, 0.92), min(1.0, d)) if pw > 1 else base
        c = mul(c, 1 + n * 0.03)
    else:
        c = mul(base, 1 + n * 0.05)
    if pw >= 4 and ph >= 4:
        if x == 0 or y == 0:
            c = mul(c, 1.12)
        elif x == pw - 1 or y == ph - 1:
            c = mul(c, 0.84)
    return c


# ------------------------------------------------------------------ atlas packing
def pack(sizes, side):
    """Shelf packing: returns a list of (x, y) in the given order, or None if the sheet is too small."""
    order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0]))
    pos = [None] * len(sizes)
    x = y = shelf = 0
    for i in order:
        w, h = sizes[i]
        if x + w > side:
            x, y, shelf = 0, y + shelf, 0
        if y + h > side:
            return None
        pos[i] = (x, y)
        x += w
        shelf = max(shelf, h)
    return pos


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


def face_sizes(frm, to):
    dx, dy, dz = (to[0] - frm[0]), (to[1] - frm[1]), (to[2] - frm[2])
    px = lambda v: max(1, int(round(v)))
    return {"north": (px(dx), px(dy)), "south": (px(dx), px(dy)), "east": (px(dz), px(dy)), "west": (px(dz), px(dy)), "up": (px(dx), px(dz))}


def main():
    models = json.load(open(os.path.join(HERE, "route_icons.json"), encoding="utf-8"))
    jobs = []      # (model, box index, face, size, material, base colour)
    for name, boxes in models.items():
        for i, (x, y, z, w, d, h, colour, flag) in enumerate(boxes):
            frm, to = box_corners(x, y, z, w, d, h)
            mat = material_of(name, colour, flag)
            for face, size in face_sizes(frm, to).items():
                jobs.append((name, i, face, size, mat, hexc(colour)))
    sizes = [j[3] for j in jobs]
    side = 64
    positions = None
    while positions is None:
        side *= 2
        positions = pack(sizes, side)
    rows = [[(0, 0, 0, 0)] * side for _ in range(side)]
    for (name, i, face, (pw, ph), mat, base), (ox, oy) in zip(jobs, positions):
        seed = int(hash01(len(name), i, ord(face[0])) * 1e6)
        for y in range(ph):
            for x in range(pw):
                r, g, b = paint(mat, base, x, y, pw, ph, seed)
                rows[oy + y][ox + x] = (r, g, b, 255)
    tex_dir = os.path.join(ASSETS, "textures", "item", "route")
    os.makedirs(tex_dir, exist_ok=True)
    with open(os.path.join(tex_dir, "atlas.png"), "wb") as f:
        f.write(png(side, side, rows))
    stale = os.path.join(tex_dir, "palette.png")
    if os.path.exists(stale):
        os.remove(stale)

    where = {(j[0], j[1], j[2]): (pos, j[3]) for j, pos in zip(jobs, positions)}
    k = 16.0 / side
    model_dir = os.path.join(ASSETS, "models", "item", "route")
    os.makedirs(model_dir, exist_ok=True)
    for name, boxes in models.items():
        elements = []
        for i, (x, y, z, w, d, h, colour, flag) in enumerate(boxes):
            frm, to = box_corners(x, y, z, w, d, h)
            faces = {}
            for face in ("north", "south", "east", "west", "up"):
                (ox, oy), (pw, ph) = where[(name, i, face)]
                faces[face] = {"uv": [round(ox * k, 4), round(oy * k, 4), round((ox + pw) * k, 4), round((oy + ph) * k, 4)], "texture": "#p"}
            elements.append({"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces})
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
    print("wrote %d models and a %d x %d atlas holding %d faces" % (len(models), side, side, len(jobs)))


if __name__ == "__main__":
    main()
