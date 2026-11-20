"""Concept board for the tower route door (P59): three base shapes x three states in Neutral, then the per-tower trims.

Drawn at texel scale (16 px a block) as the door would read face-on from the atrium, inside the doorway the tower generator builds
(5 x 6 opening, chiseled-quartz jambs and header, froglight row, gold keystone). Run: py -3.14 -I tools/gate_door/concept_board.py
Writes docs/design/P59/gate_door_board.png.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ICONS = ROOT / "src/main/resources/assets/cobbletowers/textures/font/route_icons.png"
OUT = ROOT / "docs/design/P59/gate_door_board.png"

B = 16                        # texels a block
TW, TH = 9 * B, 11 * B        # a tile: the doorway plus one block of wall either side, floor to the row above the light
DX0, DX1 = 2 * B, 7 * B       # the 5-wide opening
DY0, DY1 = 4 * B, 10 * B      # its top (row 4) to the floor line (row 10)
GX0, GX1 = DX0 + B, DX1 - B   # the 3 x 5 gap inside the door's own jambs and lintel
GY0 = DY0 + B


def hexc(s: str) -> tuple[int, int, int]:
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def shade(c, f: float):
    return tuple(max(0, min(255, int(v * f))) for v in c)


# Per-tower trims. Regional colours are sampled from each region's approved armour (textures/armor/<region>_1 and its glowmask).
TRIMS = {
    "Neutral":   dict(leaf="#8a8d90", band="#c9a227", frame="#e9e4da", accent="#a8382c", glow="#fff1d6", note="andesite leaves, gold bands, red seal"),
    "Duskvale":  dict(leaf="#2a1840", band="#d097db", frame="#361f4e", accent="#f0b6eb", glow="#ff9bcf", note="night violet, lilac bands, pink glow"),
    "Rootvale":  dict(leaf="#3c3022", band="#d8b880", frame="#1d241b", accent="#a78256", glow="#d2ff8b", note="bark, tan-gold bands, sap-green glow"),
    "Tideforge": dict(leaf="#2a4467", band="#def4fa", frame="#08112c", accent="#76c2de", glow="#8ffff2", note="deep navy, foam bands, aqua glow"),
    "Poke Tower": dict(leaf="#c8352b", band="#1b1b1f", frame="#f2f2f0", accent="#f2f2f0", glow="#ffffff", note="red over white, black band, white button", pokeball=True),
}


# ---------------------------------------------------------------- block textures

def noise_fill(d: ImageDraw.ImageDraw, rng, x0, y0, x1, y1, base, spread=0.06):
    for y in range(y0, y1):
        for x in range(x0, x1):
            d.point((x, y), shade(base, 1 + rng.uniform(-spread, spread)))


def quartz_bricks(d, rng, bx, by):
    x0, y0 = bx * B, by * B
    noise_fill(d, rng, x0, y0, x0 + B, y0 + B, hexc("#ebe5dc"), 0.025)
    line = hexc("#cfc7ba")
    for yy in (0, 8):
        d.line((x0, y0 + yy, x0 + B - 1, y0 + yy), fill=line)
    for yy, xx in ((1, 0), (9, 8)):
        d.line((x0 + xx, y0 + yy, x0 + xx, y0 + yy + 6), fill=line)


def chiseled(d, rng, bx, by):
    x0, y0 = bx * B, by * B
    noise_fill(d, rng, x0, y0, x0 + B, y0 + B, hexc("#e9e4da"), 0.02)
    d.rectangle((x0 + 2, y0 + 2, x0 + 13, y0 + 13), outline=hexc("#cbc2b3"))
    d.rectangle((x0 + 4, y0 + 4, x0 + 11, y0 + 11), outline=hexc("#d8d0c3"))


def andesite(d, rng, bx, by):
    x0, y0 = bx * B, by * B
    noise_fill(d, rng, x0, y0, x0 + B, y0 + B, hexc("#868a8c"), 0.05)
    d.rectangle((x0, y0, x0 + B - 1, y0 + B - 1), outline=hexc("#74787a"))


def froglight(d, rng, bx, by):
    x0, y0 = bx * B, by * B
    noise_fill(d, rng, x0, y0, x0 + B, y0 + B, hexc("#f6e6ee"), 0.03)
    d.rectangle((x0, y0, x0 + B - 1, y0 + B - 1), outline=hexc("#e3c9d6"))


def gold(d, rng, bx, by):
    x0, y0 = bx * B, by * B
    noise_fill(d, rng, x0, y0, x0 + B, y0 + B, hexc("#f2c94c"), 0.05)
    d.rectangle((x0, y0, x0 + B - 1, y0 + B - 1), outline=hexc("#c99a1e"))


def surround(img: Image.Image, glow: Image.Image, rng):
    d = ImageDraw.Draw(img)
    for by in range(11):
        for bx in range(9):
            if by == 10:
                andesite(d, rng, bx, by)
            elif bx in (0, 8) or by == 0:
                quartz_bricks(d, rng, bx, by)
            elif by == 1:
                froglight(d, rng, bx, by)
                ImageDraw.Draw(glow).rectangle((bx * B, by * B, bx * B + B - 1, by * B + B - 1), fill=shade(hexc("#f6e6ee"), 0.35))
            elif by in (2, 3) or bx in (1, 7):
                if (bx, by) == (4, 2):
                    gold(d, rng, bx, by)
                else:
                    chiseled(d, rng, bx, by)


def passage(img: Image.Image):
    """What an open door shows: the lane chamber behind, dim, a floor running away and the first steps of the lane stair."""
    d = ImageDraw.Draw(img)
    for y in range(GY0, DY1):
        t = (y - GY0) / (DY1 - GY0)
        d.line((GX0, y, GX1 - 1, y), fill=shade(hexc("#26232a"), 0.55 + 0.6 * t))
    for i, y in enumerate(range(DY1 - 14, DY1, 2)):
        d.line((GX0 + 6 - i, y, GX1 - 7 + i, y), fill=hexc("#5c5a5e"))
    d.rectangle((GX0 + 14, GY0 + 18, GX1 - 15, GY0 + 40), fill=hexc("#3a363d"))
    for k in range(4):
        y = GY0 + 22 + k * 5
        d.line((GX0 + 15, y, GX1 - 16, y), fill=hexc("#4b464f"))


# ---------------------------------------------------------------- the door parts

class Kit:
    def __init__(self, img, glow, trim, icon, rng):
        self.img, self.glow, self.rng = img, glow, rng
        self.d, self.g = ImageDraw.Draw(img), ImageDraw.Draw(glow)
        self.leaf, self.band, self.frame = hexc(trim["leaf"]), hexc(trim["band"]), hexc(trim["frame"])
        self.accent, self.gc = hexc(trim["accent"]), hexc(trim["glow"])
        self.poke = trim.get("pokeball", False)
        self.icon = icon

    def frame_parts(self):
        """The door's own jambs and lintel (part of the model, standing in the generator's 5 x 6 opening)."""
        d, f = self.d, self.frame
        for x0, x1 in ((DX0, DX0 + B), (DX1 - B, DX1)):
            noise_fill(d, self.rng, x0, DY0, x1, DY1, f, 0.03)
            d.line((x0, DY0, x0, DY1 - 1), fill=shade(f, 1.12))
            d.line((x1 - 1, DY0, x1 - 1, DY1 - 1), fill=shade(f, 0.72))
            for y in range(DY0 + 20, DY1 - 4, 22):
                d.rectangle((x0 + 4, y, x1 - 5, y + 1), fill=shade(f, 0.8))
        noise_fill(d, self.rng, DX0, DY0, DX1, GY0, f, 0.03)
        d.line((DX0, GY0 - 1, DX1 - 1, GY0 - 1), fill=shade(f, 0.62))
        d.line((DX0, DY0, DX1 - 1, DY0), fill=shade(f, 1.15))

    def lintel_slit(self, lit: bool):
        y = DY0 + 7
        self.d.rectangle((GX0 + 4, y, GX1 - 5, y + 1), fill=self.gc if lit else shade(self.frame, 0.5))
        if lit:
            self.g.rectangle((GX0 + 4, y, GX1 - 5, y + 1), fill=self.gc)

    def leaf_fill(self, x0, x1, y0=GY0):
        d = self.d
        if self.poke:   # red over white with the black band through the middle
            mid = y0 + (DY1 - y0) // 2
            noise_fill(d, self.rng, x0, y0, x1, mid, self.leaf, 0.04)
            noise_fill(d, self.rng, x0, mid, x1, DY1, hexc("#f2f2f0"), 0.02)
            d.rectangle((x0, mid - 2, x1 - 1, mid + 2), fill=self.band)
            return
        noise_fill(d, self.rng, x0, y0, x1, DY1, self.leaf, 0.05)
        for x in range(x0 + 6, x1, 6):
            d.line((x, y0, x, DY1 - 1), fill=shade(self.leaf, 0.82))

    def bands(self, x0, x1, ys=(GY0 + 10, DY1 - 14)):
        if self.poke:
            return
        for y in ys:
            self.d.rectangle((x0, y, x1 - 1, y + 3), fill=self.band)
            self.d.line((x0, y, x1 - 1, y), fill=shade(self.band, 1.2))
            for x in range(x0 + 3, x1 - 2, 8):
                self.d.point((x, y + 2), fill=shade(self.band, 0.6))

    def put_icon(self, cx, cy):
        w, h = self.icon.size
        self.img.alpha_composite(self.icon, (cx - w // 2, cy - h // 2))

    def disc(self, cx, cy, r, rim, fill, lit, clip=None):
        """A round seal; clip limits it to one leaf so the halves part with the leaves."""
        layer = Image.new("RGBA", self.img.size)
        ld = ImageDraw.Draw(layer)
        ld.ellipse((cx - r, cy - r, cx + r, cy + r), fill=rim)
        ld.ellipse((cx - r + 3, cy - r + 3, cx + r - 3, cy + r - 3), fill=fill)
        if clip:
            mask = Image.new("L", self.img.size)
            ImageDraw.Draw(mask).rectangle(clip, fill=255)
            layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
        self.img.alpha_composite(layer)
        if lit:
            self.g.ellipse((cx - r, cy - r, cx + r, cy + r), outline=self.gc, width=2)
            self.d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=self.gc, width=1)


def shape_split_seal(k: Kit, state: str):
    """A: twin leaves meeting on a round seal that carries the lane icon; the seal splits as the leaves swing in."""
    k.frame_parts()
    k.lintel_slit(state != "shut")
    mid, cy = (GX0 + GX1) // 2, GY0 + 34
    if state == "open":
        passage(k.img)
        for x0, x1 in ((GX0, GX0 + 4), (GX1 - 4, GX1)):
            k.leaf_fill(x0, x1)
            k.d.line((x0 if x0 > GX0 else x1 - 1, GY0, x0 if x0 > GX0 else x1 - 1, DY1 - 1), fill=shade(k.leaf, 0.6))
        return
    k.leaf_fill(GX0, GX1)
    k.bands(GX0, GX1)
    k.d.line((mid, GY0, mid, DY1 - 1), fill=shade(k.leaf, 0.45))
    k.disc(cx := mid, cy, 15, k.band if not k.poke else hexc("#1b1b1f"), shade(k.accent, 0.5) if not k.poke else hexc("#f2f2f0"), state == "chosen")
    k.put_icon(cx, cy)
    if state == "chosen":
        k.g.line((mid, GY0, mid, cy - 16), fill=k.gc, width=2)
        k.g.line((mid, cy + 16, mid, DY1 - 1), fill=k.gc, width=2)
        k.d.line((mid, GY0, mid, cy - 16), fill=k.gc)
        k.d.line((mid, cy + 16, mid, DY1 - 1), fill=k.gc)


def shape_lifting_slab(k: Kit, state: str):
    """B: one solid slab with an icon plate; it rises straight up into the wall above, the tower swallowing its door."""
    k.frame_parts()
    k.lintel_slit(state != "shut")
    cx, cy = (GX0 + GX1) // 2, GY0 + 28
    if state == "open":
        passage(k.img)
        k.d.rectangle((GX0, GY0, GX1 - 1, GY0 + 5), fill=shade(k.leaf, 0.7))
        k.d.line((GX0, GY0 + 5, GX1 - 1, GY0 + 5), fill=k.band)
        return
    k.leaf_fill(GX0, GX1)
    k.d.rectangle((GX0 + 3, GY0 + 3, GX1 - 4, DY1 - 4), outline=shade(k.leaf, 0.6))
    k.bands(GX0, GX1, ys=(DY1 - 10,))
    for x in (GX0 + 10, GX1 - 11):     # the two grooves the slab rides in, lit when chosen
        k.d.line((x, cy + 18, x, DY1 - 14), fill=k.gc if state == "chosen" else shade(k.leaf, 0.5), width=2)
        if state == "chosen":
            k.g.line((x, cy + 18, x, DY1 - 14), fill=k.gc, width=2)
    k.d.rectangle((cx - 14, cy - 14, cx + 13, cy + 13), fill=k.band if not k.poke else hexc("#1b1b1f"))
    k.d.rectangle((cx - 11, cy - 11, cx + 10, cy + 10), fill=shade(k.accent, 0.5) if not k.poke else hexc("#f2f2f0"))
    if state == "chosen":
        k.g.rectangle((cx - 14, cy - 14, cx + 13, cy + 13), outline=k.gc, width=2)
        k.d.rectangle((cx - 14, cy - 14, cx + 13, cy + 13), outline=k.gc)
    k.put_icon(cx, cy)


def shape_keystone_arch(k: Kit, state: str):
    """C: arched twin leaves under a keystone; the lane icon lives on the keystone, so it still names the lane once the door is open."""
    k.frame_parts()
    cx = (GX0 + GX1) // 2
    r = (GX1 - GX0) // 2
    spring = GY0 + r
    if state == "open":
        passage(k.img)
        for x0, x1 in ((GX0, GX0 + 4), (GX1 - 4, GX1)):
            k.leaf_fill(x0, x1, spring)
    else:
        k.leaf_fill(GX0, GX1)
        k.bands(GX0, GX1, ys=(spring + 8, DY1 - 14))
        k.d.line((cx, GY0, cx, DY1 - 1), fill=shade(k.leaf, 0.45))
        for x in (cx - 6, cx + 5):  # ring pulls
            k.d.ellipse((x - 3, spring + 22, x + 3, spring + 28), outline=k.band if not k.poke else hexc("#1b1b1f"), width=1)
    # the arch: frame fills the corners above the springline
    for y in range(GY0, spring):
        dy = spring - y
        half = int(math.sqrt(max(0, r * r - dy * dy)))
        k.d.line((GX0, y, cx - half - 1, y), fill=k.frame)
        k.d.line((cx + half, y, GX1 - 1, y), fill=k.frame)
    lit = state != "shut"
    k.d.arc((GX0, GY0, GX1 - 1, GY0 + 2 * r), 180, 360, fill=k.gc if lit else shade(k.frame, 0.6), width=2)
    if lit:
        k.g.arc((GX0, GY0, GX1 - 1, GY0 + 2 * r), 180, 360, fill=k.gc, width=3)
    # keystone, standing proud of the lintel into the arch
    ks = (cx - 13, DY0 - 2, cx + 12, GY0 + 10)
    k.d.polygon([(ks[0], ks[1]), (ks[2], ks[1]), (ks[2] - 3, ks[3]), (ks[0] + 3, ks[3])], fill=k.band if not k.poke else hexc("#1b1b1f"))
    k.d.polygon([(ks[0] + 3, ks[1] + 3), (ks[2] - 3, ks[1] + 3), (ks[2] - 5, ks[3] - 3), (ks[0] + 5, ks[3] - 3)],
                fill=shade(k.accent, 0.5) if not k.poke else hexc("#f2f2f0"))
    if lit:
        k.g.polygon([(ks[0], ks[1]), (ks[2], ks[1]), (ks[2] - 3, ks[3]), (ks[0] + 3, ks[3])], outline=k.gc, width=2)
    k.put_icon(cx, (ks[1] + ks[3]) // 2)


SHAPES = [
    ("A", "Split Seal", "Two leaves meet on a round seal that carries the lane icon. On the vote the seam lights and the seal splits as the leaves swing in.",
     shape_split_seal),
    ("B", "Rising Slab", "One solid slab with an icon plate. On the vote its grooves light and it slides straight up into the wall above.",
     shape_lifting_slab),
    ("C", "Keystone Arch", "Arched leaves under a keystone. The icon is on the keystone, so an open door still names its lane.",
     shape_keystone_arch),
]
STATES = [("shut", "Shut"), ("chosen", "Chosen (vote settled)"), ("open", "Open")]


# ---------------------------------------------------------------- rendering

def icon_sheet() -> list[Image.Image]:
    sheet = Image.open(ICONS).convert("RGBA")
    n = sheet.width // sheet.height
    return [sheet.crop((i * sheet.height, 0, (i + 1) * sheet.height, sheet.height)) for i in range(n)]


def render_tile(shape_fn, state, trim, icon, scale, seed=7) -> Image.Image:
    rng = random.Random(seed)
    img = Image.new("RGBA", (TW, TH))
    glow = Image.new("RGB", (TW, TH))
    surround(img, glow, rng)
    shape_fn(Kit(img, glow, trim, icon, rng), state)
    big = img.resize((TW * scale, TH * scale), Image.NEAREST).convert("RGB")
    bloom = glow.resize((TW * scale, TH * scale), Image.BILINEAR).filter(ImageFilter.GaussianBlur(5 * scale))
    out = ImageChops.screen(big, bloom)
    out = ImageChops.screen(out, bloom)
    return out


def font(size, bold=False):
    for name in (("seguisb.ttf" if bold else "segoeui.ttf"), "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if d.textlength(trial, font=f) > width and cur:
            lines.append(cur)
            cur = w
        else:
            cur = trial
    return lines + [cur]


def main() -> int:
    icons = icon_sheet()
    lane_icon = {"A": icons[1], "B": icons[7], "C": icons[3]}   # battle, vault, rest
    S, s2 = 3, 2
    tw, th = TW * S, TH * S
    pad, label_w, gap = 48, 330, 24
    trim_tw, trim_th = TW * s2, TH * s2
    W = pad * 2 + label_w + 3 * tw + 2 * gap
    H = pad + 110 + 40 + 3 * (th + gap) + 90 + trim_th + 110 + pad
    bg, ink, dim, rule = hexc("#141317"), hexc("#ece8e1"), hexc("#9a958d"), hexc("#2c2a30")
    board = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(board)

    d.text((pad, pad), "CobbleTowers  ·  P59", font=font(20, True), fill=hexc("#c9a227"))
    d.text((pad, pad + 30), "Tower route door: concept board", font=font(44, True), fill=ink)
    d.text((pad, pad + 86), "Replaces the iron bars on the three route doors. Solid, lane icon on its face, one shape shared by every tower. "
           "Shown face-on in the Neutral doorway the generator builds (5 x 6).", font=font(19), fill=dim)

    y = pad + 150
    x0 = pad + label_w
    for i, (_, title) in enumerate(STATES):
        d.text((x0 + i * (tw + gap), y), title.upper(), font=font(17, True), fill=dim)
    y += 34
    for letter, name, desc, fn in SHAPES:
        d.line((pad, y - 12, W - pad, y - 12), fill=rule)
        d.text((pad, y + 8), letter, font=font(64, True), fill=hexc("#c9a227"))
        d.text((pad + 56, y + 22), name, font=font(30, True), fill=ink)
        for j, line in enumerate(wrap(d, desc, font(19), label_w - 40)):
            d.text((pad, y + 100 + j * 27), line, font=font(19), fill=dim)
        for i, (state, _) in enumerate(STATES):
            board.paste(render_tile(fn, state, TRIMS["Neutral"], lane_icon[letter], S), (x0 + i * (tw + gap), y))
        y += th + gap

    y += 20
    d.line((pad, y, W - pad, y), fill=rule)
    d.text((pad, y + 18), "PER-TOWER TRIM", font=font(17, True), fill=dim)
    d.text((pad, y + 44), "Same base shape, the tower's own colours. Shown on A, shut; trims apply to whichever shape you pick. "
           "Regional colours sampled from each region's armour art.", font=font(19), fill=dim)
    y += 90
    tgap = (W - 2 * pad - 5 * trim_tw) // 4
    for i, (name, trim) in enumerate(TRIMS.items()):
        x = pad + i * (trim_tw + tgap)
        board.paste(render_tile(shape_split_seal, "shut", trim, icons[1], s2, seed=11 + i), (x, y))
        d.text((x, y + trim_th + 12), name, font=font(22, True), fill=ink)
        for j, line in enumerate(wrap(d, trim["note"], font(16), trim_tw)):
            d.text((x, y + trim_th + 44 + j * 22), line, font=font(16), fill=dim)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    board.save(OUT)
    print(OUT, board.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
