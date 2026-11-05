# Threat, boss rush and the modifier pass (balance phase 2)

CobbleTowers' half of the Threat balance (AscensionLib's half is its `docs/BALANCE-THREAT.md`). Everything Threat-specific
switches on with AscensionLib's `threat.enabled`; with it off, towers fight and pay as before. The boss-only rule and the
modifier pass below apply whatever the Threat setting is.

## Threat at the door

- The lobby offers Threat 0-VI (`/tower threat <0-6>` or the lobby picker). 0 means the legacy scouting tiers.
- A fight's band is entry + Ascension cycle (+1 for the champion), capped at VII. VII is reached only through cycles.
- The old Ascension growth (extra opponents, boss health, enemy EVs) starts only past VII; the loot factor still counts every cycle.
- The door shows AscensionLib's recommendation from the party's mean rarity. It is advice, never a lock.

## Boss only

Towers have no ordinary battles. Every floor is its boss: a milestone floor's own boss, or one drawn from the floor's
`boss_pool`. `"boss_rush"` in a tower definition defaults to true; the opponent waves, the encounter pools, the enemy
`level_offset` and `extra_opponents` effects and the `encounter` modifier type no longer exist. An Ascension's extra
opponents are an enrage on the boss: one is an enrage every 5 turns, two every 4, three every 3.

Echo Duels and the Trick or Treat vault mimic are exhibition fights built from the floor's boss draw: the boss's level
and species stand in for one Pokemon (an Echo swaps in its own Pokemon, the mimic wears its Mimikyu costume).

## Modifier pass

| Rule | Value |
| --- | --- |
| Relic damage cap | together the party's damage rules deal at most x1.35 and take at least x0.80 (`cap` op) |
| Reward percent | drafted percentages add (+20% and +50% is +70%), capped at x3; Raid Points are never multiplied |
| Clamped levels | each boss level the level cap swallows comes back as +3% boss health |
| Completion score | under Threat: risk points (minor 1, moderate 2, severe 3; relics 0) instead of the card count |
| `enrage` op | `{"op":"enrage","side":"foe","every":N}`: +1 Atk and SpA every N turns |

Changed cards: War Banner x1.10 damage; Iron Hide x0.92 taken; Berserker's Mask x1.25 damage, 80% HP; Glass Cannon is a
relic, x1.40 damage, 60% HP; typed charms x1.15; Reckless Banner x1.20 dealt, x1.12 taken; Offense Set (4) x1.12;
Fragile Champion is a relic, boss 85% HP; Sharpened Claws boss +2 levels; Seasoned Ranks boss +3; Double Trouble boss
+5; Crowded Floor, Mob Rule, Gauntlet and Gambler's Coin enrage the boss every 4, 2, 5 and 4 turns; Golden Idol and
Giant Slayer move their levels to the boss; Veteran's Medal boss -2; No Setup is severe.

Not yet: Seasoned Ranks' and Double Trouble's extra rank credits and Unique need an AscensionLib hook; until then they
are boss levels only.

## Testing

- `./gradlew build` (JUnit: `ThreatPolicyTest`, `BossRushEnrageTest`, `ModifierEffectsTest`, `CustomEffectsTest`).
- `python3 validation/validate_definitions.py`.
- Battle ops against the real simulator: `node validation/showdown/tower_fx_test.js --showdown-dir <rig>/showdown`, or
  from a folder with the `pokemon-showdown` npm package, `node validation/showdown/balance_upstream_test.js`.
