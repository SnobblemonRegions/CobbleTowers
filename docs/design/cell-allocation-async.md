# Cell allocation off the critical path

Status (2026-10-08): admission control (A), the clean-cell check (C1) and the once-a-second warm pool tick (C4) were built earlier (`HeavyWork`,
`CellCleanliness`, `CellWarmPool.tick`). The warm pool's build is now sliced across ticks (B, for the one caller nobody waits on): see "As built" at the end.
The run-start prepare and the release reset are still synchronous, and C2/C3 are not built. Release blocker #3 in `docs/RELEASE-CHECKLIST.md`.

## The problem

Every run needs a cell: about 325,000 blocks over 49 chunks of the tower dimension. All of the work runs **synchronously on the server thread**:

| When | Call | What it does | Measured |
|---|---|---|---|
| Run start / floor change | `CellPreparer.prepare` | `CellTickets.hold` (49 chunks), `reset` (scan every non-air section), `StructureTemplate.placeInWorld`, `CellAnchors.validate` | paste 0.3 to 0.9 s |
| Run end / abandon | `InstanceAllocator.release` | `reset` (clear every non-air block), `CellCleanup.sweepDebris`, `verify`, `CellTickets.drop` | reset ~0.3 s+ |
| After either | `CellWarmPool.topUp` | `allocate` + `prepare` up to 2 cells per structure, at most once per 5 s | same as prepare |

`reset` calls `level.getChunk(x, z)` for every chunk of the cell. That is a **blocking** load: if the chunk was just unloaded and its save is still queued (the previous
release dropped the tickets), the call waits on chunk I/O. A burst of releases and allocations backs the saves up, one blocked `getChunk` stalls the whole server,
and the 60 s watchdog stops it (found 2026-10-05 with `soak_test`, hundreds of cycles a minute; pre-existing since P27).

Two separate problems:
1. **Stalls (lag spikes)**: each paste or reset is 0.3 to 0.9 s of one tick, 6 to 18 ticks. Players on the server feel that on every run start and end, long before the watchdog.
2. **Collapse under bursts**: when blocking chunk loads queue behind saves, the stalls compound until the watchdog fires.

Ordinary load is a few run starts and ends a minute; a busy server at peak could be 10 to 20. That is 10 to 30 s of main-thread blocking per minute in 0.3 to 0.9 s lumps, which
is why this is a blocker for a public release even though a quiet server never notices it.

## Options

### A. Admission control only (small, safe, partial)
Queue run starts, floor changes and releases behind a limiter: at most one heavy operation (paste or reset) per N ticks, and refuse or delay new run starts when the queue is
long ("Your arena is being prepared..."). Fixes problem 2 (collapse), not problem 1 (each op is still a 0.3 to 0.9 s stall).
- Risk: low. Touches the callers' timing, not the block work. Needs a "preparing" state the party can wait in.

### B. Slice the work across ticks (the real fix for stalls)
Turn `prepare` and `release` into jobs with a per-tick time budget (for example 3 ms):
- **reset**: clear a few chunks per tick (the scan is already per chunk, and sections that hold only air are skipped).
- **paste**: `StructureTemplate.placeInWorld` honours `StructurePlaceSettings.setBoundingBox`, so the template can be pasted one chunk-sized box at a time (49 clipped pastes instead of one). Entities are already ignored.
- **chunk loading**: do not call the blocking `getChunk`. Add the ticket, then poll `ServerChunkCache.getChunkNow` (or `getChunkFuture`) each tick and continue when the chunk is full.
- A job state machine per cell: `HOLD -> WAIT_CHUNKS -> RESET -> PASTE -> VALIDATE -> READY`, and `SWEEP -> VERIFY -> DROP -> FREE` for release. A global queue drains by a time budget each tick.
- Callers that expect a cell *now* (`RunTransitionService`, lobby start) need a "cell not ready yet" result: the run waits in a `PREPARING` state and the party sees a progress message. The warm pool hides most of the latency (ready cells are taken instantly), so the common case is unchanged.
- Risk: **high**. It touches run recovery (a crash mid-job must leave the cell quarantined or reset, never half-built), the leasing index, and everything that assumes `prepare` returns a finished floor. Needs the soak and crash tests re-run.

### C. Do less work (cheap wins that combine with A or B)
1. **Skip the redundant reset.** `prepare` always runs `reset` first "so the paste is independent of history". After a verified release the cell is already clean. Track a
   `CLEAN` bit per cell in memory (set by a verified release, cleared on allocate); `prepare` skips `reset` for a clean cell. After a restart all cells are unknown, so the first use of
   each resets once. Saves a full 49-chunk scan per prepare.
2. **Do not drop the tickets immediately on release.** Keep a released cell's chunks loaded for a short grace period (for example 30 s) so a quick re-allocation, or the reset of the
   next prepare, does not have to reload chunks that are still being saved. Smooths the unload/save/reload churn that causes the blocking waits. Costs memory for the grace period only.
3. **Reuse an untouched cell for the same layout.** If an arena's blocks are never changed during a run (players cannot break blocks; entities are swept separately), a released cell
   whose structure matches the next request could skip reset and paste entirely. Needs proof that nothing changes blocks (a block-change counter per cell would give that).
   Highest payoff, but only safe once that is proven.
4. **Top the warm pool up away from run starts**, not in the same tick as an allocation.
5. **Cap burst size**: `soak_test` already paces itself (`--pause 2`); give the server the same guard (a hard limit of heavy jobs per second).

## Recommendation

1. **Now (low risk):** C1 (skip redundant reset), C2 (release grace period), C4, and A (admission control with a visible "preparing" wait). These remove the watchdog collapse and
   roughly halve the work, without changing how a cell is built.
2. **Then:** B for reset and chunk loading (the part that blocks on I/O), keeping the paste synchronous at first. Slicing the paste (per-chunk bounding box) can follow once the
   state machine is trusted.
3. **Later, only with proof:** C3.

Measure before and after with `FrameSampler`-style server numbers: `TowerMetrics.recordAllocation` already times prepares and releases; extend it to record the longest single-tick
stall and the queue depth, and run `soak_test.py` with `--pause 0` as the burst case and `--pause 2` as the ordinary case, watching MSPT.

## Decisions for the owner

- Is "your arena is being prepared" (a short wait under load) acceptable, in exchange for no lag spikes?
- How many simultaneous runs does a target server realistically see at peak? That sets the limiter and whether option B is needed for the first release or can follow it.
- Should a released cell keep its chunks loaded for a grace period (memory for smoothness)?

## Test plan

- Unit tests (no server): the limiter and queue ordering; the `CLEAN` bit transitions (verified release sets it, allocate clears it, restart treats every cell as unknown).
- Rig: `bot_stress_test.py` and `soak_test.py` (both `--pause 0` and `--pause 2`) with MSPT recorded; the cell-reset and quarantine smoke tests; a kill-and-restart during a prepare and during a release (the cell must come back reset or quarantined).
- Live: two bots start and end runs back to back while a third watches the tick time.

## As built: the warm pool build, a slice per tick (2026-10-08)

`SlicedBuild` does what `CellPreparer.prepare` does, in steps: reset per chunk when the cell is not known clean, then one paste per chunk the structure reaches
(`StructurePlaceSettings.setBoundingBox`), each step skipping a chunk that is not loaded yet (`getChunkNow`, never a blocking load; 60 s then it gives up and releases).
`CellWarmPool.advance` gives the build in progress one step-or-more inside a 4 ms budget every tick; `tick` (once a second) only starts the next build, one at a time.

Measured on the rig (`soak_test --pause 2`, 18 builds): the longest single step averaged 60 ms (max 84 ms), against a 343 ms mean (up to 1.0 s) for the old
synchronous paste. The total work is higher (about 580 ms against 343 ms) because every `placeInWorld` call walks the whole template, so splitting the paste
further (Y bands) made it worse (2.3 s total, steps no shorter) and was dropped. Run-start `prepare` and release `reset` still hold a tick for their full length.

### Release clear, a slice per tick (2026-10-08)

`CellClearJobs` clears a finished run's cell chunk by chunk (10 ms budget a tick) before the release: `RunTransitionService.releaseCell` starts a job when the cell is not
known clean and returns; the once-a-second exit sweep comes back, finds the cell clean, and the unchanged synchronous release then has nothing left to clear (it takes no
HeavyWork slot). A burst (`soak_test --pause 0`, peak 66 cells held) now queues instead of stalling. Cost: a cell is released about a second later than before, and a burst holds
its chunks loaded until each cell's turn. A server stopped mid-clear leaves the run holding its cell, and the exit sweep releases it after the restart as it does for any deferred release.
`floor_encounter_test` failed one check ("the opponent is marked uncatchable: No entity was found") in 1 of 5 runs and passed on rerun; it looks like a timing race in the test.

### Start-up pre-warm and an honest allocation metric (2026-11-12)

Live data from 12 Nov (`cobbletowers_diagnostics.dat`, AscensionLib's `server.tick` telemetry) showed two things:
- The `allocation` category averaged 1.1 s over 658 samples, but the warm pool recorded its sliced builds' *total* work there, spread over many ticks. That read as a
  one-second stall when no tick was held that long. It now records the build's longest step, so `allocation` means the longest single hold, which is what its budget checks.
- Each shipped tower is one building for all its floors, so the in-place floor rebuild only runs on a detour into another region's building. The common synchronous paste was
  the run start after a restart: the pool only learned what to build from a run that had already started. `CellWarmPool.prewarm` now wants every open tower's first floor at
  start-up, so the sliced build has those cells ready before the first run.

### Run start and floor change, a slice per tick (2026-11-12, owner approved a short wait)

`RunBuildJobs` builds the floors a party waits on with the same `SlicedBuild` the warm pool uses, one job a tick, ahead of the warm pool (which steps aside
while any party waits).

- **Run start, empty pool:** `RunLifecycle.beginAllocation` takes a warm cell when there is one, as before; otherwise it leases a cell to the run, starts a job
  and returns `Building`. The run stays at `ALLOCATING_INSTANCE`; the lobby says "Your arena is being prepared..." and `LobbyLaunch.tickPending` finishes the
  launch through `pollAllocation` (same INSTANCE_ALLOCATED checkpoint, same failure handling). The lobby cannot launch twice while it waits.
- **Floor change into another building (a detour):** the party stands in its own cell, so that cell cannot be rebuilt a slice at a time. The next building is
  built in a separate cell under the run's next-floor tenant (taken from the warm pool when one is ready), started about a second after the route is
  settled. `openNextFloor` returns `PREPARING` until it is up (the intermission round waits and asks again each second, "The next arena is being
  prepared..."); then the run moves: the old cell is swept of entities as the in-place rebuild's sweep did, the leases swap, the run is checkpointed with
  its new cell, spectators are moved (fighters are moved by the floor's start), and the old cell is retired (cleared in slices, swept, released).
- **Fallbacks:** no free cell, a structure that is not loaded, or a separate build that fails falls back to the old in-place synchronous rebuild, so a run is
  never worse off than before. `/tower runs allocate` (operator) stays synchronous.
- **Ending and restarts:** a run that ends gives back any cell still being built for it (`RunBuildJobs.abandon` from `releaseInstance`). Jobs live in memory:
  a restart parks every live run as before, and a lease never written to a run is free again (dirty, so its next build resets it).

Rig results (2026-11-12, a copy of the integration rig on the C: SSD, dev jars of `main` e379c8c and of this branch, run side by side):

| Test | main | this branch |
|---|---|---|
| `lobby_test` (keys off) | 12/12 | 12/12 |
| `route_arena_test` | no detour met before the rig's Tower Key ran out | **12/12**: a detour built in its own cell (longest step 173 ms), the run moved cell 2 -> 3 and back |
| `sliced_build_test` (new; keys off) | n/a | **9/9**: sliced start (longest step 81 to 250 ms), a hard kill mid-build parks the run, no quarantine, the cell is reused |
| `cell_reset_test` | 6/6 | 6/6 |
| `soak_test --pause 2` | 6/6 | 5/6 before the baseline check allowed the pre-warm's chunks; same cycle count (92 vs 90) and end state |
| `soak_test --pause 0` | **watchdog crash** | **same watchdog crash** |
| `instance_lifecycle_test` | (not rerun) | 14/14 |
| `floor_encounter_test` | 11/16 | 11/16, the same five checks (no battle starts: the test predates the boss-only towers) |

The `--pause 0` crash is the same on both builds: `RunsCommand.allocate` -> `allocateInstance` -> `CellPreparer.prepare` -> `reset` -> a blocking
`getChunk`, the collapse under bursts described at the top. The operator command stays synchronous and is what the soak drives; player starts go
through `beginAllocation` and never call `prepare`. Not covered: a kill during a detour build, and a detour with a spectator.
