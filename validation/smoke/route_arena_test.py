#!/usr/bin/env python3
"""P50 stage 2 live: a detour in the route fights a floor in another region's building, and the party comes back.

Plays the real path (play tower / confirm / start, a draft pick, ready, the countdown) with operator commands only to clear floors, as
milestone_floor_test.py does. A detour is one lane in about a third of the steps, so the test plays runs until it meets one. It proves:

1. A route step can offer one stop whose battle is in another tower's building (the stop shows `@<tower>`).
2. Taking it builds that building for the next floor (a cell is prepared with that tower's structure, or the run moves into one) and `runs show` says so.
3. The floor after is back in the run's own building (prepared with its structure again, or moved into a ready one).

Since sliced floor builds (2026-11-12), a floor in another building is built in a cell of its own and the run moves into it; a ready cell from the
warm pool prints no "prepared with" line at that moment, only "moved from cell A to cell B for <structure>", so either line counts.
4. Nothing throws.

    python validation/smoke/route_arena_test.py --server-dir <rig>/testserver-181 --java <jdk21 java> [--jar build/libs/CobbleTowers-<version>-dev.jar]
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

BOT = f"TRa{int(time.time()) % 100000}"
TOWER = "cobbletowers:neutral"
HOME = "neutral"
UUID_RE = r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"


def run_line(rcon: Rcon) -> str:
    """The first listed run that is still going; an abandoned run stays in the list."""
    for line in rcon.command("cobbletowers runs list").splitlines():
        if re.search(UUID_RE, line) and not re.search(r"ABANDONED|CASHED_OUT|COMPLETED|FAILED|WIPED", line):
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


def stops(shown: str) -> list[tuple[int, str, str]]:
    """(lane, kind, arena) of each card of an open route draft; arena is a tower id or empty."""
    found = re.search(r"\(ROUTE ([^)]*)\)", shown)
    if not found:
        return []
    return [(int(lane), kind, arena) for lane, kind, arena in re.findall(r"(\d)=([A-Z]+)(?:@(\S+))?", found.group(1))]


def built_lines(log: str, place: str) -> int:
    """How often a cell was built for, or a run moved into, that tower's building."""
    moved = len(re.findall(rf"moved from cell \d+ to cell \d+ for cobbletowers:battle_tower_{place}", log))
    return log.count(f"prepared with cobbletowers:battle_tower_{place}") + moved


def built_in(shown: str, floor: int) -> str:
    found = re.search(rf"floor {floor} (?:is|will be) built in: (\S+) \((\S+)\)", shown)
    return found.group(1) if found else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=None)
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--node-modules", type=Path, default=None)
    parser.add_argument("--attempts", type=int, default=10)
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
        handle = open(server_dir / "logs" / "towers-route-arena-bot.log", "w", encoding="utf-8", errors="replace")
        bot = subprocess.Popen(["node", str(HERE / "joinbot.js"), BOT, str(server_port(server_dir))], stdout=handle, stderr=subprocess.STDOUT, env=env)

        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            for _ in range(90):
                if BOT in rcon.command("list"):
                    break
                time.sleep(1)
            else:
                raise RuntimeError("the bot never joined")
            clear_tower(rcon)
            rcon.command(f"pokegiveother {BOT} glaceon level=100")

            def play(command: str) -> None:
                rcon.command(f"execute as {BOT} run cobbletowers play {command}")

            met = False
            for attempt in range(1, args.attempts + 1):
                play(f"tower {TOWER}")
                play("confirm")
                play("start")
                if not wait_state(rcon, "ENCOUNTER_ACTIVE", 1, seconds=40):
                    raise RuntimeError("floor 1 never opened: " + run_line(rcon))
                run = re.search(UUID_RE, run_line(rcon)).group(1)
                print(f"attempt {attempt}: run {run}")
                floor = 1
                detour_floor = 0
                while floor <= 3:
                    rcon.command(f"cobbletowers runs advance {run} encounter_resolved_cleared")
                    rcon.command(f"cobbletowers runs advance {run} rewards_banked")
                    if not wait_state(rcon, "INTERMISSION", floor, seconds=20):
                        raise RuntimeError(f"floor {floor} never reached its intermission: {run_line(rcon)}")
                    time.sleep(0.8)
                    shown = rcon.command(f"cobbletowers runs show {run}")
                    offered = stops(shown)
                    away = [i for i, (_, _, arena) in enumerate(offered) if arena]
                    if floor == 1 or away:
                        results.append(Result(f"floor {floor}: at most one stop is a detour", len(away) <= 1, str(offered)))
                    card = (away[0] if away else 0) + 1
                    log_before = server.read_log()
                    play(f"pick {card}")
                    time.sleep(0.8)
                    play("pick 1")   # a room may have opened behind the draft; an unneeded pick is refused harmlessly
                    play("pick 1")
                    after = rcon.command(f"cobbletowers runs show {run}")
                    if away:
                        place = offered[away[0]][2].split(":")[-1]
                        results.append(Result(f"floor {floor}: taking the detour says the next floor is built in {place}", built_in(after, floor + 1) == offered[away[0]][2],
                                              built_in(after, floor + 1) + " / " + after.strip()[-240:]))
                        play("ready")
                        opened = wait_state(rcon, "ENCOUNTER_ACTIVE", floor + 1, seconds=60)
                        log = server.read_log()
                        results.append(Result(f"floor {floor + 1} opens after the detour", opened, run_line(rcon)))
                        results.append(Result(f"the cell was prepared with {place}'s building for it",
                                              built_lines(log, place) > built_lines(log_before, place),
                                              f"no 'prepared with battle_tower_{place}' line after the pick"))
                        shown_there = rcon.command(f"cobbletowers runs show {run}")
                        results.append(Result("and the run reports the building it stands in", built_in(shown_there, floor + 1) == offered[away[0]][2], built_in(shown_there, floor + 1)))
                        # the way back: clear that floor, take any stop that is not another detour, and the home building is put up again
                        rcon.command(f"cobbletowers runs advance {run} encounter_resolved_cleared")
                        rcon.command(f"cobbletowers runs advance {run} rewards_banked")
                        results.append(Result(f"floor {floor + 1} reaches its intermission", wait_state(rcon, "INTERMISSION", floor + 1, seconds=20), run_line(rcon)))
                        time.sleep(0.8)
                        shown2 = rcon.command(f"cobbletowers runs show {run}")
                        back = stops(shown2)
                        home_cards = [i for i, (_, _, arena) in enumerate(back) if not arena]
                        log_before2 = server.read_log()
                        play(f"pick {(home_cards[0] if home_cards else 0) + 1}")
                        time.sleep(0.8)
                        play("pick 1")
                        play("pick 1")
                        play("ready")
                        results.append(Result(f"floor {floor + 2} opens", wait_state(rcon, "ENCOUNTER_ACTIVE", floor + 2, seconds=60), run_line(rcon)))
                        log2 = server.read_log()
                        results.append(Result("the party is back in the run's own building: the cell is prepared with it again",
                                              built_lines(log2, HOME) > built_lines(log_before2, HOME),
                                              f"no 'prepared with battle_tower_{HOME}' line after the detour"))
                        results.append(Result("and the run reports it", built_in(rcon.command(f'cobbletowers runs show {run}'), floor + 2) == f"cobbletowers:{HOME}", ""))
                        met = True
                        break
                    play("ready")
                    if not wait_state(rcon, "ENCOUNTER_ACTIVE", floor + 1, seconds=60):
                        raise RuntimeError(f"floor {floor + 1} never opened: {run_line(rcon)}")
                    floor += 1
                if met:
                    break
                rcon.command(f"cobbletowers runs advance {run} abandon_requested")
                time.sleep(2)
            results.append(Result(f"a detour appeared within {args.attempts} runs", met, "none met"))
            log = server.read_log()
            results.append(Result("no CobbleTowers exception during any of it", "\tat com.cobbletowers" not in log, "a CobbleTowers stack frame is in the log"))
    except Exception as exc:  # noqa: BLE001
        results.append(Result("route arena run", False, repr(exc)))
    finally:
        if bot is not None:
            bot.kill()
        print("Stopping server")
        server.stop()

    print()
    width = max(len(result.name) for result in results) if results else 0
    failed = sum(1 for result in results if not result.passed)
    for result in results:
        print(f"  [{'PASS' if result.passed else 'FAIL'}] {result.name:<{width}}  {result.detail if not result.passed else ''}")
    print(f"\nroute_arena_test: {len(results) - failed}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
