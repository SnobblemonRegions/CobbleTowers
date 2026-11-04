package com.cobbletowers.runtime;

import java.util.Locale;
import java.util.Set;

/**
 * Which commands are off inside the tower (P27): the ones that undo its rules ({@code /pokeheal}, home or warp
 * commands, PC, ender chest, kits). Pure; a deny list matching the first word, with or without a {@code namespace:}
 * prefix. Operators extend it; see {@link TowerCommandGuard}.
 */
public final class CommandRules {

    /** Healing and cheating, party and storage changes, getting out, and carrying things in. */
    public static final Set<String> DEFAULTS = Set.of(
            // healing and feeding
            "pokeheal", "healpokemon", "heal", "feed", "repair", "fix", "god", "fly", "flyspeed", "walkspeed",
            // changing the party
            "pokegive", "pokegiveother", "givepokemon", "pokespawn", "spawnpokemon", "evolve", "releasepokemon",
            "deletepokemon", "changeaspect", "teachmove", "setlevel", "levelup", "pokemonedit", "pokeedit",
            // storage: PC, boxes, chests, trading
            "pc", "box", "boxes", "pokebox", "pokepc", "party", "trade", "ec", "enderchest", "echest", "chest",
            "backpack", "bp", "craft", "workbench", "wb", "anvil", "grindstone", "smithingtable", "stonecutter",
            // getting out, or in somewhere else
            "tp", "teleport", "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel", "tpr", "spawn", "hub", "lobby",
            "home", "homes", "sethome", "delhome", "warp", "warps", "setwarp", "back", "dback", "rtp", "wild",
            "randomtp", "top", "jump", "spreadplayers", "spawnpoint", "setworldspawn",
            // shops, kits and free items
            "kit", "kits", "give", "shop", "sell", "buy", "market", "ah", "auction", "bank", "gift", "skull", "hat",
            "more", "item", "i",
            // changing yourself
            "gamemode", "gm", "gmc", "gms", "gmsp", "effect", "xp", "experience", "clear", "enchant",
            "suicide", "kill");

    /** Commands that always work, even if an operator lists them by mistake: the way out and the way to play. */
    private static final Set<String> ALWAYS = Set.of("tower", "cobbletowers");

    /**
     * Commands that share their first word with harmless ones, so the word alone cannot be listed: matched by their first three words. CobbleRaids'
     * World Boss {@code warp} and {@code return} both teleport the player (return to world spawn when it has nowhere else to send them), which
     * is a way out of the tower that the {@code tp}/{@code spawn} entries above exist to stop.
     */
    static final Set<String> DENIED_PHRASES = Set.of("cobbleraids worldboss warp", "cobbleraids worldboss return");

    private CommandRules() {}

    /** The command word: the first token, without a leading slash or a {@code namespace:}, in lower case. */
    public static String wordOf(String commandLine) {
        String line = commandLine.strip();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        String first = space < 0 ? line : line.substring(0, space);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        return first.toLowerCase(Locale.ROOT);
    }

    /** The first three tokens, lower case and single-spaced, without a leading slash or a {@code namespace:} on the first. */
    static String phraseOf(String commandLine) {
        String line = commandLine.strip();
        if (line.startsWith("/")) line = line.substring(1);
        String[] tokens = line.split("\\s+");
        if (tokens.length > 0) {
            int colon = tokens[0].indexOf(':');
            if (colon >= 0) tokens[0] = tokens[0].substring(colon + 1);
        }
        StringBuilder phrase = new StringBuilder();
        for (int i = 0; i < Math.min(3, tokens.length); i++) {
            if (i > 0) phrase.append(' ');
            phrase.append(tokens[i].toLowerCase(Locale.ROOT));
        }
        return phrase.toString();
    }

    /** Whether {@code commandLine} is turned off, given the full deny list (defaults plus any the operator added). */
    public static boolean isBlocked(String commandLine, Set<String> denied) {
        String word = wordOf(commandLine);
        if (word.isEmpty() || ALWAYS.contains(word)) return false;
        return denied.contains(word) || DENIED_PHRASES.contains(phraseOf(commandLine));
    }
}
