#!/usr/bin/env python3
"""The Haunted rare of a Trick or Treat vault (P58), on a real server.

Starts runs with fresh bots until floor 1's route offers a Trick or Treat stop and the vault rolls a TREAT. With -Dcobbletowers.forceRare=true that treat
holds a rare. Then both bots throw (the same /cobbletowers rare the chat button and the vault click run) and the log is read:

  * the rare is announced ("a Haunted X (level N) hides in the treat");
  * a bot throws until it catches or runs out, never more than three times, and a catch is logged once ("caught the Haunted");
  * a throw after a catch, or after the third miss, is refused;
  * a player outside a run is told there is no rare to throw at.

Bots cannot receive the tray payload, so the tray itself is checked by eye on a real client.

    SMOKE_JAVA_OPTS="-Dcobbletowers.forceRare=true" python validation/smoke/haunted_rare_test.py --attach --server-dir <rig>
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from rcon import Rcon  # noqa: E402
from floor_encounter_test import give_party  # noqa: E402
from intermission_test import play, UUID_RE  # noqa: E402
from run_durability_test import Result, read_password  # noqa: E402
import walk_tower_test as w  # noqa: E402


def newest_draft(server_dir: Path, floor: int, skip: int):
    text = (server_dir / "logs" / "latest.log").read_text(encoding="utf-8", errors="replace")
    found = re.findall(rf"Run ([0-9a-f-]{{36}}) opened a draft at floor {floor}: .*? stops \[([A-Z_, ]+)\]", text)
    if len(found) <= skip:
        return None
    return found[-1][0], [k.strip() for k in found[-1][1].split(",")]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--attach", action="store_true", help="drive a server that is already running (the only mode)")
    parser.add_argument("--attempts", type=int, default=60)
    args = parser.parse_args()
    server_dir = args.server_dir.resolve()
    node_modules = Path(__import__("os").environ.get("NODE_PATH", str(server_dir.parent / "bots" / "node_modules")).split(":")[0])
    results: list[Result] = []

    def check(name: str, passed: bool, detail: str = "") -> bool:
        results.append(Result(name, bool(passed), detail))
        print(("PASS  " if passed else "FAIL  ") + name + ("  " + detail if detail and not passed else ""), flush=True)
        return bool(passed)

    def last(rcon: Rcon, state: str) -> str:
        lines = [l for l in rcon.command("cobbletowers runs list").splitlines() if re.search(UUID_RE, l) and state in l]
        return lines[-1] if lines else ""

    def throw(rcon: Rcon, name: str) -> str:
        return rcon.command(f"execute as {name} run cobbletowers rare")

    walkers = []
    with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
        seen = 0
        for attempt in range(1, args.attempts + 1):
            a, b = f"HRa{w.STAMP % 1000}{attempt}", f"HRb{w.STAMP % 1000}{attempt}"
            pair = [w.Walker(n, 25565, node_modules, server_dir / "logs" / f"hauntedrare-{n}.log") for n in (a, b)]
            walkers += pair
            for n in (a, b):
                w.wait_for(lambda n=n: n in rcon.command("list"), 90)
                give_party(rcon, n)
            play(rcon, a, f"tower {w.TOWER}"); play(rcon, a, f"invite {b}"); play(rcon, b, f"accept {a}"); play(rcon, a, "confirm"); play(rcon, a, "start")
            w.wait_for(lambda: last(rcon, "ENCOUNTER_ACTIVE") != "", 60)
            time.sleep(3)
            for _ in range(8):
                rcon.command(f"cobbletowers runs winfloor {a}")
                if w.wait_for(lambda: last(rcon, "INTERMISSION") != "", 6):
                    break
            draft = None
            deadline = time.time() + 25
            while time.time() < deadline and not draft:
                draft = newest_draft(server_dir, 1, seen)
                time.sleep(0.5)
            if not draft:
                continue
            seen += 1
            run, kinds = draft
            if "TRICK_OR_TREAT" not in kinds:
                rcon.command(f"cobbletowers runs abandon {run}")
                continue
            card = kinds.index("TRICK_OR_TREAT") + 1
            for rnd in range(2):
                for n in (a, b):
                    play(rcon, n, f"pick {card if rnd == 0 else 1}")
                time.sleep(2)
            outcome = w.log_has(server_dir, rf"Run {run} opened the Jack-o'-Lantern Vault of floor 1: (TREAT|TRICK)", 15)
            if outcome != "TREAT":
                rcon.command(f"cobbletowers runs abandon {run}")
                continue
            check(f"attempt {attempt}: floor 1 offers a Trick or Treat stop and the vault is a treat", True)
            rare = w.log_has(server_dir, rf"Run {run} floor 1: a Haunted (\w+) \(level (\d+)\) hides in the treat", 10)
            if not check("the treat holds a Haunted rare (forceRare is on)", bool(rare)):
                break
            for n in (a, b):
                for _ in range(5):   # more than the three throws allowed
                    throw(rcon, n)
                    time.sleep(0.6)
                log = (server_dir / "logs" / "latest.log").read_text(encoding="utf-8", errors="replace")
                lines = re.findall(rf"Haunted rare: {n} threw: (.*)", log)
                real = [l for l in lines if not l.startswith(("You already", "You are out of", "There is no", "You are not"))]
                caught = [l for l in lines if l.startswith("Caught!")]
                check(f"{n}: five throws made at most three real ones", len(real) <= 3, f"{len(real)} real of {len(lines)}")
                check(f"{n}: a catch happens at most once", len(caught) <= 1, f"{len(caught)}")
                check(f"{n}: it either caught it or used all three throws", bool(caught) or len(real) == 3, f"{len(real)} real, {len(caught)} caught")
                check(f"{n}: the throws after the end were refused", len(lines) == 5 and len(lines) - len(real) >= 2, "; ".join(lines)[:160])
                if caught:
                    check(f"{n}: a catch is logged by the service once", len(re.findall(rf"Run {run}: {n} caught the Haunted", log)) == 1)
            rcon.command(f"cobbletowers runs abandon {run}")
            break
        else:
            check("a floor 1 with a Trick or Treat stop that rolls a treat turned up", False, f"{args.attempts} attempts")
    for walker in walkers:
        walker.process.kill()
    failed = [r for r in results if not r.passed]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    for r in failed:
        print(f"FAIL  {r.name}  {r.detail}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
