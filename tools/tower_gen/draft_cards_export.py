#!/usr/bin/env python3
"""Exports the Rental Draft cards and effects shown on a Tower Hub gate's rules board (owner, 23 Nov 2026), from the draft screen's own art
(the bridge mod's snobblemon_regions textures, rd_*.png), with each Pokemon drawn by Cobblemon Cards' own sprite (owner, 23 Nov 2026: cleaner
than a photographed model). The sprites are referenced where Cobblemon Cards keeps them (its entity_icon textures), not copied: every player
has that mod, and its art stays its own.

A card is drawn as text (owner, 23 Nov 2026: the cards must light themselves; an item model in the world is always shaded by the light's
direction, text is not), in two text displays a little apart so no two layers ever share a plane (24 Nov: they flickered): behind, the card
itself (its type's face with its tier's frame baked in, one glyph, hanging below the first line, 52 x 36.4 units, 1.3 x 0.91 blocks at a
text display's 1/40 block a unit); in front, the Pokemon's sprite on the second line (raised into the burst) and a type gem on each of the
next two (at the right edge). Face down is the back's glyph. The effects (pack, its torn strip, rays, ring, flash, stamp, kept frame, check, cover) stay flat
item models, centred on their (8, 8, 8), facing +z.

    py -3.14 -I tools/tower_gen/draft_cards_export.py --art "<bridge>/snobblemon-server/src/main/resources/assets/snobblemon_regions/textures/gui/pages/cobbletowers" \
        --cards <cobblemon-cards-fabric-*.jar>

Writes assets/cobbletowers/textures/item/draft/**, models/draft/fx/**, font/draft_cards.json, and data/.../hub/draft.json (the glyphs, the card's
size and where its name sits).
"""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TEX = ROOT / "src/main/resources/assets/cobbletowers/textures/item/draft"
MODELS = ROOT / "src/main/resources/assets/cobbletowers/models/draft"
SETS = ROOT / "src/main/resources/data/cobbletowers/cobbletowers/rental_sets"
DATA = ROOT / "src/main/resources/data/cobbletowers/cobbletowers/hub/draft.json"
TYPES = ("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost",
         "dragon", "steel", "fairy", "dark")
TIERS = ("common", "uncommon", "rare", "epic", "legendary", "mythic")
FONT = ROOT / "src/main/resources/assets/cobbletowers/font/draft_cards.json"
# the card in text units (1/40 block): 1.3 x 0.91 blocks
CARD_UH = 52
SPRITE_UH = 22                           # the sprite's height: 22 x 33 units across the burst
LINE = 9                                 # a text line's height
GEM_UH = 5

W, H = 14.56, 20.8                       # the card, in 1/16 block
X0, X1 = 8 - W / 2, 8 + W / 2
Y0, Y1 = 8 - H / 2, 8 + H / 2
FACE_W, FACE_H = 280, 400                # the card textures: half the screen's 560 x 802 (owner, 24 Nov 2026: a quarter was blurry up close)
FX_W, FX_H = 140, 200                    # the foil and kept-frame effects, drawn at the card's old size
SPRITE_PX = 0.27                         # one sprite pixel, in 1/16 block (a 48 x 32 sprite is 13 x 8.6 across the burst)
SPRITE_MID = 0.37                        # the burst's middle, as a share of the card's height down from the top
ICONS = "assets/cobblemon-cards/textures/item/cards/pokemon/entity_icon/"
# rental species whose sprite file is named otherwise in Cobblemon Cards: the standard form
SPRITE_NAMES = {"alcremie": "alcremie_vanilla", "golurk": "golurk_1", "hippowdon": "hippowdon_male", "mrrime": "mr_rime",
                "porygonz": "porygon-z", "toxtricity": "toxtricity_amped", "venusaur": "venusaur_male"}
NAME_AT = 0.665                          # the name line's middle, likewise


def put(path: Path, img: Image.Image):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n")


def plane(x0, y0, x1, y1, z, tex, uv=(0, 0, 16, 16), back=None, depth=0.01):
    """A flat element facing +z (and -z when `back` is given)."""
    faces = {"south": {"uv": list(uv), "texture": tex}}
    if back:
        faces["north"] = {"uv": [16, 0, 0, 16], "texture": back}
    # unshaded: the cards and effects light themselves (owner, 23 Nov 2026), so no face is darkened by its direction
    return {"from": [round(x0, 4), round(y0, 4), round(z - depth, 4)], "to": [round(x1, 4), round(y1, 4), round(z, 4)], "shade": False,
            "faces": faces}


def model(textures: dict, elements: list) -> dict:
    t = dict(textures)
    t.setdefault("particle", next(iter(textures.values())))
    return {"textures": t, "elements": elements,
            "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}}


def textures(art: Path):
    # each type's face with each tier's frame laid over it, as one picture (owner, 24 Nov 2026: two glyphs drawn on one plane fought for
    # every pixel and flickered); a 256-colour palette keeps the 108 of them small
    import shutil

    def palette(img):
        """255 colours plus one fully clear index, so the card's rounded corners stay see-through."""
        clear = img.getchannel("A").point(lambda v: 255 if v < 128 else 0)
        p = img.convert("RGB").quantize(255, method=Image.Quantize.MEDIANCUT)
        p.paste(255, mask=clear)
        p.info["transparency"] = 255
        return p

    for old in ("face", "tier"):
        shutil.rmtree(TEX / old, ignore_errors=True)
    tiers = {r: Image.open(art / f"rd_r_{r}.png").convert("RGBA").resize((FACE_W, FACE_H), Image.LANCZOS) for r in TIERS}
    for t in TYPES:
        face = Image.open(art / f"rd_{t}.png").convert("RGBA").resize((FACE_W, FACE_H), Image.LANCZOS)
        for r in TIERS:
            card = face.copy()
            card.alpha_composite(tiers[r])
            put(TEX / "card" / f"{t}_{r}.png", palette(card))
        gem = Image.open(art / f"rd_gem_{t}.png").convert("RGBA").resize((20, 20), Image.LANCZOS)
        gem.putalpha(gem.getchannel("A").point(lambda v: 255 if v >= 110 else 0))     # hard-edged: drawn over the card, no soft fringe
        put(TEX / "gem" / f"{t}.png", gem)
    put(TEX / "back.png", Image.open(art / "rd_back.png").convert("RGBA").resize((FACE_W, FACE_H), Image.LANCZOS))
    # the stats back (owner, 24 Nov 2026: Inspect turns a card over to its set): a dark card under its tier's frame, one per tier; the set
    # itself is lit text in front of it, so new sets need no pictures
    from PIL import ImageDraw, ImageFilter
    big = (560, 800)                                                                    # drawn at the art's own size
    for r in TIERS:
        panel = Image.new("RGBA", big, (0, 0, 0, 0))
        ImageDraw.Draw(panel).rounded_rectangle((6, 6, big[0] - 7, big[1] - 7), 26, fill=(16, 16, 24, 255))
        glow = Image.new("RGBA", big, (0, 0, 0, 0))                                     # a faint cool light from the top
        ImageDraw.Draw(glow).ellipse((-big[0] // 2, -big[1] // 3, big[0] * 3 // 2, big[1] // 3), fill=(120, 130, 170, 40))
        lit = Image.alpha_composite(panel, glow.filter(ImageFilter.GaussianBlur(60)))
        lit.putalpha(panel.getchannel("A"))
        card = lit.resize((FACE_W, FACE_H), Image.LANCZOS)
        card.alpha_composite(tiers[r])
        put(TEX / "stats" / f"{r}.png", palette(card))
    for name, size in (("pack", (110, 157)), ("pack_top", (110, 15)), ("pack_body", (110, 142)), ("ring", (128, 128)), ("rays", (154, 154)),
                       ("check", (32, 32)), ("stamp_legendary", (200, 60)), ("stamp_mythic", (200, 60)), ("glow", (128, 64))):
        put(TEX / "fx" / f"{name}.png", Image.open(art / f"rd_{name}.png").convert("RGBA").resize(size, Image.LANCZOS))
    # the foil: the screen's prism band sweeping across a card-sized frame, as an animated texture (16 frames)
    prism = Image.open(art / "rd_prism.png").convert("RGBA").resize((FX_W // 2 * 3, FX_H // 2), Image.LANCZOS)
    fw, fh, n = FX_W // 2, FX_H // 2, 16
    strip = Image.new("RGBA", (fw, fh * n), (0, 0, 0, 0))
    for k in range(n):
        frame = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
        frame.alpha_composite(prism, (int(-prism.width + (fw + prism.width) * k / (n - 1)), 0))
        r, g, b, a = frame.split()
        frame.putalpha(a.point(lambda v: int(v * 0.45)))
        strip.alpha_composite(frame, (0, k * fh))
    put(TEX / "fx" / "foil.png", strip)
    (TEX / "fx" / "foil.png.mcmeta").write_text(json.dumps({"animation": {"frametime": 3, "interpolate": True, "width": fw, "height": fh}}) + "\n")
    # the kept frame: a gold rounded border with a soft glow, a little larger than the card
    frame = Image.new("RGBA", (FX_W + 16, FX_H + 16), (0, 0, 0, 0))
    from PIL import ImageDraw, ImageFilter
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((5, 5, frame.width - 6, frame.height - 6), 12, outline=(221, 171, 60, 255), width=4)
    halo = frame.filter(ImageFilter.GaussianBlur(4))
    halo.alpha_composite(frame)
    put(TEX / "fx" / "kept.png", halo)
    put(TEX / "fx" / "flash.png", Image.new("RGBA", (16, 16), (255, 255, 255, 110)))
    put(TEX / "fx" / "cover.png", Image.new("RGBA", (16, 16), (21, 18, 28, 255)))


def sprites(jar: Path) -> dict:
    """Each species' sprite in Cobblemon Cards: species -> (texture id, width, height)."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not (name.startswith(ICONS) and name.endswith(".png")):
                continue
            parts = name[len(ICONS):].split("/")
            if len(parts) != 2 or "_" not in parts[0]:           # only <dex>_<species>/<file>: variant folders are skipped
                continue
            folder, file = parts
            species = folder.split("_", 1)[1]
            if file[:-4] != SPRITE_NAMES.get(species, species):
                continue
            with z.open(name) as f:
                w, h = Image.open(f).size
            out[species] = ("cobblemon-cards:" + name[len("assets/cobblemon-cards/textures/"):-4], w, h)
    return out


def advance(tex_w: int, tex_h: int, height: int) -> int:
    """How far a bitmap glyph moves the pen, as the game reckons it."""
    return int(0.5 + tex_w * height / tex_h) + 1


def font(found: dict, species_types: dict) -> dict:
    """The card font, and the glyph of every part: face_<type>, tier_<tier>, back, sprite_<species>, gem<line>_<type>, and the spaces."""
    providers, glyphs = [], {}
    code = [0xE200]

    def char():
        c = chr(code[0])
        code[0] += 1
        return c

    def bitmap(key, file, height, ascent):
        c = char()
        providers.append({"type": "bitmap", "file": file + ".png", "height": height, "ascent": ascent, "chars": [c]})
        glyphs[key] = c

    # A card picture is bigger than the game's 256 x 256 glyph pages (owner, 24 Nov 2026: at 280 x 400 it drew as a missing-glyph box), so
    # each is cut into four 140 x 200 tiles, each its own glyph from the same file: the top two hang from the baseline, the bottom two a
    # half-card lower, laid edge to edge by small (fractional) spaces, since a glyph's own advance is rounded and adds a unit.
    half_w = CARD_UH / 2 * (FACE_W / 2) / (FACE_H / 2)                                  # a tile's drawn width, in text units
    tile_adv = advance(FACE_W // 2, FACE_H // 2, CARD_UH // 2)
    tile_spaces = {}

    def space(adv):
        c = char()
        tile_spaces[c] = round(adv, 4)
        return c

    seam = space(half_w - tile_adv)                                                     # back to where the left tile's right edge is
    back_row = space(-(half_w + tile_adv))                                              # from the end of the top row to its start

    def tiled(key, file):
        a, b, c, d = char(), char(), char(), char()
        z = "\u0000"
        providers.append({"type": "bitmap", "file": file + ".png", "height": CARD_UH // 2, "ascent": 0, "chars": [a + b, z + z]})
        providers.append({"type": "bitmap", "file": file + ".png", "height": CARD_UH // 2, "ascent": -(CARD_UH // 2), "chars": [z + z, c + d]})
        glyphs[key] = a + seam + b + back_row + c + seam + d

    for t in TYPES:
        for r in TIERS:                                                                 # the card hangs below the first line's baseline
            tiled(f"card_{t}_{r}", f"cobbletowers:item/draft/card/{t}_{r}")
        bitmap(f"gem3_{t}", f"cobbletowers:item/draft/gem/{t}", GEM_UH, -(round(CARD_UH * 0.56) - 2 * LINE))
        bitmap(f"gem4_{t}", f"cobbletowers:item/draft/gem/{t}", GEM_UH, -(round(CARD_UH * 0.56) + GEM_UH + 1 - 3 * LINE))
    tiled("back", "cobbletowers:item/draft/back")
    for r in TIERS:
        tiled(f"stats_{r}", f"cobbletowers:item/draft/stats/{r}")
    mid = round(CARD_UH * SPRITE_MID)                                                   # the burst's middle, below the card's top
    for sp in sorted(species_types):
        tex, w, h = found[sp]
        bitmap(f"sprite_{sp}", tex, SPRITE_UH, round(SPRITE_UH / 2 + LINE - mid))       # second line, raised into the burst
    card_adv = round(half_w + tile_adv, 4)                                              # a tiled card's own advance
    gem_adv = advance(20, 20, GEM_UH)
    gem_right = CARD_UH * FACE_W / FACE_H / 2 - 1.6                                     # the gem's right edge, in from the card's
    gem_pad = round(2 * (gem_right - GEM_UH) + gem_adv)                                 # so a centred line of pad + gem lands there
    spaces = dict(tile_spaces)
    for key, adv in (("back_card", -card_adv), ("gem_pad", gem_pad)):
        c = char()
        spaces[c] = adv
        glyphs[key] = c
    providers.insert(0, {"type": "space", "advances": spaces})
    return {"providers": providers}, glyphs


def fx_models():
    """Flat effect models, each centred on (8, 8, 8), facing +z; sizes in 1/16 block."""
    def flat(name, w, h, tex=None, back=None, depth=0.01):
        t = {"t": f"cobbletowers:item/draft/fx/{tex or name}"}
        if back:
            t["b"] = f"cobbletowers:item/draft/{back}"
        write(MODELS / "fx" / f"{name}.json", model(t, [plane(8 - w / 2, 8 - h / 2, 8 + w / 2, 8 + h / 2, 8 + depth / 2, "#t",
                                                               back="#b" if back else None, depth=depth)]))
    for old in (MODELS / "card").glob("*.json") if (MODELS / "card").exists() else []:
        old.unlink()                                       # cards are text now: the old card models go
    flat("pack", 16, 22.8, depth=1.2)                     # a fat sealed pack, about a block wide
    flat("pack_top", 16, 2.2, depth=1.2)
    flat("pack_body", 16, 20.6, depth=1.2)
    flat("rays", 32, 32)
    flat("ring", 16, 16)
    flat("flash", W, H, depth=0.02)
    flat("stamp_legendary", 20, 6)
    flat("stamp_mythic", 20, 6)
    flat("kept", W + 1.7, H + 1.7)
    flat("check", 3.2, 3.2)
    flat("glow", 24, 12)
    flat("cover", 16, 4)                                    # the rules panel's face, dark, over the shared rules text: the display scales
                                                            # it by 7 (7 x 1.75 blocks); an item model's elements must stay within -16..32


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--art", required=True, type=Path)
    ap.add_argument("--cards", required=True, type=Path, help="the Cobblemon Cards jar, for its sprites' names and sizes")
    args = ap.parse_args()
    textures(args.art)
    fx_models()
    found = sprites(args.cards)
    cards = {}
    missing = []
    for p in sorted(SETS.glob("*.json")):
        d = json.loads(p.read_text())
        sp = d["species"]
        if sp not in found:
            missing.append(sp)
            continue
        cards[sp] = d["types"]
    data, glyphs = font(found, cards)
    write(FONT, data)
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.write_text(json.dumps({"card": [round(CARD_UH * FACE_W / FACE_H / 40, 4), round(CARD_UH / 40, 4)],
                                "name_at": round((0.5 - NAME_AT) * CARD_UH / 40, 4), "tiers": list(TIERS), "species": sorted(cards),
                                "glyphs": glyphs}, indent=2, ensure_ascii=True) + "\n")
    print(len(cards), "species,", len(glyphs), "glyphs;", "no Cobblemon Cards sprite: " + ", ".join(missing) if missing else "every sprite found")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
