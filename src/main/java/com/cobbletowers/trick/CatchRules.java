package com.cobbletowers.trick;

import com.cobbletowers.encounter.EncounterSeed;
import java.util.List;
import java.util.Random;

/**
 * The rules of the Haunted rare (P58): a treat may carry a catchable Pokemon, and each player gets a few throws at it. Pure, so the odds
 * are tested without Minecraft. The pool is the Halloween boss species; the draw is seeded like the vault's coin.
 */
public final class CatchRules {

    /** The chance, in percent, that a treat also carries a Haunted rare. */
    public static final int RARE_PERCENT = 15;
    /** Throws each player gets at the rare. */
    public static final int THROWS = 3;
    /** The wild level of the rare, set from the floor so deeper vaults hold stronger ones. */
    public static final int BASE_LEVEL = 20;

    /** The species a rare can be (Halloween boss species; shiny and skins stay boss-only). */
    static final List<String> SPECIES = List.of("phantump", "mimikyu", "rowlet", "togepi", "eevee");

    /** Its own ordinal space, like every other draw. */
    static final int ORDINAL_BASE = 7_000_041;

    private CatchRules() {}

    /** Whether the treat of this floor also holds a rare. */
    public static boolean treatHasRare(long runSeed, int floorIndex) {
        if (Boolean.getBoolean("cobbletowers.forceRare")) return true;   // for the rig: every treat holds one
        return Math.floorMod(EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE), 100) < RARE_PERCENT;
    }

    /** The species of this floor's rare. */
    public static String species(long runSeed, int floorIndex) {
        return SPECIES.get((int) Math.floorMod(EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE + 1), SPECIES.size()));
    }

    /** Its level: {@value #BASE_LEVEL} plus two for each floor. */
    public static int level(int floorIndex) {
        return BASE_LEVEL + 2 * Math.max(0, floorIndex);
    }

    /**
     * The chance, 0 to 100, that one throw catches the rare.
     *
     * @param throwNumber which throw this is, 1 to {@value #THROWS}
     * @param floorIndex  the floor the vault is on (deeper floors are harder)
     */
    public static int chancePercent(int throwNumber, int floorIndex) {
        // 35% on the first throw, +10 for each later one (pity), -2 for each floor; about 75% over three throws on floor 1.
        int base = 35 + 10 * (Math.max(1, throwNumber) - 1) - 2 * Math.max(0, floorIndex);
        return Math.max(5, Math.min(95, base));
    }

    /** Whether this throw lands, settled by the seed so a retry of the same throw comes out the same. */
    public static boolean lands(int chancePercent, long runSeed, int floorIndex, long playerBits, int throwNumber) {
        long seed = EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE + 2) * 31 + playerBits * 104_729L + throwNumber;
        return new Random(seed).nextInt(100) < chancePercent;
    }
}
