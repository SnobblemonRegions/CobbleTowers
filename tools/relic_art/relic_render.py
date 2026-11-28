"""A small pixel-art material renderer for the relic icons (P59).

A recipe lays down shapes in 32-unit coordinates (scaled to the canvas); each shape has a material and a height profile:

    dome     round parts (a coin, a bell, a skull): height falls off like a sphere toward the rim
    cyl      shafts, handles, blades drawn as thick lines: round across the stroke
    bevel    plates and panels (a shield, a book cover): flat on top, rounded within a few pixels of the edge
    ring     a hoop: round across the band
    flat     marks and inlays: no relief, lit straight on

From the height map the renderer works out a surface normal per pixel and lights it from the top left: diffuse light, a specular highlight on
shiny materials, a contact shadow where a shape sits on one drawn before it. The light level picks a tone from the material's ramp (hue-shifted:
shadows cooler, highlights warmer), with ordered dithering between tones and the material's own texture (wood grain, brushed metal, weave,
speckle, facets). The silhouette gets an outline in the darkest tone of the material it touches, and the whole icon a soft drop shadow.
"""
import colorsys
import math

import numpy as np
from PIL import Image, ImageDraw

BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0 - 0.5
LIGHT = np.array([-0.55, -0.72, 0.85])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
HALF = (LIGHT + np.array([0, 0, 1.0])) / np.linalg.norm(LIGHT + np.array([0, 0, 1.0]))


def _hash(x, y, seed=0):
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def _vnoise(x, y, scale, seed=0):
    """Smooth value noise in 0..1."""
    fx, fy = x / scale, y / scale
    x0, y0 = math.floor(fx), math.floor(fy)
    tx, ty = fx - x0, fy - y0
    sx, sy = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a, b = _hash(x0, y0, seed), _hash(x0 + 1, y0, seed)
    c, d = _hash(x0, y0 + 1, seed), _hash(x0 + 1, y0 + 1, seed)
    return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy


def ramp(base, n=7, shift=0.06, lo=0.30, hi=1.18):
    """n tones from darkest to lightest around `base`: shadows lean blue and gain saturation, highlights lean yellow and lose it."""
    h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in base))
    out = []
    for i in range(n):
        t = i / (n - 1) * 2 - 1                       # -1 .. 1
        target = 0.68 if t < 0 else 0.13              # blue for shade, warm yellow for light
        dh = ((target - h + 0.5) % 1.0) - 0.5
        hh = (h + dh * shift * abs(t) * (1.6 if t < 0 else 1.0)) % 1.0
        ss = min(1.0, s * (1 + 0.25 * max(0, -t)) * (1 - 0.38 * max(0, t)))
        vv = min(1.0, v * (lo + (hi - lo) * (i / (n - 1))))
        r, g, b = colorsys.hsv_to_rgb(hh, ss, vv)
        out.append((int(r * 255), int(g * 255), int(b * 255)))
    return out


class Mat:
    """A material: its tone ramp, how shiny it is, and its texture. `tone` shifts it darker (negative) or lighter on the ramp."""

    def __init__(self, name, base, shine=0.0, power=12, texture=None, emissive=False, tone=0, ramp_override=None, fresnel=0.0):
        self.name, self.base, self.shine, self.power, self.texture, self.emissive = name, base, shine, power, texture, emissive
        self.tone, self.fresnel = tone, fresnel
        self.ramp = ramp_override or ramp(base)

    def shade(self, dt):
        m = Mat(self.name, self.base, self.shine, self.power, self.texture, self.emissive, self.tone + dt, self.ramp, self.fresnel)
        return m


# ---- textures: (x, y, normal z, height) -> change in light level -----------------------------------------------------------------

def tex_wood(x, y, nz, h):
    return 0.10 * math.sin(x * 0.55 + y * 1.6 + 5 * _vnoise(x, y, 6, 3)) + 0.05 * (_hash(x, y, 9) - 0.5)


def tex_brushed(x, y, nz, h):
    return 0.06 * (_vnoise(x * 0.3, y * 3.0, 2, 5) - 0.5) + 0.03 * (_hash(x, y, 4) - 0.5)


def tex_gold(x, y, nz, h):
    return 0.05 * (_vnoise(x, y, 3, 7) - 0.5) + (0.18 if _hash(x, y, 11) > 0.985 else 0.0)


def tex_leather(x, y, nz, h):
    return 0.08 * (_hash(x, y, 13) - 0.5) + 0.06 * (_vnoise(x, y, 4, 2) - 0.5)


def tex_cloth(x, y, nz, h):
    return 0.05 * (1 if (x + y) % 2 == 0 else -1) + 0.04 * (_vnoise(x, y, 5, 8) - 0.5)


def tex_soft(x, y, nz, h):
    return 0.05 * (_vnoise(x, y, 3, 6) - 0.5) + 0.03 * (_hash(x, y, 21) - 0.5)


def tex_stone(x, y, nz, h):
    return 0.10 * (_vnoise(x, y, 3, 17) - 0.5) + 0.05 * (_hash(x, y, 23) - 0.5)


def tex_leaf(x, y, nz, h):
    return 0.06 * (_vnoise(x, y, 4, 31) - 0.5)


def tex_fire(x, y, nz, h):
    return 0.10 * (_vnoise(x, y * 0.6, 3, 41) - 0.5)


def mat(name, base, **kw):
    return Mat(name, base, **kw)


FIRE_RAMP = [(98, 20, 18), (156, 34, 20), (210, 64, 22), (244, 116, 30), (255, 168, 52), (255, 214, 102), (255, 246, 196)]
ICE_RAMP = ramp((150, 214, 240), shift=0.02, lo=0.45, hi=1.2)


class Canvas:
    """Shapes on an n x n canvas, in recipe units of `unit` (the recipes are written on a 32 grid)."""

    def __init__(self, n=48, unit=32):
        self.n, self.k = n, n / unit
        self.mat = [[None] * n for _ in range(n)]
        self.shape = np.full((n, n), -1, int)
        self.height = np.zeros((n, n))
        self.count = 0

    # ---- laying down shapes
    def _mask(self, draw):
        im = Image.new("L", (self.n, self.n), 0)
        draw(ImageDraw.Draw(im))
        return np.array(im) > 0

    def _put(self, mask, height, m):
        sid = self.count
        self.count += 1
        ys, xs = np.nonzero(mask)
        for y, x in zip(ys, xs):
            self.mat[y][x] = m
            self.shape[y, x] = sid
            self.height[y, x] = height[y, x]

    def _s(self, v):
        return v * self.k

    def ell(self, x0, y0, x1, y1, m, profile="dome"):
        box = [self._s(x0), self._s(y0), self._s(x1) + self.k - 1, self._s(y1) + self.k - 1]
        mask = self._mask(lambda d: d.ellipse(box, fill=255))
        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        rx, ry = max(0.5, (box[2] - box[0]) / 2 + 0.5), max(0.5, (box[3] - box[1]) / 2 + 0.5)
        yy, xx = np.mgrid[0:self.n, 0:self.n]
        d2 = ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2
        h = np.sqrt(np.clip(1 - d2, 0, 1)) if profile == "dome" else self._bevel(mask) if profile == "bevel" else np.full_like(d2, 0.6)
        self._put(mask, h, m)

    def rect(self, x0, y0, x1, y1, m, profile="bevel"):
        box = [self._s(x0), self._s(y0), self._s(x1) + self.k - 1, self._s(y1) + self.k - 1]
        mask = self._mask(lambda d: d.rectangle(box, fill=255))
        self._put(mask, self._bevel(mask) if profile == "bevel" else np.full(mask.shape, 0.6), m)

    def poly(self, pts, m, profile="bevel"):
        sp = [(self._s(x) + self.k / 2, self._s(y) + self.k / 2) for x, y in pts]
        mask = self._mask(lambda d: d.polygon(sp, fill=255))
        self._put(mask, self._bevel(mask) if profile == "bevel" else np.full(mask.shape, 0.6), m)

    def line(self, pts, m, w=1):
        sp = [(self._s(x) + self.k / 2, self._s(y) + self.k / 2) for x, y in pts]
        width = max(1, round(w * self.k))
        mask = self._mask(lambda d: d.line(sp, fill=255, width=width))
        if width <= 2:
            self._put(mask, np.full(mask.shape, 0.5), m)          # a thin mark: no roundness to speak of
            return
        yy, xx = np.mgrid[0:self.n, 0:self.n]
        dist = np.full(mask.shape, 1e9)
        for (ax, ay), (bx, by) in zip(sp, sp[1:]):
            vx, vy = bx - ax, by - ay
            L2 = vx * vx + vy * vy or 1e-9
            t = np.clip(((xx - ax) * vx + (yy - ay) * vy) / L2, 0, 1)
            dist = np.minimum(dist, np.hypot(xx - (ax + t * vx), yy - (ay + t * vy)))
        h = np.sqrt(np.clip(1 - (dist / (width / 2 + 0.5)) ** 2, 0, 1))
        self._put(mask, h, m)

    def ring(self, cx, cy, r, w, m):
        cx, cy, r, w = self._s(cx) + self.k / 2, self._s(cy) + self.k / 2, self._s(r), max(2, round(w * self.k))
        yy, xx = np.mgrid[0:self.n, 0:self.n]
        dist = np.abs(np.hypot(xx - cx, yy - cy) - r)
        mask = dist <= w / 2
        self._put(mask, np.sqrt(np.clip(1 - (dist / (w / 2 + 0.5)) ** 2, 0, 1)), m)

    def px(self, x, y, m):
        s = max(1, round(self.k))
        x0, y0 = round(self._s(x)), round(self._s(y))
        mask = np.zeros((self.n, self.n), bool)
        mask[y0:y0 + s, x0:x0 + s] = True
        self._put(mask, np.full(mask.shape, 0.7), m)

    def _bevel(self, mask, b=None):
        b = b or max(2, round(2.2 * self.k))
        dist = np.zeros(mask.shape)
        cur = mask.copy()
        for step in range(1, b + 1):
            dist[cur] = step
            nxt = cur.copy()
            nxt[1:, :] &= cur[:-1, :]; nxt[:-1, :] &= cur[1:, :]; nxt[:, 1:] &= cur[:, :-1]; nxt[:, :-1] &= cur[:, 1:]
            cur = nxt
        return np.sin(np.clip(dist / b, 0, 1) * math.pi / 2)

    # ---- rendering
    def render(self, outline=True, drop=True) -> Image.Image:
        n = self.n
        H = self.height * 3.2
        out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        filled = self.shape >= 0

        def hgt(x, y, sid):
            if 0 <= x < n and 0 <= y < n and self.shape[y, x] == sid:
                return H[y, x]
            return 0.0

        for y in range(n):
            for x in range(n):
                sid = self.shape[y, x]
                if sid < 0:
                    continue
                m = self.mat[y][x]
                if m.name == "void":
                    out.putpixel((x, y), m.ramp[1] + (255,))
                    continue
                dx = hgt(x + 1, y, sid) - hgt(x - 1, y, sid)
                dy = hgt(x, y + 1, sid) - hgt(x, y - 1, sid)
                nrm = np.array([-dx / 2, -dy / 2, 1.0])
                nrm /= np.linalg.norm(nrm)
                if m.name == "gem":                                        # facets: snap the normal to a few directions
                    ang = math.atan2(nrm[1], nrm[0])
                    ang = round(ang / (math.pi / 3)) * (math.pi / 3)
                    tilt = min(0.8, math.hypot(nrm[0], nrm[1]))
                    tilt = 0.0 if tilt < 0.25 else 0.55
                    nrm = np.array([math.cos(ang) * tilt, math.sin(ang) * tilt, 1.0])
                    nrm /= np.linalg.norm(nrm)
                diff = max(0.0, float(nrm @ LIGHT))
                light = 0.30 + 0.78 * diff
                if m.shine:
                    light += m.shine * max(0.0, float(nrm @ HALF)) ** m.power
                if m.fresnel:
                    light += m.fresnel * (1 - nrm[2])
                if m.emissive:
                    light = 0.35 + 0.75 * self.height[y, x]
                if m.texture:
                    light += m.texture(x, y, nrm[2], self.height[y, x])
                # a contact shadow on a shape where one laid on top of it sits just above-left (light comes from the top left)
                for ox, oy, amt in ((-1, -1, 0.16), (0, -1, 0.10), (-1, 0, 0.10), (-2, -2, 0.07)):
                    xx, yy = x + ox, y + oy
                    if 0 <= xx < n and 0 <= yy < n and self.shape[yy, xx] > sid:
                        light -= amt
                level = light * (len(m.ramp) - 1) + m.tone + BAYER4[y % 4, x % 4] * 0.6
                idx = int(min(len(m.ramp) - 1, max(0, round(level))))
                out.putpixel((x, y), m.ramp[idx] + (255,))

        if outline:
            src = out.copy()
            for y in range(n):
                for x in range(n):
                    if filled[y, x]:
                        continue
                    touch = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if 0 <= x + dx < n and 0 <= y + dy < n and filled[y + dy, x + dx]]
                    if touch:
                        tx, ty = touch[0]
                        m = self.mat[ty][tx]
                        r, g, b = m.ramp[0]
                        out.putpixel((x, y), (int(r * 0.45), int(g * 0.42), int(b * 0.5), 255))
            # an inner rim of light on the lit (top-left) silhouette, one pixel in, for a crisp read at small size
            for y in range(n):
                for x in range(n):
                    if not filled[y, x] or self.mat[y][x].name in ("void",):
                        continue
                    if (x > 0 and not filled[y, x - 1]) or (y > 0 and not filled[y - 1, x]):
                        r, g, b, a = src.getpixel((x, y))
                        out.putpixel((x, y), (min(255, r + 22), min(255, g + 20), min(255, b + 14), a))

        if drop:
            alpha = np.array(out.getchannel("A")) > 0
            shadow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
            for y in range(n):
                for x in range(n):
                    if alpha[y, x] and y + 2 < n and x + 1 < n:
                        shadow.putpixel((x + 1, y + 2), (10, 6, 16, 110))
            shadow.alpha_composite(out)
            out = shadow
        return out
