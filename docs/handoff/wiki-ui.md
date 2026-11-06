# Handoff: a wiki UI for the CobbleTowers server

Task: build a read-only, searchable wiki that tells players what is in CobbleTowers (towers, bosses, modifiers, relics, rewards, achievements, playlists, seasons, vendor services, armor, rental Pokemon) and how the systems work. Nothing here changes gameplay.

Written 2026-10-23, updated 2026-10-24 (rental cards and the Ranks tab changed; see "Changes since first written"). Verify the counts below against the data before relying on them; they drift.

## Changes since first written (2026-10-24)

Read this before the content map. Two systems moved and the wiki must describe the new behaviour.

### Rental Pokemon (Prism Pull card, P53, rolled IVs and EVs)

Commits `6ab9537`, `5d2123f`, `6dae3a9`, `5d1a22e`. Design: `docs/design/P53-rental-rarity-roll.md`, `docs/design/P33-rental-draft.md`.

- **Species class and tier are now two different things.** A set's `rarity` in `rental_sets/*.json` is its *class*; it decides which species a pack can offer (three common, one uncommon, one rare-or-better, an epic-or-better somewhere, an occasional God Pack, no species twice). Each card then rolls its own *tier* (common ... mythic), dealt onto the species at random with the same pack shape. Any species can wear any tier.
- **The tier is what the player sees and what counts.** It drives the card look and reveal, the "at most 2 legendary or mythic on a team" cap, and the AscensionLib profile the Pokemon is lent with. The class still decides which species appear and the CobblemonCards rewards.
- **Do not publish the 150 sets' `rarity` as "the card's rarity".** On the wiki call it the species' *class* or *pool* and explain that the tier is rolled per draw. A legendary-class species can show up at common tier and the reverse.
- **IVs and EVs are rolled per card when the draft is drawn** (`RentalSpread`): IVs 15 to 31 each, except Speed stays 31 when the set invests EVs in Speed; EVs are the set's main stat plus one other random stat, both at 252. The card shows them and the lent Pokemon gets exactly those. The values in `rental_sets` (`nature`, `evs`, `ivs`) are only the *set's* spread; the nature is fixed by the set, the IVs and EVs are not. `gen-data.mjs` already emits all three per set (defaults hardy / 0 EVs / 31 IVs), so show them as "set spread; each card rolls its own".
- **Same seed, same pack.** Species and tiers come from separate seeded contexts, so the Daily Trial draft stays a pure function of the seed. The wiki can say daily trial packs are the same for everyone that day.
- **Card art.** The "Prism Pull" card, pack, card back and kept mark are drawn in CSS (`web/src/components/RentalCard.js`, `styles/rcard.css`, `cardTheme.js` `TIERS`). The Byzantine look is archived in `archive/byzantine/` and not shipped. Do not reuse the card as a wiki entry card; the reveal uses a sparks canvas and animations. For the wiki use plain Desk cards with a tier chip.
- **Not verified live.** The Prism Pull card and the rolls were checked in the dev browser build, unit tests and `ci_local` only. Treat the odds as unproven and do not publish numbers for tier odds; say "rarer tiers are rarer" until `/tower report` has real data.
- **Still unknown to the wiki:** the type glyphs on the card are placeholders; the tooltip does not read a party Pokemon's live AscensionLib tier.

### Leaderboard: the Ranks tab and the region duel

Commits `1a3e822`, `785b934`. Code: `region/RegionRules.java`, `region/RegionService.java`, `persistence/TowerRegionStatsStore.java`, `web/src/desk/DeskRanks.js`, `ranksView.js`.

- **Ranks is a Hall tab** (pills: Play, Trials, Progress, Social, Ranks, Codex). It shows a head-to-head duel between two of the three regions: Tideforge, Rootvale, Duskvale. The viewer's own region and its closest rival are the default pair; picking the other side's region swaps them. A verdict bar says who leads "N to M" or "Level at N each".
- **A player's region is their CobbleTeams team.** CobbleTeams puts players on a vanilla scoreboard team named after the region; the mod reads the team name (case-insensitive) and matches `tideforge`, `rootvale` or `duskvale`. There is no region picker in the tower menu and no compile dependency on CobbleTeams. A player with no matching team is simply not counted. Region membership is remembered (last seen), so an offline clear still counts for the right side.
- **A tower's region** is its regional theme, or its own id. Neutral towers belong to no region.
- **The five rows** (`RegionRules.Stat`):

  | Row | Kind | How it is judged |
  |---|---|---|
  | Total clears | total | tower cycles cleared by that region's players |
  | Away conquests | total | a player clearing a *rival* region's tower (home clears do not count; neutral or no-region is never away) |
  | Trial clears | total | scored daily/weekly trial finishes |
  | Deepest Ascension | record (high) | one player's best; compared raw |
  | Fastest cycle | record (low) | one player's best time; 0 means none set yet, which loses to any real time |

- **Totals are judged per member.** A bigger region does not win by size: the lead compares `total / players` (done as a cross-multiplication in whole numbers). If either side has no players, raw totals decide. Records are one player's feat and are compared as they are. Say this on the wiki; it is the rule players will ask about.
- **Scope.** The tab shows the running season, else all-time. Results are written to both all-time and the current season (`s3`-style ids). A champions list per region is capped at 3000 player rows.
- **Server decides everything.** The page gets a `RanksState` payload (`RanksRequest` action) with the rows and who leads; the wiki must not recompute anything. The wiki only *explains* the rules, as static text.
- **Live data stays out.** The duel numbers are per server and change constantly. Do not put counts in the wiki; link to the Hall Ranks tab instead.
- **Unverified:** the live CobbleTeams team ids on the real server are unconfirmed (config defaults are ember / tide / grove; the live config differs, and the region match needs the names `tideforge`, `rootvale`, `duskvale`). If a wiki page says "join a region via CobbleTeams", confirm that first with the owner.

## Decisions to make first (owner, not yet taken)

1. **Where does it live?** Two candidates:
   - **A. A new page in the existing web UI** (WebGUI/Rinku, the `web/` bundle). Players open it in game, e.g. a Hall tab or `/tower wiki`. No new hosting. The page is untrusted input and the bridge drops payloads over 32,768 chars, so the wiki must ship its data in the bundle, not over the bridge.
   - **B. A static site** generated from the same data files and hosted anywhere. Needs hosting the server does not have today.
   - Recommended: A first. It reuses the Desk look, the sprite atlas and the build, and matches the owner's server-only goal (clients run Cobblemon + WebGUI + Rinku only). B can be generated from the same data module later.
2. **Static or live?** The data module below is built at bundle time from the mod's own files, so it shows the shipped defaults. A server with datapack overrides (an addon, a changed reward table) would show stale numbers. Static is fine for v1; say so on the page ("default values; this server may differ").
3. **Spoilers.** Boss pools, reward odds and modifier weights are all in the data. Decide what a player should see. Suggestion: show boss pools and reward *kinds*, hide raw weights.

## What already exists (reuse it)

| Thing | Where | Notes |
|---|---|---|
| Web UI sources | `web/src` (Preact + htm, esbuild) | `node build.mjs` writes the release bundle to `src/main/resources/web/cobbletowers/` (COMMITTED, rebuild after every `web/src` change, then the gradle build). `ci_local.sh` checks the bundle is current. |
| Build-time data module | `web/tools/gen-data.mjs` -> `web/src/gen/data.js` | Reads the mod's data files and emits `{rental, species, residents, version, guide}`. **Extend this**, do not add a second reader. `web/build.mjs` line ~26 writes it. |
| Player guide text | `src/main/resources/data/cobbletowers/guide.json` | Title + sections of lines. Already shown by `web/src/screens/Guide.js` (native look) and a Desk version, and as `/tower help` in chat. The wiki's "How it works" section can reuse it. |
| Sprites | `web/src/gen/atlas.js`, `web/tools/atlas.mjs` | 87 partner sprites (CC0) in an atlas. Species not in the atlas need a text fallback. |
| Item pictures | `web/src/itemart.js`, `web/src/gen/items.js` | Generic shape + colour by item name; AscensionLib material textures. |
| Look | `web/src/desk/*`, `web/src/styles` | "Frontier Desk" is the default look. Match it. Hall is mounted in `web/src/App.js` (`screen === 'hall' && look === 'desk' && view === 'guide'` shows `DeskGuide`). A wiki is a new `view` there. |
| Tests | `web/test/*.test.js`, `npm test` in `web/` | Pure view-model tests per screen. Do the same. |

Read first: `docs/design/P44-webgui-ui.md` (architecture, perf rules), `docs/design/webgui-bridge.md` (what the page can and cannot do), `docs/design/P51-frontier-desk-hall.md` (the look), and memory notes on web perf (idle frames ~0.2/s; no infinite animation loops, animate only transform/opacity).

## Content map: data files to wiki pages

All data lives in `src/main/resources/data/cobbletowers/cobbletowers/` (call it `$D`). Every file has `schema_version`. Names shown to players come from `display_name`; ids are `cobbletowers:<file stem>`. Labels for items and some strings are in `src/main/resources/assets/cobbletowers/lang/en_us.json`.

| Wiki page | Source | Fields worth showing | Count (2026-10-23) |
|---|---|---|---|
| **Towers** | `$D/towers/*.json` (skip `test`: dev-only) | `display_name`, `floors[]`, `milestones[]`, `ruleset`, `reward_table`, `regional_theme`, `ascension` | Neutral, Tideforge, Rootvale, Duskvale (+ dev Test Tower) |
| **Floors & bosses** | `$D/floors/<tower>/floor_NN.json`, `$D/boss_pools/<tower>/{early,late}.json`, `$D/encounter_pools/*.json`, `$D/milestones/<tower>/*.json` | floor `index`, `encounter_pool`, `boss_pool`; boss pool `entries[].definition` (a `cobbleraids:<species>` id) and `weight`; milestone `floor`, `kind` (boss / champion), `raid_definition`, `banks_rewards` | 10 floors per tower; milestones at floors 5 and 10 |
| **Rules** | `$D/rulesets/*.json` | `enemy_level` min/max, `registered_party_size`, `carryover`, `player_held_items`, `requires_battle_ready_party` | 4 (standard + one per region) |
| **Regional themes** | `$D/regional_themes/*.json` | `jersey_signatures[].species` (signature Pokemon) and theme text | 3 |
| **Modifiers** | `$D/modifiers/*.json` | `display_name`, `risk` (minor/moderate/severe), `tags`, `stack_limit`, `weight`, `relic` flag, `type` (custom/enemy/reward/scouting/field/encounter/player_constraint), `effect` | 81 files: 47 minor, 24 moderate, 10 severe |
| **Relics** | the modifiers with `"relic": true` | same as modifiers; group by tag (offense, risk, ...) | 38 |
| **Rewards** | `$D/reward_tables/*.json` | tiers (`opponent_defeated`, boss, champion, ...), each entry `item`, `min_amount`/`max_amount`, `weight`, `min_floor`/`max_floor`, table `growth_percent_per_floor` | 4 tables |
| **Achievements** | `$D/achievements/*.json` | `display_name`, `description`, `condition` | 30 |
| **Mastery & tracks** | `$D/mastery_tracks/*.json`, `$D/season_tracks/*.json`; format in `docs/TRACKS-GUIDE.md` | level, rank names, perks (vendor discount, Cobble Dollar bonus, raid points bonus), grants | 1 + 1 |
| **Seasons** | `$D/seasons/season_N.json` | `number`, `name`, `spotlight` tower | 3 |
| **Playlists** | `$D/playlists/*.json` | `display_name`, `description`, `party` rules, `difficulty_bonus` | 6 (hardcore, level cap 50, monotype, rental, solo gauntlet, underdog) |
| **Daily / weekly trials** | `$D/trial_pools/*.json`, `$D/trial_recipes/*.json` | pools the rotating trial draws from | 2 + 2 |
| **Contracts** | `$D/contract_templates/*.json` | `display_name`, `description`, `period`, `condition`, `reward` | 14 |
| **Vendor** | `$D/vendor_services/*.json` | `display_name`, `price_cobble_dollars`, `max_purchases_per_run`, `effect` | 3 (cure status, full heal, mystery bag) |
| **Rental Pokemon** | `$D/rental_sets/*.json` | already in `DATA.rental`: `species`, `name`, `level`, `types`, `rarity` (the species' *class*, not the card tier), `item`, `nature`, `evs`, `ivs` (the set's spread; each card rolls its own IVs and EVs). Plus a "how packs and tiers work" text from the section above | 150 |
| **Ranks (region duel)** | rules only; numbers are live server state | the five rows, per-member judging, away conquest, how a region is chosen (CobbleTeams). No counts | 3 regions |
| **How it works** | `data/cobbletowers/guide.json` | already in `DATA.guide` | 1 |
| **Currencies & materials** | `docs/design/tower-key-and-lib-payouts.md`, memory note "Armor archived + AscensionLib pays" | Cobble Dollars, Raid Points, and AscensionLib materials (resonance dust, facets, tower marks, scouters), paid only by AscensionLib | n/a |

### Do not put in the wiki

- **Armor sets** (`$D/armor_sets`, 12 sets): archived on 2026-10-23, out of drops, WIP names. Leave out, or add a "coming later" stub only if the owner asks.
- **Test Tower, test floors** (`towers/test.json`, `floors/test`): dev build only.
- Held items, mints and Tera Shards are not rewards. Do not list them as such.
- **Rental tier odds and live Ranks numbers.** Odds are untuned and unverified; the duel is live state. Explain the rules, not the figures.
- **The Byzantine card look.** Archived; the Prism Pull card is the only one.
- Ascension is paid by AscensionLib, not by tower reward tables (the tower tables no longer carry dust, facet, mark or scouter entries).

## Suggested design

- **Shell**: a left list of sections (the table above, grouped: Play, Content, Progress, Reference) and a right reading pane. On narrow widths the list collapses to a top picker. Search box over every entry name and description (client-side, a prebuilt lowercase index; no library needed at ~600 entries).
- **Cross-links**: a modifier mentions a tag, a tower lists its modifiers' risk; make ids links (`#wiki/modifier/glass_cannon`). Keep it hash-based inside the page; the bridge URL must not carry a fragment (`webgui-bridge.md`), so route with in-page state, not `location.hash` from the server.
- **Entry cards** reuse Desk components (`web/src/desk`) and `ItemIcon`/sprite atlas. Risk gets a label and a sign, never colour alone.
- **Performance**: windowed lists for modifiers, rewards and rental sets. No infinite animations. Budget: first paint under 150 ms, idle at ~0 timers.
- **Bundle size**: WebGUI has a file-count and size ceiling (~170 files / ~480 KB seen). The wiki data should be one JSON-in-JS module of a few tens of KB; check the bundle size after adding it (`node build.mjs` prints it; it was 582 KB before this).

## Build plan

1. Extend `gen-data.mjs` to return a `wiki` object: towers (with resolved floors, bosses, milestones, ruleset, theme), modifiers (+ relic flag), reward tables, achievements, playlists, trials, contracts, seasons, vendor, tracks. Resolve ids to display names at build time so the page needs no lookup. Add a Node test that every referenced id resolves and every `display_name` is non-empty.
2. Normalise effect text: `modifiers/*.json` carry machine `effect` blocks (`op: boost|hp|...`). Write one pure function that turns an effect into a sentence, and have the test fail on an unknown `op` so a new modifier cannot ship with a blank description. Check whether `lang/en_us.json` already has text for modifiers before inventing any.
3. Build the shell + two pages (Towers, Modifiers) end to end, with search. Screenshot with `node build.mjs --dev && node tools/shoot.mjs <dir>`.
4. Add the remaining pages one at a time.
5. Add a Hall entry point (Desk Hall tab or button) and `/tower wiki` if an open request is cheap. Opening a page from the server uses `WebGuiBridge`; read `docs/design/webgui-bridge.md` before adding a new payload kind (`SUPPORTED` must list it in the same change, and `TowerNetworking.handleAction` needs a branch for every action kind `PageActions.decode` returns). A pure client-side wiki tab inside an already-open Hall needs no new payload.
6. `npm test`, `node build.mjs`, gradle build, push (the pre-push hook runs `validation/ci_local.sh`).

## Constraints and traps

- **Web UI only.** The old native client is archived in `archive/native-ui`; do not touch it. Build is server-only.
- **Rebuild and commit the bundle** after any `web/src` change, or CI's freshness check fails. Beware `core.autocrlf=true`: the check compares line-ending-neutral hashes.
- **Tool trap**: in this harness a `\` in a shell heredoc or python string comes out as one backslash; write regex-heavy edits with the Edit tool.
- **Never build during a push.** Do not run a gradle build while the pre-push hook is running.
- **Commits**: no Co-Authored-By, no Claude-Session, no attribution lines of any kind. Commit and push once tested; no need to ask.
- **Comments**: short notes, not narratives.
- **Data is the single source.** Do not copy numbers into the web source by hand; the wiki goes stale the first time a reward table is retuned. Everything comes from `gen-data.mjs`.
- **Text**: English only, ASCII " * " as a separator in server text (the page turns it into a middle dot). Font is Jersey 15 (not Pixelify; its small C reads as O).
- **Species art**: only species with a sprite in the atlas get a picture. Boss `definition` ids are CobbleRaids definitions (`cobbleraids:<species>`); strip the namespace to find the sprite.

## Open questions for the owner

- Wiki inside the game (A) or on the web (B)?
- Show raw drop weights and boss pools, or hide odds?
- Should it read live server data (needs a new payload kind and paging because of the 32,768-char limit), or is shipped-defaults enough?
- Is a search result page enough, or should entries be linkable from chat (`/tower wiki <name>`)?
- Should the rental page list all 150 sets with their set spread, or only explain packs, tiers and the rolled spread? (Recommended: list species grouped by class, with the set spread as "typical", since each card rolls its own.)
- Does the wiki say how to join a region (CobbleTeams)? Needs the live team names confirmed first.

## Done when

- Every section above renders from generated data, with no hand-copied numbers. The rental and Ranks rule text is the one exception: write it once as prose in the data module, and keep it in step with `RentalSpread` (IV floor 15, EVs 252 on two stats) and `RegionRules` (five rows, per-member totals) via a test that reads those constants or fails when they change.
- Search finds any modifier, relic, achievement or tower by name.
- Node tests cover id resolution and the effect-to-sentence function; `npm test`, `ci_local.sh` and the bundle check pass.
- Screenshots at 1280x720 and 1920x1080 are checked for every page, and the owner has seen it live on a test server through a stock client.
