package com.cobbletowers.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.cobbletowers.TestRuns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A modifier card's scene key says only what the modifier does: art never implies an effect the definition does not
 * have.
 */
class ModifierArtTest {

    @Test
    @DisplayName("a weather or terrain modifier is keyed by the weather or terrain it actually sets")
    void fieldModifiers() {
        assertEquals("weather:raindance", ModifierArt.theme(TestRuns.modifier("rain", "field", "\"weather\":\"raindance\"")));
        assertEquals("terrain:grassyterrain", ModifierArt.theme(TestRuns.modifier("grass", "field", "\"terrain\":\"grassyterrain\"")));
    }

    @Test
    @DisplayName("a reward modifier is keyed by the direction of its reward factor, so a cut never gets the open coffer")
    void rewardDirection() {
        assertEquals("reward_up", ModifierArt.theme(TestRuns.modifier("some", "reward", "\"reward_percent\":120")));
        assertEquals("reward_up:hoard", ModifierArt.theme(TestRuns.modifier("more", "reward", "\"reward_percent\":150")));
        assertEquals("reward_down", ModifierArt.theme(TestRuns.modifier("less", "reward", "\"reward_percent\":50")));
        assertNotEquals(ModifierArt.theme(TestRuns.modifier("more", "reward", "\"reward_percent\":150")),
                ModifierArt.theme(TestRuns.modifier("less", "reward", "\"reward_percent\":50")));
    }

    @Test
    @DisplayName("enemy and constraint modifiers get their own scenes")
    void otherTypes() {
        assertEquals("enemy:champion", ModifierArt.theme(TestRuns.modifier("tough", "enemy", "\"boss_level_offset\":3")));
        assertEquals("enemy:tough", ModifierArt.theme(TestRuns.modifier("wall", "enemy", "\"boss_health_percent\":130")));
        assertEquals("enemy:fragile", ModifierArt.theme(TestRuns.modifier("soft", "enemy", "\"boss_health_percent\":70")));
        assertEquals("constraint:no_switch", ModifierArt.theme(TestRuns.modifier("locked", "player_constraint", "\"allow_switching\":false")));
    }

    @Test
    @DisplayName("a missing modifier falls back to the neutral scene, and scouting has its own")
    void fallback() {
        assertEquals(ModifierArt.UNKNOWN, ModifierArt.theme(null));
        assertEquals("scouting", ModifierArt.theme(TestRuns.modifier("eye", "scouting", "\"scouting_bonus\":2")));
    }
}
