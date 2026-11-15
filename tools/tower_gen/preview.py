#!/usr/bin/env python3
"""Pictures of what tower_gen.py generates (trimmed, as it will be pasted), with the Halloween tower's eye-level ray caster.

    py -3.14 -I tools/tower_gen/preview.py <out_dir> [shot ...] [--theme neutral]

No entities: the vendor, pedestals, holograms and route-map icons are not drawn.
"""
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "halloween_tower"))
import tower_gen as tg  # noqa: E402
import view as V  # noqa: E402

F1, F5 = tg.feet(1), tg.feet(5)
SHOTS = {
    # name: (building, x, feet y, z, yaw, pitch, fov)   building: act1 | prep | plan1 (floor 1 cut at head height, seen from above) | shell
    "gen_arrival": ("act1", tg.MID_X, F1, 111, 180, -2, 95),
    "gen_battle_hall": ("act1", tg.MID_X, F1, 97, 180, -6, 90),
    "gen_crown_hall": ("act1", tg.MID_X, F5, 97, 180, -14, 95),
    "gen_gallery": ("act1", 6, F1 + 6, 92, 210, 10, 90),
    "gen_atrium": ("act1", 20, F1, 44, 180, -2, 95),
    "gen_stand": ("act1", 44, F1, 41, 135, 4, 85),
    "gen_chamber_stair": ("act1", tg.LANES[1], F1, 26, 180, -10, 90),
    "gen_threshold": ("act1", tg.LANES[1], F1, 10, 180, -16, 90),
    "gen_prep": ("prep", tg.PREP_ROOM[0] + 1, tg.PREP_FEET, 20, 270, -4, 95),
    "gen_plan_floor1": ("plan1", tg.MID_X, F1 + 70, 120, 180, 55, 80),
    "gen_shell": ("shell", -40, 90, -40, -45, 22, 60),
}


def scene(kind: str, acts, prep) -> V.Scene:
    if kind == "prep":
        return V.Scene(prep.grid, prep.palette)
    g = acts[1]
    grid = g.grid
    if kind == "plan1":
        grid = grid.copy()
        grid[:, F1 + 7:, :] = 0                                             # cut at head height: the plan of floor 1
        grid = np.pad(grid, ((0, 0), (0, 0), (0, 40)))
    if kind == "shell":
        grid = np.pad(grid, ((60, 0), (0, 40), (60, 0)))                    # room for an eye outside, north-west and above
        return V.Scene(grid, g.palette, sky=True)
    return V.Scene(grid, g.palette, sky=(kind == "plan1"))


def main() -> int:
    args = sys.argv[1:]
    theme = args.pop(args.index("--theme") + 1) if "--theme" in args else "neutral"
    args = [a for a in args if a != "--theme"]
    out = Path(args[0] if args else "gen_out")
    out.mkdir(parents=True, exist_ok=True)
    acts, prep, _ = tg.build_all(theme)
    scenes = {}
    for name in args[1:] or list(SHOTS):
        kind, x, y, z, yaw, pitch, fov = SHOTS[name]
        if kind not in scenes:
            scenes[kind] = scene(kind, acts, prep)
        if kind == "shell":
            x, z = x + 60, z + 60
        scenes[kind].render((x + 0.5, y + 1.62, z + 0.5), yaw, pitch, 960, 540, fov, exposure=1.15).save(out / f"{name}.png")
        print("wrote", out / f"{name}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
