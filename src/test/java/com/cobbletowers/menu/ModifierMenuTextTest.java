package com.cobbletowers.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModifierMenuTextTest {

    private static List<String> lines(String effect) {
        return lines("enemy", effect);
    }

    private static List<String> lines(String type, String effect) {
        ModifierDefinition m = TestRuns.modifier("probe", type, effect, "\"risk\":\"moderate\"");
        return ModifierMenuText.lines(m);
    }

    private static boolean has(List<String> lines, String marker, String text) {
        return lines.stream().anyMatch(line -> line.startsWith(marker) && line.contains(text));
    }

    @Test
    @DisplayName("what helps the player is marked good and what hurts is marked bad")
    void directions() {
        List<String> lines = lines("\"level_offset\":3,\"boss_health_percent\":80,\"reward_percent\":125,\"allow_items\":false,\"scouting_bonus\":1");
        assertTrue(has(lines, ModifierMenuText.BAD, "Foes rise 3 levels"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.GOOD, "frail: only 80%"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.GOOD, "Loot swells"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.BAD, "No items in battle"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.GOOD, "scouts see 1 floors"), lines.toString());
    }

    @Test
    @DisplayName("the opposite effect flips the colour")
    void flipped() {
        List<String> lines = lines("\"level_offset\":-2,\"boss_health_percent\":150,\"reward_percent\":60,\"extra_opponents\":2");
        assertTrue(has(lines, ModifierMenuText.GOOD, "Foes fall 2 levels"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.BAD, "The boss endures: 150%"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.BAD, "Loot shrinks"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.BAD, "2 more challengers"), lines.toString());
    }

    @Test
    @DisplayName("a mixed custom modifier says its benefit and its cost on separate lines")
    void mixed() {
        List<String> lines = lines("custom", "\"custom\":\"glass_cannon\"");
        assertTrue(has(lines, ModifierMenuText.GOOD, "40% more damage"), lines.toString());
        assertTrue(has(lines, ModifierMenuText.BAD, "60% HP"), lines.toString());
    }

    @Test
    @DisplayName("information that is neither is left unmarked, and the marker can be stripped")
    void neutralAndPlain() {
        List<String> lines = lines("field", "\"weather\":\"raindance\"");
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("The sky turns")), lines.toString());
        assertEquals("Foes rise", ModifierMenuText.plain(ModifierMenuText.BAD + "Foes rise"));
        assertEquals("Plain", ModifierMenuText.plain("Plain"));
    }
}
