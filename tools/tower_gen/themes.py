"""Tower themes (P59): every room the generator builds is made from roles, and a theme maps each role to a block.

A new tower is a new dict here, not new geometry. The generator bakes the blocks into the structure files, so the mod itself never reads a theme.
"""

ROLES = ("wall", "wall_low", "pier", "trim", "vault", "rib", "floor", "inlay", "accent", "hanging", "light", "glass", "wood", "rail")
OPTIONAL_ROLES = ("column",)   # falls back to `pier`; see tower_gen.column()

# Neutral's identity lives in this palette. Every room is built only from these roles, so changing a value re-skins the whole tower (and the
# concept pictures in tools/neutral_tower/concept.py, which read the same table). White quartz with a red base and polished andesite.
# `rail` must stay a wall-type block (the gallery rail is 1.5 high so nobody climbs it).
NEUTRAL = {
    "wall": "minecraft:quartz_bricks",                # main walls
    "wall_low": "minecraft:polished_andesite",        # plinth, the bottom two rows of every wall
    "pier": "minecraft:quartz_pillar",                # columns
    "trim": "minecraft:chiseled_quartz_block",        # capitals, door frames, cornices
    "vault": "minecraft:quartz_bricks",               # ceiling surface
    "rib": "minecraft:polished_andesite",             # ceiling ribs, gallery decks
    "floor": "minecraft:polished_andesite",           # field of the floor
    "inlay": "minecraft:red_terracotta",              # floor inlay, runners
    "accent": "minecraft:gold_block",                 # keystones, medallion centre
    "hanging": "minecraft:red_wool",                  # banners and the stand awning
    "light": "minecraft:pearlescent_froglight",       # light coves, chandeliers, backlit glass
    "glass": "minecraft:white_stained_glass",         # the glass in front of backlit panels
    "wood": "minecraft:spruce_planks",                # the vendor stand
    "rail": "minecraft:polished_andesite_wall",       # gallery rails
}

# Tideforge (doctrine: Momentum; Kyogre, Vaporeon, Blastoise, Golisopod, Empoleon). Today's battle_tower_tideforge is mostly prismarine
# bricks, white and blue concrete, sea lanterns and cyan wool. The structural roles below are drafted; the identity roles are left open.
# Rules: wall/pier/vault/floor must be full, opaque blocks (anything named *_slab, *_stairs, *_wall or *glass counts as see-through to the
# generator); nothing matching stray_check.FOREIGN (so no warped_* blocks, even though the old tower used them); `rail` must be a *_wall block.
TIDEFORGE = {
    "wall": "minecraft:prismarine_bricks",            # main walls
    "pier": "minecraft:quartz_pillar",                # wall piers and statues in the stop rooms (they stand on open floor: must be solid)
    "column": "minecraft:water",                      # free-standing hall columns: a water core, cased in `glass` wherever it meets air
    "inlay": "minecraft:lapis_block",                 # floor inlay, runners
    "accent": "minecraft:diamond_block",              # keystones, medallion centre
    "light": "minecraft:sea_lantern",                 # light coves, chandeliers, backlit glass
    "wall_low": "minecraft:dark_prismarine",          # plinth, the bottom two rows of every wall
    "trim": "minecraft:polished_diorite",             # capitals, door frames, cornices
    "vault": "minecraft:prismarine_bricks",           # ceiling surface
    "rib": "minecraft:packed_ice",                    # ceiling ribs, gallery decks (plain ice melts beside sea lanterns and drips)
    "floor": "minecraft:polished_diorite",            # field of the floor
    "hanging": "minecraft:cyan_wool",                 # banners and the stand awning
    "glass": "minecraft:light_blue_stained_glass",    # the glass in front of backlit panels
    "wood": "minecraft:stripped_birch_wood",          # the vendor stand (driftwood)
    "rail": "minecraft:prismarine_wall",              # gallery rails
}

THEMES = {"neutral": NEUTRAL, "tideforge": TIDEFORGE}
