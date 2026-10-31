# Session state: CobbleTowers (as of 2026-10-31)

Branch `cmor/amazing-hopper-1r6071`. Fabric 1.21.1, server-side Polymer. Commits here carry no attribution lines; commit and push once tested; no PRs unless asked.
Vault notes: `session-state-vault.md`. How to run the live rig and take client photographs: `rig-recipe.md` (scripts in `validation/rig/`).

## Build
* `./gradlew build -x test -Pcobbleraids_version=0.8.191-world-boss --offline` (tests: drop `-x test`). It needs **CobbleRaids built from SnobblemonRaids `main`** (it has `CobbleRaidsEncounters.withdraw`):
  `./gradlew publishToMavenLocal` there (the version string is unchanged), then delete `.gradle/loom-cache/remapped_mods/remapped/com/cobbleraids` or Loom keeps the stale remap.
* 1076 unit tests and 17 generator tests (`python -m unittest tools.halloween_tower.test_hollow_tower`) green at last run.

## Done
* Balance pass (`docs/design/tower-key-and-lib-payouts.md`, last section): enemy level from the two highest registered levels, armor only from milestone and crown chests with no duplicates, floor and boss rolls 2 to 1, daily completion budget (3 full, 3 at 50%, then 25%), daily and weekly trials pay only on the scored attempt (practice: 10% for one rental key).
* Hollow's Eve physical tower (`docs/design/P55-explorable-towers.md`, last sections): generator `tools/halloween_tower/` (`hollow_tower.py`, `interiors.py`, `rooms.py`, `exterior.py`; `apply_layout.py` copies the layout into the ten floor files; `preview.py` and `view.py` draw pictures without a client). Structure 164 x 215 x 163.
  Regenerate: generator, `apply_layout.py`, `validation/schem_to_structure.py` (+ `--check`), `validation/validate_definitions.py` (about two minutes). Floors: gothic hall, gates, three route doors, nine stop rooms, corridor, stair; walk-mode intermission.
* Trick or Treat vault (`com.cobbletowers.trick`, `room_trick_or_treat`, `RouteGraph.Kind.TRICK_OR_TREAT`): the vote warns first; even-odds treat or mimic; a lost mimic costs a relic and 5% of the loot banked from then on, taken first and returned on a win; the Hallowbound Reliquary vault opens by clicking it (a vote) and the Hallowbound door guards its doorway.
* Live proof on the rig: `validation/smoke/walk_tower_test.py` 140/140 (10 floors on foot, vault checks inside), `validation/smoke/trick_loss_test.py` (lost mimic: price taken, nothing back, cut in a cash-out payout, relic taken; `--win`: relic returned). `runs losemimic` and `runs grant <run> <relic>` are the operator tools it uses.
* A generator bug found by the walk test is fixed: wall aging replaced the bars of gate A and the doors with bricks (`interiors.weather`, regression test).

## Not done
* A real-client look at the Reliquary chest and a click-to-open on the rig; a real-client pass over the final interiors.
* Not built: a catchable rare treat, a disguised mimic skin, a mimic fight that survives a server restart (the price stays taken), the other stops' votes inside their rooms (they are still pedestal votes), per-player run history.
* Untested with real players: balance numbers, the 90 s straggler teleport with a slow player.
* The world-boss graveyard arena (`validation/arena_schematics/`) is the user's current track.
