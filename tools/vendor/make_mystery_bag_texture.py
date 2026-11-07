"""Draws the Mystery Bag's 32 x 32 item texture, with no image library (a plain PNG written by hand).

  python tools/vendor/make_mystery_bag_texture.py   writes src/main/resources/assets/cobbletowers/textures/item/mystery_bag.png

A velvet pouch with a gold drawstring, an embroidered gold "?" and a plume of glittering dust (concept C, approved by the owner 2026-11-05). The
picture is lit from a height field, so the artwork is the 'where' and 'what' of the code below and a change is a diff, not a binary.
"""
import math, os, struct, zlib
HERE = os.path.dirname(os.path.abspath(__file__))
N = 32

def hx(s): return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))
def ramp(*c): return [hx(x) for x in c]
def mul(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)

BURLAP   = ramp('3a2133', '603a30', '8c5c38', 'bd8a4b', 'e8bb78')
LITBURL  = ramp('6b3326', 'a65a2a', 'dc8836', 'f8b44e', 'ffdc82')
FIRE     = ramp('b2341a', 'e45f1c', 'ff9a2e', 'ffc94a', 'fff3a8')
GOBLIN   = ramp('1b3a2c', '2c6a3c', '4da04c', '8dd25c', 'd6f48c')
VELVET   = ramp('1b0f33', '33205c', '53358c', '7b55bd', 'ab8fe6')
GOLD     = ramp('5a380c', 'a56d1a', 'e5ab2c', 'ffd85a', 'fff5b4')
LIME     = ramp('2a560c', '5aa419', 'a5e02c', 'd8ff60', 'f8ffb8')
TEAL     = ramp('0c3354', '1d78a4', '4fc6e8', 'a6f0ff', 'efffff')
TEETH    = ramp('8c808f', 'bcb2bf', 'e8e0e6', 'fff8ee', 'ffffff')
TONGUE   = ramp('6c1744', 'b5336f', 'f0709f', 'ff9fc2', 'ffd0e0')
MIMIC    = ramp('150c2a', '2b1b4d', '47306f', '6a4b9c', '9a80cc')
MIMICLIT = ramp('16303a', '2a5a48', '3f7f56', '66a864', '9ad07e')
DARK = hx('0d0716')


class Icon:
    def __init__(self):
        self.mask = [[False] * N for _ in range(N)]
        self.h = [[0.0] * N for _ in range(N)]
        self.mat = [[None] * N for _ in range(N)]
        self.fixed = [[None] * N for _ in range(N)]
        self.bias = [[0.0] * N for _ in range(N)]
        self.float = []

    def ellipsoid(self, cx, cy, rx, ry, amp, mat):
        for y in range(N):
            for x in range(N):
                dx, dy = (x + .5 - cx) / rx, (y + .5 - cy) / ry
                r2 = dx * dx + dy * dy
                if r2 <= 1:
                    self.mask[y][x] = True
                    self.h[y][x] = amp * math.sqrt(1 - r2)
                    self.mat[y][x] = mat

    def poly(self, test, mat, hfn):
        for y in range(N):
            for x in range(N):
                if test(x + .5, y + .5):
                    self.mask[y][x] = True
                    self.h[y][x] = hfn(x + .5, y + .5)
                    self.mat[y][x] = mat

    def blob(self, x, y, mat, h):
        self.mask[y][x] = True
        self.mat[y][x] = mat
        self.h[y][x] = h
        self.fixed[y][x] = None

    def shade(self):
        hs = [[0.0] * N for _ in range(N)]
        for y in range(N):
            for x in range(N):
                t = c = 0
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        yy, xx = y + dy, x + dx
                        v = self.h[yy][xx] if 0 <= yy < N and 0 <= xx < N else 0.0
                        wgt = 2 if dx == dy == 0 else 1
                        t += v * wgt
                        c += wgt
                hs[y][x] = t / c
        Lx, Ly, Lz = -0.45, -0.65, 0.62
        norm = math.sqrt(Lx * Lx + Ly * Ly + Lz * Lz)
        Lx, Ly, Lz = Lx / norm, Ly / norm, Lz / norm

        def g(xx, yy):
            return hs[yy][xx] if 0 <= xx < N and 0 <= yy < N else 0.0

        px = [[None] * N for _ in range(N)]
        for y in range(N):
            for x in range(N):
                if not self.mask[y][x]:
                    continue
                if self.fixed[y][x] is not None:
                    px[y][x] = self.fixed[y][x]
                    continue
                gx, gy = (g(x + 1, y) - g(x - 1, y)) * 0.5, (g(x, y + 1) - g(x, y - 1)) * 0.5
                nx, ny, nz = -gx * 0.9, -gy * 0.9, 1.0
                l = math.sqrt(nx * nx + ny * ny + nz * nz)
                lam = (nx * Lx + ny * Ly + nz * Lz) / l + self.bias[y][x]
                idx = 0 if lam < 0.30 else 1 if lam < 0.50 else 2 if lam < 0.68 else 3 if lam < 0.84 else 4
                px[y][x] = self.mat[y][x][idx]
        outl = [[None] * N for _ in range(N)]
        for y in range(N):
            for x in range(N):
                if self.mask[y][x]:
                    continue
                best = None
                for dx, dy, f in ((-1, 0, 0.55), (0, -1, 0.65), (1, 0, 0.42), (0, 1, 0.38)):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < N and 0 <= yy < N and self.mask[yy][xx] and px[yy][xx] is not None:
                        base = self.mat[yy][xx][0] if self.mat[yy][xx] else px[yy][xx]
                        c = mul(base, f)
                        if best is None or sum(c) < sum(best):
                            best = c
                outl[y][x] = best
        for y in range(N):
            for x in range(N):
                if px[y][x] is None and outl[y][x]:
                    px[y][x] = outl[y][x]
        for (x, y, c) in self.float:
            if 0 <= x < N and 0 <= y < N:
                px[y][x] = c
        return px


def dist_to(points, x, y):
    return min((math.hypot(x + .5 - a, y + .5 - b) for a, b in points), default=99)


def sparkle(icon, cx, cy, big, core, rim):
    pts = [(0, 0, core)] + ([(1, 0, rim), (-1, 0, rim), (0, 1, rim), (0, -1, rim)] if big else [])
    for dx, dy, c in pts:
        icon.float.append((cx + dx, cy + dy, c))


def concept_a():
    """Goblin's jack-o'-lantern sack: burlap with a carved, glowing face and a goblin-green drawstring."""
    ic = Icon()
    ic.ellipsoid(16, 21.2, 11.2, 9.2, 7.0, BURLAP)
    ic.poly(lambda x, y: 10.0 <= y <= 14.5 and abs(x - 16) <= 4.0 + (14.5 - y) * 0.42, BURLAP,
            lambda x, y: 3.6 * math.sqrt(max(0.0, 1 - ((x - 16) / 5.8) ** 2)) + 1.5)
    ic.ellipsoid(12.2, 8.6, 3.4, 3.9, 3.6, BURLAP)
    ic.ellipsoid(20.0, 8.2, 3.6, 4.2, 3.9, BURLAP)
    ic.ellipsoid(16.0, 9.8, 2.5, 2.8, 2.2, BURLAP)
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.mat[y][x] is BURLAP:
                ic.h[y][x] += 0.55 * math.sin((x * 0.9 + y * 1.5) * 0.9) * (1 if y > 14 else 0.4)

    def tri(p, q, r, x, y):
        def d(a, b):
            return (x - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (y - b[1])
        d1, d2, d3 = d(p, q), d(q, r), d(r, p)
        return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))

    def mouth(x, y):
        if not 9.5 <= x <= 22.5:
            return False
        t = ((x - 9.5) % 4.0) / 4.0
        top = 23.6 + 1.9 * abs(t * 2 - 1)
        bottom = 28.4 - 0.05 * (x - 16) ** 2
        return top <= y <= bottom

    def holes(x, y):
        return (tri((8.0, 21.0), (14.6, 21.0), (11.3, 14.6), x, y) or tri((17.4, 21.0), (24.0, 21.0), (20.7, 14.6), x, y)
                or tri((14.9, 23.0), (17.1, 23.0), (16.0, 21.4), x, y) or mouth(x, y))

    hole = [(x, y) for y in range(N) for x in range(N) if ic.mask[y][x] and holes(x + .5, y + .5)]
    holeset = set(hole)
    edge = [(x + .5, y + .5) for (x, y) in hole
            if any((x + a, y + b) not in holeset for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for (x, y) in hole:
        d = dist_to(edge, x, y)
        ic.fixed[y][x] = FIRE[1] if d < 0.6 else FIRE[3] if d < 1.4 else FIRE[4]
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and (x, y) not in holeset and ic.mat[y][x] is BURLAP and y > 13:
                d = dist_to([(a + .5, b + .5) for a, b in hole], x, y)
                if d < 1.25:
                    ic.fixed[y][x] = BURLAP[0] if (x + y) % 2 == 0 else BURLAP[1]
                elif d < 2.8:
                    ic.mat[y][x] = LITBURL
                    ic.bias[y][x] = 0.10
                elif d < 4.2:
                    ic.bias[y][x] = 0.12
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.fixed[y][x] is None and ic.mat[y][x] in (BURLAP, LITBURL) and (x + y) % 2 == 0:
                ic.bias[y][x] -= 0.06
    for y in range(10, 14):
        for x in range(11, 22):
            if ic.mask[y][x] and ic.fixed[y][x] is None and 11.0 <= y + .5 <= 13.2:
                ic.mat[y][x] = GOBLIN
                ic.h[y][x] += 1.6
    for (x, y) in ((16, 11), (15, 11), (16, 12), (15, 12), (17, 12)):
        ic.blob(x, y, GOBLIN, 5.2)
    for (x, y) in ((14, 13), (13, 14), (12, 15), (18, 13), (19, 14), (19, 15)):
        ic.blob(x, y, GOBLIN, 3.8)
    for (x, y) in ((12, 16), (19, 16)):
        ic.mask[y][x] = True
        ic.fixed[y][x] = GOBLIN[3]
    for (x, y) in ((8, 19), (8, 20)):
        if ic.mask[y][x] and (x, y) not in holeset:
            ic.fixed[y][x] = BURLAP[4]
    sparkle(ic, 26, 6, True, hx('fff3a8'), hx('ff9a2e'))
    sparkle(ic, 5, 12, False, hx('ffc94a'), hx('ff9a2e'))
    return ic.shade()


def concept_b():
    """The mimic: a knotted dark bundle with one big glowing eye and a toothy grin."""
    ic = Icon()
    ic.ellipsoid(16, 19.6, 12.0, 9.8, 7.0, MIMIC)
    ic.ellipsoid(11.0, 7.6, 3.4, 4.0, 3.2, MIMIC)
    ic.ellipsoid(21.0, 7.2, 3.6, 4.4, 3.4, MIMIC)
    ic.ellipsoid(16.0, 9.8, 4.2, 3.2, 3.0, MIMIC)
    ic.ellipsoid(11.5, 29.6, 2.9, 1.5, 2.2, MIMIC)
    ic.ellipsoid(20.5, 29.6, 2.9, 1.5, 2.2, MIMIC)
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.mat[y][x] is MIMIC:
                ic.h[y][x] += 0.5 * math.sin((x * 1.1 - y * 1.3) * 0.8)
    ecx, ecy, er = 16.0, 16.5, 5.4
    for y in range(N):
        for x in range(N):
            d = math.hypot(x + .5 - ecx, y + .5 - ecy) / er
            if d <= 1:
                ic.mask[y][x] = True
                i = 1 if d > 0.82 else 2 if d > 0.52 else 3 if d > 0.22 else 4
                ic.fixed[y][x] = LIME[i]
                if abs(x + .5 - ecx) <= 1.25 - 0.15 * abs(y + .5 - ecy) and abs(y + .5 - ecy) <= 3.9:
                    ic.fixed[y][x] = DARK
    for (x, y) in ((13, 14), (14, 14), (13, 15)):
        ic.fixed[y][x] = hx('ffffff')
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.fixed[y][x] is None and ic.mat[y][x] is MIMIC:
                d = math.hypot(x + .5 - ecx, y + .5 - ecy) - er
                if d < 1.6:
                    ic.mat[y][x] = MIMICLIT
                elif d < 3.2:
                    ic.bias[y][x] += 0.08
    for y in range(N):
        for x in range(N):
            xx, yy = x + .5, y + .5
            if ic.mask[y][x] and ic.fixed[y][x] is None and 8.0 <= xx <= 24.0:
                top = 23.6
                bottom = 28.0 - 0.045 * (xx - 16) ** 2
                if top <= yy <= bottom:
                    ic.fixed[y][x] = DARK
    for tx in (9, 12, 15, 18, 21):
        for (dx, dy, i) in ((0, 0, 3), (1, 0, 2), (0, 1, 3), (1, 1, 2), (0, 2, 4)):
            x, y = tx + dx, 24 + dy
            if ic.mask[y][x]:
                ic.fixed[y][x] = TEETH[i]
    for tx in (11, 14, 17, 20):
        for (dy, i) in ((0, 3), (-1, 2)):
            x, y = tx, 27 + dy
            if ic.mask[y][x]:
                ic.fixed[y][x] = TEETH[i]
    for (x, y, i) in ((18, 26, 1), (19, 26, 2), (20, 26, 2), (19, 27, 1), (20, 27, 1)):
        if ic.mask[y][x]:
            ic.fixed[y][x] = TONGUE[i]
    for (x, y) in ((6, 19), (7, 20), (6, 21), (7, 22), (6, 23), (7, 24)):
        if ic.mask[y][x]:
            ic.fixed[y][x] = hx('c9b98f') if y % 2 == 1 else hx('8a7a5c')
    sparkle(ic, 27, 5, False, hx('c8ff3a'), hx('7bc21c'))
    sparkle(ic, 4, 9, False, hx('c8ff3a'), hx('7bc21c'))
    return ic.shade()


def concept_c():
    """Velvet pouch with a gold drawstring and embroidered ?, spilling a plume of glittering dust."""
    ic = Icon()
    ic.ellipsoid(16, 22.0, 10.4, 8.4, 6.6, VELVET)
    ic.poly(lambda x, y: 11.0 <= y <= 15.0 and abs(x - 16) <= 3.8 + (15.0 - y) * 0.45, VELVET,
            lambda x, y: 3.4 * math.sqrt(max(0.0, 1 - ((x - 16) / 6.2) ** 2)) + 1.2)
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.mat[y][x] is VELVET:
                ic.h[y][x] += (0.75 if y < 17 else 0.25) * math.sin((x - 16) * 1.9)
    for y in range(N):
        for x in range(N):
            xx, yy = x + .5, y + .5
            d = ((xx - 16) / 5.6) ** 2 + ((yy - 10.4) / 2.0) ** 2
            if d <= 1:
                ic.mask[y][x] = True
                ic.fixed[y][x] = TEAL[3 if d < 0.25 else 2 if d < 0.6 else 1]
    for y in range(N):
        for x in range(N):
            if ic.mask[y][x] and ic.fixed[y][x] is None and 12.6 <= y + .5 <= 14.4 and abs(x + .5 - 16) <= 5.0:
                ic.mat[y][x] = GOLD
                ic.h[y][x] += 1.6
    for (x, y) in ((14, 13), (15, 13), (16, 13), (17, 13), (18, 13), (13, 14), (19, 14), (12, 14), (20, 14),
                   (21, 15), (21, 16), (22, 17), (22, 18), (10, 15), (10, 16), (9, 17), (9, 18)):
        ic.blob(x, y, GOLD, 4.0)
    for (x, y) in ((21, 19), (22, 19), (21, 20), (22, 20), (9, 19), (10, 19), (9, 20), (10, 20)):
        ic.blob(x, y, GOLD, 3.0)
    q = ["..XXXX..",
         ".XX..XX.",
         ".XX..XX.",
         "....XXX.",
         "...XXX..",
         "...XX...",
         "........",
         "...XX...",
         "...XX..."]
    cells = {(c, r) for r, row in enumerate(q) for c, ch in enumerate(row) if ch == 'X'}
    ox, oy = 12, 16
    ring = set()
    for (c, r) in cells:
        for a in range(-1, 3):
            for b in range(-1, 3):
                ring.add((c + a, r + b))
    for (c, r) in ring - cells:
        x, y = ox + c, oy + r
        if 0 <= x < N and 0 <= y < N and ic.mask[y][x] and ic.fixed[y][x] is None:
            ic.fixed[y][x] = VELVET[0]
    for (c, r) in cells:
        x, y = ox + c, oy + r
        ic.mask[y][x] = True
        ic.fixed[y][x] = GOLD[4] if r <= 1 else GOLD[3] if r < 5 else GOLD[2]
    stars = [(16, 6, True, 'fff5b4', 'ffd85a'), (11, 4, True, 'a6f0ff', '4fc6e8'), (21, 3, True, 'ffb8ee', 'e86ad0'),
             (14, 1, False, 'efffff', ''), (24, 7, False, 'fff5b4', ''), (7, 7, False, 'ffb8ee', ''),
             (18, 1, False, 'a6f0ff', ''), (9, 9, False, 'efffff', ''), (23, 10, False, 'a6f0ff', ''),
             (26, 3, False, 'ffd85a', ''), (5, 3, False, 'ffd85a', '')]
    for (x, y, big, core, rim) in stars:
        sparkle(ic, x, y, big, hx(core), hx(rim) if rim else hx(core))
    return ic.shade()


def png(w, h, raw):
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def write(path, pixels):
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for c in row:
            r, g, b, a = (c + (255,)) if c else (0, 0, 0, 0)
            raw += bytes((r, g, b, a))
    open(path, "wb").write(png(len(pixels[0]), len(pixels), bytes(raw)))


def sheet(icons, path, S=8):
    pad = 16
    cell = N * S + pad
    W = cell * len(icons) + pad
    Hh = N * S + pad * 2 + N + pad * 2
    canvas = [[(22, 18, 30)] * W for _ in range(Hh)]

    def rect(x0, y0, w, h, c):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                canvas[y][x] = c

    for i, ic in enumerate(icons):
        x0 = pad + i * cell
        rect(x0, pad, N * S, N * S, (139, 139, 139))
        rect(x0, pad, N * S, 3, (55, 55, 55))
        rect(x0, pad, 3, N * S, (55, 55, 55))
        rect(x0 + N * S - 3, pad, 3, N * S, (255, 255, 255))
        rect(x0, pad + N * S - 3, N * S, 3, (255, 255, 255))
        for y in range(N):
            for x in range(N):
                if ic[y][x]:
                    rect(x0 + x * S, pad + y * S, S, S, ic[y][x])
        for k, bgc in enumerate(((20, 16, 28), (110, 150, 66), (139, 139, 139))):
            bx, by = x0 + k * (N + 8), pad * 2 + N * S
            rect(bx, by, N, N, bgc)
            for y in range(N):
                for x in range(N):
                    if ic[y][x]:
                        canvas[by + y][bx + x] = ic[y][x]
    raw = bytearray()
    for row in canvas:
        raw.append(0)
        for r, g, b in row:
            raw += bytes((r, g, b, 255))
    open(path, "wb").write(png(W, Hh, bytes(raw)))


if __name__ == "__main__":
    out = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "cobbletowers", "textures", "item")
    os.makedirs(out, exist_ok=True)
    write(os.path.join(out, "mystery_bag.png"), concept_c())
    print("wrote", os.path.normpath(os.path.join(out, "mystery_bag.png")))
