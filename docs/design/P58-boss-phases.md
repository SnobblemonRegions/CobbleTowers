# P58 Boss phases

Each milestone boss (floor 5 boss, floor 10 champion, five towers: ten bosses) gets two named phases. A phase is a
trigger plus a bundle of the battle ops that already exist. Owner decisions (2026-11-03): the ten milestone bosses only;
phases are announced and listed up front; **same difficulty, different shape** -- every phase pairs a strength with a
cost, so a boss is about as hard as today and the balance work of phase 2 stands.

## Mechanism

`phases` list in `milestones/<tower>/<boss_05|champion_10>.json`, passed to the battle with the other tower effects:

```json
"phases": [
  {"id": "overclock", "name": "Overclock", "when": {"hp_below": 50},
   "announce": "Regieleki overclocks! It is faster, and its shell is thinner.",
   "do": [ {"op":"boost","side":"foe","stat":"spe","stages":2}, {"op":"terrain","terrain":"electricterrain"},
           {"op":"resist","side":"foe","percent":115} ]}
]
```

- `when`: `{"hp_below": N}` (percent of the boss's max HP, checked after every move) or `{"turn": N}` (start of turn N).
  Each phase fires at most once per battle; a phase with `hp_below` fires on the turn the HP crosses it, not before.
- `do`: any op `tower-fx.js` already runs, plus the new `heal` (`percent`, once) and `cure` (clears the side's negative
  stat stages). An op a build does not know is skipped, as everywhere in `tower-fx.js`.
- `tag` + `clear` (Arceus): a `damage`/`resist` op may carry a `tag`; `{"op":"clear","tag":"x"}` removes its rules, so a
  phase can swap the one before it.
- Fails open: a phase that throws is logged from inside the JS and skipped; it can never hang a battle. The Java side
  hands over `phases` with `towerFx` and the JS logs `[CobbleTowers] Phase <id> fired` (the only proof it ran).
- Counts toward `MAX_OPS` like any op; phases are capped at 3 per boss.

## Telegraph (announced and fair)

- The battle log says the `announce` line when a phase fires.
- The boss card in the web UI lists each phase's name and trigger ("Overclock -- below 50% HP") so a team can be built
  for it. The text comes from the same JSON: one source.
- Scouting adds nothing here (no hidden phases).

## The ten bosses

"Strength / cost" is the same-difficulty rule: what the boss gains, what it pays.

| Tower | Boss | Phase 1 | Phase 2 |
|---|---|---|---|
| Tideforge 5 | Regieleki | **Overclock** below 60%: +2 Spe, Electric Terrain / takes x1.15 | **Overload** below 30%: enrage every 3 turns / loses its +2 Spe |
| Tideforge 10 | Kyogre | **Downpour** turn 1: heavy rain (Fire moves fail) / its Water damage x0.9 | **Tidal Surge** below 40%: Water x1.25 and 20% drain / takes x1.1 |
| Rootvale 5 | Tapu Bulu | **Overgrowth** turn 1: Grassy Terrain / -1 Spe | **Bulk Up** below 50%: +1 Atk +1 Def / its Grass damage x0.9 |
| Rootvale 10 | Celebi | **Time Skip** below 30%, once: heal to 60%, clear stat drops / its Spe -1 | **Grove Guardian** turn 6: Reflect and Light Screen / its damage x0.85 |
| Duskvale 5 | Necrozma | **Prism Armor** turn 1: takes x0.9 / its damage x0.95 | **Ultra Burst** below 40%: +1 Atk +1 SpA +1 Spe / takes x1.2 |
| Duskvale 10 | Darkrai | **Bad Dreams** below 60%: lead falls asleep once / Darkrai -1 SpD | **Nightmare** below 30%: 25% drain / takes x1.15 |
| Neutral 5 | Cobalion | **Sacred Sword** below 60%: +1 Atk, ignores your Def boosts / -1 Def | **Steel Wall** below 30%: Reflect / its damage x0.85 |
| Neutral 10 | Arceus | **Multitype** every 3 turns: favours one type x1.25 and takes x1.15 from that type's counter, rotating (tagged, `clear`ed each time) | **Judgment** below 25%: +1 all offence / favoured type rotates every 2 turns |
| Halloween 5 | Mimikyu | **Busted Disguise** below 70%: +2 Atk after the disguise breaks / takes x1.1 | **Grudge** below 30%: Curse-style 15% drain / its Spe -1 |
| Halloween 10 | Mew | **Many Moves** turn 1: random weather each 4 turns / -1 Def | **Mirror Trick** below 40%: copies your lead's boosts once / takes x1.15 |

The exact numbers are first guesses. They are tuned by the simulation rig (`tower_fx_test.js`) against each boss's
current clear rate, not by feel.

## Build order

1. `tower-fx.js`: the `phase` engine, `heal`, `cure`, `clear`/`tag`; unit-tested in `tower_fx_test.js` against the real
   Showdown (hp_below crossing, turn trigger, once-only, fail-open).
2. Java: carry `phases` from the milestone JSON into the battle's tower effects; validator
   (`validate_definitions.py`) checks ids, triggers, ops and the 3-phase cap.
3. Content: the ten bosses, then web boss-card list.
4. Live: one rig run per tower; read `Phase <id> fired` in the server log.

## Open

- Whether Threat scales phase triggers (e.g. earlier phases at higher Threat). Not in this pass.
- Halloween Mew's "random weather" needs a deterministic seed per run so a run code replays the same fight.
