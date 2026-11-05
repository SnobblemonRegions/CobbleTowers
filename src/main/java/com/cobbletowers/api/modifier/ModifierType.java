package com.cobbletowers.api.modifier;

/**
 * What part of a run a modifier changes (TDS #56). A modifier is applied by whatever owns its type, so the engine
 * never grows a monolithic conditional. PLAYER_CONSTRAINT and FIELD reach Showdown through the CobbleRaids boundary
 * (TDS #48); SCOUTING arrived in P12.
 */
public enum ModifierType {

    /** The boss: its level and health. Towers have no other opponents. */
    ENEMY,

    /** What the party may do in a battle: items, switching, banned moves. Effective in P8b. */
    PLAYER_CONSTRAINT,

    /** Weather, terrain and other battlefield conditions. Effective in P8b. */
    FIELD,

    /** What the run earns. Recorded, never valued here; the economy prices it (P9). */
    REWARD,

    /** How much of a floor's scouting profile a run can see (TDS #49). Effective in P12. */
    SCOUTING,

    /**
     * A rare modifier whose effect is a coded behavior (P29). It may also set ordinary fields as its price or bonus.
     */
    CUSTOM
}
