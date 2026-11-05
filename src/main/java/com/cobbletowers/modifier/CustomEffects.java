package com.cobbletowers.modifier;

import com.cobbletowers.definition.CustomBehavior;
import com.cobbletowers.definition.ModifierDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a run's CUSTOM modifiers add up to (P29): typed parameters their coded behaviors reduce to. Consumers read a
 * number or flag: battle adapters, intermission healing, reward valuation, vendor price. Pure.
 */
public record CustomEffects(Set<CustomBehavior> behaviors, JsonArray ops) {

    public static final CustomEffects NONE = new CustomEffects(Set.of(), new JsonArray());

    public CustomEffects(Set<CustomBehavior> behaviors) {
        this(behaviors, new JsonArray());
    }

    /** The odds and multipliers of the Wheel, in percent: a 30% jackpot at x4, otherwise x0.5. */
    static final int WHEEL_JACKPOT_CHANCE = 30;
    static final int WHEEL_JACKPOT_PERCENT = 400;
    static final int WHEEL_CONSOLATION_PERCENT = 50;
    static final int BLACK_MARKET_PRICE_PERCENT = 50;
    /** Balance phase 2: the party's relics together deal at most x1.35 and take at least x0.80 (tower-fx.js {@code cap}). */
    public static final int DEALT_CAP = 135;
    public static final int TAKEN_FLOOR = 80;

    public CustomEffects {
        behaviors = Set.copyOf(behaviors);
        ops = ops.deepCopy();
    }

    public static CustomEffects of(List<ModifierDefinition> modifiers) {
        Set<CustomBehavior> held = EnumSet.noneOf(CustomBehavior.class);
        JsonArray ops = new JsonArray();
        for (ModifierDefinition modifier : modifiers) {
            modifier.effect().custom().flatMap(CustomBehavior::fromId).ifPresent(held::add);
            // Battle operations a modifier declares as data (P45); the run's cap on operations is the battle layer's.
            ops.addAll(modifier.effect().battle());
        }
        return new CustomEffects(held, ops);
    }

    public boolean has(CustomBehavior behavior) {
        return behaviors.contains(behavior);
    }

    /** Whether everybody's party is healed when an intermission opens. */
    public boolean healsEachIntermission() {
        return has(CustomBehavior.FIELD_HOSPITAL);
    }

    /** The percentage a vendor price is multiplied by (100 = unchanged). */
    public int vendorPricePercent() {
        return has(CustomBehavior.BLACK_MARKET) ? BLACK_MARKET_PRICE_PERCENT : 100;
    }

    /**
     * What one reward grant is multiplied by, in percent. Deterministic from the run seed and the grant's ledger
     * place, so a re-bank cannot change it.
     */
    public int rewardPercent(long runSeed, int floorIndex, int ordinal) {
        if (!has(CustomBehavior.FORTUNES_WHEEL)) return 100;
        long seed = com.cobbletowers.encounter.EncounterSeed.of(runSeed, floorIndex, WHEEL_ORDINAL_BASE + ordinal);
        return Math.floorMod(seed, 100) < WHEEL_JACKPOT_CHANCE ? WHEEL_JACKPOT_PERCENT : WHEEL_CONSOLATION_PERCENT;
    }

    /** Its own ordinal space, like every other draw (see {@code DraftDraw}). */
    static final int WHEEL_ORDINAL_BASE = 3_000_017;

    /** Logical battle operations for the player's side, merged with a worn armor set's. */
    public JsonArray battleOps() {
        JsonArray ops = new JsonArray();
        // Balance phase 2: percent damage instead of stat stages, so relics stay inside the cap op below and cannot be
        // multiplied again by in-battle setup moves.
        if (has(CustomBehavior.GLASS_CANNON)) {
            ops.add(op("damage", "percent", 140, "type", "any"));
            ops.add(op("hp", "percent", 60));
        }
        if (has(CustomBehavior.SWIFT_START)) ops.add(op("boost", "stat", "spe", "stages", 1));
        if (has(CustomBehavior.IRON_HIDE)) ops.add(op("resist", "percent", 92, "type", "any"));
        if (has(CustomBehavior.WAR_BANNER)) ops.add(op("damage", "percent", 110, "type", "any"));
        ops.addAll(this.ops);
        if (!ops.isEmpty()) ops.add(op("cap", "dealt", DEALT_CAP, "taken", TAKEN_FLOOR));
        return ops;
    }

    private static JsonObject op(String name, Object... pairs) {
        JsonObject op = new JsonObject();
        op.addProperty("op", name);
        op.addProperty("side", "self");
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Number number) op.addProperty((String) pairs[i], number);
            else op.addProperty((String) pairs[i], String.valueOf(value));
        }
        return op;
    }
}
