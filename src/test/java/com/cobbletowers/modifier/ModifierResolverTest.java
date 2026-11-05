package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.ModifierDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** TDS #58: exclusions, groups, prerequisites and stack limits, each on its own. */
class ModifierResolverTest {

    private static ModifierDefinition enemy(String path, String... extra) {
        return TestRuns.modifier(path, "enemy", "\"boss_level_offset\":3", extra);
    }

    @Nested
    @DisplayName("stack limits")
    class StackLimits {

        @Test
        @DisplayName("a modifier may be held up to its stack limit and no further")
        void upToTheLimit() {
            ModifierDefinition twice = enemy("twice", "\"stack_limit\":2");
            assertTrue(ModifierResolver.eligible(twice, List.of()), "the first copy is fine");
            assertTrue(ModifierResolver.eligible(twice, List.of(twice)), "the second reaches the limit");
            assertFalse(ModifierResolver.eligible(twice, List.of(twice, twice)), "the third is over it");
        }

        @Test
        @DisplayName("the default stack limit is one")
        void defaultsToOne() {
            ModifierDefinition once = enemy("once");
            assertFalse(ModifierResolver.eligible(once, List.of(once)));
        }
    }

    @Nested
    @DisplayName("exclusions")
    class Exclusions {

        @Test
        @DisplayName("an exclusion is read from both sides, however it was declared")
        void symmetric() {
            ModifierDefinition declaring = enemy("declaring", "\"excludes\":[\"cobbletowers:silent\"]");
            ModifierDefinition silent = enemy("silent");

            assertFalse(ModifierResolver.eligible(silent, List.of(declaring)),
                    "the modifier that declares nothing is still excluded by the one that does");
            assertFalse(ModifierResolver.eligible(declaring, List.of(silent)),
                    "and the same pair the other way round");
        }

        @Test
        @DisplayName("a modifier that excludes itself is refused at load, not at draft")
        void selfExclusion() {
            assertThrows(IllegalArgumentException.class,
                    () -> enemy("selfish", "\"excludes\":[\"cobbletowers:selfish\"]"));
        }
    }

    @Nested
    @DisplayName("groups")
    class Groups {

        @Test
        @DisplayName("a group admits one modifier")
        void oneFromAGroup() {
            ModifierDefinition first = enemy("first", "\"group\":\"weather\"");
            ModifierDefinition second = enemy("second", "\"group\":\"weather\"");
            assertFalse(ModifierResolver.eligible(second, List.of(first)));
        }

        @Test
        @DisplayName("a group does not veto a second copy of its own member")
        void groupDoesNotBlockStacking() {
            // The rule that is easy to get wrong: reading "one per group" as "one copy per group"
            // makes any stack limit above 1 unreachable for a grouped modifier, silently.
            ModifierDefinition stackable = enemy("stackable", "\"group\":\"weather\"", "\"stack_limit\":2");
            assertTrue(ModifierResolver.eligible(stackable, List.of(stackable)));
        }
    }

    @Nested
    @DisplayName("prerequisites")
    class Prerequisites {

        @Test
        @DisplayName("a modifier is offered only once what it requires is held")
        void requiresHeld() {
            ModifierDefinition base = enemy("base");
            ModifierDefinition advanced = enemy("advanced", "\"requires\":[\"cobbletowers:base\"]");

            assertFalse(ModifierResolver.eligible(advanced, List.of()), "nothing held, so not yet");
            assertTrue(ModifierResolver.eligible(advanced, List.of(base)), "its prerequisite is held");
        }

        @Test
        @DisplayName("a modifier that requires what it excludes is refused at load")
        void contradiction() {
            assertThrows(IllegalArgumentException.class, () -> enemy("impossible",
                    "\"requires\":[\"cobbletowers:x\"]", "\"excludes\":[\"cobbletowers:x\"]"));
        }
    }

    @Test
    @DisplayName("validate judges the whole set, not the order it was drafted in")
    void orderIndependent() {
        ModifierDefinition a = enemy("a", "\"excludes\":[\"cobbletowers:b\"]");
        ModifierDefinition b = enemy("b");

        assertEquals(ModifierResolver.validate(List.of(a, b)).size(),
                ModifierResolver.validate(List.of(b, a)).size(),
                "a set is legal or not; which way round it is listed cannot change that");
    }

    @Test
    @DisplayName("eligibleFrom drops what the run cannot legally take")
    void eligibleFromFilters() {
        ModifierDefinition held = enemy("held");
        ModifierDefinition blocked = enemy("blocked", "\"excludes\":[\"cobbletowers:held\"]");
        ModifierDefinition field = TestRuns.modifier("field", "field", "\"weather\":\"raindance\"");
        ModifierDefinition offerable = enemy("offerable");

        List<ModifierDefinition> offers = ModifierResolver.eligibleFrom(
                List.of(blocked, field, offerable), List.of(held));

        // The FIELD card stays on the table: since P8b its effect reaches the boss through the
        // CobbleRaids encounter rules, so there is no longer such a thing as a type nothing applies.
        assertEquals(List.of(field, offerable), offers, "only the excluded one is left off");
    }
}
