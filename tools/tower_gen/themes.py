"""Tower themes (P59): every room the generator builds is made from roles, and a theme maps each role to a block.

A new tower is a new dict here, not new geometry. The generator bakes the blocks into the structure files, so the mod itself never reads a theme.
"""

ROLES = ("wall", "wall_low", "pier", "trim", "vault", "rib", "floor", "inlay", "accent", "hanging", "light", "glass", "wood", "rail")

# TODO(human): Neutral's identity lives in this palette. Every room is built only from these roles, so changing a value re-skins the whole
# tower (and the concept pictures in tools/neutral_tower/concept.py, which read the same table). Today's Neutral is white quartz with a red
# base and polished andesite; keep that, or push it. `rail` must stay a wall-type block (the gallery rail is 1.5 high so nobody climbs it).
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

THEMES = {"neutral": NEUTRAL}
