"""The gate mode boards (owner, 20 Nov 2026): a pair of boards flanking each Gate Altar, in the altar's frame (1/16-block units, the altar at
the origin, its front +z toward the plaza, the gate at -z). The left board shows the seven modes as framed icon tiles (the leader clicks one;
the chosen tile stands proud in a lit frame); the right board is the rules panel for the chosen mode (its text drawn by the mod). Same
materials as the altar (gate_altar.THEMES), so each tower's boards wear its palette.

Elements are (lo, hi, material, uv) where uv is None for world-tiled textures, or "+x" / "-x" for an icon mapped once onto that face.
"""
from __future__ import annotations

import gate_altar as GA
import mode_icons as MI

BOARD_X = 120                                   # each board's face, out from the path's middle (7.5 blocks)
SPAN = 66                                        # half the board's length along the path
TILE = 16
STEP = 18                                        # tile pitch along the board


def tile_z(i: int) -> float:
    """Seven tiles centred on z = 0, the first (Standard) on the left as seen from the path."""
    return STEP * 3 - STEP * i


def board(side: int, chosen: str | None) -> list[tuple]:
    """side -1: the mode tiles (left, facing +x); side 1: the rules panel (right, facing -x)."""
    s = side
    face = s * BOARD_X
    els = []

    def box(x0, y0, z0, x1, y1, z1, mat, uv=None):
        els.append(((min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1)), mat, uv))

    back = face + s * 8                          # the board's back, further from the path
    box(back + s * 2, 0, -SPAN - 6, face - s * 4, 4, SPAN + 6, "base")            # a stepped plinth
    box(back, 4, -SPAN - 4, face - s * 2, 8, SPAN + 4, "base2")
    box(back, 8, -SPAN, face + s * 0, 46, SPAN, "core")                             # the board
    for zz in (-SPAN - 4, SPAN):                                                    # end pilasters, crowned
        box(back + s * 1, 0, zz, face - s * 3, 52, zz + 4, "base")
        box(back + s * 2, 52, zz - 1, face - s * 4, 56, zz + 5, "top")
        box(back + s * 2, 44, zz - 0.5, face - s * 3.5, 46, zz + 4.5, "trim")
    box(back + s * 2, 46, -SPAN - 2, face - s * 3, 50, SPAN + 2, "base2")          # the cornice, gold-banded
    box(back + s * 2.5, 48.5, -SPAN - 2.5, face - s * 3.5, 49.5, SPAN + 2.5, "trim")
    box(back + s * 1, 50, -SPAN, face - s * 2, 52, SPAN, "top")
    box(back, 52, -10, face - s * 1, 62, 10, "trim")                                # the crest over the middle
    box(back, 55, -6, face - s * 2, 59, 6, "accent")
    if side < 0:
        for i, mode in enumerate(MI.MODES):
            zc = tile_z(i)
            proud = mode == chosen
            out = face - s * (3 if proud else 1)                                    # the chosen tile stands proud
            frame = "frame_lit" if proud else "base2"
            box(face, 15, zc - 9.5, out - s * 0.5, 16.5, zc + 9.5, frame)
            box(face, 33.5, zc - 9.5, out - s * 0.5, 35, zc + 9.5, frame)
            box(face, 16.5, zc - 9.5, out - s * 0.5, 33.5, zc - 8, frame)
            box(face, 16.5, zc + 8, out - s * 0.5, 33.5, zc + 9.5, frame)
            box(face, 17, zc - 8, out, 33, zc + 8, "icon_" + mode, "+x")
            box(face, 10, zc - 7, face - s * 1.5, 13, zc + 7, "plaque")             # its name plate below (text by the mod)
    else:
        box(face, 13, -56, face - s * 1, 41, 56, "panel")                           # the rules panel, framed
        for z0, z1 in ((-58, -56), (56, 58)):
            box(face, 12, z0, face - s * 2, 42, z1, "frame_lit")
        box(face, 41, -58, face - s * 2, 43, 58, "frame_lit")
        box(face, 11, -58, face - s * 2, 13, 58, "frame_lit")
    return els


def materials(tower: str) -> dict:
    """The boards' own materials on top of the altar's: a lit frame, the dark panel, the plaques, and the icons."""
    m = dict(GA.THEMES[tower])
    m["frame_lit"] = GA.mat("glow", "#f2c744", glow=True)
    m["panel"] = GA.mat("polished", "#15121c")
    m["plaque"] = GA.mat("polished", "#2a2532")
    return m
