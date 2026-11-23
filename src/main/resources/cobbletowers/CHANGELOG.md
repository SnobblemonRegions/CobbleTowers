# CobbleTowers changelog

## 1.0.14+fabric-1.21.1 (2026-11-23)

### New
- Route map: /cobbletowers route opens the tower's branching map as a book, and a Route Map item sits in your hotbar during a run. /cobbletowers play route opens the wide route map screen at any point of a run.
- Threat bands on tower runs, with a Threat picker in the lobby so your party chooses how hard the climb is.
- Mystery Bag: a tradable item you right-click to open. Two bags in three hold dust and the good materials are very rare. The prize is paid as vouchers. The limit of 2 bags is per player, not shared by the team.
- Players with the Snobblemon client now see the tower screens directly in the client.
- Hollow's Eve event tower: a walkable 10-floor haunted building with route doors behind pedestals that open on the party's vote, Grizelda the Goblin Merchant, the Trick or Treat vault, haunted rare Pokemon and a 20-level mastery track. The event window has ended.

### Changed
- Towers are now boss-only. Waves and basic battles are gone, and boss rush is the default for every tower.
- Raid Points from towers now count against CobbleRaids' daily tower cap of 150. A boss kill pays 2 to 5 Raid Points instead of 5 to 10, so a clean ten-floor run pays about 42 instead of about 51.
- Nobody can break or place blocks, or use buckets, spawn eggs, boats and similar items on the world, inside the tower. Pedestals, vendors, chests and doors still work, and an operator in creative mode is exempt.
- Tower and Rental Keys are now taken just before floor 1 opens. If a key was moved away while the arena was being prepared the run does not start, and a floor that fails to open gives the key back.
- A cash-out that took at least half the tower counts as a completion for the daily loot budget.
- Closing a parked run now pays out the floors you cleared since the last payout, without a risk bonus.
- Arenas are prepared a slice at a time, so starting a run or changing buildings no longer stalls the server. The lobby says Your arena is being prepared while you wait.

### Fixed
- Players no longer fall through a freshly built floor on arrival. The arrival spot is checked before anyone is sent, and for 15 seconds afterwards anyone who has dropped far below it is put back.
- The server's world border no longer kills players arriving in the tower, and clicks on pedestals and the vendor register from far out in the tower dimension.
- After a vendor purchase the intermission menu no longer opens behind the shop.
- The room behind a chosen lane is no longer overwritten, and a relic or lock-in floor no longer opens onto a bare dark shell.
- A second click on one pedestal no longer votes to leave a vault shut.
- The co-op boss roster is repaired after a participant leaves, and camera obstructions in tower battles are fixed.
- A player who withdraws from a shared boss fight no longer keeps the Ascension scouting panel.
