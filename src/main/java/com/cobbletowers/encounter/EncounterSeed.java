package com.cobbletowers.encounter;

import java.util.UUID;

/**
 * The seed one encounter is drawn from (TDS #29). An opponent is derived from the run seed, floor and ordinal, never
 * stored, so a crash cannot reroll it. The parts are mixed, not added, so floor 2 opponent 3 differs from floor 3
 * opponent 2.
 */
public final class EncounterSeed {

    /** Odd 64-bit constants from the SplitMix64 family; any odd multiplier avoids collapsing bits. */
    private static final long FLOOR_MIX = 0x9E3779B97F4A7C15L;
    private static final long ORDINAL_MIX = 0xBF58476D1CE4E5B9L;

    private EncounterSeed() {}

    /** The seed for one opponent on one floor of one run. */
    public static long of(long runSeed, int floorIndex, int ordinal) {
        long mixed = runSeed ^ (floorIndex * FLOOR_MIX) ^ (ordinal * ORDINAL_MIX);
        return scramble(mixed);
    }

    /** SplitMix64's finaliser: cheap, and it spreads neighbouring inputs to unrelated outputs. */
    private static long scramble(long value) {
        long x = value + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** Mixed in by {@link #hidden}; any odd 64-bit constant not used above. */
    private static final long HIDDEN_MIX = 0x94D049BB133111EBL ^ 0x2545F4914F6CDD1DL;

    /**
     * The seed for draws that pay something (the Trick or Treat coin, relics, the Haunted rare). A run's own seed is
     * printed as a shareable run code, and a code can be written by hand, so a payout drawn from it alone is a payout a
     * player can choose. The run id is made by the server, saved with the run and sent to no client, so mixing it in keeps
     * the draw stable across restarts and out of the player's reach. Opponents, bosses and drafts keep the plain seed, so a
     * run code still replays them.
     */
    public static long hidden(long runSeed, UUID runId) {
        return scramble(runSeed ^ forNewRun(runId) ^ HIDDEN_MIX);
    }

    /** A run's own seed, for a run that has to be given one rather than restoring it. */
    public static long forNewRun(UUID runId) {
        return scramble(runId.getMostSignificantBits() ^ Long.rotateLeft(runId.getLeastSignificantBits(), 32));
    }
}
