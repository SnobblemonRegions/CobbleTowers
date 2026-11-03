#!/usr/bin/env python3
"""Real-client pictures of the Halloween tower: arena, each intermission room, the vault and its vote.

Boots the rig server, joins a vanilla-form client as the only player of a Halloween run and, floor by floor, forces one
room, photographs it from the player's feet at four headings and from the pedestals, then moves on.

    python validation/halloween_look.py --server-dir <rig>/testserver-181 --java <jdk21 java> --out <dir> --jar <dev jar> [--origin 2560,0]

The window is moved to --origin (the top-left of the monitor to use) and only that window is photographed.
Needs the dev jar (forced rooms), a display and a GPU. The server must be stopped.
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
ROOT_DIR = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "smoke"))

import pedestal_shots as ps  # noqa: E402
from rcon import Rcon  # noqa: E402
from run_durability_test import Server, install_jar, reset_tower_world, read_password, server_port  # noqa: E402
from floor_encounter_test import give_party  # noqa: E402
from intermission_test import play, run_line, wait_state, clear_floor, UUID_RE  # noqa: E402

ROOMS = ["SHRINE", "GAMBLER", "REST", "FORGE", "LIBRARY", "VAULT", "POOL", "TRICK_OR_TREAT"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("SMOKE_JAVA", "java")))
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--origin", default="2560,0")
    parser.add_argument("--rooms", default=",".join(ROOMS))
    args = parser.parse_args()
    ox, oy = (int(v) for v in args.origin.split(","))

    rig = args.server_dir.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    os.environ["SMOKE_JAVA_OPTS"] = (os.environ.get("SMOKE_JAVA_OPTS", "") + " -Dcobbletowers.ignoreWindows=true").strip()
    if args.jar:
        install_jar(rig, args.jar.resolve())
    reset_tower_world(rig)

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
        print("shot", shot.name, shot.exists(), (done.stderr or done.stdout).strip()[:120])
        if shot.exists():
            shots.append(shot)

    try:
        print("Booting server")
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
                photo("0_stuck_client")
                sys.exit("the client never joined; see " + str(game / "logs" / "latest.log"))
            time.sleep(8)
            rcon.command("time set noon")
            rcon.command(f"gamemode survival {ps.VIEWER}")
            give_party(rcon, ps.VIEWER)
            rcon.command(f"give {ps.VIEWER} cobbletowers:tower_key 3")
            rcon.command(f"cobbletowers runs vendor credit {ps.VIEWER} 5000")
            play(rcon, ps.VIEWER, f"tower {ps.TOWER}")
            play(rcon, ps.VIEWER, "confirm")
            play(rcon, ps.VIEWER, "start")
            if not wait_state(rcon, "ENCOUNTER_ACTIVE", seconds=60, floor=1):
                sys.exit("floor 1 never opened: " + run_line(rcon))
            run = re.search(UUID_RE, run_line(rcon)).group(1)

            def pos() -> list[float]:
                return ps.nums(rcon.command(f"{ps.IN_TOWER}data get entity {ps.VIEWER} Pos"))

            def look(x: float, y: float, z: float, yaw: float, pitch: float) -> None:
                rcon.command(f"{ps.IN_TOWER}tp {ps.VIEWER} {x:.2f} {y:.2f} {z:.2f} {yaw:.1f} {pitch:.1f}")

            def around(tag: str) -> None:
                """Four headings from where the player stands, then one up at the ceiling."""
                x, y, z = pos()
                for n, yaw in enumerate((0, 90, 180, 270)):
                    look(x, y, z, yaw, 4)
                    time.sleep(2.5)
                    photo(f"{tag}_h{n}")
                look(x, y, z, 0, -50)
                time.sleep(2.5)
                photo(f"{tag}_up")
                look(x, y, z, 0, 4)

            import json
            import math
            floors_dir = ROOT_DIR / "src/main/resources/data/cobbletowers/cobbletowers/floors/halloween"

            def layout(floor: int) -> dict:
                return json.loads((floors_dir / f"floor_{floor:02d}.json").read_text(encoding="utf-8"))["layout"]

            def centre(box: dict) -> tuple[float, float, float]:
                lo, hi = box["min"], box["max"]
                return ((lo[0] + hi[0] + 1) / 2, lo[1], (lo[2] + hi[2] + 1) / 2)

            def yaw_to(fx: float, fz: float, tx: float, tz: float) -> float:
                return math.degrees(math.atan2(-(tx - fx), tz - fz))

            time.sleep(6)
            for _ in range(30):   # the arrival teleport can land after ENCOUNTER_ACTIVE; the lobby platform is at y 200
                me = pos()
                if me[1] < 150:
                    break
                time.sleep(2)
            else:
                for q in ("Dimension", "playerGameType", "Health", "OnGround"):
                    print("diag", q, ascii(rcon.command(f"data get entity {ps.VIEWER} {q}")))
                print("diag runs", ascii(rcon.command("cobbletowers runs list")))
                print("diag battle", ascii(rcon.command("cobbletowers runs encounter")))
                print("HOLDING for live diagnosis", flush=True)
                time.sleep(float(os.environ.get("HOLD_SECONDS", "0")))
                sys.exit(f"the player never reached the arena, still at {me}: " + run_line(rcon))
            me = pos()
            first = layout(1)["entry"]
            off = (me[0] - first["x"], me[1] - first["y"], me[2] - first["z"])
            print("arena", me, "offset", off)

            def at(x: float, y: float, z: float) -> tuple[float, float, float]:
                return (x + off[0], y + off[1], z + off[2])

            def shoot(tag: str, spot: tuple[float, float, float], toward: tuple[float, float], pitch: float = 4) -> None:
                x, y, z = at(*spot)
                tx, tz = toward[0] + off[0], toward[1] + off[2]
                look(x, y, z, yaw_to(x, z, tx, tz), pitch)
                time.sleep(2.2)
                photo(tag)

            def tour(floor: int, lay: dict, label: str) -> None:
                """Hall from the vendor, then each chamber behind a door: from inside, back at the door, and both sides."""
                ex = lay["exit"]
                ped = lay["doors"][1]["pedestal"]
                shoot(f"{floor:02d}_{label}_room_a", (ex["x"], ex["y"], ex["z"]), (ped["x"], ped["z"]))
                shoot(f"{floor:02d}_{label}_room_b", (ped["x"], ped["y"], ped["z"] + 1), (ex["x"], ex["z"]))
                for n, door in enumerate(lay["doors"]):
                    cx, cy, cz = centre(door["open"])
                    p = door["pedestal"]
                    sign = 1 if cz < p["z"] else -1            # the chamber lies on the far side of the door from its pedestal
                    inside = (cx, cy, cz - sign * 5)
                    shoot(f"{floor:02d}_{label}_door{n}_in", (cx, cy, cz - sign * 1.5), (cx, cz - sign * 12))
                    shoot(f"{floor:02d}_{label}_door{n}_mid", inside, (cx, cz - sign * 14))
                    shoot(f"{floor:02d}_{label}_door{n}_back", (cx, cy, cz - sign * 11), (cx, cz))
                    shoot(f"{floor:02d}_{label}_door{n}_left", inside, (cx - 6, cz - sign * 5))
                    shoot(f"{floor:02d}_{label}_door{n}_right", inside, (cx + 6, cz - sign * 5))

            around("00_arena")
            rooms = args.rooms.split(",")
            for floor, room in enumerate(rooms, start=1):
                lay = layout(floor)
                if floor > 1:
                    play(rcon, ps.VIEWER, "ready")
                    entry = lay["entry"]
                    look(entry["x"] + off[0], entry["y"] + off[1], entry["z"] + off[2], entry["yaw"], 4)   # the walk to the next floor's entry
                    if not wait_state(rcon, "ENCOUNTER_ACTIVE", seconds=60, floor=floor):
                        sys.exit(f"floor {floor} never opened: " + run_line(rcon))
                    time.sleep(4)
                    shoot(f"{floor:02d}_arena", (lay["entry"]["x"], lay["entry"]["y"], lay["entry"]["z"]), (lay["presentation"]["x"], lay["presentation"]["z"]))
                clear_floor(rcon, run)
                if not wait_state(rcon, "INTERMISSION", seconds=40, floor=floor):
                    sys.exit(f"floor {floor} never reached its intermission: " + run_line(rcon))
                time.sleep(3)
                rcon.command(f"cobbletowers runs draft room {room}")
                play(rcon, ps.VIEWER, "pick 1")   # the ordinary draft settles and the forced room opens behind it
                time.sleep(5)
                print("floor", floor, "room", room)
                tour(floor, lay, room.lower())
                if room == "TRICK_OR_TREAT":
                    listed = rcon.command("cobbletowers vault list")
                    found = re.search(r"at (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) facing (\w+)", listed)
                    print("vault", listed[:200])
                    if found:
                        vx, vy, vz = (float(found.group(i)) for i in (1, 2, 3))
                        for n, (dx, dz) in enumerate(((-4, 0), (-6, 3), (-3, -3))):
                            x, z = vx + dx, vz + dz
                            look(x, vy, z, yaw_to(x, z, vx, vz), 8)
                            time.sleep(2.2)
                            photo(f"{floor:02d}_vault_closed_{n}")
                        look(vx - 5, vy, vz, yaw_to(vx - 5, vz, vx, vz), 8)
                        time.sleep(1)
                        play(rcon, ps.VIEWER, "pick 1")   # the vote to open the vault
                        time.sleep(1.5)
                        burst = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "capture_burst.ps1"),
                                                  "-OriginX", str(ox), "-OriginY", str(oy), "-OutDir", str(out / "vault"), "-Frames", "30",
                                                  "-DelayMs", "120", "-Prefix", "vault", "-ProcessId", str(client.pid)],
                                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        burst.wait(timeout=120)
                        photo(f"{floor:02d}_vault_after")
                else:
                    play(rcon, ps.VIEWER, "pick 1")
                    time.sleep(2)
            log = (game / "logs" / "latest.log").read_text(encoding="utf-8", errors="replace") if (game / "logs" / "latest.log").exists() else ""
            bad = [l for l in log.splitlines() if re.search(r"error|exception", l, re.I)]
            print("client log error lines:", len(bad))
            for line in bad[:8]:
                print("  ", line[:200])
    finally:
        if client:
            client.kill()
        server.stop()
    print("\n".join(str(s) for s in shots))


if __name__ == "__main__":
    main()
