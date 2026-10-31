# Rig recipe: live-testing and photographing the tower

The rig is a scratch Fabric 1.21.1 server (Cobblemon, CobbleRaids `0.8.191-world-boss`, Polymer, fabric-language-kotlin) with RCON on 25575 (password `walktest`), `-Dcobbletowers.ignoreWindows=true`
in `start.sh`, and a console fed from `console.txt` (`tail -n0 -F console.txt | start.sh`). It lives outside the repo (the cloud container is temporary), so rebuild it from this page.

1. Build the jar: `./gradlew build -x test -Pcobbleraids_version=0.8.191-world-boss --offline` (without those two flags the CobbleRaids artifact is missing).
2. `RIG=<rig> bash validation/rig/restart_rig.sh` stops the server, wipes the tower dimension and run state, installs the jar from `build/libs`, boots, and waits for `Done (`.
3. Walk test (about 10 min, 137 checks): `NODE_PATH=<rig>/bots/node_modules python validation/smoke/walk_tower_test.py --attach --server-dir <rig>/srv`. Needs `mineflayer` and `vec3` in that folder.
   Restart the rig between runs (the test reads the first run). Never print `runs show` over RCON (replies over 4096 bytes break the harness).
4. Pictures: `RIG=<rig> python validation/rig/hold_run.py &` starts a two-bot run and parks it at INTERMISSION (a run left unanswered in battle wipes itself), prints `ORIGIN x y z`.
   Start Xvfb (`Xvfb :99 -screen 0 1280x720x24`), launch a real 1.21.1 client as user `ShotCam` with `--quickPlayMultiplayer 127.0.0.1:25565`, click Yes on the resource-pack prompt, then
   `RIG=<rig> OUT=<folder> bash validation/rig/shots.sh shot name x y z yaw pitch seconds`. Set `view-distance=16` in the rig's `server.properties` for exterior shots.
5. Regenerating the building: see the last section of `docs/design/P55-explorable-towers.md` (generator, `apply_layout.py`, converter, `validate_definitions.py`, tests).

Pitfalls met so far: `pkill -f` / `pgrep -f` with a pattern that appears in your own command line kills your shell (write `[x]yz`); bots keep their party between runs, so use fresh names;
the dev-only `runs advance` is absent from the production jar, use `runs winfloor` (repeat it on floors with extra opponents).

Real client and a real click (met 2026-10-31):
* A **vanilla client cannot join**: Cobblemon 1.8.2 sends trim patterns naming its own items and the join dies with "Registry loading errors". Use Fabric Loader + Fabric API + Cobblemon + fabric-language-kotlin on the client (what players have). Launch it with `net.fabricmc.loader.impl.launch.knot.KnotClient`, `-Dfabric.gameJarPath=<client.jar>`, the loader libraries on the classpath and the vanilla libraries and assets; `meta.fabricmc.net` is blocked, so the server runs `KnotServer` the same way with the loader jars from the Gradle cache.
* The pack prompt only appears with **polymer-autohost** (`eu.pb4:polymer-autohost:0.9.19+1.21.1` on maven.nucleoid.xyz, a separate jar) and `config/polymer/auto-host.json` with `"enabled": true`.
* Run the client with `LIBGL_ALWAYS_SOFTWARE=1`. It then draws a few frames a second and handles keys and clicks **seconds late**: wait 10 s or more after a click before reading the server, and settle any open screen first. A click only reaches the game when no screen is open.
* A solo run is enough: `execute as <name> run cobbletowers play tower cobbletowers:halloween` / `confirm` / `start`, then `runs winfloor <name>`, then `play pick <card>` for the Trick or Treat stop; `vault list` gives the vault's position (it faces west), stand 3 blocks west at its y minus 1, yaw 270, pitch 0, and right-click. Every run after the day's first free one needs `give <name> cobbletowers:tower_key 5`.
* `runs list` prints all runs on one line, so parse it with `findall` over `<uuid> <tower> <STATE>` and not line by line; abandoned runs stay listed.
* `tools/vault/realclient_scene.py` (fixed 2026-10-31: sets creative before it teleports, makes its `anim/` folder; checked from a survival start) takes `SP=<rig folder> OUT=<folder>`. F1 toggles the HUD: set `TOGGLE_HUD=1` only when the HUD is showing, or press F1 yourself and leave it unset.
* To check that clicks reach the game at all, summon a vanilla `minecraft:interaction` in front of the player and read its `interaction` NBT after the click.
