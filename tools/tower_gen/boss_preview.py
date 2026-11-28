#!/usr/bin/env python3
"""Builds the boss halls (floors 5 and 10) of the towers on their own, checks the walk through each and renders eye-level concept pictures.

    py -3.14 -I tools/tower_gen/boss_preview.py <out_dir> [tower ...]        (towers: neutral tideforge duskvale rootvale)
"""
from __future__ import annotations

import importlib
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "halloween_tower"))
import boss_kit as K  # noqa: E402
import cathedral  # noqa: E402,F401  (water is see-through for the trim)
import grand_hall as gh  # noqa: E402
import tower_gen as tg  # noqa: E402
from themes import THEMES  # noqa: E402

TOWERS = ("neutral", "tideforge", "duskvale", "rootvale")
if "leaves" not in tg.SEE_THROUGH:
    tg.SEE_THROUGH = tg.SEE_THROUGH + ("leaves",)


def concept_spec(tower: str) -> gh.Spec:
    m = importlib.import_module(f"boss_{tower}")
    return gh.Spec(theme=tower, buildings=((5,), (10,), (6,)), crowns=(), pitch=0, base_feet=0, top=lambda c: 0, hall=None,
                   entry_z=0, presentation_z=0, spectator=(0, 0, 0), boss={5: m.BOSS5, 10: m.BOSS10})


def check(spec: gh.Spec, floor: int, g) -> list[str]:
    boss = spec.boss[floor]
    f = boss.base_feet
    lay = gh.layout(spec, floor)
    w = tg.Walker(g)
    out = []
    entry, pres, spot = tg._cell(lay["entry"]), tg._cell(lay["presentation"]), tg._cell(lay["spectator"])
    for name, c in (("entry", entry), ("presentation", pres), ("spectator", spot)):
        if not w.standable(*c):
            out.append(f"the {name} anchor {c} is not standable")
    closed = w.reach(entry, [])
    if pres not in closed:
        out.append("the opponent's spot is not reachable from the entry")
    if spot in closed:
        out.append("the spectators can be reached from the arena")
    vendor = (lay["exit"]["x"], f, lay["exit"]["z"] + 2)
    if vendor in closed:
        out.append("the vendor is reachable with gate A shut")
    if vendor not in w.reach(entry, [lay["gate_a"]]):
        out.append("the vendor is not reachable through gate A")
    arrival = (gh.MID, f, gh.ARRIVAL_ANCHOR_Z)
    if entry not in w.reach(arrival, []):
        out.append("the hall is not reachable from the arrival")
    out += boss.checks(g)
    return out


def main() -> int:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    towers = sys.argv[2:] or TOWERS
    import view as V
    failed = 0
    for tower in towers:
        spec = concept_spec(tower)
        for b, floor in ((1, 5), (2, 10)):
            g = gh.build_building(spec, THEMES[tower], b)
            problems = check(spec, floor, g)
            tg.trim(g)
            boss = spec.boss[floor]
            starts = [tg._cell(gh.layout(spec, floor)[k]) for k in ("entry", "spectator")]
            problems += gh.shell_problems(g, starts, boss.open_sky)
            print(f"{tower} floor {floor}: {g.grid.shape}, " + ("OK" if not problems else f"{len(problems)} problem(s)"), flush=True)
            for p in problems:
                print("   ", p)
            failed += bool(problems)
            scene = V.Scene(g.grid, g.palette, day=boss.open_sky)
            f = boss.base_feet + 1.62
            sx, sy, sz = boss.spectator
            shots = {
                "a_enter": (gh.MID + 0.5, f, 138.5, 180, -12, 100),
                "b_arena": (gh.MID + 0.5, f, boss.entry_z + 0.5, 180, -24, 108),
                "c_spectator": (sx + 0.5, f + sy, sz + 0.5, 270, 6, 92),
                "d_back": (gh.MID + 6.5, f, boss.presentation_z + 0.5, 20, -20, 108),
            }
            for name, (x, y, z, yaw, pitch, fov) in shots.items():
                scene.render((x, y, z), yaw, pitch, 960, 540, fov, exposure=1.15).save(out / f"{tower}_f{floor}_{name}.png")
            if scene.missing:
                print("    no texture for:", sorted(scene.missing))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
