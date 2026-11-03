#!/usr/bin/env python3
"""The shipped tower datapack must parse and resolve.

The Java registry already skips a malformed file at runtime, on purpose: one bad JSON in a
third-party datapack must not stop a server booting. That safety net would also hide a mistake in
the content this mod itself ships, where a dangling floor id means a tower nobody can play. So this
checks the bundled data at build time, where failing is exactly what should happen:

- every definition file parses, and has the fields its kind requires
- every reference resolves: tower -> ruleset, tower -> reward table, tower -> floors,
  floor -> encounter pool, floor -> ruleset override, tower -> milestones
- floors are numbered 1..n in listed order
- a milestone lands on a floor marked for it, of the same kind
- weights are positive, level bounds are ordered, party size is 1..6, reward amounts are ordered
- every floor layout names a structure that exists, and every anchor in it is somewhere a player
  could actually stand: inside the structure, feet on something solid, head clear
- every floor names a boss: a loaded boss pool, or a milestone that names a raid definition

What is deliberately NOT checked: whether CobbleRaids has a raid definition by that id, or whether a
reward table's item ids actually exist. Those live behind registries this script (and the Java
registry that loads content) has no access to outside a running game -- CobbleRaids' behind an API
with no listing, vanilla's item registry behind a bootstrap this project's unit tests never run. A
missing definition or item fails at grant/start time instead, reported as a technical fault.

    python validation/validate_definitions.py
"""

from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from schem_to_structure import Reader  # noqa: E402  -- same directory, shared NBT reader

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "src" / "main" / "resources" / "data"
STRUCTURES = DATA / "cobbletowers" / "structure"
ANCHORS = ("entry", "presentation", "spectator", "exit")
# Present in the structure but nothing to stand on. Not exhaustive -- it covers what the arenas
# actually contain, and anything else present counts as solid, which errs towards refusing an anchor
# rather than approving one.
NON_SUPPORTING = ("banner", "torch", "carpet", "button", "pressure_plate", "sign", "rail", "pane", "_wall", "fence", "trapdoor",
                  "door", "end_rod", "chain", "leaves", "lantern", "head")


def supports(name: str, properties: dict) -> bool:
    """Whether a player could stand on top of this block (the runtime asks isFaceSturdy(UP))."""
    if name == "minecraft:sea_lantern":
        return True
    if name.endswith("_slab"):
        return properties.get("type") in ("top", "double")
    if name.endswith("_stairs"):
        return properties.get("half") == "top"
    return not any(weak in name for weak in NON_SUPPORTING)
KINDS = ("towers", "floors", "rulesets", "milestones", "boss_pools", "modifiers",
         "reward_tables", "vendor_services", "scouting_profiles", "regional_themes", "achievements", "playlists", "trial_pools", "contract_templates", "seasons")
MAX_PARTY = 6
MIN_LEVEL, MAX_LEVEL = 1, 100


def load(namespace_dir: Path, kind: str) -> dict[str, dict]:
    """Every definition of one kind, keyed by its id, as the registry keys them."""
    found: dict[str, dict] = {}
    base = namespace_dir / "cobbletowers" / kind
    if not base.is_dir():
        return found
    for path in sorted(base.rglob("*.json")):
        ident = f"{namespace_dir.name}:{path.relative_to(base).with_suffix('').as_posix()}"
        found[ident] = json.loads(path.read_text(encoding="utf-8"))
    return found


def read_structure(name: str):
    """A committed structure as (size, occupied positions, supporting positions)."""
    path = STRUCTURES / f"{name}.nbt"
    if not path.is_file():
        return None
    raw = path.read_bytes()
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    _, root = Reader(data).root()
    palette = [entry["Name"] for entry in root["palette"]]
    palette_props = [entry.get("Properties", {}) for entry in root["palette"]]
    occupied, supporting = set(), set()
    for block in root["blocks"]:
        position = tuple(block["pos"])
        if palette[block["state"]] == "minecraft:light":
            continue   # invisible and without collision: neither something to stand on nor something in the way
        occupied.add(position)
        if supports(palette[block["state"]], palette_props[block["state"]]):
            supporting.add(position)
    return tuple(root["size"]), occupied, supporting


def check_layouts(content: dict, problems: list[str]) -> int:
    """Anchors are declared by hand, so they are checked against what was actually built.

    The schematics carry no marker blocks, so nothing derives these positions -- which makes an
    anchor inside a wall, or over a hole, an ordinary typo. Catching it here means it fails on the
    build that introduced it, rather than as a player standing inside a pillar.
    """
    checked = 0
    for floor_id, floor in content["floors"].items():
        layout = floor.get("layout")
        if layout is None:
            continue
        structure = layout.get("structure", "")
        if ":" not in structure:
            problems.append(f"{floor_id} names structure '{structure}', which is not a valid id")
            continue
        namespace, _, name = structure.partition(":")
        if namespace != "cobbletowers":
            continue  # somebody else's structure; not ours to verify
        loaded = read_structure(name)
        if loaded is None:
            problems.append(f"{floor_id} names structure {structure}, which is not committed"
                            f" (expected {STRUCTURES.name}/{name}.nbt)")
            continue
        size, occupied, supporting = loaded
        for anchor_name in ANCHORS:
            anchor = layout.get(anchor_name)
            if anchor is None:
                problems.append(f"{floor_id} layout has no {anchor_name} anchor")
                continue
            x, y, z = anchor.get("x", 0), anchor.get("y", 0), anchor.get("z", 0)
            where = f"{floor_id} {anchor_name} ({x},{y},{z})"
            if not (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]):
                problems.append(f"{where} is outside the structure, which is {size[0]}x{size[1]}x{size[2]}")
                continue
            if (x, y - 1, z) not in supporting:
                problems.append(f"{where} has nothing solid to stand on")
            if (x, y, z) in occupied:
                problems.append(f"{where} is inside a block")
            if (x, y + 1, z) in occupied:
                problems.append(f"{where} has no headroom")
            checked += 1
        check_gates(floor_id, layout, size, occupied, problems)
        check_doors(floor_id, layout, size, occupied, supporting, problems)
    return checked


def check_doors(floor_id: str, layout: dict, size, occupied: set, supporting: set, problems: list[str]) -> None:
    """Each route door is a shut gate of bars with a lintel above it and a pedestal spot in front of it; at most three, left to right."""
    doors = layout.get("doors")
    if doors is None:
        return
    if not isinstance(doors, list) or len(doors) > 3:
        problems.append(f"{floor_id} doors must be a list of at most three")
        return
    for i, door in enumerate(doors):
        where = f"{floor_id} door {i}"
        for part in ("open", "frame"):
            box = door.get(part)
            if box is None:
                problems.append(f"{where} has no {part} box")
                continue
            low, high = box.get("min"), box.get("max")
            if not (isinstance(low, list) and isinstance(high, list) and len(low) == 3 and len(high) == 3) or any(a > b for a, b in zip(low, high)):
                problems.append(f"{where} {part} is not a box")
                continue
            if any(a < 0 for a in low) or any(b >= limit for b, limit in zip(high, size)):
                problems.append(f"{where} {part} is outside the structure")
                continue
            missing = [(x, y, z) for x in range(low[0], high[0] + 1) for y in range(low[1], high[1] + 1)
                       for z in range(low[2], high[2] + 1) if (x, y, z) not in occupied]
            if missing:
                problems.append(f"{where} {part} is not solid as built: {len(missing)} of its blocks are air, for example {missing[0]}")
        pedestal = door.get("pedestal")
        if pedestal is None:
            problems.append(f"{where} has no pedestal anchor")
            continue
        x, y, z = pedestal.get("x", 0), pedestal.get("y", 0), pedestal.get("z", 0)
        if (x, y - 1, z) not in supporting or (x, y, z) in occupied or (x, y + 1, z) in occupied:
            problems.append(f"{where} pedestal ({x},{y},{z}) is not somewhere to stand")
    xs = [d.get("pedestal", {}).get("x", 0) for d in doors]
    if xs != sorted(xs):
        problems.append(f"{floor_id} doors are not listed left to right (west to east): pedestal x {xs}")


def check_gates(floor_id: str, layout: dict, size, occupied: set, problems: list[str]) -> None:
    """A gate is a box of blocks that a tower clears when it opens: it must lie inside the structure and be shut (all blocks) as built."""
    for gate_name in ("gate_a", "gate_b"):
        gate = layout.get(gate_name)
        if gate is None:
            continue
        low, high = gate.get("min"), gate.get("max")
        where = f"{floor_id} {gate_name}"
        if not (isinstance(low, list) and isinstance(high, list) and len(low) == 3 and len(high) == 3):
            problems.append(f"{where} needs min and max corners, each [x, y, z]")
            continue
        if any(a > b for a, b in zip(low, high)):
            problems.append(f"{where} has a min corner above its max corner: {low} {high}")
            continue
        if any(a < 0 for a in low) or any(b >= limit for b, limit in zip(high, size)):
            problems.append(f"{where} {low}..{high} is outside the structure, which is {size[0]}x{size[1]}x{size[2]}")
            continue
        open_cells = [(x, y, z) for x in range(low[0], high[0] + 1) for y in range(low[1], high[1] + 1)
                      for z in range(low[2], high[2] + 1) if (x, y, z) not in occupied]
        if open_cells:
            problems.append(f"{where} is not shut as built: {len(open_cells)} of its blocks are air, for example {open_cells[0]}")


def check_modifiers(content: dict, problems: list[str]) -> None:
    """Modifier references and the contradictions a machine notices (TDS #58).

    The one-file mistakes -- excluding itself, requiring what it excludes -- are refused by
    ModifierDefinition's own constructor, so a malformed file never loads. These are the checks
    that need the whole loaded set, which is the part Java cannot do until a server is running.
    """
    effect_fields_by_type = {
        "enemy": ("boss_level_offset", "boss_health_percent"),
        "player_constraint": ("banned_moves", "allow_switching", "allow_items"),
        "field": ("weather", "terrain"),
        "reward": ("reward_percent",),
        "scouting": ("scouting_bonus",),
        "custom": ("custom", "battle"),
    }
    battle_ops = ("weather", "terrain", "boost", "evs", "hp", "status", "sidecondition", "suppress_items", "drain",
                  "damage", "resist", "enrage", "cap")
    known_customs = ("glass_cannon", "field_hospital", "fortunes_wheel", "black_market",
                     "swift_start", "iron_hide", "war_banner")
    for modifier_id, modifier in content["modifiers"].items():
        for field in ("schema_version", "type", "display_name"):
            if field not in modifier:
                problems.append(f"{modifier_id} is missing required field '{field}'")

        kind = modifier.get("type")
        if kind is not None and kind not in effect_fields_by_type:
            problems.append(f"{modifier_id} has unknown type '{kind}'")

        excludes = modifier.get("excludes", [])
        requires = modifier.get("requires", [])
        for other in excludes:
            if other not in content["modifiers"]:
                problems.append(f"{modifier_id} excludes {other}, which does not exist")
        for other in requires:
            if other not in content["modifiers"]:
                problems.append(f"{modifier_id} requires {other}, which does not exist")
        if modifier_id in excludes:
            problems.append(f"{modifier_id} excludes itself")
        if modifier_id in requires:
            problems.append(f"{modifier_id} requires itself, which nothing could ever satisfy")
        for other in requires:
            if other in excludes:
                problems.append(f"{modifier_id} both requires and excludes {other}")

        if modifier.get("stack_limit", 1) < 1:
            problems.append(f"{modifier_id} has stack_limit {modifier.get('stack_limit')}; it must be >= 1")
        if modifier.get("weight", 100) < 1:
            problems.append(f"{modifier_id} has weight {modifier.get('weight')}; weights must be >= 1")

        custom = modifier.get("effect", {}).get("custom")
        if custom is not None and custom not in known_customs:
            problems.append(f"{modifier_id} names custom behavior '{custom}', which is not coded "
                            f"(known: {', '.join(known_customs)})")

        for op in modifier.get("effect", {}).get("battle", []):
            if not isinstance(op, dict) or op.get("op") not in battle_ops:
                problems.append(f"{modifier_id} has a battle operation the game does not know: {op}")
        bonus = modifier.get("tag_bonus")
        if bonus is not None:
            if modifier.get("relic"):
                problems.append(f"{modifier_id} is both a relic and a tag bonus")
            if not bonus.get("tag") or not 2 <= bonus.get("count", 0) <= 6:
                problems.append(f"{modifier_id} has a tag_bonus that needs a tag and a count of 2..6")

        # The mistake content actually makes: copy a modifier, change its type, keep the payload.
        effect = modifier.get("effect", {})
        if kind in effect_fields_by_type and not any(f in effect for f in effect_fields_by_type[kind]):
            problems.append(
                f"{modifier_id} is type '{kind}' but its effect sets none of "
                f"{', '.join(effect_fields_by_type[kind])}")

    # A prerequisite cycle can never be drafted: every member needs another member held first.
    requires_map = {mid: [r for r in m.get("requires", []) if r in content["modifiers"]]
                    for mid, m in content["modifiers"].items()}
    for start in requires_map:
        seen, stack = set(), [start]
        while stack:
            current = stack.pop()
            for nxt in requires_map.get(current, []):
                if nxt == start:
                    problems.append(f"{start} is in a prerequisite cycle, so it can never be drafted")
                    stack = []
                    break
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)


def load_known_items() -> dict:
    """Item ids for the namespaces whose mods we can read, for catching a typo offline (P21)."""
    path = Path(__file__).with_name("known_items.json")
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {namespace: set(ids) for namespace, ids in data.items() if not namespace.startswith("_")}


# Credited, not given: never a registered item, so never checked against one.
RESERVED_CURRENCIES = ("cobbletowers:cobble_dollar", "cobbleraids:raid_points")


def check_item_id(table_id: str, where: str, item: str, known: dict, problems: list[str]) -> None:
    if item in RESERVED_CURRENCIES:
        return
    if ":" not in item:
        problems.append(f"{table_id} {where} names '{item}', which is not a namespaced id")
        return
    namespace, path = item.split(":", 1)
    if namespace in known and path not in known[namespace]:
        problems.append(f"{table_id} {where} names {item}, which is not a {namespace} item;"
                        " a player would lose that drop (a typo?)")


def check_entry(table_id: str, where: str, entry: dict, known: dict, problems: list[str]) -> None:
    if "item" not in entry:
        problems.append(f"{table_id} {where} has an entry with no item")
        return
    check_item_id(table_id, where, entry["item"], known, problems)
    if entry.get("weight", 100) < 1:
        problems.append(f"{table_id} {where} has an entry with weight {entry.get('weight')}; weights must be >= 1")
    low, high = entry.get("min_amount", 1), entry.get("max_amount", 1)
    if not 1 <= low <= high:
        problems.append(f"{table_id} {where} has min_amount/max_amount {low}/{high}; must be >= 1 and ordered")


def check_reward_tables(content: dict, problems: list[str]) -> None:
    """Structure, plus -- for the namespaces known_items.json covers -- that every item id really exists.

    Items from any other namespace (vanilla, a mod we hold no list for) are only checked at runtime, by the
    server's own RewardCatalogCheck at startup.
    """
    tier_keys = ("opponent_defeated", "boss_defeated", "floor_cleared")
    milestone_keys = ("boss", "champion")
    known = load_known_items()
    for table_id, table in content["reward_tables"].items():
        for field in ("schema_version", "display_name"):
            if field not in table:
                problems.append(f"{table_id} is missing required field '{field}'")
        tiers = table.get("tiers", {})
        for key in tiers:
            if key not in tier_keys:
                problems.append(f"{table_id} has an unknown tier '{key}'; must be one of {tier_keys}")
        for key, entries in tiers.items():
            for entry in entries:
                check_entry(table_id, f"tier '{key}'", entry, known, problems)

        for key, milestone in table.get("milestones", {}).items():
            if key not in milestone_keys:
                problems.append(f"{table_id} has an unknown milestone '{key}'; must be one of {milestone_keys}")
                continue
            guaranteed = milestone.get("guaranteed", [])
            for entry in guaranteed:
                if "item" not in entry:
                    problems.append(f"{table_id} milestone '{key}' has a guaranteed entry with no item")
                    continue
                check_item_id(table_id, f"milestone '{key}' guaranteed", entry["item"], known, problems)
                if entry.get("amount", 1) < 1:
                    problems.append(f"{table_id} milestone '{key}' guarantees {entry['item']} x{entry.get('amount')};"
                                    " amounts must be >= 1")
            pool = milestone.get("bonus_pool", [])
            for entry in pool:
                check_entry(table_id, f"milestone '{key}' bonus_pool", entry, known, problems)
            rolls = milestone.get("bonus_rolls", 0)
            if rolls < 0 or (rolls > 0 and not pool):
                problems.append(f"{table_id} milestone '{key}' has bonus_rolls {rolls} and {len(pool)} bonus_pool entries")
            if not guaranteed and rolls == 0:
                problems.append(f"{table_id} milestone '{key}' pays nothing: no guaranteed items and no bonus_rolls")


def check_vendor_services(content: dict, problems: list[str]) -> None:
    """Structural checks only, the same posture as {@code check_reward_tables}."""
    known_effects = ("full_heal", "cure_status")
    for service_id, service in content["vendor_services"].items():
        for field in ("schema_version", "display_name", "price_cobble_dollars", "effect"):
            if field not in service:
                problems.append(f"{service_id} is missing required field '{field}'")
        if service.get("effect") is not None and service.get("effect") not in known_effects:
            problems.append(f"{service_id} has unknown effect '{service.get('effect')}'; must be one of {known_effects}")
        if service.get("price_cobble_dollars", 1) < 1:
            problems.append(f"{service_id} has price_cobble_dollars {service.get('price_cobble_dollars')};"
                            " must be >= 1")
        if service.get("max_purchases_per_run", 0) < 0:
            problems.append(f"{service_id} has max_purchases_per_run {service.get('max_purchases_per_run')};"
                            " must be >= 0")


def check_regional_themes(content: dict, problems: list[str]) -> None:
    """Structural checks only, the same posture as {@code check_reward_tables}.

    Species/aspect legality is not checked here for the same reason `check_encounter_pools`-shaped
    logic never has: Cobblemon's species/aspect registries are not available outside a running game,
    and its aspect property never throws for an unrecognized value anyway.
    """
    for theme_id, theme in content["regional_themes"].items():
        for field in ("schema_version", "display_name", "doctrine"):
            if field not in theme:
                problems.append(f"{theme_id} is missing required field '{field}'")
        signatures = theme.get("jersey_signatures", [])
        if len(signatures) != 5:
            problems.append(f"{theme_id} has {len(signatures)} jersey_signatures; a regional theme needs exactly 5")
        for signature in signatures:
            if "species" not in signature:
                problems.append(f"{theme_id} has a jersey signature with no species")
        if theme.get("jersey_weight_growth_percent_per_floor", 0) < 0:
            problems.append(f"{theme_id} has a negative jersey_weight_growth_percent_per_floor")


def check_achievements(content: dict, problems: list[str]) -> None:
    """Mastery achievements (P31): a condition of a known shape with sensible numbers, and no two with the same name."""
    names = {}
    for achievement_id, achievement in content["achievements"].items():
        for field in ("schema_version", "display_name", "condition"):
            if field not in achievement:
                problems.append(f"{achievement_id} is missing required field '{field}'")
        name = achievement.get("display_name")
        if name in names:
            problems.append(f"{achievement_id} and {names[name]} share the display name '{name}'")
        names[name] = achievement_id
        condition = achievement.get("condition", {})
        kind = condition.get("type")
        if kind == "cycles_cleared":
            if condition.get("count", 0) < 1:
                problems.append(f"{achievement_id} needs a count of at least 1")
        elif kind == "ascension_reached":
            if condition.get("level", 0) < 1:
                problems.append(f"{achievement_id} needs a level of at least 1")
        elif kind == "clear":
            for key in ("min_ascension", "max_seconds", "min_severe", "min_score"):
                if condition.get(key, 0) < 0:
                    problems.append(f"{achievement_id} has a negative {key}")
        else:
            problems.append(f"{achievement_id} has unknown condition type '{kind}'")
    if len(content["achievements"]) > 30:
        problems.append(f"{len(content['achievements'])} achievements are loaded; mastery levels stop at 30")


def check_seasons(content: dict, problems: list[str]) -> None:
    """Seasons (P36a): numbered from 1 without repeats, named, and any spotlight is a loaded regional tower."""
    numbers = {}
    for season_id, season in content["seasons"].items():
        for field in ("schema_version", "number", "name"):
            if field not in season:
                problems.append(f"{season_id} is missing required field '{field}'")
        number = season.get("number")
        if not isinstance(number, int) or number < 1:
            problems.append(f"{season_id} has number {number!r}; it must be a whole number >= 1")
        elif number in numbers:
            problems.append(f"{season_id} and {numbers[number]} are both season {number}")
        else:
            numbers[number] = season_id
        # A season's track finale grants its trim template (P36b): the pattern, the template and the language must all exist.
        if isinstance(number, int) and number >= 1:
            pattern = DATA / "cobbletowers" / "trim_pattern" / f"season_{number}.json"
            if number > 12:
                problems.append(f"{season_id} is season {number}, past the 12 template items registered in code (SeasonTrimItems.MAX_SEASONS)")
            if not pattern.exists():
                problems.append(f"{season_id} has no trim pattern {pattern.name}; run tools/generate_season_trims.py after adding the season")
        spotlight = season.get("spotlight")
        if spotlight is not None:
            tower = content["towers"].get(spotlight)
            if tower is None:
                problems.append(f"{season_id} spotlights {spotlight}, which is not a loaded tower")
            elif not tower.get("regional_theme"):
                problems.append(f"{season_id} spotlights {spotlight}, which is not a regional tower")


def check_playlists(content: dict, problems: list[str]) -> None:
    """Playlists (P32): readable, sensible limits, and every forced modifier exists."""
    names = {}
    for playlist_id, playlist in content["playlists"].items():
        for field in ("schema_version", "display_name"):
            if field not in playlist:
                problems.append(f"{playlist_id} is missing required field '{field}'")
        name = playlist.get("display_name")
        if name in names:
            problems.append(f"{playlist_id} and {names[name]} share the display name '{name}'")
        names[name] = playlist_id
        if not 0 <= playlist.get("enemy_level_max", 0) <= 100:
            problems.append(f"{playlist_id} has enemy_level_max {playlist.get('enemy_level_max')}; it must be 0..100")
        if not 0 <= playlist.get("max_players", 0) <= 4:
            problems.append(f"{playlist_id} has max_players {playlist.get('max_players')}; it must be 0..4")
        party = playlist.get("party", {})
        if party.get("max_party", 0) < 0 or party.get("max_level", 0) < 0:
            problems.append(f"{playlist_id} has a negative party limit")
        for modifier_id in playlist.get("forced_modifiers", []):
            if modifier_id not in content["modifiers"]:
                problems.append(f"{playlist_id} forces modifier {modifier_id}, which does not exist")


def check_trial_pools(content: dict, problems: list[str]) -> None:
    """Trial pools (P32): a known kind, a sensible length, and every entry names a tower, playlist and modifiers that exist."""
    kinds = {}
    for pool_id, pool in content["trial_pools"].items():
        for field in ("schema_version", "display_name", "kind", "floors", "entries"):
            if field not in pool:
                problems.append(f"{pool_id} is missing required field '{field}'")
        if pool.get("kind") not in ("daily", "weekly"):
            problems.append(f"{pool_id} has kind '{pool.get('kind')}'; it must be daily or weekly")
        kinds[pool.get("kind")] = kinds.get(pool.get("kind"), 0) + 1
        if not 1 <= pool.get("floors", 0) <= 10:
            problems.append(f"{pool_id} has floors {pool.get('floors')}; it must be 1..10")
        if not 0 <= pool.get("streak_min_floors", 0) <= pool.get("floors", 0):
            problems.append(f"{pool_id} has streak_min_floors {pool.get('streak_min_floors')} outside 0..floors")
        if not pool.get("entries"):
            problems.append(f"{pool_id} has no entries")
        for index, entry in enumerate(pool.get("entries", [])):
            where = f"{pool_id} entry {index} ({entry.get('label', '?')})"
            if entry.get("tower") not in content["towers"]:
                problems.append(f"{where} names tower {entry.get('tower')}, which does not exist")
            tower = content["towers"].get(entry.get("tower"), {})
            if pool.get("kind") and len(tower.get("floors", [])) and pool.get("floors", 0) > len(tower.get("floors", [])):
                problems.append(f"{where}: the trial is {pool.get('floors')} floors but {entry.get('tower')} has {len(tower.get('floors', []))}")
            if entry.get("playlist") is not None and entry.get("playlist") not in content["playlists"]:
                problems.append(f"{where} names playlist {entry.get('playlist')}, which does not exist")
            for modifier_id in entry.get("modifiers", []):
                if modifier_id not in content["modifiers"]:
                    problems.append(f"{where} names modifier {modifier_id}, which does not exist")
            if not 0 <= entry.get("enemy_level", 0) <= 100:
                problems.append(f"{where} has enemy_level {entry.get('enemy_level')} outside 0..100")
    for kind, count in kinds.items():
        if count > 1:
            problems.append(f"{count} {kind} trial pools are loaded; only the first would be used")


def check_contract_templates(content: dict, problems: list[str]) -> None:
    """Contract templates (P32c): a known period and condition, a count, a bounded reward, and enough of each period to draw from."""
    kinds = ("floors_cleared", "bosses_defeated", "purchases", "severe_drafts", "trials_finished", "daily_trials_finished")
    per_period = {}
    for template_id, template in content["contract_templates"].items():
        for field in ("schema_version", "display_name", "period", "condition"):
            if field not in template:
                problems.append(f"{template_id} is missing required field '{field}'")
        if template.get("period") not in ("daily", "weekly"):
            problems.append(f"{template_id} has period '{template.get('period')}'; it must be daily or weekly")
        per_period[template.get("period")] = per_period.get(template.get("period"), 0) + 1
        condition = template.get("condition", {})
        if condition.get("type") not in kinds:
            problems.append(f"{template_id} has unknown condition type '{condition.get('type')}'")
        if condition.get("count", 0) < 1:
            problems.append(f"{template_id} needs a count of at least 1")
        if not 0 <= template.get("reward", 0) <= 2000:
            problems.append(f"{template_id} has reward {template.get('reward')}; it must be 0..2000")
    if per_period.get("daily", 0) < 3:
        problems.append(f"only {per_period.get('daily', 0)} daily contract templates; a day draws three distinct ones")
    if per_period.get("weekly", 0) < 2:
        problems.append(f"only {per_period.get('weekly', 0)} weekly contract templates; a week draws two distinct ones")


def check_scouting_profiles(content: dict, problems: list[str]) -> None:
    """Structural checks only, the same posture as {@code check_reward_tables}."""
    for profile_id, profile in content["scouting_profiles"].items():
        if "schema_version" not in profile:
            problems.append(f"{profile_id} is missing required field 'schema_version'")
        categories = profile.get("categories", [])
        if not categories:
            problems.append(f"{profile_id} has no categories")
        for category in categories:
            if "name" not in category:
                problems.append(f"{profile_id} has a category with no name")


def main() -> None:
    problems: list[str] = []
    counts = {kind: 0 for kind in KINDS}
    content = {kind: {} for kind in KINDS}

    for namespace_dir in sorted(p for p in DATA.iterdir() if p.is_dir()):
        for kind in KINDS:
            try:
                loaded = load(namespace_dir, kind)
            except json.JSONDecodeError as ex:
                problems.append(f"{namespace_dir.name}/{kind}: invalid JSON: {ex}")
                continue
            counts[kind] += len(loaded)
            content[kind].update(loaded)

    for tower_id, tower in content["towers"].items():
        for field in ("schema_version", "display_name", "ruleset", "reward_table", "floors"):
            if field not in tower:
                problems.append(f"{tower_id} is missing required field '{field}'")
        if tower.get("ruleset") not in content["rulesets"]:
            problems.append(f"{tower_id} names ruleset {tower.get('ruleset')}, which does not exist")
        if tower.get("reward_table") not in content["reward_tables"]:
            problems.append(f"{tower_id} names reward table {tower.get('reward_table')}, which does not exist")
        if "ascension" in tower and not isinstance(tower["ascension"], bool):
            problems.append(f"{tower_id} has ascension {tower['ascension']!r}; it must be true or false")
        if "pedestal_votes" in tower and not isinstance(tower["pedestal_votes"], bool):
            problems.append(f"{tower_id} has pedestal_votes {tower['pedestal_votes']!r}; it must be true or false")
        regional_theme = tower.get("regional_theme")
        if regional_theme is not None and regional_theme not in content["regional_themes"]:
            problems.append(f"{tower_id} names regional theme {regional_theme}, which does not exist")
        floors = tower.get("floors", [])
        if len(set(floors)) != len(floors):
            problems.append(f"{tower_id} lists the same floor twice")
        for position, floor_id in enumerate(floors, start=1):
            floor = content["floors"].get(floor_id)
            if floor is None:
                problems.append(f"{tower_id} names floor {floor_id}, which does not exist")
                continue
            if floor.get("index") != position:
                problems.append(f"{floor_id} has index {floor.get('index')} but is floor {position} of {tower_id}")
            override = floor.get("ruleset_override")
            if override is not None and override not in content["rulesets"]:
                problems.append(f"{floor_id} names ruleset override {override}, which does not exist")
            # Reserved since P1 and carried untouched; P8 is where it finally has to resolve.
            for modifier_id in floor.get("modifiers", []):
                if modifier_id not in content["modifiers"]:
                    problems.append(f"{floor_id} names modifier {modifier_id}, which does not exist")

        by_index = {content["floors"][f].get("index"): f for f in floors if f in content["floors"]}
        for milestone_id in tower.get("milestones", []):
            milestone = content["milestones"].get(milestone_id)
            if milestone is None:
                problems.append(f"{tower_id} names milestone {milestone_id}, which does not exist")
                continue
            floor_id = by_index.get(milestone.get("floor"))
            if floor_id is None:
                problems.append(f"{milestone_id} is for floor {milestone.get('floor')}, which {tower_id} lacks")
                continue
            marked = content["floors"][floor_id].get("milestone")
            if marked is None:
                problems.append(f"{floor_id} is used by {milestone_id} but is not marked as a milestone floor")
            elif marked != milestone.get("kind"):
                problems.append(f"{floor_id} is marked {marked} but {milestone_id} is {milestone.get('kind')}")
            if milestone.get("kind") == "boss" and not milestone.get("raid_definition"):
                problems.append(f"{milestone_id} is a boss milestone but names no raid_definition")

    check_modifiers(content, problems)
    check_reward_tables(content, problems)
    check_vendor_services(content, problems)
    check_scouting_profiles(content, problems)
    check_achievements(content, problems)
    check_playlists(content, problems)
    check_seasons(content, problems)
    check_trial_pools(content, problems)
    check_contract_templates(content, problems)
    check_regional_themes(content, problems)

    for pool_id, pool in content["boss_pools"].items():
        entries = pool.get("entries", [])
        if not entries:
            problems.append(f"{pool_id} has no entries")
        for entry in entries:
            if "definition" not in entry:
                problems.append(f"{pool_id} has an entry with no definition")
            if entry.get("weight", 100) < 1:
                problems.append(f"{pool_id} has an entry with weight {entry.get('weight')}; weights must be >= 1")

    # Every floor has to be finishable: a boss pool, or a milestone that names one.
    for tower_id, tower in content["towers"].items():
        milestone_floors = {}
        for milestone_id in tower.get("milestones", []):
            milestone = content["milestones"].get(milestone_id)
            if milestone:
                milestone_floors[milestone.get("floor")] = milestone.get("raid_definition")
        for floor_id in tower.get("floors", []):
            floor = content["floors"].get(floor_id)
            if floor is None:
                continue
            if floor.get("boss_pool"):
                continue
            if not milestone_floors.get(floor.get("index")):
                problems.append(f"{floor_id} names no boss_pool and no milestone boss, so it cannot be finished")

    for ruleset_id, ruleset in content["rulesets"].items():
        levels = ruleset.get("enemy_level", {})
        low, high = levels.get("min", MIN_LEVEL), levels.get("max", MAX_LEVEL)
        if not MIN_LEVEL <= low <= high <= MAX_LEVEL:
            problems.append(f"{ruleset_id} has enemy levels {low}..{high}; must be {MIN_LEVEL}..{MAX_LEVEL} and ordered")
        size = ruleset.get("registered_party_size", MAX_PARTY)
        if not 1 <= size <= MAX_PARTY:
            problems.append(f"{ruleset_id} has registered_party_size {size}; must be 1..{MAX_PARTY}")

    anchors_checked = check_layouts(content, problems)

    total = sum(counts.values())
    if not total:
        print("Tower definition validation: FAIL -- no definitions found; nothing was checked")
        sys.exit(1)
    if problems:
        print("Tower definition validation: FAIL")
        for problem in problems:
            print("  " + problem)
        sys.exit(1)
    print("Tower definition validation: PASS -- " + ", ".join(f"{counts[kind]} {kind}" for kind in KINDS)
          + f"; every reference resolves, {anchors_checked} anchor(s) stand on solid ground")


if __name__ == "__main__":
    main()
