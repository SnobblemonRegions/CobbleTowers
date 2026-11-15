#!/usr/bin/env python3
"""Eye-level pictures of the generated tower, for judging interiors without a Minecraft client.

    python tools/halloween_tower/view.py <out_dir> <floor> name:x,y,z,yaw,pitch [name:...]
    python tools/halloween_tower/view.py shots 1 hall:50,0,30,0,5

Positions are floor-local tower coordinates (x east, z south, y relative to the floor's feet; the eye is 1.62 above). Yaw as in the game:
0 looks south (+z), 90 west, 180 north, -90 east. Pitch is positive down.

A voxel ray caster over the real 1.21.1 block textures (read from the Loom cache jar), with the game's own block-light propagation
(15 levels, one lost per block, white light) so a room looks as bright as it will in play. Stairs, slabs, walls, lanterns, chains, bars,
candles and a few thin blocks are boxes; the rest are cubes. Glass tints, cobwebs and bars cut out. It is a design aid, not the renderer:
no ambient occlusion, no entities.
"""
from __future__ import annotations

import io
import re
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hollow_tower as H  # noqa: E402

JAR_GLOBS = ["~/.gradle/caches/fabric-loom/1.21.1/minecraft-merged.jar", "~/AppData/Roaming/.minecraft/versions/1.21.1/1.21.1.jar"]
FULL = [(0, 0, 0, 1, 1, 1)]
WATER_GLOW = np.array([0.006, 0.024, 0.045], dtype=np.float32)
EMIT = {"jack_o_lantern": 15, "shroomlight": 15, "glowstone": 15, "sea_lantern": 15, "lantern": 15, "soul_lantern": 10, "torch": 14, "soul_torch": 10,
        "end_rod": 14, "ochre_froglight": 15, "verdant_froglight": 15, "pearlescent_froglight": 15, "lava": 15, "fire": 15, "soul_fire": 10,
        "crying_obsidian": 10, "amethyst_cluster": 5, "magma_block": 3, "candle": 3, "redstone_lamp": 15, "campfire": 15, "soul_campfire": 10}
AVG_ONLY = {"lantern", "soul_lantern", "candle", "chain", "end_rod", "amethyst_cluster", "skeleton_skull", "wither_skeleton_skull", "torch", "soul_torch",
            "cauldron", "lava_cauldron", "anvil", "campfire", "soul_campfire", "flower_pot"}
TRANSPARENT_LIGHT = {"air", "cobweb", "iron_bars", "chain", "lantern", "soul_lantern", "candle", "end_rod", "amethyst_cluster", "skeleton_skull",
                     "torch", "soul_torch", "wither_skeleton_skull", "fire", "soul_fire"}


def bname(block: str) -> str:
    return block.split("[")[0].removeprefix("minecraft:")


def bprops(block: str) -> dict[str, str]:
    if "[" not in block:
        return {}
    return dict(kv.split("=") for kv in block[block.index("[") + 1:-1].split(","))


class Textures:
    def __init__(self) -> None:
        for pattern in JAR_GLOBS:
            path = Path(pattern.replace("~", str(Path.home())))
            if path.exists():
                self.zip = zipfile.ZipFile(path)
                break
        else:
            raise SystemExit("no Minecraft 1.21.1 jar found for textures")
        self.names = {n.rsplit("/", 1)[1][:-4] for n in self.zip.namelist() if n.startswith("assets/minecraft/textures/block/") and n.endswith(".png")}
        self.cache: dict[str, np.ndarray] = {}
        self.missing: set[str] = set()

    def load(self, name: str) -> np.ndarray | None:
        if name in self.cache:
            return self.cache[name]
        if name not in self.names:
            return None
        im = Image.open(io.BytesIO(self.zip.read(f"assets/minecraft/textures/block/{name}.png"))).convert("RGBA")
        im = im.crop((0, 0, im.width, im.width)).resize((16, 16), Image.NEAREST)   # animated strips: first frame
        self.cache[name] = np.asarray(im, dtype=np.uint8)
        return self.cache[name]

    def material(self, base: str) -> str:
        for cut in ("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_button", "_pressure_plate"):
            if base.endswith(cut):
                base = base[: -len(cut)]
                break
        for cand in (base, base + "s", base + "_planks", base + "_block", base.replace("_brick", "_bricks"), base + "_bricks",
                     base.replace("_wood", "_log")):                  # *_wood is a log with bark on all six faces
            if cand in self.names:
                return cand
        return base

    def faces(self, block: str) -> list[np.ndarray]:
        """Six face textures in the order +x, -x, +y, -y, +z, -z."""
        base, props = bname(block), bprops(block)
        mat = self.material(base)

        def get(*names: str) -> np.ndarray:
            for n in names:
                t = self.load(n)
                if t is not None:
                    return t
            self.missing.add(base)
            return np.full((16, 16, 4), (120, 120, 120, 255), dtype=np.uint8)

        if base in AVG_ONLY:
            t = self.load(base) if base in self.names else None
            if t is None:
                t = self.load("lantern") if "lantern" in base else None
            col = (t[t[..., 3] > 40][:, :3].mean(axis=0) if t is not None and (t[..., 3] > 40).any() else np.array([200, 170, 120]))
            if base in ("lantern", "soul_lantern", "end_rod", "torch", "soul_torch"):
                col = np.array({"lantern": (255, 190, 90), "soul_lantern": (90, 210, 230), "end_rod": (240, 240, 255), "torch": (255, 200, 90),
                                "soul_torch": (90, 210, 230)}[base])
            if base == "candle":
                col = np.array((225, 215, 190))
            if base in ("skeleton_skull", "wither_skeleton_skull"):
                col = np.array((215, 215, 205) if base == "skeleton_skull" else (50, 50, 55))
            a = np.full((16, 16, 4), 255, dtype=np.uint8)
            a[..., :3] = col.astype(np.uint8)
            return [a] * 6
        if base in ("pumpkin", "carved_pumpkin", "jack_o_lantern"):
            side, top = get("pumpkin_side"), get("pumpkin_top")
            out = [side, side, top, top, side, side]
            if base != "pumpkin":
                face = {"east": 0, "west": 1, "south": 4, "north": 5}.get(props.get("facing", "south"), 4)
                out[face] = get(base)
            return out
        if base == "hay_block":
            return [get("hay_block_side")] * 2 + [get("hay_block_top")] * 2 + [get("hay_block_side")] * 2
        if base == "bookshelf":
            s, t = get("bookshelf"), get("oak_planks")
            return [s, s, t, t, s, s]
        if base == "bone_block":
            return [get("bone_block_side")] * 2 + [get("bone_block_top")] * 2 + [get("bone_block_side")] * 2
        if base.endswith("_wood"):                                    # bark (the log's side) on every face
            return [get(base[:-5] + "_log", base)] * 6
        if base.endswith("_log") or base == "basalt" or base == "polished_basalt":
            s, t = get(base, base + "_side"), get(base + "_top", base)
            return [s, s, t, t, s, s]
        if base in ("grass_block",):
            return [get("grass_block_side")] * 2 + [get("grass_block_top"), get("dirt")] + [get("grass_block_side")] * 2
        if base in ("smithing_table", "grindstone", "lectern", "enchanting_table", "cartography_table", "fletching_table", "loom", "barrel"):
            return [get(base + "_side", base)] * 2 + [get(base + "_top", base)] * 2 + [get(base + "_side", base)] * 2
        if base == "dripstone_block" or base.endswith("_concrete_powder"):
            t = get(base)
            return [t] * 6
        t = get(mat, base, mat + "_side")
        return [t] * 6


def shape_of(block: str) -> list[tuple[float, float, float, float, float, float]]:
    base, p = bname(block), bprops(block)
    if base.endswith("_slab"):
        kind = p.get("type", "bottom")
        return FULL if kind == "double" else [(0, 0, 0, 1, .5, 1)] if kind == "bottom" else [(0, .5, 0, 1, 1, 1)]
    if base.endswith("_stairs"):
        f, top = p.get("facing", "north"), p.get("half", "bottom") == "top"
        low = (0, .5, 0, 1, 1, 1) if top else (0, 0, 0, 1, .5, 1)
        hy0, hy1 = (0, .5) if top else (.5, 1)
        back = {"east": (.5, hy0, 0, 1, hy1, 1), "west": (0, hy0, 0, .5, hy1, 1), "south": (0, hy0, .5, 1, hy1, 1), "north": (0, hy0, 0, 1, hy1, .5)}[f]
        return [low, back]
    if base.endswith("_wall"):
        boxes = [(.25, 0, .25, .75, 1, .75)] if p.get("up", "true") == "true" else []
        for side, box in (("east", (.5, 0, .3125, 1, 0, .6875)), ("west", (0, 0, .3125, .5, 0, .6875)), ("south", (.3125, 0, .5, .6875, 0, 1)),
                          ("north", (.3125, 0, 0, .6875, 0, .5))):
            h = p.get(side, "none")
            if h != "none":
                boxes.append((box[0], 0, box[2], box[3], .875 if h == "low" else 1, box[5]))
        return boxes or [(.25, 0, .25, .75, 1, .75)]
    if base == "candle":
        return [(.35, 0, .35, .65, .4, .65)]
    if base in ("lantern", "soul_lantern"):
        return [(.31, .06 if p.get("hanging") == "true" else 0, .31, .69, .5 if p.get("hanging") != "true" else .56, .69)]
    if base == "chain":
        a = p.get("axis", "y")
        return {"y": [(.41, 0, .41, .59, 1, .59)], "x": [(0, .41, .41, 1, .59, .59)], "z": [(.41, .41, 0, .59, .59, 1)]}[a]
    if base == "iron_bars":
        return [(0, 0, .4375, 1, 1, .5625), (.4375, 0, 0, .5625, 1, 1)]
    if base == "end_rod":
        return [(.4, 0, .4, .6, 1, .6)]
    if base == "amethyst_cluster":
        return [(.25, 0, .25, .75, .6, .75)]
    if base in ("skeleton_skull", "wither_skeleton_skull"):
        return [(.25, 0, .25, .75, .5, .75)]
    if base in ("torch", "soul_torch"):
        return [(.4, 0, .4, .6, .6, .6)]
    if base.endswith("_carpet"):
        return [(0, 0, 0, 1, .0625, 1)]
    if base in ("cauldron", "lava_cauldron"):
        return [(0, 0, 0, 1, .75, 1)]
    if base == "anvil":
        return [(.15, 0, .15, .85, 1, .85)]
    return FULL


class Scene:
    def __init__(self, grid: np.ndarray, palette: list[str], sky: bool = False) -> None:
        self.grid, self.palette, self.sky = grid, palette, sky
        self.tex = Textures()
        n = len(palette)
        self.faces = np.zeros((n, 6, 16, 16, 4), dtype=np.uint8)
        self.boxes: list[list] = []
        self.kind = np.zeros(n, dtype=np.uint8)       # 0 air/pass, 1 full cube, 2 box shape, 3 translucent filter, 4 cutout cube
        self.emit = np.zeros(n, dtype=np.uint8)
        self.opaque = np.zeros(n, dtype=bool)
        self.tint = np.ones((n, 3), dtype=np.float32)
        self.water = np.zeros(n, dtype=bool)
        for i, block in enumerate(palette):
            base = bname(block)
            boxes = shape_of(block)
            self.boxes.append(boxes)
            if base == "air":
                continue
            if base == "water":                       # a blue filter per block crossed: shallow water reads pale, a deep pool dark blue
                self.faces[i, ..., :3], self.faces[i, ..., 3] = (60, 110, 200), 255
                self.kind[i] = 3
                self.tint[i] = (0.90, 0.95, 0.98)
                self.water[i] = True
                continue
            self.faces[i] = np.stack(self.tex.faces(block))
            if "stained_glass" in base or base == "glass":
                self.kind[i] = 3
                col = self.faces[i, 0][self.faces[i, 0][..., 3] > 0][:, :3].mean(axis=0) / 255.0
                self.tint[i] = 0.35 + 0.65 * col
            elif base == "cobweb":
                self.kind[i] = 4
            elif boxes != FULL:
                self.kind[i] = 2
            else:
                self.kind[i] = 1
            self.emit[i] = EMIT.get(base, 0)
            if base == "candle" and bprops(block).get("lit") == "false":
                self.emit[i] = 0
            if base == "candle":
                self.emit[i] = min(12, 3 * int(bprops(block).get("candles", "1"))) if bprops(block).get("lit", "true") == "true" else 0
            self.opaque[i] = base not in TRANSPARENT_LIGHT and self.kind[i] not in (3, 4) and not base.endswith(("_carpet", "_stairs", "_slab")) or base.endswith("_slab") and bprops(block).get("type") == "double"
        self.missing = self.tex.missing
        self.light = self.propagate()
        if sky:
            solid = self.opaque[grid]
            top = np.where(solid.any(axis=1), grid.shape[1] - 1 - np.argmax(solid[:, ::-1, :], axis=1), -1)      # highest opaque block per column
            above = np.arange(grid.shape[1])[None, :, None] > top[:, None, :]
            self.light = np.maximum(self.light, np.where(above, 5, 0).astype(np.uint8))                        # moonlight, level 5

    def propagate(self) -> np.ndarray:
        g = self.grid
        light = self.emit[g].astype(np.uint8)
        opaque = self.opaque[g]
        for _ in range(15):
            nb = light.copy()
            for ax in range(3):
                for sh in (1, -1):
                    s = np.roll(light, sh, axis=ax)
                    # no wrap-around
                    idx = [slice(None)] * 3
                    idx[ax] = slice(0, 1) if sh == 1 else slice(-1, None)
                    s[tuple(idx)] = 0
                    cand = np.where(s > 0, s - 1, 0).astype(np.uint8)
                    nb = np.maximum(nb, np.where(opaque, 0, cand))
            if np.array_equal(nb, light):
                break
            light = nb
        return light

    @staticmethod
    def night_sky(d: np.ndarray) -> np.ndarray:
        """A purple night: a gradient, a full moon with a halo, and a scatter of stars, from the ray direction alone."""
        up = np.clip(d[:, 1], 0, 1)[:, None]
        col = (1 - up ** 0.6) * np.array([0.09, 0.07, 0.20]) + up ** 0.6 * np.array([0.03, 0.02, 0.09])
        horizon = np.exp(-np.abs(d[:, 1]) * 9)[:, None] * np.array([0.10, 0.07, 0.17])
        moon = np.array([-0.18, 0.55, -0.82])
        moon /= np.linalg.norm(moon)
        ang = np.degrees(np.arccos(np.clip(d @ moon, -1, 1)))[:, None]
        glow = np.exp(-(ang / 14.0) ** 2) * np.array([0.30, 0.20, 0.45])
        disk = (ang < 2.6) * np.array([0.92, 0.92, 0.98])
        cell = np.floor(d * 140).astype(np.int64)
        h = ((cell[:, 0] * 73856093) ^ (cell[:, 1] * 19349663) ^ (cell[:, 2] * 83492791)) % 1000
        stars = ((h < 2) & (d[:, 1] > 0.02))[:, None] * np.array([0.7, 0.7, 0.9])
        return (col + horizon + glow + disk + stars).astype(np.float32)

    def render(self, eye: tuple[float, float, float], yaw: float, pitch: float, w: int = 960, h: int = 540, fov: float = 90.0, exposure: float = 1.0) -> Image.Image:
        g, N = self.grid, w * h
        aspect = w / h
        f = 1.0 / np.tan(np.radians(fov) / 2)
        us = (np.arange(w) + 0.5) / w * 2 - 1
        vs = 1 - (np.arange(h) + 0.5) / h * 2
        uu, vv = np.meshgrid(us * aspect, vs)
        cam = np.stack([uu, vv, np.full_like(uu, f)], axis=-1).reshape(-1, 3)    # x right, y up, z forward
        yr, pr = np.radians(yaw), np.radians(pitch)
        fwd = np.array([-np.sin(yr) * np.cos(pr), -np.sin(pr), np.cos(yr) * np.cos(pr)])
        right = np.array([-np.cos(yr), 0.0, -np.sin(yr)])
        up = np.cross(fwd, right) * -1
        up = np.cross(right, fwd)
        d = cam[:, 0:1] * right + cam[:, 1:2] * up + cam[:, 2:3] * fwd
        d /= np.linalg.norm(d, axis=1, keepdims=True)
        d[np.abs(d) < 1e-9] = 1e-9
        o = np.tile(np.array(eye, dtype=np.float64), (N, 1))
        vox = np.floor(o).astype(np.int64)
        step = np.where(d > 0, 1, -1).astype(np.int64)
        tdelta = np.abs(1.0 / d)
        tmax = np.where(d > 0, (vox + 1 - o) / d, (o - vox) / -d)
        color = np.zeros((N, 3), dtype=np.float32)
        hit_any = np.zeros(N, dtype=bool)
        filt = np.ones((N, 3), dtype=np.float32)
        glow = np.zeros((N, 3), dtype=np.float32)          # light scattered back by the water a ray crosses, so water is seen, not only seen through
        active = np.arange(N)
        t_enter = np.zeros(N)
        axis_in = np.full(N, 1, dtype=np.int64)
        shape = np.array(g.shape)
        bg = np.array([0.02, 0.015, 0.03], dtype=np.float32)
        color[:] = bg
        for _ in range(700):
            if active.size == 0:
                break
            v = vox[active]
            inside = np.all((v >= 0) & (v < shape), axis=1)
            if not inside.all():
                active, v = active[inside], v[inside]
                if active.size == 0:
                    break
            bid = g[v[:, 0], v[:, 1], v[:, 2]]
            kind = self.kind[bid]
            hit_t = np.full(active.size, np.inf)
            hit_axis = np.zeros(active.size, dtype=np.int64)
            cand = kind != 0
            # cubes: the entry face
            full = cand & ((kind == 1) | (kind == 4) | (kind == 3))
            hit_t[full] = t_enter[active][full]
            hit_axis[full] = axis_in[active][full]
            # boxes
            boxy = np.nonzero(cand & (kind == 2))[0]
            if boxy.size:
                for k in np.unique(bid[boxy]):
                    sel = boxy[bid[boxy] == k]
                    ra = active[sel]
                    org = o[ra] - vox[ra]
                    best_t = np.full(sel.size, np.inf)
                    best_ax = np.zeros(sel.size, dtype=np.int64)
                    for (x0, y0, z0, x1, y1, z1) in self.boxes[k]:
                        lo, hi = np.array([x0, y0, z0]), np.array([x1, y1, z1])
                        inv = 1.0 / d[ra]
                        t0, t1 = (lo - org) * inv, (hi - org) * inv
                        tn, tf = np.minimum(t0, t1), np.maximum(t0, t1)
                        tnear, tfar = tn.max(axis=1), tf.min(axis=1)
                        ax = tn.argmax(axis=1)
                        ok = (tnear <= tfar) & (tfar > t_enter[ra]) & (tnear < tmax[ra].min(axis=1) + 1e-9)
                        tt = np.maximum(tnear, t_enter[ra])
                        better = ok & (tt < best_t)
                        # a ray that starts inside the box (tnear < t_enter) hits the face it entered the voxel through
                        ax = np.where(tnear < t_enter[ra], axis_in[ra], ax)
                        best_t = np.where(better, tt, best_t)
                        best_ax = np.where(better, ax, best_ax)
                    hit_t[sel], hit_axis[sel] = best_t, best_ax
            hit = np.isfinite(hit_t)
            if hit.any():
                ids = np.nonzero(hit)[0]
                ra = active[ids]
                b = bid[ids]
                ax = hit_axis[ids]
                p = o[ra] + d[ra] * hit_t[ids][:, None]
                local = np.clip(p - vox[ra], 0, 0.9999)
                sgn = np.sign(d[ra][np.arange(ids.size), ax])
                face = np.where(ax == 0, np.where(sgn > 0, 1, 0), np.where(ax == 1, np.where(sgn > 0, 3, 2), np.where(sgn > 0, 5, 4)))
                uv = np.where((ax == 0)[:, None], local[:, [2, 1]], np.where((ax == 1)[:, None], local[:, [0, 2]], local[:, [0, 1]]))
                px = np.clip((uv[:, 0] * 16).astype(int), 0, 15)
                py = np.clip(((1 - uv[:, 1]) * 16).astype(int), 0, 15) if True else 0
                py = np.where(ax == 1, np.clip((uv[:, 1] * 16).astype(int), 0, 15), py)
                texel = self.faces[b, face, py, px]
                solid = texel[:, 3] > 100
                tr = self.kind[b] == 3
                # translucent glass: tint the ray and carry on
                if tr.any():
                    wat = tr & self.water[b]
                    if wat.any():
                        wv = vox[ra[wat]]
                        lv = self.light[wv[:, 0], wv[:, 1], wv[:, 2]].astype(np.float32)[:, None] / 15.0
                        glow[ra[wat]] += filt[ra[wat]] * WATER_GLOW * (0.15 + lv)
                    filt[ra[tr]] *= self.tint[b[tr]]
                stop = solid & ~tr
                if stop.any():
                    s_ids, s_rays, s_b = ids[stop], ra[stop], b[stop]
                    prev = vox[s_rays].copy()
                    prev[np.arange(s_rays.size), ax[stop]] -= step[s_rays, ax[stop]]
                    own = self.light[vox[s_rays][:, 0], vox[s_rays][:, 1], vox[s_rays][:, 2]]
                    pl = np.where((prev >= 0).all(axis=1) & (prev < shape).all(axis=1),
                                  self.light[np.clip(prev[:, 0], 0, shape[0] - 1), np.clip(prev[:, 1], 0, shape[1] - 1), np.clip(prev[:, 2], 0, shape[2] - 1)], 0)
                    lvl = np.maximum(own, pl).astype(np.float32) / 15.0
                    br = lvl / (4 - 3 * lvl)                           # the game's lightmap curve
                    br = 0.5 * br + 0.5 * (1 - (1 - br) ** 4)          # the default "Moody" gamma of 50
                    emit = self.emit[s_b] > 0
                    br = np.where(emit, 1.0, br) + 0.02
                    shade = np.array([.6, .6, 1.0, .5, .8, .8], dtype=np.float32)[face[stop]]
                    warm = np.stack([np.ones_like(br), 0.93 + 0.07 * lvl, 0.82 + 0.18 * lvl], axis=1)
                    col = texel[stop][:, :3].astype(np.float32) / 255.0 * (br * np.where(emit, 1.0, shade))[:, None] * warm
                    color[s_rays] = col * filt[s_rays]
                    hit_any[s_rays] = True
                    done = np.zeros(active.size, dtype=bool)
                    done[s_ids] = True
                    active, v = active[~done], v[~done]
            if active.size == 0:
                break
            # advance to the next voxel
            tm = tmax[active]
            ax = tm.argmin(axis=1)
            idx = np.arange(active.size)
            t_enter[active] = tm[idx, ax]
            tmax[active, ax] += tdelta[active, ax]
            vox[active, ax] += step[active, ax]
            axis_in[active] = ax
        if self.sky:
            miss = ~hit_any
            color[miss] = self.night_sky(d[miss]) * filt[miss]
        img = np.clip((color + glow) * exposure, 0, 1)
        return Image.fromarray((img.reshape(h, w, 3) * 255).astype(np.uint8))


def room_scene(kind: str) -> "Scene":
    """One stop room set inside a stone chamber shell (walls, the tower's slab above, a back doorway and the door), the way the tower holds it."""
    import rooms as R
    room = R.build(kind)
    w, d, h = R.W, R.D, R.H
    pal = ["minecraft:air", H.PLAIN_WALL, H.SLAB, H.BARS]
    grid = np.zeros((w + 2, h + 3, d + 2), dtype=np.uint16)
    grid[:, :, :] = 1
    grid[1:w + 1, 1:h + 1, 1:d + 1] = 0                                   # the chamber: room x 0..10 is grid x 1..11, room y 1..8 is grid y 1..8
    grid[:, h + 1:h + 3, :] = 2
    grid[5:8, 1:5, 0] = 0                                                  # the back doorway, three wide, four high
    grid[4:7 + 1, 1:7, d + 1] = 3                                          # the door, bars
    index = {n: i for i, n in enumerate(pal)}
    for name in room.palette[1:]:
        index[name] = len(pal)
        pal.append(name)
    sub = room.grid[:, 0:h, :]
    for rid, name in enumerate(room.palette):
        if rid == 0:
            continue
        mask = sub == rid
        xs, ys, zs = np.nonzero(mask)
        grid[xs + 1, ys, zs + 1] = index[name]                              # room y 0 is the ground row: grid y 0
    return Scene(grid, pal)


def main() -> int:
    if len(sys.argv) >= 4 and sys.argv[1] == "--npz":
        data = np.load(sys.argv[2], allow_pickle=True)
        scene = Scene(data["grid"], [str(n) for n in data["palette"]], sky=True)
        out = Path(sys.argv[3])
        out.mkdir(parents=True, exist_ok=True)
        for spec in sys.argv[4:]:
            name, vals = spec.split(":")
            x, y, z, yaw, pitch, *rest = (float(v) for v in vals.split(","))
            fov = rest[0] if rest else 90.0
            scene.render((x, y, z), yaw, pitch, 960, 540, fov).save(out / f"{name}.png")
            print("wrote", out / f"{name}.png")
        return 0
    if len(sys.argv) >= 3 and sys.argv[1] == "--rooms":
        out = Path(sys.argv[2])
        out.mkdir(parents=True, exist_ok=True)
        import rooms as R
        kinds = sys.argv[3:] or list(R.ROOMS)
        views = {"a": (5, 13, 180, 5), "b": (5, 2, 0, 5), "c": (3, 7, -90, 3), "d": (7, 7, 90, 3)}
        for kind in kinds:
            scene = room_scene(kind)
            for tag, (x, z, yaw, pitch) in views.items():
                scene.render((x + 1.5, 1 + 1.62, z + 1.5), yaw, pitch, 640, 360).save(out / f"{kind}_{tag}.png")
            print("rendered", kind)
        return 0
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    out, floor = Path(sys.argv[1]), int(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    tower = H.build()
    scene = Scene(tower.grid, tower.palette)
    f = H.feet(floor)
    mirrored = H.mirrored(floor)
    for spec in sys.argv[3:]:
        name, vals = spec.split(":")
        x, y, z, yaw, pitch = (float(v) for v in vals.split(","))
        if mirrored:
            x, yaw = H.SIZE_X - 1 - x, -yaw
        img = scene.render((x + 0.5, f + y + 1.62, z + 0.5), yaw, pitch)
        img.save(out / f"{name}.png")
        print("wrote", out / f"{name}.png")
    if scene.missing:
        print("no texture for:", sorted(scene.missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
