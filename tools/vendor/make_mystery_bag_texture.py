"""Draws the Mystery Bag's 16 x 16 item texture, with no image library (plain PNG written by hand).

  python tools/vendor/make_mystery_bag_texture.py            writes src/main/resources/assets/cobbletowers/textures/item/mystery_bag.png
  python tools/vendor/make_mystery_bag_texture.py --preview  also writes mystery_bag_x16.png next to this script (16x enlarged, for looking at; not part of the jar)

A tied brown sack with an orange rope tie, a bold gold "?" with a purple shadow, and a sparkle. The artwork is the 'where' and 'what' of the code below, so a
change is a diff, not a binary.
"""
import os
import struct
import sys
import zlib

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "cobbletowers", "textures", "item")

OUTLINE = (0x3A, 0x22, 0x12, 255)
SACK_LIGHT = (0xC8, 0x92, 0x4B, 255)
SACK_MID = (0x9C, 0x66, 0x30, 255)
SACK_DARK = (0x6E, 0x45, 0x23, 255)
TIE = (0xF2, 0xA7, 0x3B, 255)
TIE_DARK = (0xB8, 0x71, 0x1B, 255)
GOLD = (0xFF, 0xDB, 0x4D, 255)
SHADOW = (0x5B, 0x2A, 0x8C, 255)
SPARK = (0xFF, 0xF6, 0xC8, 255)

QUESTION = [
    ".XXX.",
    "X...X",
    "....X",
    "...X.",
    "..X..",
    ".....",
    "..X..",
]


def canvas():
    return [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]


def inside_body(x, y):
    """The sack's round belly: an ellipse centred low in the icon."""
    return ((x + 0.5 - 8.0) / 6.3) ** 2 + ((y + 0.5 - 10.6) / 5.0) ** 2 <= 1.0


def draw():
    px = canvas()
    # the belly, shaded light at the top left and dark at the bottom right
    for y in range(16):
        for x in range(16):
            if inside_body(x, y):
                shade = (x - 8) * 0.55 + (y - 10) * 0.9
                px[y][x] = SACK_LIGHT if shade < -2.0 else SACK_MID if shade < 2.2 else SACK_DARK
    # the neck above the tie, and the two ruffled ears of the sack's mouth
    for y, (a, b) in {4: (6, 9), 3: (5, 10)}.items():
        for x in range(a, b + 1):
            px[y][x] = SACK_MID if x > 7 else SACK_LIGHT
    for x, y in [(5, 2), (6, 2), (9, 2), (10, 2), (5, 3), (10, 3)]:
        px[y][x] = SACK_LIGHT if x < 8 else SACK_MID
    # the rope tie round the neck
    for x in range(5, 11):
        px[5][x] = TIE
        px[6][x] = TIE_DARK
    # the outline: every non-empty pixel that touches an empty one
    filled = [[px[y][x][3] > 0 for x in range(16)] for y in range(16)]
    edge = []
    for y in range(16):
        for x in range(16):
            if filled[y][x]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and filled[ny][nx]:
                    edge.append((x, y))
                    break
    for x, y in edge:
        px[y][x] = OUTLINE
    # the question mark: purple shadow first, gold over it
    for ox, oy, colour in ((1, 1, SHADOW), (0, 0, GOLD)):
        for row, line in enumerate(QUESTION):
            for col, ch in enumerate(line):
                if ch == "X":
                    x, y = 5 + col + ox, 8 + row + oy
                    if 0 <= x < 16 and 0 <= y < 16:
                        px[y][x] = colour
    # a sparkle, top right
    for x, y in [(13, 2), (12, 3), (14, 3), (13, 3), (13, 4)]:
        px[y][x] = SPARK
    return px


def png(pixels, scale=1):
    h = len(pixels) * scale
    w = len(pixels[0]) * scale
    raw = bytearray()
    for row in pixels:
        line = bytearray([0])
        for r, g, b, a in row:
            line += bytes((r, g, b, a)) * scale
        for _ in range(scale):
            raw += line
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    art = draw()
    with open(os.path.join(OUT, "mystery_bag.png"), "wb") as f:
        f.write(png(art))
    if "--preview" in sys.argv:
        with open(os.path.join(os.path.dirname(__file__), "mystery_bag_x16.png"), "wb") as f:
            f.write(png(art, 16))
    print("wrote", os.path.normpath(os.path.join(OUT, "mystery_bag.png")))
