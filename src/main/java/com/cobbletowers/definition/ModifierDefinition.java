package com.cobbletowers.definition;

import com.cobbletowers.api.modifier.ModifierType;
import com.cobbletowers.api.modifier.ModifierView;
import com.cobbletowers.api.modifier.RiskTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One drafted challenge: what it changes and what it cannot be held with (TDS #56, #58). The effect is one flat
 * record with neutral defaults; {@link #validateEffectMatchesType} refuses a definition whose effect does not match
 * its type.
 */
public record ModifierDefinition(
        ResourceLocation id,
        int schemaVersion,
        int revision,
        ModifierType type,
        String displayName,
        RiskTier risk,
        int weight,
        Optional<String> group,
        List<ResourceLocation> excludes,
        List<ResourceLocation> requires,
        int stackLimit,
        Effect effect,
        boolean relic,
        List<String> tags,
        Optional<TagBonus> tagBonus) implements ModifierView {

    /**
     * A set bonus (P45): never drafted or held; it applies while a run holds at least {@code count} relics carrying
     * {@code tag}.
     */
    public record TagBonus(String tag, int count) {
        public TagBonus {
            if (tag == null || tag.isBlank()) throw new IllegalArgumentException("tag_bonus needs a tag");
            if (count < 2 || count > 6) throw new IllegalArgumentException("tag_bonus count must be 2..6, got " + count);
        }
    }

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * What a modifier does; every default changes nothing. Percentages are integers (100 = unchanged) so they persist
     * and sum exactly.
     * @param bossLevelOffset boss level (ENEMY)
     * @param bossHealthPercent boss pool percentage (ENEMY)
     * @param rewardPercent earnings percentage (REWARD)
     * @param bannedMoves Showdown move ids (PLAYER_CONSTRAINT)
     * @param allowSwitching switching allowed (PLAYER_CONSTRAINT)
     * @param allowItems items allowed (PLAYER_CONSTRAINT)
     * @param weather Showdown weather id (FIELD)
     * @param terrain Showdown terrain id (FIELD)
     * @param custom id of a coded {@link CustomBehavior} (CUSTOM)
     * @param battle P23 battle operations, validated at load (CUSTOM)
     * @param scoutingBonus floors added to a scouting category's concealment threshold (SCOUTING)
     */
    public record Effect(
            int bossLevelOffset,
            int bossHealthPercent,
            int rewardPercent,
            List<String> bannedMoves,
            boolean allowSwitching,
            boolean allowItems,
            Optional<String> weather,
            Optional<String> terrain,
            int scoutingBonus,
            Optional<String> custom,
            JsonArray battle) {

        /** Changes nothing: the baseline every field is measured against. */
        public static final Effect NEUTRAL =
                new Effect(0, 100, 100, List.of(), true, true, Optional.empty(), Optional.empty(), 0,
                        Optional.empty(), new JsonArray());

        /** Everything except battle operations, which is what every modifier before P45 declared. */
        public Effect(int bossLevelOffset, int bossHealthPercent,
                      int rewardPercent, List<String> bannedMoves, boolean allowSwitching, boolean allowItems,
                      Optional<String> weather, Optional<String> terrain, int scoutingBonus, Optional<String> custom) {
            this(bossLevelOffset, bossHealthPercent, rewardPercent, bannedMoves,
                    allowSwitching, allowItems, weather, terrain, scoutingBonus, custom, new JsonArray());
        }

        /** Everything except a coded behavior, which is what every modifier before P29 declared. */
        public Effect(int bossLevelOffset, int bossHealthPercent,
                      int rewardPercent, List<String> bannedMoves, boolean allowSwitching, boolean allowItems,
                      Optional<String> weather, Optional<String> terrain, int scoutingBonus) {
            this(bossLevelOffset, bossHealthPercent, rewardPercent, bannedMoves,
                    allowSwitching, allowItems, weather, terrain, scoutingBonus, Optional.empty(), new JsonArray());
        }

        public Effect {
            bannedMoves = List.copyOf(bannedMoves);
            Objects.requireNonNull(weather, "weather");
            Objects.requireNonNull(terrain, "terrain");
            Objects.requireNonNull(custom, "custom");
            Objects.requireNonNull(battle, "battle");
            battle = battle.deepCopy();
            custom.ifPresent(id -> {
                if (CustomBehavior.fromId(id).isEmpty()) {
                    throw new IllegalArgumentException("custom '" + id + "' is not a coded behavior; known: "
                            + CustomBehavior.ids());
                }
            });
            if (bossHealthPercent < 1) {
                throw new IllegalArgumentException("boss_health_percent must be >= 1, got " + bossHealthPercent);
            }
            if (rewardPercent < 0) {
                throw new IllegalArgumentException("reward_percent must be >= 0, got " + rewardPercent);
            }
        }

        /** Whether this effect leaves the enemies exactly as they would have been. */
        public boolean touchesEnemies() {
            return bossLevelOffset != 0 || bossHealthPercent != 100;
        }

        public boolean touchesReward() {
            return rewardPercent != 100;
        }

        public boolean touchesConstraints() {
            return !bannedMoves.isEmpty() || !allowSwitching || !allowItems;
        }

        public boolean touchesField() {
            return weather.isPresent() || terrain.isPresent();
        }

        public boolean touchesScouting() {
            return scoutingBonus != 0;
        }

        public boolean touchesCustom() {
            return custom.isPresent() || !battle.isEmpty();
        }

        public static Effect fromJson(JsonObject root) {
            if (root == null) return NEUTRAL;
            return new Effect(
                    TowerJson.integer(root, "boss_level_offset", 0),
                    TowerJson.integer(root, "boss_health_percent", 100),
                    TowerJson.integer(root, "reward_percent", 100),
                    TowerJson.strings(root, "banned_moves"),
                    TowerJson.bool(root, "allow_switching", true),
                    TowerJson.bool(root, "allow_items", true),
                    optionalString(root, "weather"),
                    optionalString(root, "terrain"),
                    TowerJson.integer(root, "scouting_bonus", 0),
                    optionalString(root, "custom"),
                    battleOf(root));
        }

        /** The {@code battle} array, every operation run through the validator the armor sets and playlists use. */
        private static JsonArray battleOf(JsonObject root) {
            JsonArray out = new JsonArray();
            if (!root.has("battle")) return out;
            if (!root.get("battle").isJsonArray()) throw new IllegalArgumentException("effect.battle must be an array");
            for (JsonElement element : root.getAsJsonArray("battle")) {
                out.add(com.cobbletowers.showdown.TowerBattleFx.validate(element).orElseThrow(() ->
                        new IllegalArgumentException("effect.battle has an operation that is not valid: " + element)));
            }
            return out;
        }

        private static Optional<String> optionalString(JsonObject root, String key) {
            String value = TowerJson.string(root, key, "");
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    public ModifierDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(group, "group");
        excludes = List.copyOf(excludes);
        requires = List.copyOf(requires);
        tags = List.copyOf(tags);
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version " + schemaVersion + " is not supported; expected "
                    + SUPPORTED_SCHEMA_VERSION);
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1, got " + revision);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name must not be blank");
        }
        if (weight < 1) throw new IllegalArgumentException("weight must be >= 1, got " + weight);
        if (stackLimit < 1) throw new IllegalArgumentException("stack_limit must be >= 1, got " + stackLimit);
        Objects.requireNonNull(tagBonus, "tagBonus");
        if (tagBonus.isPresent() && relic) throw new IllegalArgumentException(id + " is a tag bonus and cannot also be a relic");
        if (excludes.contains(id)) {
            throw new IllegalArgumentException(id + " excludes itself");
        }
        if (requires.contains(id)) {
            // Nothing could ever satisfy it: the modifier would have to be held before it can be
            // offered, and it can only be held by being offered.
            throw new IllegalArgumentException(id + " requires itself");
        }
        for (ResourceLocation required : requires) {
            if (excludes.contains(required)) {
                throw new IllegalArgumentException(id + " both requires and excludes " + required);
            }
        }
        validateEffectMatchesType(id, type, effect);
    }

    /**
     * Refuses a definition whose effect does not match its type, e.g. a copied modifier with a changed type but the
     * old payload.
     */
    private static void validateEffectMatchesType(ResourceLocation id, ModifierType type, Effect effect) {
        boolean matches = switch (type) {
            case ENEMY -> effect.touchesEnemies();
            case PLAYER_CONSTRAINT -> effect.touchesConstraints();
            case FIELD -> effect.touchesField();
            case REWARD -> effect.touchesReward();
            case SCOUTING -> effect.touchesScouting();
            case CUSTOM -> effect.touchesCustom();
        };
        if (!matches) {
            throw new IllegalArgumentException(id + " is type " + type
                    + " but its effect changes nothing that type applies");
        }
    }

    /**
     * Whether anything in this build would apply this modifier; asked of the effect, not the type. Currently true for
     * any non-neutral effect.
     */
    public boolean effectiveNow() {
        return !effect.equals(Effect.NEUTRAL);
    }

    public static ModifierDefinition fromJson(ResourceLocation id, JsonObject root) {
        return new ModifierDefinition(
                id,
                TowerJson.requireInt(root, "schema_version"),
                TowerJson.integer(root, "revision", 1),
                parseEnum(ModifierType.class, TowerJson.requireString(root, "type"), "type"),
                TowerJson.requireString(root, "display_name"),
                parseEnum(RiskTier.class, TowerJson.string(root, "risk", "moderate"), "risk"),
                TowerJson.integer(root, "weight", 100),
                Optional.of(TowerJson.string(root, "group", "")).filter(value -> !value.isEmpty()),
                TowerJson.ids(root, "excludes"),
                TowerJson.ids(root, "requires"),
                TowerJson.integer(root, "stack_limit", 1),
                Effect.fromJson(TowerJson.object(root, "effect")),
                TowerJson.bool(root, "relic", false),
                TowerJson.strings(root, "tags"),
                tagBonusOf(root));
    }

    private static Optional<TagBonus> tagBonusOf(JsonObject root) {
        JsonObject bonus = TowerJson.object(root, "tag_bonus");
        if (bonus == null || !root.has("tag_bonus")) return Optional.empty();
        return Optional.of(new TagBonus(TowerJson.requireString(bonus, "tag"), TowerJson.requireInt(bonus, "count")));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String key) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException("field '" + key + "' is not a known " + type.getSimpleName()
                    + ": " + raw);
        }
    }
}
