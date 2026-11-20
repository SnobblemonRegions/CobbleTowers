#!/usr/bin/env python3
"""Top-down plan and cross-section of a generated floor, for checking columns, symmetry and heights at a glance.

    py -3.14 -I tools/tower_gen/plan_view.py <out.png> [floor] [--theme neutral]

Left: the plan at chest height of the floor (columns gold, walls grey, open floor dark, the centre line red). Right: the battle hall's
cross-section through a column row (x across, y up), so a column that stops short of the vault shows as a gap. Then the prep room's plan.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tower_gen as tg  # noqa: E402

COL = {"column": (60, 150, 230), "glass": (150, 210, 240), "pier": (230, 182, 52), "trim": (200, 160, 90), "rib": (150, 120, 70)}


def cell(g, x, y, z):
    name = g.name(x, y, z)
    if name == tg.AIR:
        return (24, 22, 30)
    for role, c in COL.items():
        if g.theme.get(role) == name:
            return c
    return (110, 110, 120)


def plan(g, y, x0, x1, z0, z1, mid_x=None):
    img = Image.new("RGB", (x1 - x0 + 1, z1 - z0 + 1))
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            img.putpixel((x - x0, z - z0), cell(g, x, y, z))
    if mid_x is not None:
        for z in range(z1 - z0 + 1):
            if img.getpixel((mid_x - x0, z)) == (24, 22, 30):
                img.putpixel((mid_x - x0, z), (120, 30, 40))
    return img


def section(g, z, x0, x1, y0, y1):
    img = Image.new("RGB", (x1 - x0 + 1, y1 - y0 + 1))
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            img.putpixel((x - x0, y1 - y), cell(g, x, y, z))
    return img


def main() -> int:
    args = sys.argv[1:]
    name = args.pop(args.index("--theme") + 1) if "--theme" in args else "neutral"
    args = [a for a in args if a != "--theme"]
    out = Path(args[0])
    floor = int(args[1]) if len(args) > 1 else 1
    theme = tg.THEMES[name]
    g = tg.Grid(theme, (tg.SX, tg.SY, tg.SZ))
    g.grid[:] = g.id("wall")
    tg.build_floor(g, floor)
    f = tg.feet(tg.local(floor))
    p = plan(g, f + 2, 0, tg.SX - 1, tg.ATRIUM_Z[0] - 2, tg.ARRIVAL_Z[1] + 2, tg.MID_X)
    pier_z = next(z for z in range(tg.HALL_Z[0], tg.HALL_Z[1]) if g.name(tg.PIERS_X[0], f + 2, z) == theme.get("column", theme["pier"]))
    s = section(g, pier_z, 0, tg.SX - 1, f - 1, f + 30)
    prep = tg.build_prep(theme)
    pp = plan(prep, tg.PREP_FEET + 2, 0, tg.PREP_SIZE[0] - 1, 0, tg.PREP_SIZE[2] - 1)
    k = 6
    sheet = Image.new("RGB", ((p.width + s.width + pp.width) * k + 40, max(p.height, s.height, pp.height) * k + 30), (12, 10, 16))
    xo = 0
    for im, label in ((p, "hall + atrium plan, chest height"), (s, f"hall section at z {pier_z}"), (pp, "prep room plan")):
        sheet.paste(im.resize((im.width * k, im.height * k), Image.NEAREST), (xo, 24))
        ImageDraw.Draw(sheet).text((xo + 4, 4), label, fill=(230, 220, 200))
        xo += im.width * k + 20
    sheet.save(out)
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
