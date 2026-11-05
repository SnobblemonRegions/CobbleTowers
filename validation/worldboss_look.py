#!/usr/bin/env python3
"""Real-client pictures of the World Boss in the graveyard under the Spooky Surge tiers (rig only).

Enables the World Boss on the rig with a one-minute auto-spawn interval (config restored afterwards), pastes the graveyard
(datapack structure gy:graveyard) high in the overworld, scans the arena, starts a surge, steps its tiers 1 to 4 and waits for
the boss to spawn by itself. Pictures: the plaza, the boss, and the surge bar at each tier.

    python validation/worldboss_look.py --server-dir <rig>/testserver-181 --java <jdk21 java> --out <dir> [--origin 2560,0] [--wait 240]

The rig needs the gy datapack, CobbleRaids, SpookySurge and a free port 25565. Keep hands off the keyboard and mouse while it runs.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "smoke"))

import pedestal_shots as ps  # noqa: E402
from rcon import Rcon  # noqa: E402
from run_durability_test import Server, read_password, server_port  # noqa: E402

BASE_Y = 150   # the build floats over the terrain so nothing of the rig world clips into it
SPAWN = (64, 13, 90)

# name, viewer (x, y, z), look-at (x, y, z), schematic coordinates
VIEWS = [
    ("plaza_from_gate", (64, 18, 118), (64, 20, 70)),
    ("boss_close", (64, 20, 112), (64, 24, 90)),
    ("church_behind_boss", (40, 24, 112), (64, 20, 70)),
    ("aerial", (64, 70, 140), (64, 15, 90)),
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--origin", default="2560,0")
    parser.add_argument("--wait", type=int, default=240, help="seconds to wait for the automatic spawn")
    args = parser.parse_args()
    ox, oy = (int(v) for v in args.origin.split(","))
    rig = args.server_dir.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    cfg = rig / "config" / "cobbleraids" / "world_boss.json"
    backup = cfg.with_suffix(".json.audit-backup")
    shutil.copy2(cfg, backup)
    conf = json.loads(cfg.read_text(encoding="utf-8"))
    conf["enabled"] = True
    conf["spawn"]["interval_minutes"] = 1
    conf["spawn"]["auto_start"] = True
    conf["window"] = {"start": "2026-10-31T04:00:00Z", "end": "2026-11-09T05:00:00Z"}   # the shipped Halloween window; without one nothing starts by itself
    cfg.write_text(json.dumps(conf, indent=2), encoding="utf-8")

    launch = ps.load_launcher()
    game = rig.parent / "clientrig-pedestal"
    game.mkdir(exist_ok=True)
    server = Server(rig, args.java)
    client = None
    shots: list[Path] = []

    def ps1(script: str, *extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / script),
                               "-OriginX", str(ox), "-OriginY", str(oy), *extra], check=False, capture_output=True, text=True)

    def photo(name: str) -> None:
        shot = out / f"{name}.png"
        done = ps1("capture_window.ps1", "-Out", str(shot), "-ProcessId", str(client.pid))
        print("shot", shot.name, shot.exists(), (done.stderr or done.stdout).strip()[:120], flush=True)
        if shot.exists():
            shots.append(shot)

    def say(rcon: Rcon, command: str) -> str:
        reply = ascii(rcon.command(command))
        print(">", command, "->", reply[:300], flush=True)
        return reply

    try:
        print("Booting server", flush=True)
        server.start()
        server.wait_until_ready()
        pack = rig / "polymer" / "resource_pack.zip"
        ps.prepare_client(launch, game, rig, pack)
        port = server_port(rig)
        client = subprocess.Popen(launch.command(args.java, game, ["--quickPlayMultiplayer", f"127.0.0.1:{port}"], rig),
                                  cwd=game, stdout=open(game / "client_output.log", "w", encoding="utf-8", errors="replace"),
                                  stderr=subprocess.STDOUT)
        with Rcon("127.0.0.1", 25575, read_password(rig)) as rcon:
            clicked = False
            for _ in range(150):
                if ps.VIEWER in rcon.command("list"):
                    break
                latest = game / "logs" / "latest.log"
                if not clicked and latest.exists() and "Connecting to" in latest.read_text(encoding="utf-8", errors="replace"):
                    time.sleep(6)
                    before = len(shots)
                    photo("0_resource_pack_prompt")
                    if len(shots) > before:
                        ps1("click_window.ps1", "-X", str(ox + 488), "-Y", str(oy + 387))
                        clicked = True
                time.sleep(1)
            else:
                sys.exit("the client never joined; see " + str(game / "logs" / "latest.log"))
            time.sleep(8)
            for rule in ("gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "time set 18000",
                         "forceload add 0 0 127 127", f"gamemode creative {ps.VIEWER}", f"tp {ps.VIEWER} 64 {BASE_Y + 30} 126"):
                rcon.command(rule)
            time.sleep(4)
            say(rcon, f"place template gy:graveyard 0 {BASE_Y} 0")
            time.sleep(12)
            say(rcon, f"execute as {ps.VIEWER} at {ps.VIEWER} run cobbleraids worldboss arena scan 96")
            say(rcon, "cobbleraids worldboss arena list")
            say(rcon, "cobbleraids worldboss status")

            def go(name: str, eye, target) -> None:
                x, y, z = eye
                dx, dy, dz = target[0] - x, target[1] - y, target[2] - z
                yaw = math.degrees(math.atan2(-dx, dz))
                pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
                rcon.command(f"tp {ps.VIEWER} {x + 0.5:.1f} {y + BASE_Y:.1f} {z + 0.5:.1f} {yaw:.1f} {pitch:.1f}")
                time.sleep(3.5)
                photo(name)

            go("00_plaza_before_boss", *VIEWS[0][1:])
            say(rcon, "surge stop")
            time.sleep(1)
            say(rcon, "surge start 0")
            time.sleep(3)
            say(rcon, "surge status")
            for tier in (1, 2, 3, 4):
                say(rcon, f"surge tier {tier}")
                time.sleep(2)
                say(rcon, "surge status")
                go(f"tier{tier}_plaza", *VIEWS[0][1:])
            say(rcon, "surge status")

            spawned = False
            deadline = time.time() + args.wait
            while time.time() < deadline:
                status = say(rcon, "cobbleraids worldboss status")
                if "No World Boss is active" not in status:
                    spawned = True
                    time.sleep(8)   # let the boss settle and the duel zone draw
                    break
                time.sleep(10)
            print("auto spawn seen:", spawned, flush=True)
            for label, eye, target in VIEWS:
                go(f"boss_{label}", eye, target)
            say(rcon, "cobbleraids worldboss status")
            say(rcon, "surge status")
            time.sleep(2)
            rcon.command("stop")
    finally:
        if client:
            client.kill()
        try:
            server.process.wait(timeout=60)
        except Exception:  # noqa: BLE001
            server.process.kill()
        shutil.copy2(backup, cfg)
        backup.unlink()
        shutil.copy2(rig / "logs" / "towers-durability.log", out / "server.log")
    print("\n".join(str(s) for s in shots))


if __name__ == "__main__":
    main()
