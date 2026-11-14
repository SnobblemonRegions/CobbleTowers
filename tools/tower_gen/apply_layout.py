#!/usr/bin/env python3
"""Copies the generator's layout into a tower's floor and tower definitions, so the anchors in data always match the structures last generated.

    py -3.14 -I tools/tower_gen/apply_layout.py [theme]          (default: neutral; run tower_gen.py --install first)

Floors: `layout` gets structure, entry, presentation, spectator, exit, gate_a and doors (gate_b is removed). Tower: `route_map` and
`pedestal_votes` are switched on and `prep` is written. Everything else in the files is left as it was.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "src/main/resources/data/cobbletowers/cobbletowers"


def main() -> int:
    theme = sys.argv[1] if len(sys.argv) > 1 else "neutral"
    layout = json.loads((ROOT / "validation/schematics" / theme / f"{theme}.layout.json").read_text())
    for index, floor_layout in layout["floors"].items():
        path = DATA / "floors" / theme / f"floor_{int(index):02d}.json"
        floor = json.loads(path.read_text())
        old = floor["layout"]
        for key in ("structure", "entry", "presentation", "spectator", "exit", "gate_a", "gate_b", "doors"):
            if key in floor_layout:
                old[key] = floor_layout[key]
            else:
                old.pop(key, None)
        path.write_text(json.dumps(floor, indent=2) + "\n")
        print("updated", path.relative_to(ROOT))
    tower_path = DATA / "towers" / f"{theme}.json"
    tower = json.loads(tower_path.read_text())
    prep = layout["prep"]
    tower["pedestal_votes"] = True
    tower["route_map"] = True
    tower["prep"] = {"structure": f"cobbletowers:{theme}_prep", **{k: prep[k] for k in ("entry", "vendor", "dais", "door", "threshold")}}
    tower["revision"] = tower.get("revision", 1) + 1
    tower_path.write_text(json.dumps(tower, indent=2) + "\n")
    print("updated", tower_path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
