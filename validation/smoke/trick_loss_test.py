#!/usr/bin/env python3
"""Proves what a lost Trick or Treat mimic costs, on a real server (the rest of the vault is walk_tower_test.py's).

With a relic in hand (--relic) the price includes it: a lost mimic takes it (granting it again then works), a won one returns it (granting it again is
refused because it is held). Starts runs with fresh bots until floor 1's route offers a Trick or Treat stop and the vault rolls a trick. Then the mimic is lost on purpose (operator
command runs losemimic) and the team cashes out, so the run's loot is banked and the cut can be read from the log:

    the price is taken first (a ledger mark: "loot cut now 5%"), the loss gives nothing back ("beat the mimic" never appears),
    and the banked payout is cut ("payout is cut 5% by lost mimic(s)").

Needs the Halloween tower open (-Dcobbletowers.ignoreWindows=true), RCON on 25575, mineflayer and vec3 on NODE_PATH.

    python validation/smoke/trick_loss_test.py --attach --server-dir <rig>/srv
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
    """The run and stops of the newest route draft at {floor} beyond the first {skip} (abandoned runs of earlier attempts stay in the log)."""
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
    parser.add_argument("--relic", default="cobbletowers:berserkers_mask", help="a relic handed to the team before the vault opens, so the mimic has one to take")
    parser.add_argument("--win", action="store_true", help="win the mimic instead of losing it: the relic must come back and the cut be undone")
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

    walkers = []
    with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
        seen = 0
        for attempt in range(1, args.attempts + 1):
            a, b = f"TLa{w.STAMP % 1000}{attempt}", f"TLb{w.STAMP % 1000}{attempt}"
            pair = [w.Walker(n, 25565, node_modules, server_dir / "logs" / f"trickloss-{n}.log") for n in (a, b)]
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
                if rnd == 0 and args.relic:
                    # the route is settled and the vault's own vote is open: put a relic in the team's hands before it is opened
                    reply = rcon.command(f"cobbletowers runs grant {run} {args.relic}")
                    check(f"the team is handed {args.relic}", "Granted relic" in reply, reply[:120])
            outcome = w.log_has(server_dir, rf"Run {run} opened the Jack-o'-Lantern Vault of floor 1: (TREAT|TRICK)", 15)
            if outcome != "TRICK":
                rcon.command(f"cobbletowers runs abandon {run}")
                continue
            check(f"attempt {attempt}: floor 1 offers a Trick or Treat stop and the vault is a trick", True)
            taken = w.log_has(server_dir, rf"Run {run} mimic on floor 1: (.*)", 15)
            check("the mimic is raised for both players and the price is taken first (the cut is marked)", "2 fighter" in taken and "cut now 5%" in taken, taken)
            if args.relic:
                check("and the price includes the relic the team held", f"relic taken {args.relic}" in taken, taken)
            time.sleep(2)
            if args.win:
                rcon.command(f"cobbletowers runs winfloor {a}")
                back = w.log_has(server_dir, rf"Run {run} beat the mimic of floor 1: (.*)", 20)
                check("beating the mimic returns the price", bool(back), back)
                if args.relic:
                    again = rcon.command(f"cobbletowers runs grant {run} {args.relic}")
                    check("and the relic is back with the team (granting it again is refused)", "already held" in again, again[:120])
                break
            rcon.command(f"cobbletowers runs losemimic {a}")
            lost = w.log_has(server_dir, rf"Run {run} lost the mimic of floor 1: (.*)", 20)
            check("losing the mimic leaves the price taken", bool(lost), lost)
            if args.relic:
                check("and the log names the relic that was lost", args.relic in lost, lost)
                gone = rcon.command(f"cobbletowers runs grant {run} {args.relic}")
                check("and the team no longer holds it (it can be granted again)", "Granted relic" in gone, gone[:120])
            time.sleep(2)
            check("and nothing is paid back", not w.log_has(server_dir, rf"Run {run} beat the mimic", 1))
            # bank the run: both players cash out, so the cut can be read off the payout
            for n in (a, b):
                rcon.command(f"execute as {n} run cobbletowers runs cashout")
            cut = w.log_has(server_dir, rf"Run {run} payout is cut (\d+)%", 30)
            check("cashing out banks the loot with the lost mimic's 5% taken from it", cut == "5", f"cut {cut or 'never logged'}")
            banked = w.log_has(server_dir, rf"Run {run} banked its rewards through floor 1: (.*)", 5)
            check("and a payout was banked", bool(banked), banked)
            break
        else:
            check("a floor 1 with a Trick or Treat stop that rolls a trick turned up", False, f"{args.attempts} attempts")
    for walker in walkers:
        walker.process.kill()
    failed = [r for r in results if not r.passed]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    for r in failed:
        print(f"FAIL  {r.name}  {r.detail}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
