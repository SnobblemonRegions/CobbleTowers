"""Exports the Gate Altars (gate_altar.py) as item models for the mod (owner, 20 Nov 2026).

Per tower: models/altar/<tower>/body.json and body_glow.json (the still altar, in the altar's frame, drawn full bright for the glow one),
focus.json and focus_glow.json (the turning focus, centred on its pivot), and an atlas textures/item/altar/<tower>.png. Shared, in
models/altar/common/: rune_off and rune_0..rune_6 (one rune tile per Threat colour), ready_off and ready_on (one ready strip), crystal_first
and crystal_best, mote_first and mote_best, centred on themselves; atlas textures/item/altar/common.png. And data/.../hub/altar.json: where
each moving part sits, in blocks from the altar's floor centre (front = +z), so the mod places exactly what was drawn.

Item models must keep every coordinate within -16..32: the body is moved by (+8, -16, +8) (the mod adds 1.5 blocks up); a centred part
has its centre at (8, 8, 8). Elements are split on the 16-unit grid so each face's texels match the concept renders (texel = coordinate mod 16).

    python tools/tower_gen/altar_export.py
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

import gate_altar as GA
import mode_board as MB
import mode_icons as MI

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "src/main/resources/assets/cobbletowers/models/altar"
TEXTURES = ROOT / "src/main/resources/assets/cobbletowers/textures/item/altar"
DATA = ROOT / "src/main/resources/data/cobbletowers/cobbletowers/hub/altar.json"
TOWERS = ("neutral", "tideforge", "duskvale", "rootvale")
BODY_SHIFT = (8, -16, 8)
COMMON_MATS = ["rune_off"] + [f"rune_on{i}" for i in range(7)] + ["ready_off", "ready_on", "first", "best", "mote_first", "mote_best"]


class Atlas:
    """Materials packed 8 to a row as 16 x 16 tiles; UVs in Minecraft's 0..16 space over the whole image."""

    def __init__(self, materials: dict[str, dict], seed_key: str):
        self.names = sorted(materials)
        self.materials = materials
        cols = 8
        rows = (len(self.names) + cols - 1) // cols
        self.w, self.h = cols * 16, max(1, rows) * 16
        self.img = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        self.at = {}
        for i, name in enumerate(self.names):
            c, r = i % cols, i // cols
            tex = GA.texture(materials[name], seed=abs(hash((seed_key, name))) & 0xFFFF)
            self.img.paste(Image.fromarray((tex * 255).astype(np.uint8)), (c * 16, r * 16))
            self.at[name] = (c * 16, r * 16)

    def uv(self, mat: str, u0, v0, u1, v1) -> list[float]:
        ox, oy = self.at[mat]
        sx, sy = 16 / self.w, 16 / self.h
        return [round((ox + u0) * sx, 4), round((oy + v0) * sy, 4), round((ox + u1) * sx, 4), round((oy + v1) * sy, 4)]


def split(lo, hi):
    """Cuts a box on the 16-unit grid, so no piece crosses a tile edge."""
    pieces = [(list(lo), list(hi))]
    for axis in range(3):
        out = []
        for a, b in pieces:
            cuts = [a[axis]] + [g for g in range(int(math.floor(a[axis] / 16)) * 16 + 16, int(math.ceil(b[axis])), 16) if a[axis] < g < b[axis]] + [b[axis]]
            for c0, c1 in zip(cuts, cuts[1:]):
                na, nb = list(a), list(b)
                na[axis], nb[axis] = c0, c1
                out.append((na, nb))
        pieces = out
    return pieces


def local(c0: float, c1: float) -> tuple[float, float]:
    """A piece's extent inside its 16-unit cell, 0..16."""
    cell = math.floor(c0 / 16 + 1e-6) * 16
    return c0 - cell, c1 - cell


def element(lo, hi, mat, atlas: Atlas, shift, name: str) -> list[dict]:
    out = []
    for a, b in split(lo, hi):
        if min(b[i] - a[i] for i in range(3)) <= 1e-6:
            continue
        (x0, x1), (y0, y1), (z0, z1) = (local(a[i], b[i]) for i in range(3))
        faces = {
            "north": atlas.uv(mat, 16 - x1, 16 - y1, 16 - x0, 16 - y0), "south": atlas.uv(mat, x0, 16 - y1, x1, 16 - y0),
            "west": atlas.uv(mat, z0, 16 - y1, z1, 16 - y0), "east": atlas.uv(mat, 16 - z1, 16 - y1, 16 - z0, 16 - y0),
            "up": atlas.uv(mat, x0, z0, x1, z1), "down": atlas.uv(mat, x0, 16 - z1, x1, 16 - z0),
        }
        frm = [round(a[i] + shift[i], 4) for i in range(3)]
        to = [round(b[i] + shift[i], 4) for i in range(3)]
        assert all(-16 <= v <= 32 for v in frm + to), (name, frm, to)
        out.append({"name": name, "from": frm, "to": to, "faces": {k: {"uv": v, "texture": "#t"} for k, v in faces.items()}})
    return out


def model(elements: list[dict], texture: str) -> dict:
    return {"textures": {"t": texture, "particle": texture}, "elements": elements,
            "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}}


def chunked(els, atlas: "Atlas", tex: str, base: Path, name: str, origin: tuple[int, int, int]) -> list[list]:
    """Model elements bigger than an item model allows: cut on the 16-unit grid, binned into 48-unit chunks from `origin` (a multiple of 16),
    each chunk its own model (and a _glow one for full-bright materials). Returns [model name, x, y, z] per model: where the chunk's middle
    sits, in blocks, in the altar's frame."""
    bins: dict[tuple[int, int, int, bool], list[dict]] = {}
    for lo, hi, mat, _ in els:
        for a, b in split(lo, hi):
            if min(b[i] - a[i] for i in range(3)) <= 1e-6:
                continue
            k = tuple(int(math.floor((a[i] - origin[i]) / 48 + 1e-6)) for i in range(3))
            centre = tuple(origin[i] + (k[i] + 0.5) * 48 for i in range(3))
            shift = tuple(8 - centre[i] for i in range(3))
            glow = atlas.materials[mat]["glow"]
            bins.setdefault((*k, glow), []).extend(element(a, b, mat, atlas, shift, mat))
    out = []
    for (kx, ky, kz, glow), elements in sorted(bins.items()):
        part = f"{name}_{kx}_{ky}_{kz}" + ("_glow" if glow else "")
        write(base / f"{part}.json", model(elements, tex))
        centre = [origin[0] + (kx + 0.5) * 48, origin[1] + (ky + 0.5) * 48, origin[2] + (kz + 0.5) * 48]
        out.append([part, *[round(c / 16, 4) for c in centre], glow])
    return out


def write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n")


def centre_of(els) -> tuple[float, float, float]:
    lo = np.min([e.lo for e in els], axis=0)
    hi = np.max([e.hi for e in els], axis=0)
    return tuple(((lo + hi) / 2).tolist())


def main() -> int:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    layout = {}
    for tower in TOWERS:
        alt = GA.build(tower)
        own = {k: v for k, v in alt.materials.items() if k not in COMMON_MATS}
        own.update(MB.materials(tower))                     # the mode boards' materials share the tower's atlas
        atlas = Atlas(own, tower)
        atlas.img.save(TEXTURES / f"{tower}.png")
        tex = f"cobbletowers:item/altar/{tower}"
        body, glow, focus, focus_glow = [], [], [], []
        focus_els = [e for e in alt.elements if e.tag == "focus"]
        pivot = centre_of(focus_els)
        fshift = tuple(8 - pivot[i] for i in range(3))
        for e in alt.elements:
            if e.tag.startswith(("rune", "ready")) or e.tag in ("first", "best"):
                continue
            g = alt.materials[e.mat]["glow"]
            if e.tag == "focus":
                (focus_glow if g else focus).extend(element(e.lo, e.hi, e.mat, atlas, fshift, "focus"))
            else:
                (glow if g else body).extend(element(e.lo, e.hi, e.mat, atlas, BODY_SHIFT, e.mat))
        for name, els in (("body", body), ("body_glow", glow), ("focus", focus), ("focus_glow", focus_glow)):
            write(MODELS / tower / f"{name}.json", model(els, tex))
        layout[tower] = {"focus": [round(v / 16, 4) for v in pivot]}
        # the mode boards: the left (the tiles' frame and plaques) and the right (the rules panel), each cut into chunks
        left = [e for e in MB.board(-1, None) if not e[2].startswith(("icon_", "frame_lit"))]
        right = MB.board(1, None)
        layout[tower]["board_left"] = chunked(left, atlas, tex, MODELS / tower, "board_left", (-144, 0, -80))
        layout[tower]["board_right"] = chunked(right, atlas, tex, MODELS / tower, "board_right", (96, 0, -80))
        print(tower, "body", len(body), "glow", len(glow), "focus", len(focus) + len(focus_glow))

    # the shared parts, each centred on itself
    alt = GA.build("neutral")
    common = {k: alt.materials[k] for k in COMMON_MATS}
    atlas = Atlas(common, "common")
    atlas.img.save(TEXTURES / "common.png")
    tex = "cobbletowers:item/altar/common"
    runes = [e for e in alt.elements if e.tag.startswith("rune")]
    r0 = runes[0]
    rc = centre_of([r0])
    for name in ["rune_off"] + [f"rune_on{i}" for i in range(7)]:
        sh = tuple(8 - rc[i] for i in range(3))
        out = "rune_off" if name == "rune_off" else "rune_" + name[-1]
        write(MODELS / "common" / f"{out}.json", model(element(r0.lo, r0.hi, name, atlas, sh, out), tex))
    strips = [e for e in alt.elements if e.tag.startswith("ready")]
    s0 = strips[0]                                          # the front strip, long along x
    sc = centre_of([s0])
    for name in ("ready_off", "ready_on"):
        sh = tuple(8 - sc[i] for i in range(3))
        write(MODELS / "common" / f"{name}.json", model(element(s0.lo, s0.hi, name, atlas, sh, name), tex))
    for tag in ("first", "best"):
        els = [e for e in alt.elements if e.tag == tag]
        c = centre_of(els)
        sh = tuple(8 - c[i] for i in range(3))
        write(MODELS / "common" / f"crystal_{tag}.json", model([x for e in els for x in element(e.lo, e.hi, tag, atlas, sh, tag)], tex))
        mote = element((-0.9, -0.9, -0.9), (0.9, 0.9, 0.9), "mote_" + tag, atlas, (8, 8, 8), "mote")
        write(MODELS / "common" / f"mote_{tag}.json", model(mote, tex))
        layout[f"crystal_{tag}"] = [round(v / 16, 4) for v in c]
    layout["runes"] = [[round(v / 16, 4) for v in centre_of([e])] for e in runes]
    # the mode tiles: an icon on its front (+x) face in a dark frame, centred; the lit frame drawn round the chosen one
    for mode in MI.MODES:
        ic = MI.icon(mode)                                      # opaque: the icon on a dark plate, so the board never shows through it
        plate = GA.texture(GA.mat("polished", "#221d2a"), seed=11)
        rgb = ic[..., :3] * ic[..., 3:] + plate * (1 - ic[..., 3:])
        Image.fromarray((rgb * 255).astype(np.uint8), "RGB").save(TEXTURES / f"icon_{mode}.png")
        icon = {"name": "icon", "from": [7.5, 0, 0], "to": [8.5, 16, 16],
                "faces": {"east": {"uv": [0, 0, 16, 16], "texture": "#i"}, "west": {"uv": [0, 0, 16, 16], "texture": "#i"},
                          **{f: {"uv": [0, 0, 1, 1], "texture": "#t"} for f in ("north", "south", "up", "down")}}}
        frame = []
        for lo, hi in (((7, -1.5, -1.5), (9, 0, 17.5)), ((7, 16, -1.5), (9, 17.5, 17.5)), ((7, 0, -1.5), (9, 16, 0)), ((7, 0, 16), (9, 16, 17.5))):
            frame += element(lo, hi, "ready_off", atlas, (0, 0, 0), "frame")
        data = model([icon] + frame, tex)
        data["textures"]["i"] = f"cobbletowers:item/altar/icon_{mode}"
        write(MODELS / "common" / f"tile_{mode}.json", data)
    lit = []
    for lo, hi in (((6.5, -2.5, -2.5), (9.5, -0.5, 18.5)), ((6.5, 16.5, -2.5), (9.5, 18.5, 18.5)), ((6.5, -0.5, -2.5), (9.5, 16.5, -0.5)),
                   ((6.5, -0.5, 16.5), (9.5, 16.5, 18.5))):
        lit += element(lo, hi, "ready_on", atlas, (0, 0, 0), "lit")
    write(MODELS / "common" / "tile_lit.json", model(lit, tex))
    layout["tiles"] = {"modes": list(MI.MODES), "x": round((-MB.BOARD_X + 0.5) / 16, 4), "proud": round(2 / 16, 4), "y": round(25 / 16, 4),
                       "z": [round(MB.tile_z(i) / 16, 4) for i in range(len(MI.MODES))]}
    # ready strips: the front one's centre; the others are it turned a quarter at a time about the altar's centre
    layout["ready"] = [round(v / 16, 4) for v in sc]
    layout["body_up"] = 1.5
    layout["footprint"] = 1.5
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.write_text(json.dumps(layout, indent=2) + "\n")
    print("common parts and", DATA.name, "written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
