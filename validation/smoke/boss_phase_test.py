#!/usr/bin/env python3
"""Proves boss phases (P58) live: Tideforge's floor 5 boss, Regieleki, carries its phases into the real Showdown and one fires.

A bot that fights properly (battlebot.js) plays a Tideforge run. Floors 1-4 are skipped by operator command; at floor 5:

  * the boss starts and its battle carries the milestone's phases (`"op":"phase"` in the towerFx the server hands to Showdown),
    resolved to the boss's side;
  * the extension applies them without error ("Applied 1 of 1 tower effect(s)" for the phase op);
  * as the bot damages the boss, the Overclock phase fires: the JavaScript says so in the server log
    ("[CobbleTowers] Phase overclock fired") and the battle log carries its announcement;
  * no CobbleTowers exception during any of it.

What each effect does inside Showdown is proved by validation/showdown/tower_fx_test.js; this proves the wiring around it.

    python validation/smoke/boss_phase_test.py --server-dir <rig> --java <jdk21 java> [--jar <build>] [--node-modules <dir>]
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from rcon import Rcon  # noqa: E402
from run_durability_test import (  # noqa: E402
    Result, Server, clear_tower, install_jar, reset_tower_world, read_password, server_port,
)

BOT = f"TBp{int(time.time()) % 100000}"
TOWER = "cobbletowers:tideforge"
UUID_RE = r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
FIGHT_SECONDS = 90


def run_line(rcon: Rcon) -> str:
    for line in rcon.command("cobbletowers runs list").splitlines():
        if re.search(UUID_RE, line):
            return line.strip()
    return ""


def wait_state(rcon: Rcon, state: str, floor: int, seconds: int = 60) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        line = run_line(rcon)
        if state in line and f"floor {floor}" in line:
            return True
        time.sleep(1)
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=None)
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--node-modules", type=Path, default=None)
    args = parser.parse_args()

    server_dir = args.server_dir.resolve()
    java = args.java or Path(os.environ.get("SMOKE_JAVA", "java"))
    node_modules = args.node_modules or (server_dir.parent / "bot" / "node_modules")
    if args.jar:
        install_jar(server_dir, args.jar.resolve())
    reset_tower_world(server_dir)

    results: list[Result] = []
    server = Server(server_dir, java)
    bot = None
    try:
        print(f"Booting server ({server.log})")
        server.start()
        server.wait_until_ready()
        env = dict(os.environ, NODE_PATH=str(node_modules))
        handle = open(server_dir / "logs" / "towers-phase-bot.log", "w", encoding="utf-8", errors="replace")
        bot = subprocess.Popen(["node", str(HERE / "battlebot.js"), BOT, str(server_port(server_dir))],
                               stdout=handle, stderr=subprocess.STDOUT, env=env)

        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            for _ in range(90):
                if BOT in rcon.command("list"):
                    break
                time.sleep(1)
            else:
                raise RuntimeError("the bot never joined")
            clear_tower(rcon)
            rcon.command(f"pokegiveother {BOT} mewtwo level=100")
            rcon.command(f"pokegiveother {BOT} glaceon level=100")

            def play(command: str) -> None:
                rcon.command(f"execute as {BOT} run cobbletowers play {command}")

            # The bot cannot out-damage Regieleki, so the boss starts every battle at half health: below the Overclock line (60%),
            # which makes the phase fire on the first action. Queued for the player's tower battles; floors 1-4 are skipped anyway.
            queued = rcon.command(f"cobbletowers runs fx {BOT} " + '[{"op":"hp","side":"foe","percent":50}]')
            if "Queued 1 of 1" not in queued:
                raise RuntimeError("could not queue the boss's half health: " + queued)
            play(f"tower {TOWER}")
            play("confirm")
            play("start")
            if not wait_state(rcon, "ENCOUNTER_ACTIVE", 1, seconds=40):
                raise RuntimeError("floor 1 never opened: " + run_line(rcon))
            run = re.search(UUID_RE, run_line(rcon)).group(1)

            for floor in range(1, 5):
                rcon.command(f"cobbletowers runs advance {run} encounter_resolved_cleared")
                rcon.command(f"cobbletowers runs advance {run} rewards_banked")
                if not wait_state(rcon, "INTERMISSION", floor, seconds=20):
                    raise RuntimeError(f"floor {floor} never reached its intermission: {run_line(rcon)}")
                play("pick 1")
                play("pick 1")
                play("ready")
                if not wait_state(rcon, "ENCOUNTER_ACTIVE", floor + 1, seconds=40):
                    raise RuntimeError(f"floor {floor + 1} never opened: {run_line(rcon)}")

            # Floor 5: the bot fights Regieleki for real. Wait for the first phase to fire (or the fight to end).
            deadline = time.time() + FIGHT_SECONDS
            log = ""
            while time.time() < deadline:
                log = server.read_log()
                if "Phase overclock fired" in log:
                    break
                time.sleep(3)

            # The battle that carries the phases is floor 5's: the first "carries" line after floor 5 starts its boss.
            at_five = log.find("Floor 5 of run")
            carried = re.search(r"Tower battle \S+ carries \d+ effect\(s\): (.*)", log[at_five:] if at_five >= 0 else "")
            results.append(Result("floor 5's boss started", "boss cobbleraids:regieleki started" in log, "no Regieleki start line"))
            results.append(Result("its battle carried the phases to Showdown",
                                  bool(carried) and '"op":"phase"' in carried.group(1) and '"id":"overclock"' in carried.group(1),
                                  carried.group(1)[:200] if carried else "no 'Tower battle ... carries' line"))
            results.append(Result("the phase was resolved to the boss's side, not left as 'foe'",
                                  bool(carried) and '"side"' not in carried.group(1).split('"do"')[0] and '"sides":["p2"]' in carried.group(1),
                                  carried.group(1)[:200] if carried else ""))
            results.append(Result("the extension applied the phase op", bool(re.search(r"Applied [1-9]\d* of \d+ tower effect", log)), "no 'Applied N of M' line"))
            results.append(Result("the Overclock phase fired as the boss was damaged", "[CobbleTowers] Phase overclock fired" in log,
                                  "no 'Phase overclock fired' line within %ds" % FIGHT_SECONDS))
            results.append(Result("no CobbleTowers exception during any of it", "\tat com.cobbletowers" not in log, "a CobbleTowers stack frame is in the log"))
    except Exception as exc:  # noqa: BLE001
        results.append(Result("boss phase run", False, repr(exc)))
    finally:
        if bot is not None:
            bot.kill()
        print("Stopping server")
        server.stop()

    print()
    width = max(len(result.name) for result in results) if results else 0
    failed = sum(1 for result in results if not result.passed)
    for result in results:
        print(f"  [{'PASS' if result.passed else 'FAIL'}] {result.name:<{width}}  "
              f"{result.detail if not result.passed else ''}")
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
