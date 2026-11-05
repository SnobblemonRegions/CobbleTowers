package com.cobbletowers.showdown;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Battle effects waiting for a player's tower battle (P23). Effects are queued by whoever decides them and armed only
 * by the code starting a tower battle, so they cannot leak into another battle. Operations use logical sides ({@code
 * self}, {@code foe}, {@code both}) resolved to Showdown side ids when armed, and are validated here and in {@code
 * tower-fx.js}. Pure apart from the maps.
 */
public final class TowerBattleFx {

    /** The same cap tower-fx.js enforces; anything past it would only be ignored there. */
    public static final int MAX_OPS = 32;

    private static final Set<String> WEATHERS = Set.of("raindance", "sunnyday", "sandstorm", "hail", "snowscape");
    private static final Set<String> TERRAINS = Set.of("electricterrain", "grassyterrain", "mistyterrain", "psychicterrain");
    private static final Set<String> STATS = Set.of("atk", "def", "spa", "spd", "spe", "accuracy", "evasion");
    /** The six stats an EV can go into, HP included (a boost's stat list has accuracy and evasion instead). */
    private static final Set<String> EV_STATS = Set.of("hp", "atk", "def", "spa", "spd", "spe");
    private static final Set<String> STATUSES = Set.of("brn", "par", "psn", "tox", "slp", "frz");
    private static final Set<String> SIDE_CONDITIONS = Set.of("tailwind", "reflect", "lightscreen", "auroraveil", "safeguard", "mist");
    /** The eighteen types as Showdown names them. */
    private static final Set<String> TYPES = Set.of("Normal", "Fire", "Water", "Electric", "Grass", "Ice", "Fighting", "Poison",
            "Ground", "Flying", "Psychic", "Bug", "Rock", "Ghost", "Dragon", "Dark", "Steel", "Fairy");

    /** Waiting for a player's next tower battle: logical operations. */
    private static final Map<UUID, JsonArray> QUEUED = new ConcurrentHashMap<>();
    /** Armed for the battle being started right now: resolved operations, by player. */
    private static final Map<UUID, JsonArray> ARMED = new ConcurrentHashMap<>();

    static {
        ServerState.onStop(() -> {
            QUEUED.clear();
            ARMED.clear();
        });
    }

    private TowerBattleFx() {}

    // ---- queueing ------------------------------------------------------------------------------------------------

    /** Replaces whatever is queued for {@code player}. @return how many operations survived validation */
    public static int queue(UUID player, JsonArray logical) {
        JsonArray valid = new JsonArray();
        for (JsonElement element : logical) {
            if (valid.size() >= MAX_OPS) break;
            if (validate(element).isPresent()) valid.add(element);
        }
        if (valid.isEmpty()) {
            QUEUED.remove(player);
        } else {
            QUEUED.put(player, valid);
        }
        return valid.size();
    }

    public static Optional<JsonArray> queued(UUID player) {
        return Optional.ofNullable(QUEUED.get(player)).map(JsonArray::deepCopy);
    }

    public static void clearQueued(UUID player) {
        QUEUED.remove(player);
    }

    // ---- arming ---------------------------------------------------------------------------------------------------

    /** A floor's own battle is p1 (the player) against p2 (the opponent). */
    public static void armFloorBattle(UUID player) {
        armFloorBattle(player, new JsonArray());
    }

    /**
     * The same, merging {@code extra} operations derived from the live player (a worn armor set, P24); extras are
     * never stored.
     */
    public static void armFloorBattle(UUID player, JsonArray extra) {
        arm(player, List.of("p1"), List.of("p2"), extra);
    }

    /** A boss raid is p1..pN against p(N+1); each player's effects resolve against their own side. */
    public static void armBossBattle(List<UUID> players) {
        armBossBattle(players, player -> new JsonArray());
    }

    /** As above, with each player's own derived operations ({@code extras}). */
    public static void armBossBattle(List<UUID> players, java.util.function.Function<UUID, JsonArray> extras) {
        String boss = "p" + (players.size() + 1);
        for (int i = 0; i < players.size(); i++) {
            arm(players.get(i), List.of("p" + (i + 1)), List.of(boss), extras.apply(players.get(i)));
        }
    }

    private static void arm(UUID player, List<String> self, List<String> foe, JsonArray extra) {
        JsonArray logical = new JsonArray();
        JsonArray queued = QUEUED.get(player);
        if (queued != null) queued.forEach(logical::add);
        extra.forEach(logical::add);
        if (logical.isEmpty()) return;
        JsonArray resolved = resolve(logical, self, foe);
        if (!resolved.isEmpty()) ARMED.put(player, resolved);
    }

    /** Called straight after the battle start, whether or not it worked: nothing armed may outlive the call. */
    public static void disarm(UUID player) {
        ARMED.remove(player);
    }

    public static void disarmAll(List<UUID> players) {
        for (UUID player : players) ARMED.remove(player);
    }

    // ---- handing to Showdown
    // ----------------------------------------------------------------------------------------

    /**
     * The CobbleRaids format-field provider: the {@code towerFx} field for a battle whose players have armed effects.
     * Consumes them; empty for any other battle.
     */
    public static Map<String, String> fieldsFor(UUID battleId, List<UUID> players) {
        JsonArray all = new JsonArray();
        for (UUID player : players) {
            JsonArray armed = ARMED.remove(player);
            if (armed == null) continue;
            for (JsonElement op : armed) {
                if (all.size() < MAX_OPS) all.add(op);
            }
        }
        if (all.isEmpty()) return Map.of();
        TowerLog.info("Tower battle {} carries {} effect(s): {}", battleId, all.size(), all);
        return Map.of("towerFx", all.toString());
    }

    // ---- validation and resolution
    // ------------------------------------------------------------------------------------

    /** Returns the operation as a clean object, or empty if it is not one we will send. */
    public static Optional<JsonObject> validate(JsonElement element) {
        if (element == null || !element.isJsonObject()) return Optional.empty();
        JsonObject op = element.getAsJsonObject();
        String name = string(op, "op");
        if (name == null) return Optional.empty();
        JsonObject clean = new JsonObject();
        clean.addProperty("op", name);
        switch (name) {
            case "weather" -> {
                if (!oneOf(op, "id", WEATHERS, clean)) return Optional.empty();
                copyInt(op, "duration", 0, 20, clean);
            }
            case "terrain" -> {
                if (!oneOf(op, "id", TERRAINS, clean)) return Optional.empty();
                copyInt(op, "duration", 0, 20, clean);
            }
            case "boost" -> {
                if (!side(op, clean) || !oneOf(op, "stat", STATS, clean) || !copyInt(op, "stages", -6, 6, clean)) return Optional.empty();
            }
            case "evs" -> {
                // Over-the-cap EVs (P30), applied inside the battle only; see tower-fx.js.
                if (!side(op, clean) || !copyInt(op, "amount", 1, 2000, clean)) return Optional.empty();
                String stat = string(op, "stat");
                if (stat != null && !stat.equals("all")) {
                    if (!EV_STATS.contains(stat)) return Optional.empty();
                    clean.addProperty("stat", stat);
                }
            }
            case "hp" -> {
                if (!side(op, clean) || !copyInt(op, "percent", 1, 100, clean)) return Optional.empty();
            }
            case "status" -> {
                if (!side(op, clean) || !oneOf(op, "status", STATUSES, clean)) return Optional.empty();
            }
            case "sidecondition" -> {
                if (!side(op, clean) || !oneOf(op, "id", SIDE_CONDITIONS, clean)) return Optional.empty();
                copyInt(op, "duration", 0, 20, clean);
            }
            case "suppress_items" -> {
                if (!side(op, clean)) return Optional.empty();
            }
            case "drain" -> {
                if (!side(op, clean) || !copyInt(op, "percent", 1, 50, clean)) return Optional.empty();
                copyTag(op, clean);
            }
            case "enrage" -> {
                if (!side(op, clean) || !copyInt(op, "every", 1, 20, clean)) return Optional.empty();
                copyTag(op, clean);
            }
            case "heal" -> {
                // Restores up to this share of maximum HP; never lowers (boss phases, P58).
                if (!side(op, clean) || !copyInt(op, "to_percent", 1, 100, clean)) return Optional.empty();
            }
            case "cure" -> {
                if (!side(op, clean)) return Optional.empty();
            }
            case "clear" -> {
                // Takes back the damage, resist, drain and enrage rules that carry this tag.
                if (!copyTag(op, clean)) return Optional.empty();
            }
            case "phase" -> {
                return validatePhase(op, clean);
            }
            case "cap" -> {
                if (!side(op, clean) || !copyInt(op, "dealt", 100, 300, clean) || !copyInt(op, "taken", 1, 100, clean)) return Optional.empty();
            }
            case "damage", "resist" -> {
                if (!side(op, clean) || !copyInt(op, "percent", 1, 300, clean)) return Optional.empty();
                String type = string(op, "type");
                if (type != null && !type.equals("any") && !TYPES.contains(type)) return Optional.empty();
                clean.addProperty("type", type == null ? "any" : type);
                copyTag(op, clean);
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(clean);
    }

    /** Logical operations to concrete ones: {@code side} becomes {@code sides}, a list of Showdown side ids. */
    static JsonArray resolve(JsonArray logical, List<String> self, List<String> foe) {
        JsonArray out = new JsonArray();
        for (JsonElement element : logical) {
            if (out.size() >= MAX_OPS) break;
            Optional<JsonObject> valid = validate(element);
            if (valid.isEmpty()) continue;
            JsonObject op = valid.get();
            resolveSides(op, self, foe);
            if (op.has("do")) {
                for (JsonElement inner : op.getAsJsonArray("do")) resolveSides(inner.getAsJsonObject(), self, foe);
            }
            out.add(op);
        }
        return out;
    }

    /** {@code side} becomes {@code sides} on one operation. */
    private static void resolveSides(JsonObject op, List<String> self, List<String> foe) {
        if (!op.has("side")) return;
        List<String> ids = new ArrayList<>();
        switch (op.get("side").getAsString()) {
            case "self" -> ids.addAll(self);
            case "foe" -> ids.addAll(foe);
            default -> {
                ids.addAll(self);
                ids.addAll(foe);
            }
        }
        op.remove("side");
        JsonArray sides = new JsonArray();
        ids.forEach(sides::add);
        op.add("sides", sides);
    }

    /** What a boss phase may do; {@code phase} itself is left out, a phase cannot create phases. */
    private static final Set<String> PHASE_OPS = Set.of("weather", "terrain", "boost", "status", "sidecondition", "heal", "cure",
            "damage", "resist", "drain", "enrage", "clear");
    /** At most this many effects in one phase (tower-fx.js enforces the same). */
    public static final int MAX_PHASE_OPS = 8;

    /**
     * A boss phase (P58): the side it watches, a trigger ({@code hp_below} 1-99 or {@code turn} 2-50), an announcement and
     * the effects it applies once. The effects are validated like any other operation; one that is not valid is dropped.
     */
    private static Optional<JsonObject> validatePhase(JsonObject op, JsonObject clean) {
        if (!side(op, clean)) return Optional.empty();
        JsonElement whenElement = op.get("when");
        if (whenElement == null || !whenElement.isJsonObject()) return Optional.empty();
        JsonObject when = new JsonObject();
        if (!copyInt(whenElement.getAsJsonObject(), "hp_below", 1, 99, when)
                && !copyInt(whenElement.getAsJsonObject(), "turn", 2, 50, when)) return Optional.empty();
        clean.add("when", when);
        String id = string(op, "id");
        if (id != null) clean.addProperty("id", id.length() > 40 ? id.substring(0, 40) : id);
        String name = string(op, "name");
        if (name != null) clean.addProperty("name", name.length() > 40 ? name.substring(0, 40) : name);
        String announce = string(op, "announce");
        if (announce != null) clean.addProperty("announce", announce.length() > 160 ? announce.substring(0, 160) : announce);
        JsonArray inner = new JsonArray();
        JsonElement todo = op.get("do");
        if (todo != null && todo.isJsonArray()) {
            for (JsonElement element : todo.getAsJsonArray()) {
                if (inner.size() >= MAX_PHASE_OPS) break;
                if (!element.isJsonObject() || !PHASE_OPS.contains(string(element.getAsJsonObject(), "op"))) continue;
                validate(element).ifPresent(inner::add);
            }
        }
        if (inner.isEmpty()) return Optional.empty();
        clean.add("do", inner);
        return Optional.of(clean);
    }

    /** Copies an optional rule tag (at most 40 characters); returns whether one was present. */
    private static boolean copyTag(JsonObject op, JsonObject clean) {
        String tag = string(op, "tag");
        if (tag == null || tag.isEmpty()) return false;
        clean.addProperty("tag", tag.length() > 40 ? tag.substring(0, 40) : tag);
        return true;
    }

    // ---- small readers
    // ------------------------------------------------------------------------------------------------

    private static String string(JsonObject op, String key) {
        JsonElement value = op.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static boolean oneOf(JsonObject op, String key, Set<String> allowed, JsonObject clean) {
        String value = string(op, key);
        if (value == null || !allowed.contains(value)) return false;
        clean.addProperty(key, value);
        return true;
    }

    /** Copies an integer in range; an absent value is fine for an optional one, an out-of-range value is clamped. */
    private static boolean copyInt(JsonObject op, String key, int low, int high, JsonObject clean) {
        JsonElement value = op.get(key);
        if (value == null) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        double raw = value.getAsDouble();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return false;
        clean.addProperty(key, (int) Math.max(low, Math.min(high, Math.round(raw))));
        return true;
    }

    private static boolean side(JsonObject op, JsonObject clean) {
        String side = string(op, "side");
        if (side == null || !(side.equals("self") || side.equals("foe") || side.equals("both"))) return false;
        clean.addProperty("side", side);
        return true;
    }
}
