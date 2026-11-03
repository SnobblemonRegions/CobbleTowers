package com.cobbletowers.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Which facts a tower reveals about an upcoming opponent and the floor depth at which each starts hiding (TDS #22,
 * #49). Only decides when an existing fact reaches a player.
 */
public record ScoutingProfileDefinition(
        ResourceLocation id,
        int schemaVersion,
        int revision,
        List<RevealCategory> categories,
        java.util.Optional<Disguise> disguise) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * One fact and the depth it starts hiding at.
     * @param concealedFromFloor first floor it is hidden on; negative means never (TDS #49)
     */
    public record RevealCategory(String name, int concealedFromFloor) {
        public RevealCategory {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("a reveal category needs a name");
        }
    }

    /**
     * A decoy a Scouter and the typing line show instead of the real opponent, from {@code fromFloor} on (P54). Presentation only: the opponent
     * is still the one drawn.
     */
    public record Disguise(int fromFloor, List<ResourceLocation> decoys) {
        public Disguise {
            decoys = List.copyOf(decoys);
            if (decoys.isEmpty()) throw new IllegalArgumentException("a disguise needs at least one decoy species");
            if (fromFloor < 0) throw new IllegalArgumentException("disguise from_floor must be >= 0");
        }
    }

    public ScoutingProfileDefinition {
        Objects.requireNonNull(disguise, "disguise");
        Objects.requireNonNull(id, "id");
        categories = List.copyOf(categories);
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version " + schemaVersion + " is not supported; expected "
                    + SUPPORTED_SCHEMA_VERSION);
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1, got " + revision);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("a scouting profile needs at least one reveal category");
        }
    }

    public static ScoutingProfileDefinition fromJson(ResourceLocation id, JsonObject root) {
        List<RevealCategory> categories = new ArrayList<>();
        if (root.has("categories")) {
            if (!root.get("categories").isJsonArray()) {
                throw new IllegalArgumentException("field 'categories' must be an array");
            }
            for (JsonElement element : root.getAsJsonArray("categories")) {
                JsonObject category = element.getAsJsonObject();
                categories.add(new RevealCategory(
                        TowerJson.requireString(category, "name"),
                        TowerJson.integer(category, "concealed_from_floor", -1)));
            }
        }
        java.util.Optional<Disguise> disguise = java.util.Optional.empty();
        if (root.has("disguise")) {
            JsonObject d = root.getAsJsonObject("disguise");
            List<ResourceLocation> decoys = new ArrayList<>();
            for (JsonElement e : d.getAsJsonArray("decoys")) decoys.add(ResourceLocation.parse(e.getAsString()));
            disguise = java.util.Optional.of(new Disguise(TowerJson.integer(d, "from_floor", 0), decoys));
        }
        return new ScoutingProfileDefinition(
                id,
                TowerJson.requireInt(root, "schema_version"),
                TowerJson.integer(root, "revision", 1),
                categories,
                disguise);
    }
}
