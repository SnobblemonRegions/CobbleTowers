# Tutorial Tower: design (25 Nov 2026)

The owner's decisions (25 Nov 2026):

- **When:** a brand-new player's first walk into any tower's portal takes them into the tutorial instead. Afterwards a tutorial host in the hub replays it.
- **Team:** a fixed lent team. Nothing of the player's own is at risk, and every player sees the same fights.
- **Length:** three short floors.
- **Reward:** a one-time starter kit on the first clear. Replays give nothing.

## The run, step by step

The tutorial is an ordinary tower run with its own data. It reuses Neutral's halls, so no new build work is needed.

| Step | Where | What it teaches | The coach says |
|---|---|---|---|
| 0 | Hub portal (first walk-in) | You are taken to the tutorial instead of the tower | "First time? A short tutorial tower comes first: three floors, a lent team." |
| 1 | Prep room (`neutral_prep`) | The merchant; the door to floor 1 | Talk to the merchant, then walk through the door |
| 2 | Floor 1 (`neutral_act1`) | A battle; then the route doors with their pedestals | Battle the boss. Then each door shows its next room on a pedestal: pick one |
| 3 | Between floors | The relic pedestals (votes) | Pick a relic: it lasts the whole run |
| 4 | Floor 2 (`neutral_act1`) | A second battle with the relic in play | — |
| 5 | Floor 3 (small boss) | The final fight | The last fight of the tutorial |
| 6 | End | Rewards; the way home; the gates | "Tutorial cleared. Your starter kit is in your inventory. Every tower's host explains its gate." |

**Coach lines:**

- **Format:** the coach's lines are action-bar text plus one chat line per step. There are no screens.
- **Timing:** each line fires once, on the run event that starts that step: run start, battle start, battle won, door pedestals shown, relic vote open, run end.

## Data

- `towers/tutorial.json`: "Tutorial Tower", three floors.
  - `pedestal_votes` and `route_map` follow Neutral.
  - The prep room is `neutral_prep`.
  - No ascension and no window.
- `floors/tutorial/floor_01..03.json`: Neutral's act-1 layout. Each floor uses a tutorial boss pool.
- `boss_pools/tutorial/*.json`: gentle, fixed bosses, beatable with the lent team at floor-1 strength.
- `playlists/tutorial.json`:
  - `rental: true`;
  - a new `fixed_team` field listing the lent sets;
  - no card rewards.

## Code

1. **Fixed lent team:** where `LobbyLaunch.inspectRental` builds the team, a playlist with `fixed_team` builds it straight from those sets instead. No draft.
2. **`TutorialStore`** (persistent, per player): whether they have started the tutorial and whether they have cleared it.
3. **Trigger:**
   - **What changes:** in `HubPortals.walkIn`, a player alone who has never cleared the tutorial and has no run history starts the tutorial instead of that tower.
   - **Parties:** party walk-ins are not redirected. A new player who joined friends goes with them.
4. **Replay:** `cobbletowers play tutorial <player>`, operator level. A tutorial host NPC's dialog runs it, the way the tower hosts run `keeper`.
5. **Coach:** a listener on the run events above for runs of `cobbletowers:tutorial`.
6. **Starter kit:**
   - **When:** the kit is granted on the first clear only, through `TutorialStore`.
   - **What:** 3 Tower Keys and 10 Poke Balls (a placeholder; tell me the real kit).
7. **Kept out of everything else:** the tutorial is skipped by every system that pays or records a run. The tower listings also skip it, so it never shows as a fifth tower.

## Threat model: Tutorial Tower

**Inputs:**

- a walk into a portal;
- the replay host's dialog;
- the run's own battles, doors and pedestals.

**Client sends:** nothing new. Everything is server-side run state.

**Exploits considered, and how each is closed:**

- **Replay farming (loot, season points, mastery, contracts, card rewards, keys, Ascension records, leaderboards):**
  - **The rule:** a tutorial run pays and records nothing.
  - **How:** one check, `TutorialRuns.is(run)`, guards every payout and record path. The full list is audited in phase 3, and each path gets a test. This is the biggest piece of work.
- **Starter kit claimed twice:**
  - **The rule:** the kit is granted only when `TutorialStore` flips "cleared" from false to true.
  - **Crash safety:** the flag is saved in the same tick as the grant, so a crash can't reopen it.
- **Keeping the lent team:**
  - **The rule:** the lent Pokemon go through the existing rental path. They are made for the run and taken back at its end, death, logout and server stop, the same as a Rental Draft.
  - **How it's closed:** this is the rental system's existing guarantees. It needs no new rule.
- **Forcing someone into the tutorial:** the replay command needs operator level, and only the NPC runs it.
- **Using the tutorial to skip a gate's queue:** it starts its own run in its own cell, so no gate is held.
- **A party used to dodge the tutorial:**
  - **What happens:** joining a party skips the forced tutorial.
  - **Why that's accepted:** this is on purpose. Friends teach each other, and the host explains everything.

**Accepted risks:**

- A player who skips the tutorial by joining a party never sees it unless they talk to its host.

## Build phases

1. **The run itself:** the data, the fixed lent team, `TutorialStore`, the trigger and the replay command. It must play start to finish.
2. **The coach lines.**
3. **Rewards and records:** the "pays nothing" audit, with tests, and the one-time starter kit.
4. **In-game pass on rig 2,** then a commit.
