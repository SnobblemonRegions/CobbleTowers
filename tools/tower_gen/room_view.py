"""Previews of the stop rooms (rooms.py): each set in its chamber shell of the tower's own walls, seen from the door the party walks in by."""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "halloween_tower"))
import view as V  # noqa: E402


def scene(room, theme) -> "V.Scene":
    """The room inside a chamber of the theme's walls: the vault slab above, the back doorway open to the north."""
    w, h, d = room.grid.shape
    pal = ["minecraft:air", theme["wall"], theme["vault"]]
    grid = np.zeros((w + 2, h + 3, d + 2), dtype=np.uint16)
    grid[:, :, :] = 1
    grid[1:w + 1, 1:h + 1, 1:d + 1] = 0
    grid[:, h + 1:h + 3, :] = 2
    grid[5:8, 1:5, 0] = 0                               # back doorway to the lane stair
    grid[4:8, 1:7, d + 1] = 0                           # the route door, open
    index = {n: i for i, n in enumerate(pal)}
    for name in room.palette[1:]:
        if name not in index:
            index[name] = len(pal)
            pal.append(name)
    for rid, name in enumerate(room.palette):
        if rid == 0:
            continue
        xs, ys, zs = np.nonzero(room.grid[:, 0:h, :] == rid)
        grid[xs + 1, ys, zs + 1] = index[name]
    return V.Scene(grid, pal)


def shot(room, theme, w=640, h=360) -> Image.Image:
    """From just inside the door, looking down the aisle to the back doorway."""
    d = room.grid.shape[2]
    return scene(room, theme).render((6.5, 1 + 1.62, d + 0.9), 180, 8, w, h, 82, exposure=1.2)


def sheet(rooms: dict, path: Path, theme_name: str) -> None:
    from themes import THEMES
    theme = THEMES[theme_name]
    cols, cw, ch = 4, 640, 360
    rows = (len(rooms) + cols - 1) // cols
    out = Image.new("RGB", (cols * cw, rows * (ch + 24)), (20, 18, 24))
    dr = ImageDraw.Draw(out)
    for k, (kind, room) in enumerate(rooms.items()):
        x, y = (k % cols) * cw, (k // cols) * (ch + 24)
        out.paste(shot(room, theme, cw, ch), (x, y + 24))
        dr.text((x + 8, y + 6), f"{theme_name} {kind}", fill=(235, 225, 200))
    out.save(path)
