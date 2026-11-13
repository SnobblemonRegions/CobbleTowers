"""A run start with no ready cell builds its arena a slice per tick, and a hard kill in the middle of that build is safe.

Since 2026-11-12 (docs/design/cell-allocation-async.md, "Run start and floor change, a slice per tick") a lobby start with no warm cell
leases a cell, builds it a slice per tick while the run waits at ALLOCATING_INSTANCE, and finishes the launch when it is up. The server is
booted with -Dcobbletowers.prewarm=off so the pool is empty and that path is taken for certain. What this proves live:

  * the sliced start really happens (the "a slice at a time" and "prepared with" lines) and the run reaches ENCOUNTER_ACTIVE on floor 1;
  * no tick is held for the whole paste: the build's longest step is logged and must be well under the old 0.3 to 1 s paste;
  * a hard kill while a second start's arena is half built leaves the run parked (RECOVERY_REQUIRED), no cell quarantined, no
    CobbleTowers stack frame in the log, and a new run can start afterwards.

    python validation/smoke/sliced_build_test.py --server-dir <rig> --java <jdk21 java> --jar <dev build> --node-modules <mineflayer>
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
from lobby_test import as_player, give_party, runs, start_bot, wait_for, wait_online, UUID_RE  # noqa: E402

STAMP = int(time.time()) % 100000
HOST = f"TSb{STAMP}"
FIRST_TOWER = "cobbletowers:neutral"
SECOND_TOWER = "cobbletowers:rootvale"   # a different building, so the first run's warm-pool wish cannot serve it
LONGEST_STEP_LIMIT_MS = 250


def slice_lines(log: str) -> list[str]:
    return re.findall(r"Cell \d+ is being built for \S+ a slice at a time", log)


def longest_steps(log: str) -> list[int]:
    return [int(ms) for ms in re.findall(r"prepared with \S+ in \d+ ms of slices \(longest (\d+) ms\)", log)]


def boot(server: Server, prewarm_off: bool) -> None:
    os.environ["SMOKE_JAVA_OPTS"] = "-Dcobbletowers.prewarm=off" if prewarm_off else ""
    server.start()
    server.wait_until_ready()


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
    bot: subprocess.Popen | None = None
    try:
        # --- 1. a sliced start, end to end -------------------------------------------------------------
        print(f"Booting server with pre-warm off ({server.log})")
        boot(server, prewarm_off=True)
        port = server_port(server_dir)
        bot = start_bot(HOST, port, node_modules, server_dir / "logs" / f"towers-sliced-{HOST}.log")
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            if not wait_online(rcon, HOST):
                raise RuntimeError(f"{HOST} never joined")
            clear_tower(rcon)
            give_party(rcon, HOST)
            before = server.read_log()
            as_player(rcon, HOST, f"tower {FIRST_TOWER}")
            as_player(rcon, HOST, "confirm")
            as_player(rcon, HOST, "start")
            started = wait_for(lambda: any("ENCOUNTER_ACTIVE" in line for line in runs(rcon)), seconds=60)
            log = server.read_log()[len(before):]
            results.append(Result("with no ready cell the arena is built a slice at a time", bool(slice_lines(log)), "no 'a slice at a time' line"))
            results.append(Result("and the run reaches ENCOUNTER_ACTIVE on floor 1", started, "; ".join(runs(rcon))))
            steps = longest_steps(log)
            results.append(Result(f"no build step held a tick for more than {LONGEST_STEP_LIMIT_MS} ms",
                                  bool(steps) and max(steps) <= LONGEST_STEP_LIMIT_MS, f"longest steps {steps}"))
            for line in runs(rcon):
                found = re.search(UUID_RE, line)
                if found:
                    rcon.command(f"cobbletowers runs advance {found.group(1)} abandon_requested")
            clear_tower(rcon)
            time.sleep(3)

            # --- 2. a hard kill in the middle of the next start's build ------------------------------------
            before = server.read_log()
            as_player(rcon, HOST, f"tower {SECOND_TOWER}")
            as_player(rcon, HOST, "confirm")
            as_player(rcon, HOST, "start")
            building = wait_for(lambda: bool(slice_lines(server.read_log()[len(before):])), seconds=60)
            results.append(Result("the second start builds its arena in slices too", building, "no 'a slice at a time' line"))
        print("Killing the server mid-build")
        if bot:
            bot.kill()
            bot = None
        if server.process:
            server.process.kill()
            server.process.wait(timeout=60)
        killed_log = server.read_log()[len(before):]
        results.append(Result("the kill came while the build was under way", bool(slice_lines(killed_log)) and not longest_steps(killed_log),
                              "no build had started, or it had already finished, when the kill landed; the timing proved nothing"))

        # --- 3. after the restart -----------------------------------------------------------------------
        print("Restarting")
        boot(server, prewarm_off=False)
        bot = start_bot(HOST, port, node_modules, server_dir / "logs" / f"towers-sliced-{HOST}-2.log")
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            parked = [line for line in runs(rcon) if "RECOVERY_REQUIRED" in line]
            results.append(Result("the interrupted run is parked for recovery", len(parked) >= 1, "; ".join(runs(rcon))))
            cells = rcon.command("cobbletowers cells list")
            quarantined = re.search(r"(\d+) quarantined", cells)
            results.append(Result("no cell was quarantined by the half-built arena", quarantined is not None and quarantined.group(1) == "0", cells.strip()[:240]))
            if not wait_online(rcon, HOST):
                raise RuntimeError(f"{HOST} never rejoined")
            for line in parked:
                found = re.search(UUID_RE, line)
                if found:
                    rcon.command(f"cobbletowers runs advance {found.group(1)} recovery_abandoned")
            clear_tower(rcon)
            give_party(rcon, HOST)
            as_player(rcon, HOST, f"tower {SECOND_TOWER}")
            as_player(rcon, HOST, "confirm")
            as_player(rcon, HOST, "start")
            again = wait_for(lambda: any("ENCOUNTER_ACTIVE" in line for line in runs(rcon)), seconds=60)
            results.append(Result("a new run starts after the restart", again, "; ".join(runs(rcon))))
            for line in runs(rcon):
                found = re.search(UUID_RE, line)
                if found and "ENCOUNTER_ACTIVE" in line:
                    rcon.command(f"cobbletowers runs advance {found.group(1)} abandon_requested")
        whole = server.read_log()
        results.append(Result("no CobbleTowers exception after the restart", "\tat com.cobbletowers" not in whole, "a CobbleTowers stack frame is in the log"))
    except Exception as exc:  # noqa: BLE001
        results.append(Result("sliced build run", False, repr(exc)))
    finally:
        if bot:
            bot.kill()
        print("Stopping server")
        server.stop()
        os.environ.pop("SMOKE_JAVA_OPTS", None)

    print()
    width = max(len(result.name) for result in results) if results else 0
    failed = sum(1 for result in results if not result.passed)
    for result in results:
        print(f"  [{'PASS' if result.passed else 'FAIL'}] {result.name:<{width}}  {result.detail if not result.passed else ''}")
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
