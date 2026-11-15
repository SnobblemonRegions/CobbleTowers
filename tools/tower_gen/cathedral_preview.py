#!/usr/bin/env python3
"""Eye-level pictures of the built Tideforge cathedral (cathedral.py), trimmed as it is pasted. Entities (vendor, pedestals) are not drawn.

    py -3.14 -I tools/tower_gen/cathedral_preview.py <out_dir> [building]
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "halloween_tower"))
import cathedral as C  # noqa: E402
import tower_gen as tg  # noqa: E402
import view as V  # noqa: E402


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "cathedral_out")
    out.mkdir(parents=True, exist_ok=True)
    b = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    g = C.build_building(C.THEMES[C.THEME], b)
    tg.trim(g)
    scene = V.Scene(g.grid, g.palette)
    for floor in C.BUILDINGS[b - 1][-1:]:
        f = C.feet(floor) + 1.62
        shots = {
            "arrival": (C.MID + 0.5, f, C.ARRIVAL_ANCHOR_Z + 0.5, 180, -4, 95),
            "bridge": (C.MID + 0.5, f, C.HALL_Z[1] - 2.5, 180, -6, 100),
            "island": (C.MID + 0.5, f, C.ISLAND_Z[1] - 3.5, 180, -28, 105),
            "gallery": (12.5, f + C.GALLERY_UP + 1, C.HALL_Z[1] - 6.5, 220, 14, 100),
            "back": (C.MID + 0.5, f, C.ISLAND_Z[0] + 2.5, 0, -20, 105),
            "atrium": (C.MID - 14.5, f, 44.5, 180, -2, 95),
        }
        for name, (x, y, z, yaw, pitch, fov) in shots.items():
            scene.render((x, y, z), yaw, pitch, 960, 540, fov, exposure=1.15).save(out / f"floor{floor}_{name}.png")
            print("wrote", out / f"floor{floor}_{name}.png")
    if scene.missing:
        print("no texture for:", sorted(scene.missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
