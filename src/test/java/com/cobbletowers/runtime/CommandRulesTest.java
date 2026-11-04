package com.cobbletowers.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandRulesTest {

    private static final Set<String> DEFAULTS = CommandRules.DEFAULTS;

    @Test
    @DisplayName("the command word ignores a slash, arguments, case and a namespace")
    void wordOf() {
        assertEquals("pokeheal", CommandRules.wordOf("pokeheal"));
        assertEquals("pokeheal", CommandRules.wordOf("/PokeHeal Steve"));
        assertEquals("pokeheal", CommandRules.wordOf("cobblemon:pokeheal @s"));
        assertEquals("", CommandRules.wordOf("  "));
    }

    @Test
    @DisplayName("healing, teleporting and storage commands are blocked, namespaced or not")
    void blocksTheCommandsThatUndoATower() {
        for (String line : new String[] {"pokeheal", "pokeheal Steve", "cobblemon:pokeheal", "/home", "tpa Alex",
                "essentials:back", "pc", "ec", "kit starter", "give @s diamond", "gamemode creative"}) {
            assertTrue(CommandRules.isBlocked(line, DEFAULTS), line + " should be blocked");
        }
    }

    @Test
    @DisplayName("the tower's own commands and ordinary chat commands are never blocked")
    void leavesTheRestAlone() {
        for (String line : new String[] {"tower", "tower leave", "cobbletowers play ready", "msg Alex hi", "me waves",
                "list", "help"}) {
            assertFalse(CommandRules.isBlocked(line, DEFAULTS), line + " should work");
        }
    }

    @Test
    @DisplayName("the World Boss warp and return are blocked, the rest of /cobbleraids is not")
    void theWorldBossWarpAndReturnAreBlockedButTheRestOfTheCommandIsNot() {
        for (String line : new String[] {"cobbleraids worldboss warp", "/cobbleraids worldboss return", "/CobbleRaids:cobbleraids worldboss warp",
                "cobbleraids:cobbleraids worldboss return", "  /cobbleraids   worldboss   return  ", "cobbleraids worldboss warp now"}) {
            assertTrue(CommandRules.isBlocked(line, DEFAULTS), line + " should be blocked");
        }
        for (String line : new String[] {"cobbleraids leave", "cobbleraids worldboss status", "cobbleraids worldboss join", "cobbleraids worldboss me",
                "cobbleraids worldboss", "cobbleraids"}) {
            assertFalse(CommandRules.isBlocked(line, DEFAULTS), line + " should work (it is the way to fight, or to leave a raid)");
        }
    }

    @Test
    @DisplayName("an operator cannot block the way out, even by listing it")
    void theWayOutCannotBeBlocked() {
        Set<String> mistaken = new HashSet<>(DEFAULTS);
        mistaken.add("tower");
        mistaken.add("cobbletowers");
        assertFalse(CommandRules.isBlocked("tower leave", mistaken));
        assertFalse(CommandRules.isBlocked("cobbletowers runs leave", mistaken));
    }

    @Test
    @DisplayName("an operator's extra command is blocked too")
    void extrasAreBlocked() {
        Set<String> extended = new HashSet<>(DEFAULTS);
        extended.add("sparkle");
        assertTrue(CommandRules.isBlocked("/sparkle now", extended));
        assertFalse(CommandRules.isBlocked("/sparkle now", DEFAULTS));
    }
}
