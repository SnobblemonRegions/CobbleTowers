#!/usr/bin/env python3
"""Paints a 48 x 48 shaded, textured pixel-art icon for every relic (P59 relic reveal), one recipe per relic, all in one style.

    py -3.14 -I tools/relic_art/make_relic_art.py [--sheet out.png] [--bridge <pack dir>]

Each recipe lays down shapes with a material (gold, iron, wood, leather, cloth, gem, glass, fire...) in 32-unit coordinates; relic_render.py
lights them as surfaces (domes, cylinders, bevelled plates), shades each through its material's hue-shifted tone ramp with dithering and
texture, adds contact shadows, a material-tinted outline and a drop shadow. Icons are named by relic id (modifiers/<id>.json); --bridge
copies them into the SnobbleGUIOverhaul pack as relic_<id>.png, shown at 2x on the reveal card (96 x 96). --sheet writes a contact sheet.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from relic_render import (Canvas, FIRE_RAMP, ICE_RAMP, Mat, mat, tex_brushed, tex_cloth, tex_fire, tex_gold, tex_leaf,  # noqa: E402
                          tex_leather, tex_soft, tex_stone, tex_wood)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools" / "relic_art" / "out"
MODIFIERS = ROOT / "src/main/resources/data/cobbletowers/cobbletowers/modifiers"
N = 48

# ---- materials (the recipes' palette) ------------------------------------------------------------------------------------------
GOLD = mat("gold", (226, 170, 46), shine=0.55, power=10, texture=tex_gold)
GOLD_D = GOLD.shade(-1.2)
IRON = mat("iron", (168, 176, 192), shine=0.75, power=18, texture=tex_brushed)
IRON_D = mat("iron", (110, 116, 132), shine=0.45, power=14, texture=tex_brushed)
WOOD = mat("wood", (142, 92, 52), texture=tex_wood)
WOOD_D = mat("wood", (100, 62, 36), texture=tex_wood)
LEATHER = mat("leather", (156, 98, 58), shine=0.12, power=6, texture=tex_leather)
LEATHER_D = LEATHER.shade(-1.3)
RED = mat("cloth", (190, 40, 50), texture=tex_cloth)
RED_D = RED.shade(-1.3)
BLUE = mat("cloth", (62, 108, 196), texture=tex_cloth)
BLUE_D = BLUE.shade(-1.3)
BONE = mat("bone", (232, 222, 196), shine=0.15, power=8, texture=tex_soft)
PAPER = mat("paper", (236, 222, 178), texture=tex_soft)
GEM_R = mat("gem", (226, 44, 70), shine=1.0, power=30)
GEM_B = mat("gem", (64, 146, 238), shine=1.0, power=30)
GEM_G = mat("gem", (62, 196, 116), shine=1.0, power=30)
GEM_P = mat("gem", (164, 92, 228), shine=1.0, power=30)
FIRE = mat("fire", (255, 128, 36), emissive=True, texture=tex_fire, ramp_override=FIRE_RAMP)
FIRE_Y = FIRE.shade(+1.6)
ICE = mat("ice", (170, 228, 250), shine=0.9, power=24, fresnel=0.35, ramp_override=ICE_RAMP)
GLASS = mat("glass", (190, 230, 244), shine=1.0, power=26, fresnel=0.45)
LEAF = mat("leaf", (88, 178, 70), shine=0.1, texture=tex_leaf)
LEAF_D = LEAF.shade(-1.4)
INK = mat("ink", (52, 40, 62))
DARK = mat("void", (26, 20, 32), ramp_override=[(16, 12, 22), (30, 22, 38), (44, 34, 56)])
SKIN = mat("bone", (226, 214, 190), texture=tex_soft)
STONE = mat("stone", (150, 146, 160), texture=tex_stone)
RED_LAC = mat("lacquer", (198, 44, 50), shine=0.35, power=10, texture=tex_soft)       # the berserker's painted mask
WHITE = mat("glint", (250, 250, 255), ramp_override=[(232, 236, 248), (246, 248, 255), (255, 255, 255)])
WHITE_HOT = WHITE
MAP_LINE = mat("ink", (96, 150, 84))
MAP_BLUE = mat("ink", (80, 130, 200))
CLOUD = mat("cloud", (226, 232, 244), texture=tex_soft)
RUNE = mat("rune", (196, 140, 255), emissive=True, ramp_override=[(120, 70, 190), (160, 100, 230), (200, 150, 255), (236, 210, 255)])
SAND = mat("sand", (232, 196, 110), texture=tex_stone)
MIRROR = mat("glass", (150, 128, 216), shine=1.0, power=28, fresnel=0.5)
COPPER = mat("copper", (204, 116, 64), shine=0.6, power=12, texture=tex_brushed)
LIGHTNING = mat("fire", (255, 236, 120), emissive=True, ramp_override=[(220, 180, 60), (255, 220, 90), (255, 244, 170), (255, 255, 236)])
FEATHER = mat("feather", (230, 236, 248), texture=tex_soft)
SKY = mat("feather", (160, 196, 236), texture=tex_soft)
TARNISH = mat("gold", (178, 150, 72), shine=0.25, power=8, texture=tex_stone)
PATINA = mat("stone", (96, 168, 140))
SHELL = mat("shell", (240, 198, 178), shine=0.2, texture=tex_soft)
PEARL = mat("pearl", (238, 236, 246), shine=0.9, power=20, fresnel=0.3)
BURLAP = mat("cloth", (180, 152, 108), texture=tex_cloth)
BLOOD = mat("gem", (190, 20, 36), shine=0.9, power=24)
VEIL = mat("cloth", (156, 128, 200), texture=tex_cloth)
SEED = mat("wood", (150, 100, 56), shine=0.2, texture=tex_leather)
WAX = mat("wax", (184, 34, 44), shine=0.45, power=10, texture=tex_soft)


class Icon(Canvas):
    """The recipe surface: Canvas, taking either a material or a plain colour (a matte material is made for it)."""

    def __init__(self):
        super().__init__(N, 48)

    @staticmethod
    def _m(c):
        return c if isinstance(c, Mat) else mat("generic", c, texture=tex_soft)

    def rect(self, x0, y0, x1, y1, c, profile="bevel"):
        super().rect(x0, y0, x1, y1, self._m(c), profile)

    def ell(self, x0, y0, x1, y1, c, profile="dome"):
        super().ell(x0, y0, x1, y1, self._m(c), profile)

    def poly(self, pts, c, profile="bevel"):
        super().poly(pts, self._m(c), profile)

    def line(self, pts, c, w=1):
        super().line(pts, self._m(c), w)

    def ring(self, cx, cy, r, w, c):
        super().ring(cx, cy, r, w, self._m(c))

    def px(self, x, y, c):
        super().px(x, y, self._m(c))

    def finish(self):
        return self.render()


# ---- the forty recipes (48 x 48 grid; keep 3 px clear at the edges for the outline and the drop shadow) ------------------------------

def berserkers_mask(i):
    i.poly([(13, 17), (5, 3), (3, 8), (9, 20)], BONE); i.poly([(34, 17), (42, 3), (44, 8), (38, 20)], BONE)          # horns
    for x0, x1, y in ((5, 9, 9), (7, 10, 13), (42, 38, 9), (40, 37, 13)):
        i.line([(x0, y), (x1, y + 1)], BONE.shade(-2), 1)                                                       # horn ridges
    i.ell(9, 11, 38, 43, RED_LAC)
    i.ell(22, 13, 25, 16, GOLD)                                                                                 # forehead stud
    i.poly([(11, 20), (24, 24), (36, 20), (36, 23), (24, 27), (11, 23)], RED_LAC.shade(-1.5))                    # brow ridge
    i.poly([(13, 25), (21, 27), (13, 29)], DARK); i.poly([(34, 25), (26, 27), (34, 29)], DARK)                   # slanted eyes
    i.px(15, 27, FIRE_Y); i.px(32, 27, FIRE_Y)                                                                  # their glow
    i.poly([(22, 28), (25, 28), (24, 33)], RED_LAC.shade(-1.5))                                                 # nose
    i.rect(15, 35, 32, 39, DARK, "flat")                                                                        # mouth
    for x in (16, 19, 22, 25, 28, 31):
        i.rect(x, 35, x + 1, 37, BONE, "flat")
    i.line([(10, 31), (15, 34)], INK, 1); i.line([(37, 31), (32, 34)], INK, 1)                                  # war paint


def bounty_ledger(i):
    i.rect(12, 10, 39, 42, PAPER)                                                                               # page block
    for y in (14, 18, 22, 26, 30, 34, 38):
        i.line([(36, y), (39, y)], PAPER.shade(-2), 1)
    i.rect(7, 6, 36, 40, LEATHER)                                                                               # cover
    i.rect(7, 6, 11, 40, LEATHER_D)                                                                             # spine
    for y in (10, 36):
        i.line([(7, y), (11, y)], GOLD_D, 1)
    for (x, y) in ((32, 6), (32, 36)):
        i.poly([(x, y), (x + 4, y), (x + 4, y + 4)] if y < 20 else [(x, y + 4), (x + 4, y + 4), (x + 4, y)], GOLD)  # corner caps
    i.ell(15, 14, 31, 30, GOLD); i.ell(18, 17, 28, 27, GOLD_D); i.ell(20, 19, 26, 25, GOLD)                    # the bounty seal
    i.line([(21, 20), (25, 24)], GOLD_D, 1); i.line([(25, 20), (21, 24)], GOLD_D, 1)
    i.rect(35, 19, 40, 26, LEATHER_D); i.rect(37, 21, 39, 24, GOLD)                                             # strap and buckle
    i.poly([(26, 40), (30, 40), (30, 46), (28, 44), (26, 46)], RED)                                            # ribbon marker


def cartographers_lens(i):
    i.line([(29, 29), (42, 42)], WOOD, 5)
    i.line([(26, 26), (31, 31)], GOLD, 6)                                                                       # ferrule
    i.ell(7, 7, 33, 33, GLASS)
    i.line([(11, 23), (16, 17), (21, 21), (27, 14)], MAP_LINE, 1)                                               # the map through the glass
    i.line([(13, 27), (20, 26), (26, 28)], MAP_BLUE, 1)
    i.px(21, 21, GEM_R)
    i.ring(20, 20, 13, 4, GOLD)
    i.px(11, 12, WHITE); i.px(12, 11, WHITE); i.px(11, 13, WHITE)                                               # glint


def coin_purse(i):
    i.ell(7, 15, 37, 45, LEATHER)
    i.line([(13, 31), (31, 31)], LEATHER.shade(-2), 1)                                                          # stitched seam
    for x in range(14, 31, 3):
        i.px(x, 30, LEATHER.shade(1.5))
    i.poly([(14, 17), (30, 17), (35, 7), (9, 7)], LEATHER_D)                                                    # gathered neck
    i.ell(9, 4, 35, 10, LEATHER)
    i.rect(11, 15, 33, 17, GOLD, "flat")                                                                        # drawstring
    i.ell(20, 13, 25, 19, GOLD)
    i.line([(23, 18), (27, 25)], GOLD, 2)                                                                       # its tail
    i.ell(29, 23, 42, 36, GOLD); i.ell(32, 26, 39, 33, GOLD_D)                                                  # coins spilling
    i.ell(26, 33, 38, 45, GOLD); i.ell(29, 36, 35, 42, GOLD_D)


def ember_brand(i):
    i.poly([(39, 3), (44, 8), (21, 31), (16, 26)], FIRE.shade(-1.8))                                           # a blade glowing like a coal
    i.line([(41, 6), (19, 28)], FIRE, 1)
    i.line([(12, 25), (23, 36)], IRON_D, 4)                                                                     # cross-guard
    i.ell(16, 29, 20, 33, GEM_R)
    i.line([(6, 42), (16, 32)], LEATHER, 4)                                                                     # wrapped grip
    for k in range(3):
        i.line([(9 + k * 3, 36 + -k * 3 + 4), (11 + k * 3, 38 - k * 3 + 4)], LEATHER_D, 1)
    i.ell(2, 40, 9, 47, GOLD)                                                                                   # pommel
    for x, y in ((36, 2), (45, 13), (30, 9), (44, 3)):
        i.px(x, y, FIRE_Y)                                                                                      # sparks


def fragile_champion(i):
    i.poly([(24, 3), (42, 10), (39, 30), (24, 45), (9, 30), (6, 10)], IRON)
    i.poly([(24, 7), (37, 13), (35, 28), (24, 40), (13, 28), (11, 13)], BLUE)
    i.ell(20, 17, 28, 25, GOLD)                                                                                 # boss
    for x, y in ((10, 12), (38, 12), (24, 42)):
        i.px(x, y, IRON_D)
    i.line([(25, 4), (21, 15), (27, 21), (19, 31), (23, 44)], DARK, 1)                                          # the crack
    i.line([(27, 21), (33, 24)], DARK, 1)
    i.poly([(38, 9), (42, 10), (41, 14)], DARK)                                                                 # a chipped corner


def gale_banner(i):
    i.line([(8, 6), (8, 45)], WOOD, 3)
    i.ell(5, 1, 11, 7, GOLD)
    i.poly([(10, 7), (40, 9), (33, 17), (40, 26), (10, 26)], BLUE)
    i.rect(10, 7, 13, 26, BLUE_D, "flat")                                                                       # hem along the pole
    i.ring(22, 17, 5, 1.5, CLOUD); i.px(22, 17, CLOUD)                                                          # a swirl of wind
    i.line([(15, 32), (38, 32)], ICE, 2); i.line([(21, 37), (44, 37)], ICE, 2); i.line([(13, 42), (31, 42)], ICE, 2)


def gamblers_coin(i):
    i.ell(5, 5, 42, 42, GOLD)
    i.ring(24, 24, 16, 2, GOLD_D)
    for k in range(16):                                                                                         # milled rim
        a = k / 16 * 6.2832
        i.px(24 + 18 * __import__("math").cos(a), 24 + 18 * __import__("math").sin(a), GOLD_D)
    i.ell(11, 11, 36, 36, GOLD)
    i.poly([(24, 12), (27, 20), (35, 20), (29, 25), (31, 33), (24, 28), (17, 33), (19, 25), (13, 20), (21, 20)], GOLD_D)
    i.ell(22, 21, 26, 25, GEM_R)


def giant_slayer(i):
    i.line([(7, 43), (31, 19)], WOOD, 4)
    for k in range(3):
        i.line([(10 + 2 * k, 37 - 2 * k), (13 + 2 * k, 40 - 2 * k)], LEATHER, 2)                                # grip wraps
    i.poly([(25, 6), (43, 13), (44, 33), (33, 26), (27, 20)], IRON)
    i.line([(43, 13), (44, 33)], WHITE, 1)                                                                      # honed edge
    i.line([(26, 21), (32, 15)], GOLD, 5)                                                                       # collar
    i.px(36, 18, IRON_D); i.px(37, 22, IRON_D)


def glass_cannon(i):
    i.poly([(6, 28), (35, 13), (40, 22), (11, 36)], GLASS)
    i.line([(8, 30), (35, 16)], WHITE, 1)
    for a, b in (((12, 25), (16, 32)), ((27, 17), (31, 24))):
        i.line([a, b], GOLD, 3)                                                                                 # bands
    i.ell(35, 11, 44, 22, GLASS); i.ell(38, 14, 42, 19, DARK)                                                   # muzzle
    i.ell(9, 27, 27, 45, WOOD); i.ring(18, 36, 7, 2, IRON_D); i.ell(15, 33, 21, 39, IRON_D)                     # wheel, tyre, hub
    for a, b in (((18, 29), (18, 43)), ((11, 36), (25, 36))):
        i.line([a, b], WOOD_D, 1)


def golden_idol(i):
    i.rect(8, 38, 39, 44, STONE)
    i.rect(13, 34, 34, 38, GOLD_D)
    i.poly([(15, 34), (32, 34), (30, 19), (17, 19)], GOLD)
    i.line([(17, 22), (11, 30)], GOLD, 3); i.line([(30, 22), (36, 30)], GOLD, 3)                                # arms
    i.ell(15, 5, 32, 21, GOLD)
    i.poly([(15, 9), (19, 2), (24, 7), (29, 2), (32, 9)], GOLD_D)                                               # headdress
    i.px(19, 12, GEM_R); i.px(27, 12, GEM_R)                                                                    # ruby eyes
    i.line([(21, 16), (26, 16)], GOLD_D, 1)
    i.ell(21, 24, 26, 29, GEM_G)                                                                                # chest gem


def guardian_ward(i):
    i.poly([(24, 3), (42, 10), (39, 30), (24, 45), (9, 30), (6, 10)], IRON)
    i.poly([(24, 7), (37, 13), (35, 28), (24, 40), (13, 28), (11, 13)], RED)
    i.rect(22, 10, 26, 37, GOLD); i.rect(14, 18, 34, 22, GOLD)
    i.ell(21, 17, 27, 23, GEM_B)
    for x, y in ((9, 11), (39, 11), (11, 29), (37, 29), (24, 43)):
        i.px(x, y, IRON_D)


def healing_bell(i):
    i.ring(24, 6, 3, 2, IRON_D)
    i.poly([(24, 7), (32, 12), (35, 32), (41, 37), (6, 37), (13, 32), (16, 12)], GOLD)
    i.line([(14, 18), (34, 18)], GOLD_D, 1)
    i.rect(5, 36, 42, 40, GOLD_D)                                                                               # lip
    i.ell(20, 39, 28, 47, IRON_D)                                                                               # clapper
    i.rect(22, 21, 26, 32, GEM_R); i.rect(18, 24, 30, 28, GEM_R)                                                 # the healing cross
    i.px(18, 14, WHITE); i.px(17, 16, WHITE)


def hunters_compass(i):
    i.ring(24, 5, 3, 2, GOLD)
    i.ell(5, 7, 43, 45, GOLD)
    i.ell(9, 11, 39, 41, PAPER)
    for k in range(8):                                                                                          # the eight points
        a = k / 8 * 6.2832
        import math as _m
        i.px(24 + 12 * _m.cos(a), 26 + 12 * _m.sin(a), INK)
    i.poly([(24, 13), (27, 26), (24, 24), (21, 26)], GEM_R)                                                     # north needle
    i.poly([(24, 39), (27, 26), (24, 28), (21, 26)], IRON_D)
    i.ell(22, 24, 26, 28, GOLD)


def iron_hide(i):
    i.poly([(11, 6), (18, 10), (29, 10), (36, 6), (42, 13), (38, 20), (37, 42), (10, 42), (9, 20), (5, 13)], IRON)
    i.line([(24, 11), (24, 41)], IRON_D, 2)                                                                     # centre ridge
    for y in (21, 30):
        i.line([(12, y), (35, y)], IRON_D, 1)                                                                   # plate lines
    for x, y in ((13, 14), (34, 14), (13, 38), (34, 38), (19, 25), (28, 25)):
        i.px(x, y, GOLD)                                                                                        # rivets
    i.poly([(18, 10), (24, 15), (29, 10)], LEATHER_D)                                                           # collar


def keen_edge(i):
    i.poly([(40, 3), (44, 7), (20, 31), (16, 27)], IRON)
    i.line([(42, 5), (18, 29)], WHITE, 1)
    i.line([(37, 9), (22, 24)], IRON_D, 1)                                                                      # fuller
    i.line([(12, 25), (23, 36)], GOLD, 4)
    i.line([(6, 42), (16, 32)], LEATHER, 4)
    i.ell(2, 40, 9, 47, GOLD); i.px(5, 43, GEM_B)


def lucky_charm(i):
    i.ring(24, 6, 3, 2, GOLD)
    for cx, cy in ((16, 16), (32, 16), (16, 32), (32, 32)):
        i.ell(cx - 9, cy - 9, cx + 8, cy + 8, LEAF)
    for cx, cy in ((16, 16), (32, 16), (16, 32), (32, 32)):
        i.line([(24, 24), (cx, cy)], LEAF_D, 1)                                                                 # veins
    i.ell(20, 20, 28, 28, LEAF_D)
    i.line([(27, 28), (35, 44)], LEAF_D, 2)
    i.px(15, 13, LEAF.shade(2)); i.px(31, 13, LEAF.shade(2))


def numbing_frost(i):
    import math as _m
    for k in range(6):
        a = k / 6 * 6.2832 - 1.5708
        ex, ey = 24 + 20 * _m.cos(a), 24 + 20 * _m.sin(a)
        i.line([(24, 24), (ex, ey)], ICE, 3)
        for t in (0.55, 0.8):                                                                                   # branches
            bx, by = 24 + 20 * t * _m.cos(a), 24 + 20 * t * _m.sin(a)
            for s in (-0.6, 0.6):
                i.line([(bx, by), (bx + 5 * _m.cos(a + s * 1.7), by + 5 * _m.sin(a + s * 1.7))], ICE, 2)
    i.ell(18, 18, 30, 30, ICE)
    i.ell(21, 21, 27, 27, GEM_B)


def oracles_bone(i):
    i.line([(10, 37), (37, 10)], BONE, 7)
    for cx, cy in ((8, 33), (13, 39), (34, 8), (39, 13)):
        i.ell(cx - 5, cy - 5, cx + 5, cy + 5, BONE)
    for x, y in ((19, 27), (23, 23), (27, 19)):
        i.line([(x - 1, y + 1), (x + 1, y - 1)], RUNE, 1)                                                       # glowing runes
        i.px(x + 1, y + 1, RUNE)
    i.line([(14, 34), (16, 32)], BONE.shade(-2), 1)


def pacemaker(i):
    i.rect(8, 3, 39, 7, WOOD); i.rect(8, 40, 39, 44, WOOD)
    i.line([(11, 7), (11, 40)], IRON_D, 2); i.line([(36, 7), (36, 40)], IRON_D, 2)                              # posts
    i.poly([(14, 8), (33, 8), (26, 23), (33, 39), (14, 39), (21, 23)], GLASS)
    i.poly([(17, 11), (30, 11), (24, 20)], SAND)                                                                # sand still to fall
    i.line([(24, 20), (24, 33)], SAND, 1)
    i.poly([(24, 29), (31, 38), (16, 38)], SAND)
    i.px(17, 13, WHITE); i.px(16, 15, WHITE)


def prism_shard(i):
    i.poly([(24, 2), (38, 17), (31, 45), (17, 45), (10, 17)], GEM_B)
    i.poly([(24, 2), (38, 17), (24, 20)], GEM_P)
    i.poly([(10, 17), (24, 20), (17, 45)], GEM_B)
    i.poly([(24, 20), (38, 17), (31, 45)], GEM_G)
    i.poly([(17, 45), (24, 20), (31, 45)], GEM_B.shade(-1))
    for x, y in ((21, 10), (20, 12), (33, 24)):
        i.px(x, y, WHITE)


def reckless_banner(i):
    i.line([(8, 5), (8, 45)], WOOD, 3)
    i.poly([(7, 2), (10, 2), (8.5, 6)], IRON)                                                                   # spike
    i.poly([(10, 6), (40, 6), (37, 13), (41, 18), (36, 22), (39, 28), (10, 28)], RED)
    i.ell(19, 10, 29, 20, BONE)                                                                                 # skull
    i.px(22, 14, DARK); i.px(26, 14, DARK); i.rect(22, 19, 26, 22, BONE, "flat")
    for x, y in ((33, 26), (16, 27), (38, 9)):
        i.px(x, y, DARK)                                                                                        # tears in the cloth


def seers_mirror(i):
    i.line([(24, 31), (24, 45)], WOOD, 5)
    i.ell(21, 41, 27, 47, GOLD)
    i.ell(8, 3, 40, 35, GOLD)
    i.ell(12, 7, 36, 31, MIRROR)
    i.ell(18, 15, 30, 23, CLOUD); i.ell(22, 17, 26, 21, GEM_P)                                                  # the seeing eye
    i.px(15, 11, WHITE); i.px(16, 10, WHITE); i.px(14, 13, WHITE)


def siege_engine(i):
    i.rect(5, 26, 42, 31, WOOD)
    i.line([(10, 26), (34, 7)], WOOD_D, 4)                                                                      # throwing arm
    i.ell(32, 2, 41, 11, STONE)                                                                                 # the stone
    i.line([(24, 26), (24, 18)], WOOD, 3)
    i.ell(5, 30, 17, 42, WOOD_D); i.ell(30, 30, 42, 42, WOOD_D)
    i.ell(9, 34, 13, 38, IRON); i.ell(34, 34, 38, 38, IRON)
    i.rect(19, 18, 29, 21, IRON_D)


def smoldering_torch(i):
    i.line([(15, 45), (26, 20)], WOOD, 5)
    for k in range(3):
        i.line([(17 + 2 * k, 37 - 4 * k), (22 + 2 * k, 39 - 4 * k)], LEATHER, 2)                                # wrapping
    i.poly([(20, 17), (32, 17), (30, 23), (22, 23)], IRON_D)                                                    # the cup
    i.ell(17, 2, 35, 20, FIRE)
    i.ell(21, 6, 31, 17, FIRE_Y)
    i.px(25, 9, WHITE_HOT)


def snare_wire(i):
    for r in (16, 12, 8):
        i.ring(22, 22, r, 2, IRON)
    i.line([(36, 32), (42, 41)], IRON_D, 2)
    i.poly([(40, 40), (45, 46), (38, 45)], IRON)                                                                # hook
    i.ell(20, 20, 25, 25, WOOD)


def storm_coil(i):
    i.rect(13, 36, 34, 44, STONE)
    for y in range(17, 36, 4):
        i.rect(15, y, 32, y + 2, COPPER)                                                                        # the coil windings
    i.line([(23, 16), (23, 36)], IRON_D, 2)
    i.ell(17, 6, 30, 19, IRON)
    i.poly([(32, 0), (22, 13), (28, 13), (21, 26), (37, 10), (30, 10)], LIGHTNING)


def surveyors_ledger(i):
    i.rect(8, 12, 37, 40, PAPER)
    i.ell(4, 9, 12, 17, PAPER); i.ell(33, 35, 41, 43, PAPER)                                                    # rolled ends
    for y in (17, 22, 27, 32):
        i.line([(13, y), (32, y)], INK.shade(2), 1)
    i.line([(14, 35), (22, 29), (29, 33)], MAP_LINE, 1)
    i.line([(27, 33), (43, 5)], FEATHER, 3)                                                                     # the quill
    i.line([(28, 31), (26, 35)], INK, 1)


def swift_feather(i):
    i.poly([(40, 4), (34, 20), (18, 37), (11, 34), (21, 17)], FEATHER)
    i.line([(41, 3), (8, 41)], FEATHER.shade(-2), 1)                                                            # quill shaft
    for t in (0.25, 0.4, 0.55, 0.7):
        x, y = 41 - 33 * t, 3 + 38 * t
        i.line([(x, y), (x + 6, y + 2)], FEATHER.shade(-1), 1)                                                  # barbs
        i.line([(x, y), (x - 2, y - 5)], FEATHER.shade(-1), 1)
    i.poly([(40, 4), (31, 8), (23, 16)], SKY)


def tarnished_crown(i):
    i.poly([(5, 36), (5, 13), (14, 22), (24, 8), (34, 22), (43, 13), (43, 36)], TARNISH)
    i.rect(5, 32, 43, 39, TARNISH.shade(-1))                                                                    # band
    for x in (5, 24, 43):
        i.ell(x - 2, 6 if x == 24 else 11, x + 2, 10 if x == 24 else 15, TARNISH)                              # point knobs
    i.ell(21, 24, 27, 30, DARK)                                                                                 # the empty setting
    i.ell(10, 25, 15, 30, GEM_B); i.ell(33, 25, 38, 30, GEM_B)
    for x, y in ((13, 34), (30, 36), (38, 20), (17, 18)):
        i.px(x, y, PATINA)                                                                                      # verdigris


def tide_charm(i):
    i.line([(24, 1), (24, 8)], LEATHER, 1)
    i.poly([(24, 8), (40, 20), (36, 38), (12, 38), (8, 20)], SHELL)
    for x in (12, 18, 24, 30, 36):
        i.line([(24, 10), (x, 37)], SHELL.shade(-2), 1)                                                         # ridges
    i.rect(14, 38, 34, 41, SHELL.shade(-1))
    i.ell(20, 39, 28, 47, PEARL)


def titans_bane(i):
    i.line([(6, 44), (30, 20)], WOOD, 4)
    i.poly([(26, 16), (44, 3), (33, 22)], IRON)
    i.line([(43, 4), (30, 19)], WHITE, 1)
    i.line([(25, 21), (30, 16)], GOLD, 6)
    i.poly([(26, 23), (21, 32), (24, 33), (28, 25)], RED)                                                       # tassel
    i.poly([(28, 24), (25, 34), (29, 33), (30, 25)], RED_D)


def traders_pouch(i):
    i.ell(5, 14, 31, 44, BURLAP)
    i.poly([(11, 16), (25, 16), (29, 6), (7, 6)], BURLAP.shade(-1))
    i.ell(8, 3, 28, 9, BURLAP)
    i.rect(9, 14, 27, 16, RED, "flat")
    for y in (40, 36, 32, 28, 24):
        i.rect(30, y, 42, y + 3, GOLD)                                                                          # a stack of coins
        i.line([(30, y + 3), (42, y + 3)], GOLD_D, 1)


def training_weights(i):
    i.rect(12, 21, 35, 26, IRON_D)
    i.rect(14, 21, 33, 26, LEATHER, "flat")                                                                     # grip
    for x0 in (4, 34):
        i.rect(x0, 11, x0 + 9, 36, IRON)
        i.line([(x0 + 4, 12), (x0 + 4, 35)], IRON_D, 1)
    i.rect(1, 17, 4, 30, IRON_D); i.rect(43, 17, 46, 30, IRON_D)


def vampiric_fang(i):
    i.poly([(13, 3), (34, 3), (31, 20), (25, 41), (22, 41), (16, 20)], BONE)
    i.line([(20, 6), (23, 36)], BONE.shade(2), 1)
    i.rect(13, 3, 34, 7, RED_D, "flat")                                                                         # the gum
    i.ell(28, 33, 37, 44, BLOOD)
    i.poly([(32, 27), (36, 35), (29, 35)], BLOOD)
    i.px(30, 36, WHITE)


def veil_of_mist(i):
    i.poly([(12, 4), (36, 4), (42, 38), (33, 33), (24, 42), (15, 33), (6, 38)], VEIL)
    i.poly([(16, 4), (32, 4), (29, 17), (19, 17)], VEIL.shade(1.5))
    for x in (17, 24, 31):
        i.line([(x, 18), (x - 1 if x < 24 else x + 1, 36)], VEIL.shade(-1.5), 1)                                # folds
    i.line([(2, 38), (16, 38)], CLOUD, 2); i.line([(26, 43), (45, 43)], CLOUD, 2); i.line([(32, 36), (44, 36)], CLOUD, 1)


def verdant_seed(i):
    i.ell(11, 22, 36, 45, SEED)
    i.line([(23, 25), (23, 44)], SEED.shade(-2), 1)
    i.line([(23, 23), (23, 10)], LEAF_D, 3)
    i.poly([(23, 14), (7, 6), (12, 18)], LEAF)
    i.poly([(24, 11), (41, 3), (36, 15)], LEAF)
    i.line([(23, 14), (11, 9)], LEAF_D, 1); i.line([(24, 11), (37, 6)], LEAF_D, 1)


def veterans_medal(i):
    i.poly([(13, 2), (22, 2), (25, 20), (19, 20)], RED); i.poly([(26, 2), (35, 2), (29, 20), (23, 20)], BLUE)
    i.line([(17, 2), (21, 20)], WHITE, 1)
    i.rect(18, 18, 30, 22, GOLD)
    i.ell(11, 20, 37, 46, GOLD)
    i.ell(15, 24, 33, 42, GOLD_D)
    i.poly([(24, 26), (27, 31), (33, 32), (28, 36), (29, 41), (24, 38), (19, 41), (20, 36), (15, 32), (21, 31)], GOLD)


def war_banner(i):
    i.line([(8, 5), (8, 45)], WOOD, 3)
    i.ell(5, 1, 11, 7, GOLD)
    i.poly([(10, 6), (40, 6), (40, 27), (25, 33), (10, 27)], RED)
    i.line([(10, 25), (40, 25)], GOLD, 2)                                                                       # gold hem
    i.ell(17, 9, 33, 25, GOLD)
    i.ell(20, 12, 30, 22, RED_D)
    i.poly([(25, 13), (28, 18), (25, 21), (22, 18)], GOLD)


def wardens_seal(i):
    i.rect(20, 1, 28, 12, WOOD)                                                                                 # the stamp's handle
    i.ell(18, 0, 30, 6, WOOD)
    i.ell(5, 11, 43, 46, WAX)
    i.ring(24, 28, 13, 2, WAX.shade(-1.5))
    i.poly([(24, 18), (32, 23), (29, 36), (19, 36), (16, 23)], WAX.shade(-1))                                   # the warden's mark
    i.poly([(24, 22), (28, 25), (26, 32), (22, 32), (20, 25)], WAX.shade(1))
    for x, y in ((6, 20), (41, 33), (12, 42)):
        i.px(x, y, WAX)                                                                                         # drips


RECIPES = {f.__name__: f for f in (
    berserkers_mask, bounty_ledger, cartographers_lens, coin_purse, ember_brand, fragile_champion, gale_banner, gamblers_coin, giant_slayer,
    glass_cannon, golden_idol, guardian_ward, healing_bell, hunters_compass, iron_hide, keen_edge, lucky_charm, numbing_frost, oracles_bone,
    pacemaker, prism_shard, reckless_banner, seers_mirror, siege_engine, smoldering_torch, snare_wire, storm_coil, surveyors_ledger,
    swift_feather, tarnished_crown, tide_charm, titans_bane, traders_pouch, training_weights, vampiric_fang, veil_of_mist, verdant_seed,
    veterans_medal, war_banner, wardens_seal)}


def relic_ids() -> list[str]:
    import json
    ids = []
    for p in sorted(MODIFIERS.glob("*.json")):
        if json.loads(p.read_text(encoding="utf-8")).get("relic"):
            ids.append(p.stem)
    return ids


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    ids = relic_ids()
    missing = [r for r in ids if r not in RECIPES]
    if missing:
        print("no recipe for", missing)
        return 1
    icons = {}
    for rid in ids:
        icon = Icon()
        RECIPES[rid](icon)
        icons[rid] = icon.finish()
        icons[rid].save(OUT / f"{rid}.png")
    print(f"wrote {len(icons)} icons to {OUT}")
    if "--sheet" in sys.argv:
        z = 3                                                        # 48 px at 3x: every pixel square
        cols, cell = 8, N * z + 24
        rows = (len(ids) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell, rows * (cell + 16)), (28, 22, 34))
        d = ImageDraw.Draw(sheet)
        for k, rid in enumerate(ids):
            x, y = (k % cols) * cell, (k // cols) * (cell + 16)
            d.rectangle((x + 6, y + 6, x + 6 + N * z + 11, y + 6 + N * z + 11), fill=(44, 36, 54))
            big = icons[rid].resize((N * z, N * z), Image.NEAREST)
            sheet.paste(big, (x + 12, y + 12), big)
            d.text((x + 8, y + N * z + 20), rid.replace("_", " "), fill=(230, 220, 200))
        path = Path(sys.argv[sys.argv.index("--sheet") + 1])
        sheet.save(path)
        print("sheet", path)
    if "--bridge" in sys.argv:
        dest = Path(sys.argv[sys.argv.index("--bridge") + 1])
        for rid, im in icons.items():
            im.save(dest / f"relic_{rid}.png")
        print("copied to", dest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
