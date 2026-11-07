"""Builds the image characters of the route book: the stop icons, their small versions, state rings and the connector lines, plus the font that maps them.

  python tools/vendor/make_route_book_font.py

A written book can only show text, but its text can use a font from the resource pack whose characters are pictures. This makes that font:

  textures/font/route_icons.png   the 12 stop icons, 18 x 18 each, drawn from the same textured box models as the in-world icons (a flat isometric
                                  picture of each, with a dark outline so it reads on a parchment page)
  textures/font/route_mini.png    the same at 9 x 9, for a line of text
  textures/font/route_rings.png   one 22 x 22 ring, tinted by the text colour for a stop's state (white in the picture, so the colour is the tint)
  textures/font/route_lines.png   the connectors between two rows: straight, down-right and down-left, 32 x 13 each
  font/route.json                 the characters, plus "space" characters that move the text cursor by exactly 1, 2, 4 ... 64 pixels either way,
                                  which is how the book lays a picture at an exact spot

Vertical placement is by line (a line is 9 px, the baseline sits 7 px down it), so a glyph's ascent is chosen to centre it on the top of its line, or, for
a connector, to fill the one line between two rows of icons and reach 2 px into each (the icons' outline margin hides the ends). A map row is therefore
three lines: the icon's top half, its own line, and the connector line below it, which is five rows to a page. Horizontal placement needs each glyph's advance to be known: Minecraft takes it from the widest non-transparent column of the
glyph, plus one, so every glyph here carries one nearly invisible pixel (alpha 1) in its last column, and the advance is always its cell width + 1.

The character values are mirrored in RouteBookIcons.java and checked against this file by RouteBookIconsTest.
"""
import importlib.util
import json
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "cobbletowers")

STOPS = ["start", "battle", "elite", "rest", "shrine", "forge", "library", "vault", "pool", "shop", "treat", "boss"]   # RouteMap.Stop order
ICON_BASE, MINI_BASE, FOG, RING, LINES = 0xE000, 0xE040, 0xE020, 0xE060, 0xE080
SPACE_POS, SPACE_NEG = 0xE100, 0xE110
ICON, MINI, RING_SIZE = 18, 9, 22
TILE_W, TILE_H = 32, 13
OUTLINE = (0x2B, 0x1D, 0x12)

spec = importlib.util.spec_from_file_location("make_route_icons", os.path.join(HERE, "make_route_icons.py"))
MRI = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MRI)
assert MRI.ORIENTATION == "A", "the flat renderer below assumes the model orientation A"


# ------------------------------------------------------------------ the textured isometric renderer
def iso_sort(boxes):
    n, eps = len(boxes), 1e-6
    behind = lambda a, b: a[0] + a[3] <= b[0] + eps or a[1] + a[4] <= b[1] + eps or a[2] + a[5] <= b[2] + eps
    key = lambda m: m[0] + m[3] / 2 + m[1] + m[4] / 2 + m[2] + m[5] / 2
    after = [[] for _ in range(n)]
    need = [0] * n
    for a in range(n):
        for b in range(n):
            if a != b and behind(boxes[a], boxes[b]) and not behind(boxes[b], boxes[a]):
                after[a].append(b)
                need[b] += 1
    out, done = [], [False] * n
    while len(out) < n:
        pick = -1
        for a in range(n):
            if not done[a] and need[a] == 0 and (pick < 0 or key(boxes[a]) < key(boxes[pick])):
                pick = a
        if pick < 0:
            for a in range(n):
                if not done[a] and (pick < 0 or (need[a], key(boxes[a])) < (need[pick], key(boxes[pick]))):
                    pick = a
        done[pick] = True
        out.append(pick)
        for b in after[pick]:
            need[b] -= 1
    return out


def face_patch(name, i, face, box):
    """The painted pixels of one face of one box: the same texturing the in-world model uses."""
    x, y, z, w, d, h, colour, flag = box
    frm, to = MRI.box_corners(x, y, z, w, d, h)
    pw, ph = MRI.face_sizes(frm, to)[face]
    mat = MRI.material_of(name, colour, flag)
    base = MRI.hexc(colour)
    seed = int(MRI.hash01(len(name), i, ord(face[0])) * 1e6)
    return [[MRI.paint(mat, base, px, py, pw, ph, seed) for px in range(pw)] for py in range(ph)]


def render(name, boxes, target, ss=8):
    """A flat isometric picture of one model, target x target px, with a one pixel dark outline. Returns rows of (r, g, b, a)."""
    size = target * ss
    scale = (target - 2) * ss / 34.0
    cx, cy = size / 2.0, size / 2.0
    P = lambda x, y, z: (cx + (x - 8 - (y - 8)) * 0.866 * scale, cy + ((x - 8) + (y - 8)) * 0.5 * scale - (z - 8) * scale)
    buf = [[None] * size for _ in range(size)]
    for idx in iso_sort(boxes):
        x, y, z, w, d, h, colour, flag = boxes[idx]
        e = flag == "e"
        faces = (
            ("south", P(x, y + d, z + h), P(x + w, y + d, z + h), P(x, y + d, z), 0.88 if not e else 1.0),     # left in the picture
            ("east", P(x + w, y, z + h), P(x + w, y + d, z + h), P(x + w, y, z), 0.64 if not e else 0.9),     # right
            ("up", P(x, y, z + h), P(x + w, y, z + h), P(x, y + d, z + h), 1.0 if not e else 1.1),            # top
        )
        for face, o, u_end, v_end, light in faces:
            patch = face_patch(name, idx, face, boxes[idx])
            ph, pw = len(patch), len(patch[0])
            ux, uy = u_end[0] - o[0], u_end[1] - o[1]
            vx, vy = v_end[0] - o[0], v_end[1] - o[1]
            det = ux * vy - uy * vx
            if abs(det) < 1e-9:
                continue
            xs = [o[0], u_end[0], v_end[0], u_end[0] + vx, ]
            ys = [o[1], u_end[1], v_end[1], u_end[1] + vy]
            for py in range(max(0, int(min(ys))), min(size, int(max(ys)) + 1)):
                for pxl in range(max(0, int(min(xs))), min(size, int(max(xs)) + 1)):
                    dx, dy = pxl + 0.5 - o[0], py + 0.5 - o[1]
                    s = (dx * vy - dy * vx) / det
                    t = (ux * dy - uy * dx) / det
                    if 0 <= s < 1 and 0 <= t < 1:
                        r, g, b = patch[min(ph - 1, int(t * ph))][min(pw - 1, int(s * pw))]
                        buf[py][pxl] = (min(255, int(r * light)), min(255, int(g * light)), min(255, int(b * light)))
    # area-average down to the target, keeping a pixel only if it is mostly covered, then the outline
    out = [[(0, 0, 0, 0)] * target for _ in range(target)]
    for ty in range(target):
        for tx in range(target):
            rs = gs = bs = n = 0
            for sy in range(ss):
                for sx in range(ss):
                    p = buf[ty * ss + sy][tx * ss + sx]
                    if p:
                        rs += p[0]; gs += p[1]; bs += p[2]; n += 1
            if n >= ss * ss * 0.5:
                out[ty][tx] = (rs // n, gs // n, bs // n, 255)
    edge = [[False] * target for _ in range(target)]
    for ty in range(target):
        for tx in range(target):
            if out[ty][tx][3] == 0:
                for ddx, ddy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = tx + ddx, ty + ddy
                    if 0 <= nx < target and 0 <= ny < target and out[ny][nx][3] == 255:
                        edge[ty][tx] = True
                        break
    for ty in range(target):
        for tx in range(target):
            if edge[ty][tx]:
                out[ty][tx] = OUTLINE + (255,)
    return out


# ------------------------------------------------------------------ hand-drawn glyphs
def blank(w, h):
    return [[(0, 0, 0, 0)] * w for _ in range(h)]


def pin(img):
    """The nearly invisible pixel that fixes a glyph's advance: Minecraft measures a glyph to its last non-transparent column."""
    h, w = len(img), len(img[0])
    if all(img[y][w - 1][3] == 0 for y in range(h)):
        img[h - 1][w - 1] = (255, 255, 255, 1)
    return img


def ring():
    img = blank(RING_SIZE, RING_SIZE)
    c = (RING_SIZE - 1) / 2.0
    for y in range(RING_SIZE):
        for x in range(RING_SIZE):
            d = math.hypot(x - c, y - c)
            if 8.6 <= d <= 10.6:
                img[y][x] = (255, 255, 255, 255)
    return pin(img)


FONT5 = {"?": ["01110", "10001", "00001", "00110", "00100", "00000", "00100"]}


def fog():
    """A veiled stop: a dashed ring with a question mark."""
    img = blank(ICON, ICON)
    c = (ICON - 1) / 2.0
    for y in range(ICON):
        for x in range(ICON):
            d = math.hypot(x - c, y - c)
            ang = math.atan2(y - c, x - c)
            if 7.2 <= d <= 8.8 and int((ang + math.pi) / (2 * math.pi) * 14) % 2 == 0:
                img[y][x] = (255, 255, 255, 255)
    for ry, row in enumerate(FONT5["?"]):
        for rx, ch in enumerate(row):
            if ch == "1":
                img[5 + ry][6 + rx] = (255, 255, 255, 255)
    return pin(img)


def lines():
    """straight, down-right, down-left: each a 2 px line across a 32 x 13 tile, white so the text colour tints it."""
    tiles = []
    for kind in ("straight", "down_right", "down_left"):
        img = blank(TILE_W, TILE_H)
        for y in range(TILE_H):
            if kind == "straight":
                cols = [TILE_W // 2 - 1, TILE_W // 2]
            else:
                x0 = (TILE_W - 1) * y / (TILE_H - 1)
                x0 = x0 if kind == "down_right" else (TILE_W - 1) - x0
                cols = sorted({int(x0), int(x0) + 1})
            # a diagonal moves 32 px over 13 rows, so each row covers about two columns: fill the span so the line has no gaps
            if kind != "straight":
                a = (TILE_W - 1) * (y - 0.5) / (TILE_H - 1)
                b = (TILE_W - 1) * (y + 0.5) / (TILE_H - 1)
                lo, hi = (a, b) if kind == "down_right" else ((TILE_W - 1) - b, (TILE_W - 1) - a)
                cols = list(range(max(0, int(math.floor(lo))), min(TILE_W, int(math.ceil(hi)) + 1)))
            for x in cols:
                if 0 <= x < TILE_W:
                    img[y][x] = (255, 255, 255, 255)
        tiles.append(pin(img))
    return tiles


# ------------------------------------------------------------------ writing
def png(rows):
    h, w = len(rows), len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def strip(images):
    """Cells side by side: one row of equal-sized images."""
    h, w = len(images[0]), len(images[0][0])
    rows = [[] for _ in range(h)]
    for img in images:
        for y in range(h):
            rows[y].extend(img[y])
    return rows


def chars(first, n):
    return "".join(chr(first + i) for i in range(n))


def main():
    models = json.load(open(os.path.join(HERE, "route_icons.json"), encoding="utf-8"))
    icons = [pin(render(name, models[name], ICON)) for name in STOPS]
    minis = [pin(render(name, models[name], MINI, ss=16)) for name in STOPS]
    tex = os.path.join(ASSETS, "textures", "font")
    os.makedirs(tex, exist_ok=True)
    with open(os.path.join(tex, "route_icons.png"), "wb") as f:
        f.write(png(strip(icons + [fog()])))
    with open(os.path.join(tex, "route_mini.png"), "wb") as f:
        f.write(png(strip(minis)))
    with open(os.path.join(tex, "route_rings.png"), "wb") as f:
        f.write(png(ring()))
    with open(os.path.join(tex, "route_lines.png"), "wb") as f:
        f.write(png(strip(lines())))

    advances = {}
    for i in range(7):
        advances[chr(SPACE_POS + i)] = 1 << i
        advances[chr(SPACE_NEG + i)] = -(1 << i)
    font = {"providers": [
        {"type": "bitmap", "file": "cobbletowers:font/route_icons.png", "ascent": 16, "height": ICON, "chars": [chars(ICON_BASE, len(STOPS)) + chr(FOG)]},
        {"type": "bitmap", "file": "cobbletowers:font/route_mini.png", "ascent": 8, "height": MINI, "chars": [chars(MINI_BASE, len(STOPS))]},
        {"type": "bitmap", "file": "cobbletowers:font/route_rings.png", "ascent": 18, "height": RING_SIZE, "chars": [chr(RING)]},
        {"type": "bitmap", "file": "cobbletowers:font/route_lines.png", "ascent": 9, "height": TILE_H, "chars": [chars(LINES, 3)]},
        {"type": "space", "advances": advances},
    ]}
    # the icons strip holds the 12 stops then the fog glyph, so the fog character must follow them in the same row
    font["providers"][0]["chars"] = [chars(ICON_BASE, len(STOPS)) + chr(FOG)]
    os.makedirs(os.path.join(ASSETS, "font"), exist_ok=True)
    with open(os.path.join(ASSETS, "font", "route.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(font, f, indent=1)
        f.write("\n")
    print("wrote %d icons, %d minis, a ring, 3 connectors and the font" % (len(icons), len(minis)))


if __name__ == "__main__":
    main()
