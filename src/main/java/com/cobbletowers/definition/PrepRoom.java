package com.cobbletowers.definition;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * A tower's preparation room (P59): the small building the party is taken into from the lobby before floor 1. It is pasted into the run's own
 * cell, under the floor-1 building, once that building is up. Anchors and boxes are relative to the prep structure's own paste origin.
 * @param structure the template, from data/&lt;namespace&gt;/structure/&lt;name&gt;.nbt
 * @param entry where the party lands from the lobby
 * @param vendor where the run-supplies vendor stands
 * @param dais the ready ring: the great door opens once every fighter stands in it
 * @param door the great door's bars, cleared when it opens
 * @param threshold beyond the door: the whole team standing here fades and is taken to floor 1
 */
public record PrepRoom(ResourceLocation structure, FloorAnchor entry, FloorAnchor vendor, FloorBox dais, FloorBox door, FloorBox threshold) {

    public PrepRoom {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(dais, "dais");
        Objects.requireNonNull(door, "door");
        Objects.requireNonNull(threshold, "threshold");
    }

    /** The prep room under {@code key}, or empty when the tower has none. */
    public static Optional<PrepRoom> optional(JsonObject root, String key) {
        if (!root.has(key)) return Optional.empty();
        JsonObject prep = TowerJson.object(root, key);
        return Optional.of(new PrepRoom(
                TowerJson.requireId(prep, "structure"),
                FloorAnchor.fromJson(prep, "entry"),
                FloorAnchor.fromJson(prep, "vendor"),
                required(prep, "dais"),
                required(prep, "door"),
                required(prep, "threshold")));
    }

    private static FloorBox required(JsonObject prep, String key) {
        return FloorBox.optional(prep, key).orElseThrow(() -> new IllegalArgumentException("a prep room needs a '" + key + "' box"));
    }
}
