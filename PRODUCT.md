# Product

<!-- impeccable:product-schema 1 -->

## Platform

Minecraft Java 1.21.1 server mod (Fabric, Cobblemon 1.8.2). Players see it through a vanilla client plus the server resource pack only; there is no client mod.

## Users

Players on the Snobblemon server, in private parties of 1 to 4, running a 10-floor Cobblemon battle tower together. Between fights they walk the floor, vote on pedestals for the next route lane, and walk through the chosen door to the lane's stair.

## Product Purpose

CobbleTowers gives each party a private, walkable tower run: one boss per floor, a route choice between floors, rewards that survive a failed run. Success is a run that feels like a sequence of memorable rooms and clear choices, not a menu.

## Positioning

The route choice is physical. Players stand at pedestals, vote, and a real door in the room opens for the chosen lane, instead of picking from a chat menu or GUI.

## Operating Context

- One shared tower dimension; each run is pasted into its own cell from structure files built by the tower generator (`tools/tower_gen`).
- Every floor's atrium has three route doors in its north wall, 5 wide by 6 high, lit and opened when the party's vote settles.
- Towers: Neutral (first, test case), Duskvale, Rootvale, Tideforge, Poke Tower (test tower), and Hollow's Eve (Halloween, archived; keeps its trick-or-treat door).

## Capabilities and Constraints

- Anything decorative in the world is drawn with Polymer item-display models (Blockbench-style cuboids, 16 px textures) sent in the server pack; collision is invisible barrier blocks.
- Door state must survive a server restart.
- Route lanes have 12 approved route-map icons (`textures/font/route_icons.png`), used in the route book and map wall.
- Undecided: the arena gate door (gate A and gate B), to be designed after the route door.

## Brand Commitments

- Each tower has its own block palette (`tools/tower_gen/themes.py`); a shared part is one base shape with per-tower trim and colour.
- Route doors are solid (no window or grille). Each shut door shows its lane's route-map icon.

## Evidence on Hand

- Halloween trick-or-treat and grand door models: `src/main/resources/assets/cobbletowers/models/halloween/{trick_door,grand_door}`.
- Floor concept pictures: `docs/design/P59/`.

## Product Principles

1. Choices are made with your feet, in the room, not in a menu.
2. Every tower is one shared structure wearing its own palette.
3. Nothing the client needs a mod for.
4. The world's blocks are the truth; a restart never disagrees with what players see.
