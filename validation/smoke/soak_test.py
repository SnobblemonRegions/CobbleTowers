#!/usr/bin/env python3
"""A first real soak reading: many allocate/release cycles in a row, watching for anything that does
not come back down.

Every other live test in this suite runs one or two runs total. None of them repeats the cycle enough
times to notice a leak that only shows up on the hundredth allocation, not the first -- and TDS
section 11's own tracked counts (active runs, tower chunks, tower entities) are exactly what a leak
would move. This test's only job is repetition: create a run, walk it to ALLOCATING_INSTANCE for real,
then abandon it (the fastest path to a terminal state from anywhere, releasing its cell), over and
over, reading `/cobbletowers diagnostics` between cycles.

No real battle is fought. Real opponent spawn-and-cleanup is already proven by
`floor_encounter_test.py` and `participant_test.py`; what neither of them ever does is repeat the cycle
enough times for an accumulation to become visible, and that repetition is the whole reason this script
exists.

This is a SHORT soak (a few minutes by default) for a first real number, not the multi-hour or
overnight run TDS's own "long-running-server soak tests" phrase implies -- `--minutes` is there so a
future session can point this at hours unattended.

    python validation/smoke/soak_test.py \\
      --server-dir <rig>/testserver \\
      --java "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.1+1/bin/java.exe" \\
      --jar build/libs/CobbleTowers-<version>.jar \\
      --minutes 3
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
    Result, Server, install_jar, reset_tower_world, read_password, run_id_from, server_port, start_bot,
    wait_online, BOT,
)
from floor_encounter_test import TOWER  # noqa: E402

BOOT_MARKER_LOOKUP = re.compile(r"(\d+) active run\(s\), (\d+) active encounter\(s\), (\d+) tower chunk\(s\),"
                                r" (\d+) tower entities")


def overview_counts(overview: str) -> tuple[int, int, int, int]:
    found = BOOT_MARKER_LOOKUP.search(overview)
    if not found:
        raise RuntimeError("diagnostics overview did not match the expected shape: " + overview.strip()[:200])
    return tuple(int(group) for group in found.groups())


def one_cycle(rcon: Rcon) -> None:
    """Create, allocate for real, then abandon -- the fastest path from CREATED to a terminal state
    that still exercises a real cell lease and a real release."""
    run = run_id_from(rcon.command(f"cobbletowers runs create {TOWER} {BOT}"))
    rcon.command(f"cobbletowers runs advance {run} party_submitted")
    rcon.command(f"cobbletowers runs advance {run} party_validated")
    rcon.command(f"cobbletowers runs allocate {run}")
    rcon.command(f"cobbletowers runs advance {run} abandon_requested")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=None)
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--minutes", type=float, default=3.0,
                        help="how long to run the cycle for (default: 3, a first reading, not a real soak)")
    parser.add_argument("--pause", type=float, default=2.0,
                        help="seconds to rest between cycles (default 2). A cell is the Battle Tower now, about 325,000 blocks across 49 "
                             "chunks, allocated and cleared synchronously on the server thread: back-to-back cycles (hundreds a minute, "
                             "which the old small arena allowed) back the chunk saves up until the watchdog stops the server. "
                             "That is a throughput limit, not a leak, and this test is for leaks.")
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
        print(f"Booting server ({server.log})")
        server.start()
        server.wait_until_ready()
        bot_log = server_dir / "logs" / "towers-soak-bot.log"
        bot = start_bot(server_port(server_dir), node_modules, bot_log)

        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            if not wait_online(rcon):
                raise RuntimeError(f"{BOT} never joined; see {bot_log}")

            baseline = overview_counts(rcon.command("cobbletowers diagnostics"))
            # Since the start-up pre-warm (2026-11-12) the warm pool builds cells as the server starts: their chunks are held on purpose.
            # Allowed: every warm cell's 49 chunks, plus one cell still being built.
            warm = re.search(r"(\d+) warm", rcon.command("cobbletowers cells list"))
            allowed_chunks = ((int(warm.group(1)) if warm else 0) + 1) * 49
            results.append(Result("the baseline starts with nothing active (warm pool cells aside)",
                                  baseline[0] == 0 and baseline[2] <= allowed_chunks, f"{baseline}, allowed {allowed_chunks} chunks"))

            deadline = time.time() + args.minutes * 60
            cycles = 0
            max_seen = list(baseline)
            while time.time() < deadline:
                one_cycle(rcon)
                cycles += 1
                time.sleep(args.pause)
                counts = overview_counts(rcon.command("cobbletowers diagnostics"))
                max_seen = [max(a, b) for a, b in zip(max_seen, counts)]
                if cycles % 20 == 0:
                    print(f"  {cycles} cycle(s): active runs {counts[0]}, tower chunks {counts[2]},"
                          f" tower entities {counts[3]}")

            print(f"  completed {cycles} cycle(s) in {args.minutes} minute(s)")
            results.append(Result("at least a few dozen cycles completed in the window",
                                  cycles >= 20, f"only {cycles} cycle(s)"))

            # Releases are spread out (one cell clear per ~1.2 s, docs/design/cell-allocation-async.md), so after a burst of cycles the
            # backlog drains over the next seconds: wait for it rather than reading the count the instant the burst stops.
            drain_deadline = time.time() + 120
            final = overview_counts(rcon.command("cobbletowers diagnostics"))
            while final[2] > baseline[2] + 2 * 49 and time.time() < drain_deadline:
                time.sleep(2)
                final = overview_counts(rcon.command("cobbletowers diagnostics"))
            print(f"  chunks after the drain wait: {final[2]} (peak {max_seen[2]})")
            # The warm pool keeps TARGET_PER_STRUCTURE (2) cells built and loaded on purpose, so "back to baseline" means back to the baseline
            # plus the pool's own holdings (2 x 49 chunks), a figure that must not grow with the number of cycles.
            pool_chunks = 2 * 49
            results.append(Result("tower chunks returned to baseline plus the warm pool's own cells after the last release",
                                  baseline[2] <= final[2] <= baseline[2] + pool_chunks,
                                  f"baseline {baseline[2]}, final {final[2]}, warm pool holds up to {pool_chunks}"))
            # Active runs is not asserted back to baseline: each cycle's run reaches ABANDONED, a
            # terminal state, and TowerRuns' own retention keeps terminal runs indexed for their
            # history (the same reasoning that keeps a completed run's ledger readable afterward) --
            # DiagnosticsCommand already excludes them from "active", so this count should stay at
            # baseline throughout, not just at the end.
            results.append(Result("active runs never rose above the baseline mid-soak",
                                  max_seen[0] == baseline[0], f"baseline {baseline[0]}, peak {max_seen[0]}"))
            # Found live: a transient two-cell overlap (98 = 2 * 49 chunks/cell) shows up under fast
            # cycling, immediately followed by a reading back at 0 -- the warm pool evidently prepares
            # a fresh cell before the old one is fully released rather than serialising the two. That
            # is a transient overlap, not a leak; the real leak indicator is the final reading above,
            # not a peak. Three cells' worth of slack (still comfortably bounded) covers the overlap
            # without this check being unable to tell the two apart.
            # The tight bound is for the ordinary pace. Back to back (pause < 2) the operator command allocates faster than releases are
            # admitted, so cells wait for their release: the peak is the backlog, which must drain (the final reading above), not stay small.
            results.append(Result("tower chunks never rose past a bounded transient overlap",
                                  args.pause < 2 or max_seen[2] <= baseline[2] + 3 * 49,
                                  f"baseline {baseline[2]}, peak {max_seen[2]}"))

            overview = rcon.command("cobbletowers diagnostics")
            print("  final diagnostics:")
            for line in overview.split("  "):
                if line.strip():
                    print("    " + line.strip())

            results.append(Result("no CobbleTowers exception during any of it",
                                  "com.cobbletowers" not in server.read_log().replace("com.cobbletowers.CobbleTowers", ""),
                                  "see " + str(server.log)))

    finally:
        if bot:
            bot.terminate()
        server.stop()

    passed = sum(1 for r in results if r.passed)
    print()
    for r in results:
        status = "PASS" if r.passed else "FAIL"
        print(f"  [{status}] {r.name:<65} {r.detail if not r.passed else ''}")
    print(f"\n{passed}/{len(results)} checks passed")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
