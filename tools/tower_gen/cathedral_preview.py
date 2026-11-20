#!/usr/bin/env python3
"""Eye-level pictures of a themed tower's own building (grand_hall.py towers), trimmed as it is pasted. Entities (vendor, pedestals, doors)
are not drawn.

    py -3.14 -I tools/tower_gen/cathedral_preview.py <out_dir> [building] [--tower cathedral|duskvale]
"""
import importlib
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "halloween_tower"))
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
import view as V  # noqa: E402
from themes import THEMES  # noqa: E402


def main() -> int:
    args = sys.argv[1:]
    module = args.pop(args.index("--tower") + 1) if "--tower" in args else "cathedral"
    args = [a for a in args if a != "--tower"]
    spec = importlib.import_module(module).SPEC
    out = Path(args[0] if args else "cathedral_out")
    out.mkdir(parents=True, exist_ok=True)
    b = int(args[1]) if len(args) > 1 else 2
    g = gh.build_building(spec, THEMES[spec.theme], b)
    tg.trim(g)
    scene = V.Scene(g.grid, g.palette)
    gallery_x, gallery_up, centre_z = spec.spectator
    for floor in spec.buildings[b - 1][-1:]:
        f = gh.feet(spec, floor) + 1.62
        shots = {
            "arrival": (gh.MID + 0.5, f, gh.ARRIVAL_ANCHOR_Z + 0.5, 180, -4, 95),
            "bridge": (gh.MID + 0.5, f, gh.HALL_Z[1] - 2.5, 180, -6, 100),
            "island": (gh.MID + 0.5, f, spec.entry_z + 0.5, 180, -28, 105),
            "gallery": (gallery_x + 0.5, f + gallery_up, gh.HALL_Z[1] - 6.5, 220, 14, 100),
            "back": (gh.MID + 0.5, f, spec.presentation_z - 3.5, 0, -20, 105),
            "atrium": (gh.MID - 14.5, f, 44.5, 180, -2, 95),
        }
        for name, (x, y, z, yaw, pitch, fov) in shots.items():
            scene.render((x, y, z), yaw, pitch, 960, 540, fov, exposure=1.15).save(out / f"floor{floor}_{name}.png")
            print("wrote", out / f"floor{floor}_{name}.png")
    if scene.missing:
        print("no texture for:", sorted(scene.missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
