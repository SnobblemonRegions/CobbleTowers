"""Builds the art of the route map screen (concept A, the crypt ledger): one wide dark parchment panel painted over a vanilla 9 x 6 chest screen, and the
characters that draw the live part of the map on top of it.

  python tools/vendor/make_route_screen_art.py

A chest screen draws its slots first and its title last, so a title made of picture characters is drawn over everything (the slots included). The server
opens a chest screen whose title is that picture; the real slots are invisible hover targets for the tooltips. Everything here is in "container pixels":
the origin is the top left of the 176 x 222 chest, the title text starts at x = 8, y = 6, and the stop slots sit on the chest's 18 px grid (slot centre =
16 + 18 * column, 26 + 18 * row for the chest rows; 147 / 165 / 183 for the three player inventory rows and 205 for the hotbar).

What it writes (all under assets/cobbletowers):
  textures/font/screen/panel_left.png, panel_right.png   the parchment panel, 330 x 230, in two pieces (a font glyph is at most 256 px)
  textures/font/screen/links_*.png                       the connectors between floors: straight, down and up diagonals (white: the text colour tints them)
  textures/font/screen/digits.png                        the floor numbers
  textures/font/screen/names.png                         the stop names for the choice rows
  textures/font/screen/arrows.png                        the two page arrows
  font/route_screen.json                                 the characters, one provider per vertical position (a glyph's height is set by its provider)
  models/item/route/blank.json, textures/item/route/blank.png   the invisible item the hover slots hold

The character values are mirrored in RouteScreenFont.java and checked against the json by RouteScreenFontTest.
"""
import importlib.util
import json
import os
import random
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "cobbletowers")
TEX = os.path.join(ASSETS, "textures", "font", "screen")

spec = importlib.util.spec_from_file_location("make_route_book_font", os.path.join(HERE, "make_route_book_font.py"))
BOOK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(BOOK)

# ------------------------------------------------------------------ layout (container pixels)
TITLE_X, TITLE_Y = 8, 6
BASELINE = TITLE_Y + 7                  # a glyph's top is BASELINE - ascent
PANEL_X, PANEL_Y, PANEL_W, PANEL_H = -77, -4, 330, 230
PIECE_W = PANEL_W // 2
COLS = [16 + 36 * i for i in range(5)]   # the five floors of a page: slot columns 0, 2, 4, 6, 8
LANES = [44, 80, 116]                    # chest rows 1, 3, 5
HEADER_Y = 26                            # floor numbers
CHOICES = [147, 165, 183]                # the three rows of the player inventory
BUTTON_Y = 205                           # the hotbar
BUTTON_X = [16, 160]
ICON, RING, LINK = 18, 22, 36

STOPS = ["start", "battle", "elite", "rest", "shrine", "forge", "library", "vault", "pool", "shop", "treat", "boss"]
NAMES = ["START", "BATTLE", "ELITE BATTLE", "REST", "SHRINE", "FORGE", "LIBRARY", "VAULT", "MYSTIC POOL", "MERCHANT", "TRICK OR TREAT", "BOSS"]
SHORT = ["START", "BATTLE", "ELITE", "REST", "SHRINE", "FORGE", "LIBRARY", "VAULT", "POOL", "MERCHANT", "TREAT", "BOSS"]
NAME_W = 84

# character blocks (mirrored in RouteScreenFont.java)
PANEL_BASE = 0xE000
ICON_BASE, ICON_STEP = 0xE100, 0x20      # + slot * 0x20 + stop (12 = fog); slots 0..2 lanes, 3..5 choice rows
RING_BASE = 0xE200                       # + lane
STRAIGHT_BASE, DOWN_BASE, UP_BASE = 0xE300, 0xE310, 0xE320
DIGIT_BASE = 0xE400                      # + digit; 10 = dash
NAME_BASE, NAME_STEP = 0xE500, 0x10      # + choice row * 0x10 + stop
ARROW_BASE = 0xE600                      # 0 = back, 1 = next
SPACE_POS, SPACE_NEG = 0xE700, 0xE710

# ------------------------------------------------------------------ palette
PARCH, PARCH_DK, PARCH_LT = (0x2A, 0x21, 0x18), (0x1E, 0x17, 0x10), (0x35, 0x2A, 0x1D)
INK, INK_DIM = (0xE8, 0xDC, 0xC0), (0xA8, 0x9B, 0x80)
GOLD, GREEN, RED = (0xE8, 0xB8, 0x30), (0x4A, 0xDE, 0x80), (0xC0, 0x39, 0x2B)
PURPLE, PURPLE_DK = (0x6B, 0x3F, 0xA0), (0x3A, 0x22, 0x58)
BONE, FLAME, FLAME2, BAT, WEB = (0xD8, 0xCF, 0xB8), (0xF0, 0x8A, 0x1C), (0xFF, 0xD2, 0x4A), (0x0D, 0x0A, 0x12), (0x8A, 0x83, 0x98)
ROW_BG, ROW_EDGE = (0x23, 0x1B, 0x13), (0x3A, 0x2E, 0x20)

# ------------------------------------------------------------------ a 5 x 7 pixel font
GLYPHS = {
    "A": ".###./#...#/#...#/#####/#...#/#...#/#...#", "B": "####./#...#/#...#/####./#...#/#...#/####.",
    "C": ".####/#..../#..../#..../#..../#..../.####", "D": "####./#...#/#...#/#...#/#...#/#...#/####.",
    "E": "#####/#..../#..../####./#..../#..../#####", "F": "#####/#..../#..../####./#..../#..../#....",
    "G": ".####/#..../#..../#..##/#...#/#...#/.###.", "H": "#...#/#...#/#...#/#####/#...#/#...#/#...#",
    "I": "#####/..#../..#../..#../..#../..#../#####", "J": "..###/...#./...#./...#./...#./#..#./.##..",
    "K": "#...#/#..#./#.#../##.../#.#../#..#./#...#", "L": "#..../#..../#..../#..../#..../#..../#####",
    "M": "#...#/##.##/#.#.#/#.#.#/#...#/#...#/#...#", "N": "#...#/##..#/#.#.#/#..##/#...#/#...#/#...#",
    "O": ".###./#...#/#...#/#...#/#...#/#...#/.###.", "P": "####./#...#/#...#/####./#..../#..../#....",
    "Q": ".###./#...#/#...#/#...#/#.#.#/#..#./.##.#", "R": "####./#...#/#...#/####./#.#../#..#./#...#",
    "S": ".####/#..../#..../.###./....#/....#/####.", "T": "#####/..#../..#../..#../..#../..#../..#..",
    "U": "#...#/#...#/#...#/#...#/#...#/#...#/.###.", "V": "#...#/#...#/#...#/#...#/#...#/.#.#./..#..",
    "W": "#...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#", "X": "#...#/#...#/.#.#./..#../.#.#./#...#/#...#",
    "Y": "#...#/#...#/.#.#./..#../..#../..#../..#..", "Z": "#####/....#/...#./..#../.#.../#..../#####",
    "0": ".###./#...#/#..##/#.#.#/##..#/#...#/.###.", "1": "..#../.##../..#../..#../..#../..#../.###.",
    "2": ".###./#...#/....#/...#./..#../.#.../#####", "3": ".###./#...#/....#/..##./....#/#...#/.###.",
    "4": "...#./..##./.#.#./#..#./#####/...#./...#.", "5": "#####/#..../####./....#/....#/#...#/.###.",
    "6": ".###./#..../#..../####./#...#/#...#/.###.", "7": "#####/....#/...#./..#../.#.../.#.../.#...",
    "8": ".###./#...#/#...#/.###./#...#/#...#/.###.", "9": ".###./#...#/#...#/.####/....#/....#/.###.",
    "-": "...../...../...../#####/...../...../.....", ".": "...../...../...../...../...../...../..#..",
    ":": "...../..#../...../...../..#../...../.....", "?": ".###./#...#/....#/...#./..#../...../..#..",
    "!": "..#../..#../..#../..#../..#../...../..#..", "'": "..#../..#../...../...../...../...../.....",
    "/": "....#/....#/...#./..#../.#.../#..../#....", " ": "...../...../...../...../...../...../.....",
}


def glyph_rows(ch):
    return [[c == "#" for c in row] for row in GLYPHS[ch].split("/")]


def text_width(s, scale=1):
    return (len(s) * 6 - 1) * scale if s else 0


# ------------------------------------------------------------------ a small canvas
class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def put(self, x, y, c, a=255):
        if 0 <= x < self.w and 0 <= y < self.h:
            if a >= 255:
                self.px[y][x] = (c[0], c[1], c[2], 255)
            else:
                r, g, b, ba = self.px[y][x]
                if ba == 0:
                    self.px[y][x] = (c[0], c[1], c[2], a)
                else:
                    t = a / 255.0
                    self.px[y][x] = (int(r + (c[0] - r) * t), int(g + (c[1] - g) * t), int(b + (c[2] - b) * t), ba)

    def rect(self, x, y, w, h, c, a=255):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.put(xx, yy, c, a)

    def frame(self, x, y, w, h, c, a=255):
        for xx in range(x, x + w):
            self.put(xx, y, c, a)
            self.put(xx, y + h - 1, c, a)
        for yy in range(y, y + h):
            self.put(x, yy, c, a)
            self.put(x + w - 1, yy, c, a)

    def line(self, x0, y0, x1, y1, c, a=255):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.put(x0, y0, c, a)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def disc(self, cx, cy, r, c, a=255):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.put(x, y, c, a)

    def ring(self, cx, cy, r0, r1, c, a=255, dashed=False):
        import math
        for y in range(int(cy - r1 - 1), int(cy + r1 + 2)):
            for x in range(int(cx - r1 - 1), int(cx + r1 + 2)):
                d = math.hypot(x - cx, y - cy)
                if r0 <= d <= r1:
                    if dashed and int((math.atan2(y - cy, x - cx) + math.pi) / (2 * math.pi) * 12) % 2:
                        continue
                    self.put(x, y, c, a)

    def text(self, x, y, s, c, scale=1, a=255):
        for ch in s:
            for ry, row in enumerate(glyph_rows(ch)):
                for rx, on in enumerate(row):
                    if on:
                        self.rect(x + rx * scale, y + ry * scale, scale, scale, c, a)
            x += 6 * scale

    def sprite(self, rows, x, y):
        for ry, row in enumerate(rows):
            for rx, p in enumerate(row):
                if p[3]:
                    self.put(x + rx, y + ry, p[:3], p[3])

    def rows(self):
        return self.px


def hash01(*vals):
    h = 2166136261
    for v in vals:
        h = ((h ^ (v & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 0x5BD1E995) & 0xFFFFFFFF
    return ((h ^ (h >> 15)) & 0xFFFFFF) / float(0xFFFFFF)


# ------------------------------------------------------------------ reading the existing icon sheets
def read_png(path):
    d = open(path, "rb").read()
    pos, idat, w, h = 8, b"", 0, 0
    while pos < len(d):
        n, = struct.unpack(">I", d[pos:pos + 4])
        tag, body = d[pos + 4:pos + 8], d[pos + 8:pos + 8 + n]
        if tag == b"IHDR":
            w, h = struct.unpack(">II", body[:8])
        if tag == b"IDAT":
            idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    rows = []
    for y in range(h):
        line = raw[y * (w * 4 + 1):(y + 1) * (w * 4 + 1)]
        assert line[0] == 0, "only unfiltered rows (the generator's own writer) are read"
        rows.append([tuple(line[1 + i * 4:5 + i * 4]) for i in range(w)])
    return rows


def cell(rows, index, size):
    return [row[index * size:(index + 1) * size] for row in rows]


# ------------------------------------------------------------------ the panel
def torn(canvas, seed):
    """Cuts a ragged edge: the top and bottom 4 px margins (outside the chest) and the far left and right."""
    w, h = canvas.w, canvas.h
    for x in range(w):
        top = int(hash01(seed, x // 2, 1) * 4)
        bot = int(hash01(seed, x // 2, 2) * 4)
        for y in range(0, top):
            canvas.px[y][x] = (0, 0, 0, 0)
        for y in range(h - bot, h):
            canvas.px[y][x] = (0, 0, 0, 0)
    for y in range(h):
        left = int(hash01(seed, y // 3, 3) * 7)
        right = int(hash01(seed, y // 3, 4) * 7)
        for x in range(0, left):
            canvas.px[y][x] = (0, 0, 0, 0)
        for x in range(w - right, w):
            canvas.px[y][x] = (0, 0, 0, 0)


def cobweb(c, ox, oy, sx, sy, size):
    """A web from a corner: spokes and sagging arcs. (sx, sy) are the directions the web grows in."""
    import math
    spokes = [(1, 0), (0, 1), (1, 1), (2, 1), (1, 2)]
    for dx, dy in spokes:
        n = math.hypot(dx, dy)
        c.line(ox, oy, ox + int(sx * size * dx / n), oy + int(sy * size * dy / n), WEB, 170)
    for ring_r in (size // 4, size // 2, size * 3 // 4):
        pts = []
        for dx, dy in spokes:
            n = math.hypot(dx, dy)
            pts.append((ox + int(sx * ring_r * dx / n), oy + int(sy * ring_r * dy / n)))
        pts.sort()
        for a, b in zip(pts, pts[1:]):
            mx, my = (a[0] + b[0]) // 2 - sx, (a[1] + b[1]) // 2 - sy
            c.line(a[0], a[1], mx, my, WEB, 150)
            c.line(mx, my, b[0], b[1], WEB, 150)


def candle(c, x, y, tall):
    """A candle standing on y: wax, drips, a flame."""
    c.rect(x, y - tall, 7, tall, BONE)
    c.rect(x, y - tall, 2, tall, (0xB8, 0xAE, 0x98))
    c.rect(x + 1, y - tall + 4, 2, 7, BONE)
    c.rect(x + 5, y - tall + 2, 1, 5, (0xB8, 0xAE, 0x98))
    c.rect(x + 3, y - tall - 3, 1, 3, (0x20, 0x18, 0x10))
    c.disc(x + 3.5, y - tall - 7, 2.6, FLAME)
    c.disc(x + 3.5, y - tall - 6.5, 1.3, FLAME2)
    c.rect(x - 2, y, 11, 2, (0x4A, 0x3D, 0x2C))


def bat(c, x, y, s=1):
    wing = [(0, 0), (2, -2), (4, -3), (6, -2), (7, 0), (5, 1), (3, 0), (1, 1)]
    for sign in (1, -1):
        pts = [(x + sign * px * s, y + py * s) for px, py in wing]
        for a, b in zip(pts, pts[1:] + pts[:1]):
            c.line(a[0], a[1], b[0], b[1], BAT)
        c.disc(x + sign * 3 * s, y - 0.5 * s, 1.6 * s, BAT)
    c.disc(x, y + 0.5, 2.2 * s, BAT)
    c.put(x - 1, y - 3 * s, BAT)
    c.put(x + 1, y - 3 * s, BAT)


def P(cx, cy):
    return cx - PANEL_X, cy - PANEL_Y


def panel():
    c = Canvas(PANEL_W, PANEL_H)
    # parchment: grain, fibres, a darker vignette
    for y in range(PANEL_H):
        for x in range(PANEL_W):
            n = hash01(7, x, y)
            f = hash01(11, x // 9, y // 3)
            base = PARCH_LT if f > 0.86 else PARCH_DK if f < 0.1 else PARCH
            shade = 0.9 + 0.2 * n
            ex = min(x, PANEL_W - 1 - x) / 40.0
            ey = min(y, PANEL_H - 1 - y) / 24.0
            v = min(1.0, 0.72 + 0.28 * min(ex, ey, 1.0))
            c.px[y][x] = (int(base[0] * shade * v), int(base[1] * shade * v), int(base[2] * shade * v), 255)
    # stains
    for i in range(7):
        sx, sy, sr = int(hash01(21, i) * PANEL_W), int(hash01(22, i) * PANEL_H), 8 + int(hash01(23, i) * 14)
        for y in range(sy - sr, sy + sr):
            for x in range(sx - sr, sx + sr):
                d = ((x - sx) ** 2 + (y - sy) ** 2) ** 0.5
                if d < sr and hash01(24, x, y) > 0.55 and d > sr * 0.55:
                    c.put(x, y, PARCH_DK, 120)
    # frame
    c.frame(4, 4, PANEL_W - 8, PANEL_H - 8, PURPLE)
    c.frame(6, 6, PANEL_W - 12, PANEL_H - 12, PURPLE_DK)
    for (x, y) in ((4, 4), (PANEL_W - 9, 4), (4, PANEL_H - 9), (PANEL_W - 9, PANEL_H - 9)):
        c.rect(x, y, 5, 5, GOLD)
        c.rect(x + 1, y + 1, 3, 3, (0x8A, 0x6A, 0x14))

    # title
    title = "THE CRYPT LEDGER"
    tx, ty = P(88 - text_width(title, 2) // 2, 7)
    c.text(tx + 1, ty + 1, title, BAT, 2)
    c.text(tx, ty, title, GOLD, 2)

    # the map band: lane guides, column ticks, the side dividers
    for lane in LANES:
        x0, y0 = P(6, lane)
        for x in range(x0, x0 + 164, 4):
            c.rect(x, y0, 2, 1, ROW_EDGE)
    for col in COLS:
        x0, y0 = P(col, 22)
        for y in range(y0, y0 + 108, 4):
            c.rect(x0, y, 1, 2, (0x30, 0x25, 0x19))
    for cx in (-2, 178):
        x0, y0 = P(cx, 20)
        c.rect(x0, y0, 1, 108, PURPLE_DK)

    # the choices band (the chest's own "Inventory" label is drawn over the left of it, in dark grey: this is a darker strip for it to sit in)
    x0, y0 = P(4, 127)
    c.rect(x0, y0, 168, 11, (0x14, 0x10, 0x0B))
    c.rect(x0, y0 + 11, 168, 1, PURPLE_DK)
    c.text(*P(70, 129), "YOUR CHOICES", GOLD)
    for y_c, label in zip(CHOICES, ("LEFT", "MIDDLE", "RIGHT")):
        x0, y0 = P(4, y_c - 9)
        c.rect(x0, y0, 168, 18, ROW_BG)
        c.frame(x0, y0, 168, 18, ROW_EDGE)
        c.text(*P(30, y_c - 3), label, INK_DIM)

    # the footer
    for bx in BUTTON_X:
        x0, y0 = P(bx - 13, BUTTON_Y - 9)
        c.rect(x0, y0, 26, 18, ROW_BG)
        c.frame(x0, y0, 26, 18, PURPLE)
    c.text(*P(88 - text_width("HOVER A STOP") // 2, BUTTON_Y - 3), "HOVER A STOP", INK_DIM)

    # the legend, on the left
    c.text(*P(-68, 22), "LEGEND", GOLD)
    minis = read_png(os.path.join(ASSETS, "textures", "font", "route_mini.png"))
    for n, i in enumerate(range(1, 12)):
        y = 36 + 13 * n
        c.sprite(cell(minis, i, 9), *P(-68, y - 4))
        c.text(*P(-55, y - 3), SHORT[i], INK_DIM)

    # the key, on the right
    c.text(*P(186, 22), "KEY", GOLD)
    key = [("HERE", GOLD, False), ("OPEN", GREEN, False), ("UNSEEN", WEB, True), ("BOSS", RED, False)]
    for n, (word, color, dashed) in enumerate(key):
        y = 38 + 16 * n
        x0, y0 = P(186, y)
        if word == "BOSS":
            c.disc(x0, y0, 4, (0x5A, 0x1A, 0x1A))
            c.ring(x0, y0, 4, 5.4, color)
        else:
            c.ring(x0, y0, 4.2, 5.6, color, dashed=dashed)
            c.disc(x0, y0, 1.6, color if not dashed else WEB)
        c.text(*P(196, y - 3), word, INK_DIM)
    x0, y0 = P(180, 38 + 16 * 4)
    c.rect(x0, y0, 12, 2, GOLD)
    c.text(*P(196, 38 + 16 * 4 - 3), "PATH", INK_DIM)

    # decoration
    cobweb(c, 7, 7, 1, 1, 38)
    cobweb(c, PANEL_W - 8, 7, -1, 1, 38)
    candle(c, *P(-66, 218), 16)
    candle(c, *P(-52, 218), 11)
    candle(c, *P(236, 218), 14)
    candle(c, *P(222, 218), 9)
    bat(c, *P(-40, 186), 1)
    bat(c, *P(-24, 172), 1)
    bat(c, *P(212, 160), 1)
    bat(c, *P(230, 142), 1)

    torn(c, 5)
    left = Canvas(PIECE_W, PANEL_H)
    right = Canvas(PIECE_W, PANEL_H)
    for y in range(PANEL_H):
        left.px[y] = c.px[y][:PIECE_W]
        right.px[y] = c.px[y][PIECE_W:]
    return BOOK.pin(left.rows()), BOOK.pin(right.rows())


# ------------------------------------------------------------------ the live glyphs
def straight():
    img = BOOK.blank(LINK, 4)
    for x in range(LINK):
        img[1][x] = img[2][x] = (255, 255, 255, 255)
    return BOOK.pin(img)


def diagonal(down):
    img = BOOK.blank(LINK, LINK)
    for x in range(LINK):
        y = x if down else LINK - 1 - x
        for yy in (y, y + 1 if down else y - 1):
            if 0 <= yy < LINK:
                img[yy][x] = (255, 255, 255, 255)
    return BOOK.pin(img)


def digit_cells():
    cells = []
    for ch in "0123456789-":
        img = BOOK.blank(6, 8)
        for ry, row in enumerate(glyph_rows(ch)):
            for rx, on in enumerate(row):
                if on:
                    img[ry][rx] = (255, 255, 255, 255)
        cells.append(BOOK.pin(img))
    return cells


def name_cells():
    cells = []
    for name in NAMES:
        img = BOOK.blank(NAME_W, 9)
        for i, ch in enumerate(name):
            for ry, row in enumerate(glyph_rows(ch)):
                for rx, on in enumerate(row):
                    if on:
                        img[ry][i * 6 + rx] = (255, 255, 255, 255)
        cells.append(BOOK.pin(img))
    return cells


def arrow(left):
    img = BOOK.blank(16, 12)
    for y in range(12):
        for x in range(16):
            half = 5.5
            d = abs(y - half)
            head = x <= 6 and d <= x * 0.9 + 0.5
            shaft = 5 <= x <= 15 and 3 <= y <= 8
            if head or shaft:
                img[y][x if left else 15 - x] = (255, 255, 255, 255)
    # the head points the way the arrow goes: a left arrow has its head at the left
    return BOOK.pin(img)


def map_item():
    """The Route Map item: a small rolled chart tied with a purple ribbon and a gold seal, 16 x 16."""
    c = Canvas(16, 16)
    outline = (0x2B, 0x1D, 0x12)
    c.rect(2, 3, 12, 10, outline)
    c.rect(3, 4, 10, 8, (0xE6, 0xD6, 0xAE))
    c.rect(3, 4, 2, 8, (0xC9, 0xB5, 0x88))
    c.rect(12, 4, 1, 8, (0xC9, 0xB5, 0x88))
    for y in (6, 8, 10):
        c.rect(6, y, 4, 1, (0x8A, 0x7A, 0x5A))
    c.rect(7, 3, 2, 10, PURPLE)
    c.rect(7, 3, 1, 10, (0x8A, 0x5C, 0xC8))
    c.disc(8, 8, 2.2, outline)
    c.disc(8, 8, 1.5, GOLD)
    c.put(7, 7, (0xFF, 0xE0, 0x80))
    return c.rows()


def chars(first, n):
    return "".join(chr(first + i) for i in range(n))


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def main():
    left, right = panel()
    write(os.path.join(TEX, "panel_left.png"), BOOK.png(left))
    write(os.path.join(TEX, "panel_right.png"), BOOK.png(right))
    write(os.path.join(TEX, "links_straight.png"), BOOK.png(straight()))
    write(os.path.join(TEX, "links_down.png"), BOOK.png(diagonal(True)))
    write(os.path.join(TEX, "links_up.png"), BOOK.png(diagonal(False)))
    write(os.path.join(TEX, "digits.png"), BOOK.png(BOOK.strip(digit_cells())))
    write(os.path.join(TEX, "names.png"), BOOK.png(BOOK.strip(name_cells())))
    write(os.path.join(TEX, "arrows.png"), BOOK.png(BOOK.strip([arrow(True), arrow(False)])))
    os.makedirs(os.path.join(ASSETS, "models", "item", "route"), exist_ok=True)
    write(os.path.join(ASSETS, "textures", "item", "route", "map_item.png"), BOOK.png(map_item()))
    with open(os.path.join(ASSETS, "models", "item", "route", "map_item.json"), "w", newline="\n") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "cobbletowers:item/route/map_item"}}, f, indent=2)
        f.write("\n")
    blank = BOOK.blank(16, 16)
    write(os.path.join(ASSETS, "textures", "item", "route", "blank.png"), BOOK.png(blank))
    os.makedirs(os.path.join(ASSETS, "models", "item", "route"), exist_ok=True)
    with open(os.path.join(ASSETS, "models", "item", "route", "blank.json"), "w", newline="\n") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "cobbletowers:item/route/blank"}}, f, indent=2)
        f.write("\n")

    def top_ascent(top):
        return BASELINE - top

    providers = []
    top = PANEL_Y
    providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/panel_left.png", "ascent": top_ascent(top), "height": PANEL_H, "chars": [chr(PANEL_BASE)]})
    providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/panel_right.png", "ascent": top_ascent(top), "height": PANEL_H, "chars": [chr(PANEL_BASE + 1)]})
    for slot, y_c in enumerate(LANES + CHOICES):
        providers.append({"type": "bitmap", "file": "cobbletowers:font/route_icons.png", "ascent": top_ascent(y_c - ICON // 2), "height": ICON,
                          "chars": [chars(ICON_BASE + slot * ICON_STEP, 13)]})
    for lane, y_c in enumerate(LANES):
        providers.append({"type": "bitmap", "file": "cobbletowers:font/route_rings.png", "ascent": top_ascent(y_c - RING // 2), "height": RING,
                          "chars": [chr(RING_BASE + lane)]})
        providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/links_straight.png", "ascent": top_ascent(y_c - 2), "height": 4,
                          "chars": [chr(STRAIGHT_BASE + lane)]})
    for upper in range(2):
        top_y = LANES[upper]
        providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/links_down.png", "ascent": top_ascent(top_y), "height": LINK, "chars": [chr(DOWN_BASE + upper)]})
        providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/links_up.png", "ascent": top_ascent(top_y), "height": LINK, "chars": [chr(UP_BASE + upper)]})
    providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/digits.png", "ascent": top_ascent(HEADER_Y - 4), "height": 8, "chars": [chars(DIGIT_BASE, 11)]})
    for row, y_c in enumerate(CHOICES):
        providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/names.png", "ascent": top_ascent(y_c - 4), "height": 9,
                          "chars": [chars(NAME_BASE + row * NAME_STEP, 12)]})
    providers.append({"type": "bitmap", "file": "cobbletowers:font/screen/arrows.png", "ascent": top_ascent(BUTTON_Y - 6), "height": 12, "chars": [chars(ARROW_BASE, 2)]})
    advances = {}
    for i in range(7):
        advances[chr(SPACE_POS + i)] = 1 << i
        advances[chr(SPACE_NEG + i)] = -(1 << i)
    providers.append({"type": "space", "advances": advances})
    os.makedirs(os.path.join(ASSETS, "font"), exist_ok=True)
    with open(os.path.join(ASSETS, "font", "route_screen.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"providers": providers}, f, indent=1)
        f.write("\n")
    print("wrote the panel (%d x %d), %d providers" % (PANEL_W, PANEL_H, len(providers)))


if __name__ == "__main__":
    main()
