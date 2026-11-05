package com.cobbletowers.ascension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Ascension's battle effects (P30) as logical {@code evs} operations for the tower-fx extension: the enemy's growth
 * and the player's boon. Pure. The enemy operation is one per battle, since a boss battle merges every player's
 * operations.
 */
public final class AscensionFx {

    private AscensionFx() {}

    /** The operations a player's own side gets: the boon, nothing at the base cycle. */
    public static JsonArray boon(int ascension) {
        JsonArray ops = new JsonArray();
        addChunks(ops, "self", AscensionPolicy.boonEvs(ascension));
        return ops;
    }

    /** The operation the enemy side gets, nothing at the base cycle. */
    public static JsonArray enemy(int ascension) {
        JsonArray ops = new JsonArray();
        addChunks(ops, "foe", AscensionPolicy.enemyEvs(ascension));
        return ops;
    }

    /**
     * As above, for a boss-rush tower (balance phase 2): there are no waves to add opponents to, so the extra opponents
     * an Ascension would bring become an enrage instead ({@link AscensionPolicy#enrageEvery}).
     */
    public static JsonArray enemy(int ascension, boolean bossRush) {
        JsonArray ops = enemy(ascension);
        int every = bossRush ? AscensionPolicy.enrageEvery(ascension) : 0;
        if (every > 0) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "enrage");
            op.addProperty("side", "foe");
            op.addProperty("every", every);
            ops.add(op);
        }
        return ops;
    }

    /**
     * One operation carries at most {@link AscensionPolicy#MAX_EVS_PER_OPERATION}; a deeper Ascension sends several.
     */
    private static void addChunks(JsonArray ops, String side, int total) {
        for (int left = total; left > 0; left -= AscensionPolicy.MAX_EVS_PER_OPERATION) {
            ops.add(evs(side, Math.min(left, AscensionPolicy.MAX_EVS_PER_OPERATION)));
        }
    }

    private static JsonObject evs(String side, int amount) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "evs");
        op.addProperty("side", side);
        op.addProperty("amount", amount);
        return op;
    }
}
