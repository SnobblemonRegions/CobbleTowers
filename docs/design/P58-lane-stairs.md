# P58: Lane stairs (Hollow's Eve v2 flow)

Status: **2026-11-04: generator, data and runtime built and unit-tested; not yet run on the rig or a real client.** Owner answers of 2026-11-04 are in the table. Not for the live tower before the event ends
(Sunday 8 Nov, 11:59 PM Eastern): see "Timing".

## Owner decisions

| Question | Answer |
|---|---|
| Transition at the top of a stair | **Fade and teleport** |
| How different the next floor feels per route | **Dressing and modifier only** (same battle hall layout) |
| Stop rooms | **Kept: door, stop room, then that room's own staircase** (replaces today's shared corridor and gate B) |
| Per floor | Three doors / three routes stay |

## Flow of one floor

1. **Battle hall** (unchanged). Gate A opens when the floor is won.
2. **Intermission atrium**: vendor, three modifier/route pedestals, three doors (one per lane). Unchanged.
3. **Vote settles**: the winning lane's door opens (today's `TowerDoors`). The other two stay shut.
4. **Stop room** of that lane (battle, forge, library, rest, shrine, gambler, vault, pool, trick or treat). Unchanged rooms (`room_*.nbt`).
5. **Lane stair** (new): out of the stop room, a stair of its own climbs to a landing with a threshold (an arch with a mist curtain).
6. **Transition** (new): walking into the threshold fades the screen and teleports the party to that lane's **arrival anchor** in the next floor's arrival hall. The route's modifier applies to the next fight as it does today.
7. **Arrival hall is dressed for the lane** (new): the hall the party lands in is dressed by lane (forge embers, library shelves, a candle shrine, and so on), so the next floor feels like the route taken.

Because the three stairs never meet, the party can only ever climb the one the vote opened. There is no shared corridor to cross lanes.

## Contract changes (floor JSON)

Today a `FloorDoor` is `{open, frame, pedestal}` and the floor has one `gate_b` and one `stair` path.

* `FloorDoor` gains `stair_top` (a `FloorBox`, the threshold trigger) and `arrival` (a `FloorAnchor` in the **next** floor's arrival hall). One arrival per door.
* `gate_b` stays only as the "floor can be left on foot" marker and for the last floor; the shared corridor and gate B are removed from the generator.
* The structure stays one building per run cell, so `WalkRules.onFoot` (same structure) still decides walk versus teleport.

## Runtime changes

| Piece | Change |
|---|---|
| `TowerDoors` | Remember which lane opened (it already knows the winning door). |
| `IntermissionRound.beginWalk` / `WalkRules.arrived` | "Arrived" becomes "stood in the threshold of the opened lane's stair, then was teleported". Keep the 90 s straggler rule: after it, stragglers are teleported to the same lane arrival. |
| `TowerEncounters.arrive` | Take an explicit arrival anchor (the lane's) instead of the floor's single entry anchor. Still only teleports players who are not already there. |
| Fade | A short title/overlay on the party member, then teleport on the same server tick as the screen goes dark. The transition must not let a fighter drop out of the party: teleport all online fighters together when the first reaches the threshold and the rest are within the walk radius, or at 90 s. |
| Dressing | A lane dresser for the arrival hall, same idea as `RoomDresser`: swap a small list of blocks and props by lane, per run cell. Data, not code, where possible. |

## Generator and validator

* `rooms.py`: each stop room gets an exit door onto its own stair. `hollow_tower.py`: three parallel stair shafts per floor, replacing the corridor and gate B. Stair shafts must not intersect the next floor's arena.
* `validate()` flood fill (on foot): with only door *n* open, the party reaches room *n*, stair *n* and the threshold *n*, and **nothing of the other two lanes**. No path from any threshold into the next floor's arena except by the teleport.
* Mutation tests added: a missing step in one stair, an open second door, a stair that reaches another lane's room, a threshold with no arrival anchor.
* `validate_definitions.py`: each `arrival` lies inside the structure and inside the arrival hall.

## Floor size (answered separately)

Three stairs per floor cost footprint but not height. If the compact module (~48 x 48, 12 per floor) is also adopted, the stair shafts sit behind the stop rooms rather than in a shared corridor, which is the tight part to fit. Decide the footprint before building the first module.

## Timing

Not live before the event ends. The change moves anchors, boxes and the walk logic, so it needs the walk test (`walk_tower_test.py`) rewritten and a real-client pass. Build one prototype floor module (hall, atrium, three doors, one stop room, one lane stair, one arrival dressing) in the generator and prove it on the rig, then roll it across the ten floors for the next event.

## Build order

1. Contract: `FloorDoor.stair_top` and `arrival`, parsing and `validate_definitions.py` checks, tests.
2. Runtime: lane-aware walk and arrival, with the fade; unit tests for `WalkRules` and arrival.
3. Generator: one prototype floor; validator and mutation tests.
4. Rig: extend `walk_tower_test.py` to take each of the three lanes.
5. Lane dressing for the arrival hall; real-client photographs.
6. All ten floors; regenerate, convert, validate.

## Decisions (2026-11-04, later)

1. **Footprint**: keep the current floor layout size; remove needless emptiness (tighten dead space inside the floor, no change to the arena, atrium or room sizes).
2. **Fade**: yes, on every teleport between floors (lane stairs, cycle end, straggler).
3. **Lane arrival dressing**: cosmetic only.
4. **Ship**: Friday evening 6 Nov, so the whole redesign must be built, validated on the rig and deployed in two working days. The window ends Sunday 8 Nov 11:59 PM Eastern.

## As built (2026-11-04)

* **Geometry** (`tools/halloween_tower/hollow_tower.py`): the shared corridor, gate B, the ten-step stair and the slab holes are gone. Behind each chamber, the back doorway
  (unchanged, so `rooms.py` and the `room_*.nbt` are untouched) opens onto its own stub in the strip z 2..4: a flat vestibule, five steps and a three-block landing, climbing
  west for lane 0 and east for lanes 1 and 2 (`stub_span`, `cut_stub`, `LANE_DX`). The landing is feet + 5 (three blocks of headroom); the threshold is its last two columns.
  Floor 10 has no stubs (its chamber doorways are walled). The old stair hall (x 79..94, z 2..72) is solid masonry; the east windows over it are gone. The three
  lane arrivals stand in the arrival hall at x 10, z 41 / 48 / 55 (reversed on mirrored floors, so lane 0 is always on the left).
* **Dressing** (`interiors.decorate_stub`): vestibule lamp, candle niches beside the steps, a purple runner on the landing, an obsidian arch round a glass window lit from behind.
* **Validator** (`validate()`): with only door *i* open the party reaches chamber *i*, the threshold of lane *i* and nothing of the other two lanes; the walk never leaves
  its floor (y <= feet + 5); each arrival is standable and reaches the next floor's arena door. 20 tests (mutations: a sealed doorway, a missing step, a hole between two
  chambers, a walled arrival door, a block on the landing).
* **Data**: `FloorDoor.stairTop` and `arrival` (both or neither; `FloorLayout.laneStairs()` and `walkable()`). `validate_definitions.py` checks them against the structure.
  `battle_tower_halloween.nbt` is regenerated (164 x 215 x 163, 904,848 blocks).
* **Runtime**: `IntermissionService.beginWalk` opens the voted door and starts a climb of the lane `TowerDoors.openLane` finds; `tickClimb` waits for everyone on the landing
  (`LaneStairs.everyoneOnLanding`) or the deadline, fades (`FADE_OUT_MILLIS` of blindness), then `LaneStairs.transfer` teleports the team to the lane's arrival, dresses it
  (`LaneArrival`, by the kind `RoomDresser.kindBehind` reports) and the walk becomes the 45 s walk into the arena. Any other teleport into a floor fades in
  (`TowerEncounters.arrive`).
* **Arrival dressings** (`LaneArrival`): every `RouteGraph.Kind` has its own props (battle, forge, library, rest, shrine, gambler, vault, pool, trick or treat); `LaneArrivalTest`
  checks that none falls back to the generic set and that props stand behind the party for both yaws. An unknown or missing kind reads as a battle (`Kind.parse`).
* **Fade on every teleport** (2026-11-05): the cycle-end teleport goes through `TowerEncounters.arrive`, which fades in. The spectator anchor, the vendor stranded-party
  teleport, the spectator revival landing and both run-exit teleports now call `LaneStairs.fadeIn` too. Spectator-follow (`Watching`) is left as a cut on purpose.
* **Event flow at the door** (2026-11-05, owner TODOs #3, #4, #5, #8): an event round's two cards stand **beside the door the party chose** (`PedestalRules.beside`: the
  accept on the player's right, the refusal on their left, facing the door), no longer at the far left and right doors, so nobody walks to the end of the hall and
  back. The door of the chosen lane lights up and opens only once **every pick is made** (`DraftService.doorMayOpen` / `openDoorIfDone`: no open event round or
  relic draft); a battle lane has no event, so it still opens at once. Every refusal reads "Refuse ...", says what is given up, shows as a grey pedestal and answers
  in words of its own (`IntermissionEvents.isRefusal`). Refusing a rest pays every player `restPurse(floor)` CobbleDollars (50 + 10 per floor, at least twice the
  vendor's 25 CobbleDollar heal); the heal stays free. **Not built:** the agreed "+15% loot on the floor you press on through", which needs a per-floor flag
  persisted in the run (a schema change); modifiers have no per-floor expiry.
* **Not done**: rig proof (`walk_tower_test.py` still describes the old corridor and gate B), a real-client look (which also has to confirm the new fades and that the
  dressings do not crowd the way to the arena door), the east-masonry alcoves.
