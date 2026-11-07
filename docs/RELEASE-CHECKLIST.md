# Release checklist

Written 2026-10-07 from the deferred-items list and the P37 session. Tick items as they are done; add the date and what was observed.
Ordered by how badly each blocks a release.

**Refreshed 2026-11-07** for the 1.0.14 release (notes: `docs/releases/1.0.14.md`). Lines marked "2026-11-07" were checked that day; everything else is as it was on its own date and has not been re-checked. The section "Open for 1.0.14" near the end lists what this release adds to the list.

## Blockers

- [x] **Test elements out of the release jar** (2026-10-13). The default build is the release; `./gradlew build -Pdev` makes `CobbleTowers-<version>-dev.jar` with the Test Tower, `com.cobbletowers.dev`, the client screenshot/remote/frame tooling and the bot-only commands (`runs create|earn|allocate|encounter|watchdog|advance|fx|validate`, `vendor credit|buy`, `draft force`, `echoes add|record|endbattle`). Smoke and rig scripts need the dev jar. Verify a release with `unzip -l` for `dev/`, `ScreenshotHarness`, `towers/test.json`.
  - 2026-11-07: the release jar `CobbleTowers-1.0.14+fabric-1.21.1.jar` (plain `./gradlew build`, tests green) has none of `com/cobbletowers/dev/`, `ScreenshotHarness` or `towers/test.json`.

- [ ] **P37 battle tracks builds and passes tests.** The code (design: `docs/design/P37-tracks.md`) was written without ever being compiled or tested.
  - [x] `./gradlew build` is green, including `MasteryTrackTest` (the shipped `mastery_tracks/default.json` must match the old rank and perk table at every level 0..30).
    - Done 2026-10-08: build, unit tests and `ci_local` pass; `MasteryTrackTest` 6/6.
  - [ ] Progress tab screenshots at GUI scale 2 and 3 (add a `TrackStatePayload` sample to `ScreenshotHarness`), and with shaders (`validation/hall_screens.py <scale> <mode> iris`, `SHADERPACK` set).
    - Scale 3 verified 2026-10-08 (`hall_progress_*`). Scale 2 and Iris are still open, as are the `<` `>` page buttons in the tower grid (the tower selector arrows are drawn icons now and read fine).
  - [x] Live claim check on the test rig: claim one mastery level and one season step; claim all; claim twice (refused); crash or restart between queueing and recording (no double pay).
    - Done 2026-10-08: `validation/smoke/track_test.py` 21/21 (claim, claim-all, double claim refused) and `track_persist_test.py` 15/15 (hard kill between claims, no double pay).
  - [x] Migration: a world that already has season progress keeps its granted steps as claimed (no re-grant, no loss).
    - Done 2026-10-08 in `track_persist_test.py`: steps granted before claiming existed count as claimed, nothing re-granted.
  - [x] Decide: season steps need a claim by default (a change for existing servers); `auto_claim: true` restores the old behaviour. Say so in the release notes.
    - Decided (owner, 2026-10-07): claim by default, `auto_claim: true` restores the old behaviour. It is in the release notes.
  - [x] Commit and push.
- [ ] **A human plays a full tower.** Everything so far is headless bots and screenshots. At least two real players, one full tower, watching: animation feel at a real frame rate, mouse feel, audio, the vendor villager, the HUD, worn armor, the Shift tooltip, the Test Tower chambers.
  - 2026-11-07: the owner has played the Hollow's Eve tower on the rig with one real client (won a floor, the intermission screen and route map, the Mastery, Trials and rewards screens). Still open: two real players on one full tower.
- [ ] **Cell allocation on the server thread.** A burst of allocate/abandon cycles (hundreds a minute) backs up chunk saves until the 60 s watchdog kills the server, and even ordinary run starts and ends stall a tick for 0.3 to 0.9 s. Options, costs and a recommended order are in `docs/design/cell-allocation-async.md`; the owner picks (three questions at its end).
  - Partly addressed 2026-10-08: the warm pool builds a slice per tick (`SlicedBuild`, longest hold about 60 ms against a 343 ms mean for the synchronous paste) and a finished run's cell is cleared a slice per tick (`CellClearJobs`), so `soak_test --pause 0` queues instead of stalling. Still synchronous: a run start without a warm cell (about 340 ms) and the cold first allocation after boot (2 to 3 s). See "As built" in `docs/design/cell-allocation-async.md`.
- [x] **Bump the build version string** (2026-11-07: `1.0.14+fabric-1.21.1`; 2026-10-14: `0.29.0-p48-beta-ready`; 2026-10-08: `0.23.0-p38-regions-armor`; it had never been bumped before 2026-10-07). Bump it again for every build you deploy, so a log says which jar runs.
- [ ] **Deployment.** Ship the CobbleTowers jar and the AscensionLib jar (with the rental craft lock) together; find out where the real server gets AscensionLib (source: `L:\Codex\CobbleAscend\ascensionlib`). Keep the CobbleRaids version label pinned (`0.8.162-admin-helper`).
  - 2026-11-07: the pins have moved. CobbleRaids is built from what its main publishes (`0.8.204-mew-model-fix`). AscensionLib is `SnobblemonRegions/AscensionLib` `main` at `ba03047` (Threat band info #6 and the vouchers call #7 merged; jar `AscensionLib-1.0.0+fabric.jar`, built in `_integration/ascend-bands`). The Mystery Bag and the Threat picker need both. Nothing has been confirmed on the real server yet.

### P37 code review (2026-10-07, read-only plus small fixes, still uncompiled)

Fixed in the working tree: two grants of the same item in one node collapsed into one (the pending queue deduplicates on id + item +
components, and every grant of a step shared an id; now one id per grant, which matters because merging addon rewards onto a node is the
headline feature); flipping `auto_claim` on mid-season would have granted steps already claimed by hand a second time; payload strings longer
than their codec limit would have thrown in `writeUtf` and broken the packet (now clipped inside the records); a modified client could grow the
merged-track cache with arbitrary tower ids (now only known towers); no request throttle on track actions (150 ms per player).

Still open, for the owner or the next session:
- [x] **Unclaimed season steps** stay claimable through the off-season (owner decision 2026-10-07) and lapse when the next season starts; the season lane shows "CLAIM WITHIN" and the login line reminds the player. Still to check live (needs a season to end on the rig: pin the day with `TrialService.overrideDay`).
- [x] `claimAll` saves world data once per claim (`checkpoint` = `getDataStorage().save()`, twice per claim); claiming 30 steps = ~60 saves. DONE 2026-10-08: one save per batch.
  Batch it: queue all, then one checkpoint.
- [x] `SeasonTrackRegistry.current()` rebuilds the merged track on every call and `claimAll` calls it several times per step; cache the result DONE 2026-10-08: cached on the loaded files and the config.
  keyed on the loaded files and the config.
- [x] `TrackConfig` is read at server start only; `/reload` re-reads datapacks but not `config/cobbletowers-tracks.json` (document or hook it). DONE 2026-10-08: re-read on `/reload`.
- [ ] An addon season file's own `step_cost` is ignored unless it is the base file (document; the guide says the first file with steps is the base).
- [x] Mastery lane with no towers loaded sends an empty lane; the client should say "No towers" instead of an empty strip. DONE 2026-10-08: the player is told "No towers are installed on this server."
- [ ] `TowerUi.wrapped` has no line limit, so a long reward text can overflow the detail panel on a 240 px GUI.

- [ ] **Rotating trials (P46).** Built and tested 2026-10-14 (`rotating_trial_test.py` 13/13: roll, route line, run on the rotating tower, rebuilt after a restart). Still open: a full mixed-route run by a human across a region boundary, the weekly rules in play (no vendor, no Rest, double risk, relic rooms), and a day turnover at midnight with a run in progress.

- [ ] **Server-only deployment (P43).** Steps done 2026-10-14: `-Pserveronly` jar builds, boots and loads; Polymer auto-host serves the resource pack on the game port; `ci_local.sh` and the workflow check the committed web bundle. Setup notes: `docs/SERVER-ONLY-SETUP.md`. Owner played it through on a client with the web UI on 2026-10-14 (pages, a run to floor 2, vendor, rooms, draft, icons, pack) and reported it all looks and works well. Not itemised: worn armor with the pack, the HUD page closing. Still open: a longer run and a second player.

## Should be refined before players see it

- [ ] **UI polish.** The pixel font is wider than vanilla and clips some labels (`Selected destina`); check every screen at scale 2 and 3. Spectator HUD in game, hover tooltips and keyboard-only navigation are owner-deferred. Modifier-card sprites are to be replaced (owner: later). Mastery-level cosmetics are replaced by the P47 mastery badge (icon + level beside the name; built and checked by the owner 2026-10-14, placeholder icon art; design `docs/design/P47-mastery-badge.md`).
- [ ] **Balance numbers are first guesses.** Mastery thresholds and the shipped mastery grants (CobbleDollars 50/100/150/200/300/400/750), the risk payout bonus (+3% minor, +8% moderate, +15% severe, final payout only), trial and Ascension numbers, per-region tuning (each region has its own reward table with a regional booster pack at the boss floor, but all towers use the same `standard` ruleset, so difficulty and level curves are identical across regions). Add counters (contract completion rate, modifier draft frequency) and tune with real data (`docs/design/tuning-with-real-data.md`, `/cobbletowers masteryadmin report`).
  - 2026-10-08: `/cobbletowers masteryadmin counters` now records claims, season points, contract completions, modifier pick rates, the risk bonus and floor times (`docs/design/tuning-with-real-data.md`). Region rules and armor tiers (P38) are data and untuned too.
- [ ] **Confirm the milestone chase rewards are final.** The boss floor guarantees an XL candy, a regional booster pack (CobblemonCards) and 25 Raid Points plus 2 bonus rolls; the champion floor guarantees an XL candy, a God Pack Ticket and 75 Raid Points plus 3 bonus rolls (`reward_tables/<region>.json`). They were "to be determined" before the CobblemonCards crossover; check they are what you want and that the server has CobblemonCards (a missing item is skipped with a log line, so players would silently get less).
  - 2026-10-08 (P38): the God Pack Ticket stays the guaranteed F10 chase; tier III armor pieces (12 sets in all) drop from the F10 champion bonus pool alongside it. Region rules: Tideforge no held items, Rootvale enemy drain 10 to 20%, Duskvale a status on the lead every fight.
- [ ] **Unenforced rules: not release blockers, but know what they are.**
  - Battle switching (TDS #48) is deliberately not CobbleTowers' business: switching and item rules reach Showdown only through CobbleRaids' `EncounterRules` for **boss** battles (`TowerBossAdapter.withSwitching/withItems`, set by the No Retreat / no-items modifiers). Ordinary floors use the live party untouched. Nothing to build unless you want ordinary-floor rules.
  - `item_action_budget` (TDS #47) was removed 2026-10-14: it was parsed and enforced nowhere (`RulesetView.itemActionBudget()`, the ruleset field and the four shipped values are gone; a datapack that still sets it is ignored). A Hardcore-style item limit would be a new field.
  - Mid-run party changes (P18 allows moves after lock-in) are the trap for any party-restricting playlist (Monotype and similar): re-validate at each floor start, or freeze the registered party (roadmap section 'The trap: mid-run changes').
  - [x] Cash-out vote ties keep playing (owner confirmed 2026-10-14); the intermission now says "The cash-out vote is tied, so the team keeps playing." when a vote is exactly half.
- [ ] **Art.** Armor worn layers (visor, boot band, shading), jerseys (data-only today), Test Tower chambers not connected.

## Verification gaps that could hide bugs

- [ ] A multi-hour soak run. Full 10-floor soaks (2026-10-08, `battle_tower_test.py --floors 10`): Rootvale and Duskvale cleared; Neutral and Tideforge lost to a high-level boss draw with the bot (one lead against legendary raid bosses) and want a rerun with a stronger party.
- [ ] Fainted-party and offline-participant rejection (unit-tested only).
- [ ] Disconnect mid-lobby in a rental draft (no live test).
- [ ] Other mods reacting to rentals: only `POKEDEX_DATA_CHANGED_PRE` is guarded; check POKEMON_GAINED-style events and any rank track (StellarRankUp has a `pokedex` track) with that mod installed.
- [ ] Stellar Titles 1.1.2 compatibility jar: menu not tested with a player.
- [ ] Tab list and chat tag with another chat plugin installed (double or hidden tag; `chat_tags: false` switches it off).
- [ ] `floor_encounter_test.py` "boss never starts" flake (not reproduced, cause unknown). Since 2026-10-08 the check "the opponent is marked uncatchable" also fails about 1 run in 3 (passes on rerun; suspected test race, not bisected).

## Open for 1.0.14 (added 2026-11-07)

Verification state of what this release adds; the items are in `docs/releases/1.0.14.md`.

- [ ] Two real players on one full Hollow's Eve tower (lobby with more than one member, a non-host previewing a Threat band, a split pedestal vote).
- [ ] The bridge screens at GUI scales 2, 3 and 4, and with shaders. Only the default scale has been looked at by the owner.
- [x] 2026-11-07: the Mystery Bag opening against the merged AscensionLib works (owner confirmed in game).
- [ ] The Haunted rare driven by a real Trick or Treat vault (only the dev preview has been tried), and the spectator box on the overlay.
- [x] 2026-11-07: the tray width is fine (owner confirmed; it is nearly screen-wide on the bridge overlay, as the web tray was).
- [ ] CobbleTowers PRs #11 to #14, AscensionLib #6 and #7: checks were green on 2026-11-07 (one check each; not confirmed that each ran on the latest push). The bridge repo has no checks.
- [ ] Retire WebGUI from CobbleTowers (the `webgui` package, the `web/` bundle and its CI checks, the WebGUI config handling) once the bridge covers every screen. After the release.
- [x] 2026-11-07: the floor clear no longer opens the route screen by itself; the map opens on request (`/cobbletowers play route`). Owner confirmed it works as intended.
- [x] 2026-11-07: the bridge's Progress tab, Mastery, Trials, the rewards popup and the modifier vote look right after this week's fixes (owner screenshots; owner confirmed they work as intended).

## Already in good shape (this session)

Close button on every screen, Hall banner, bundled UI font with a toggle, modifier art per effect, risk payout, draw cost (Hall draw 3.1 to 1.0 ms), and Iris + Sodium with a shader pack verified for the Hall, modifier selection and the rental pack opening.
