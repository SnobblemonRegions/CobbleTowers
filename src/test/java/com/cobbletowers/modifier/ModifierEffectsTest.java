package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Offsets add, percentages compound, and nothing here decides what a level may be. */
class ModifierEffectsTest {

    @Test
    @DisplayName("nothing held is nothing changed")
    void neutral() {
        assertEquals(ModifierEffects.NONE, ModifierEffects.of(List.of()));
        assertEquals(0, ModifierEffects.NONE.levelOffset());
        assertEquals(100, ModifierEffects.NONE.bossHealthPercent());
        assertEquals(100, ModifierEffects.NONE.rewardPercent());
    }

    @Test
    @DisplayName("offsets add up")
    void offsetsAdd() {
        ModifierDefinition three = TestRuns.modifier("three", "enemy", "\"level_offset\":3");
        ModifierDefinition four = TestRuns.modifier("four", "enemy", "\"level_offset\":4");
        assertEquals(7, ModifierEffects.of(List.of(three, four)).levelOffset());
    }

    @Test
    @DisplayName("reward percentages add, and the total is capped at x3 (balance phase 2)")
    void rewardPercentagesAdd() {
        ModifierDefinition half = TestRuns.modifier("half", "reward", "\"reward_percent\":150");
        assertEquals(200, ModifierEffects.of(List.of(half, half)).rewardPercent(), "+50% and +50% is +100%");
        ModifierDefinition cut = TestRuns.modifier("cut", "reward", "\"reward_percent\":80");
        assertEquals(130, ModifierEffects.of(List.of(half, cut)).rewardPercent());
        assertEquals(ModifierEffects.MAX_REWARD_PERCENT,
                ModifierEffects.of(List.of(half, half, half, half, half)).rewardPercent(), "capped at x3");
    }

    @Test
    @DisplayName("a boss pool can be reduced but never to nothing")
    void bossHealthFloor() {
        ModifierDefinition frail = TestRuns.modifier("frail", "enemy", "\"boss_health_percent\":1");
        ModifierEffects effects = ModifierEffects.of(List.of(frail, frail, frail, frail));
        assertTrue(effects.bossHealthPercent() >= 1, "a boss with a zero pool is a corpse, not a boss");
        assertTrue(effects.applyBossHealth(10_000L) >= 1L);
    }

    @Test
    @DisplayName("the boss pool scales by the percentage")
    void bossHealthScales() {
        ModifierDefinition tough = TestRuns.modifier("tough", "enemy", "\"boss_health_percent\":150");
        assertEquals(15_000L, ModifierEffects.of(List.of(tough)).applyBossHealth(10_000L));
    }

    @Test
    @DisplayName("extra opponents add up across copies")
    void extraOpponentsAdd() {
        ModifierDefinition crowd = TestRuns.modifier("crowd", "encounter", "\"extra_opponents\":1",
                "\"stack_limit\":3");
        assertEquals(3, ModifierEffects.of(List.of(crowd, crowd, crowd)).extraOpponents());
    }

    @Test
    @DisplayName("a modifier whose effect does not match its type is refused at load")
    void typeMustMatchEffect() {
        // The mistake content makes: copy a modifier, change its type, forget the payload. Without
        // this the file loads, the card drafts, and it does nothing that its type is applied by.
        assertThrows(IllegalArgumentException.class,
                () -> TestRuns.modifier("mislabelled", "reward", "\"level_offset\":3"));
    }

    @Test
    @DisplayName("every type is offerable now that every field has somewhere to go")
    void everyTypeIsOfferable() {
        // P8b gave these three a road across the encounter boundary, so all three are real cards.
        assertTrue(TestRuns.modifier("real", "enemy", "\"level_offset\":1").effectiveNow());
        assertTrue(TestRuns.modifier("paid", "reward", "\"reward_percent\":120").effectiveNow());
        assertTrue(TestRuns.modifier("weather", "field", "\"weather\":\"raindance\"").effectiveNow());
        assertTrue(TestRuns.modifier("no_items", "player_constraint", "\"allow_items\":false").effectiveNow());
        assertTrue(TestRuns.modifier("tough_boss", "enemy", "\"boss_health_percent\":130").effectiveNow());
    }

    @Test
    @DisplayName("a card that would do nothing cannot be defined at all")
    void inertCardsCannotExist() {
        // A definition whose effect changes nothing matches no type and is refused by the constructor.
        for (String type : new String[] {"enemy", "encounter", "player_constraint", "field", "reward"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TestRuns.modifier("empty_" + type, type, ""),
                    type + " accepted an effect that changes nothing");
        }
    }

    @Test
    @DisplayName("a withdrawn permission stays withdrawn")
    void permissionsOnlyNarrow() {
        ModifierDefinition noSwitch = TestRuns.modifier("no_switch", "player_constraint",
                "\"allow_switching\":false");
        ModifierDefinition ordinary = TestRuns.modifier("ordinary", "enemy", "\"level_offset\":1");

        // Order must not matter: an ordinary modifier drafted after a restriction cannot hand the
        // permission back, because its own effect says "switching allowed" simply by not mentioning it.
        assertFalse(ModifierEffects.of(List.of(noSwitch, ordinary)).switchingAllowed());
        assertFalse(ModifierEffects.of(List.of(ordinary, noSwitch)).switchingAllowed());
    }

    @Test
    @DisplayName("banned moves are pooled across every modifier holding them")
    void bannedMovesUnion() {
        ModifierDefinition one = TestRuns.modifier("one", "player_constraint", "\"banned_moves\":[\"protect\"]");
        ModifierDefinition two = TestRuns.modifier("two", "player_constraint",
                "\"banned_moves\":[\"recover\",\"protect\"]");

        assertEquals(List.of("protect", "recover"), ModifierEffects.of(List.of(one, two)).bannedMoves(),
                "pooled and deduplicated, in the order they were drafted");
    }

    @Test
    @DisplayName("the last field condition drafted is the one that applies")
    void lastFieldWins() {
        ModifierDefinition rain = TestRuns.modifier("rain", "field", "\"weather\":\"raindance\"");
        ModifierDefinition sun = TestRuns.modifier("sun", "field", "\"weather\":\"sunnyday\"");

        // A field is one condition, so somebody has to lose. Taking the last means the card the
        // party just voted for is the one they get; taking the first would silently ignore it.
        assertEquals(Optional.of("sunnyday"), ModifierEffects.of(List.of(rain, sun)).weather());
        assertEquals(Optional.of("raindance"), ModifierEffects.of(List.of(sun, rain)).weather());
    }

    @Test
    @DisplayName("nothing drafted changes no battle rules")
    void neutralChangesNoRules() {
        assertFalse(ModifierEffects.NONE.changesBattleRules());
        assertTrue(ModifierEffects.of(List.of(
                TestRuns.modifier("tough", "enemy", "\"boss_health_percent\":130"))).changesBattleRules());
        assertFalse(ModifierEffects.of(List.of(
                TestRuns.modifier("plain", "enemy", "\"level_offset\":2"))).changesBattleRules(),
                "a level offset is not a battle rule; it is applied when the opponent is drawn");
    }

    @Test
    @DisplayName("boss levels the cap took away come back as +3% boss health each (balance phase 2)")
    void clampedLevelsBecomeHealth() {
        assertEquals(109, ModifierEffects.NONE.withClampedLevels(3).bossHealthPercent());
        assertEquals(ModifierEffects.NONE, ModifierEffects.NONE.withClampedLevels(0));
        ModifierDefinition frail = TestRuns.modifier("frail", "enemy", "\"boss_health_percent\":70");
        assertEquals(74, ModifierEffects.of(List.of(frail)).withClampedLevels(2).bossHealthPercent(), "70 x 1.06, rounded down");
    }
}
