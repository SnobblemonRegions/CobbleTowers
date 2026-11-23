# CobbleTowers

CobbleTowers is a Fabric 1.21.1 / Cobblemon 1.8.2 Battle Tower framework designed for private solo or party tower runs and integration with CobbleRaids bosses.

## Project constraints

- Java 21
- Fabric / Minecraft 1.21.1
- Mojang mappings
- Cobblemon 1.8.2
- 1–4 players per run
- 10-floor standard mode; architecture must permit a future infinite mode
- One boss per floor
- One shared Battle Tower dimension with isolated private run instances
- Event-driven state; no global per-tick entity scans
- Tower rewards are independent of CobbleRaids rewards
- Claimed rewards survive run failure
- Challenge cards affect the next boss; every fifth accepted challenge also creates a permanent modifier stack for later bosses
- No automatic party healing outside explicit buffs
- Ender pearls and external teleportation are blocked during an active run
- `/tower leave` exits after a five-second delay

## Engineering rules

CobbleTowers must depend on stable public integration contracts, never CobbleRaids internal implementation packages. APIs are kept intentionally small and are added only when verified against the actual upstream implementation.

Major implementation phases are not considered complete merely because they compile. Each phase is validated against source/API behavior, then dedicated-server behavior, with multiplayer and performance testing added as soon as executable systems exist.

Code should favor simple indexed state, explicit lifecycle ownership, deterministic behavior where useful for debugging, and explicit cleanup over broad polling or world/entity scans.

## Status

**P4 (chunk lifecycle, arenas and anchors).** A run is given a cell, the cell's arena is pasted from
a real structure, its chunks are held by a region ticket for exactly as long as the run needs them,
and ending the run clears the arena and lets the chunks go. Cells that cannot be verified clean are
quarantined. Before it: P3 gave a run a cell in a controlled dimension, P2 made a run durable and
recoverable, P1 fixed the contracts. Every phase has a design document under `docs/design/`.

The arenas are WorldEdit schematics converted to vanilla structure NBT by
`validation/schem_to_structure.py`; the sources are committed under `validation/schematics/` and CI
checks the committed structures still match them block for block.

CobbleRaids is used only through its public encounter API (`com.cobbleraids.api.encounter`,
CobbleRaids 0.8.94 and later), which a bytecode check enforces.

Not built yet: floor logic, encounters beyond the dev-only spike, rewards and any GUI. A run can be
created, given a built floor and moved through its states by command, but nothing plays out in it
yet.

## Building

CobbleTowers compiles against CobbleRaids from Maven Local. In a SnobblemonRaids checkout at the
version named by `cobbleraids_version` in `gradle.properties`:

```sh
./gradlew publishToMavenLocal
```

Then, here:

```sh
bash validation/hooks/install.sh   # once per clone: runs local CI before every push
bash validation/ci_local.sh        # build, test, and check the CobbleRaids API boundary
```

The pre-push hook is the first gate; `.github/workflows/build.yml` runs the same sequence on
GitHub for every push to main and every pull request. Run the hook anyway -- it is faster than a
push, and its first step (no source file is hidden from git by a .gitignore pattern) is one CI
cannot perform, because CI builds a clone where an ignored file does not exist.

## Releases

A version bump in build.gradle needs a matching newest entry in src/main/resources/cobbletowers/CHANGELOG.md. It ships in the jar at cobbletowers/CHANGELOG.md and is shown to players in game by Snobblemon Server, so write it for players (what they will notice, no config keys or class names) and keep to its four line kinds: the '# CobbleTowers changelog' title, '## <version> (YYYY-MM-DD)', '### New', '### Changed' or '### Fixed', and '- ' bullets. validation/validate_changelog.py enforces this. Call it the Player Changelog, not patch notes or release notes.
