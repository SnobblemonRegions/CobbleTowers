package com.cobbletowers.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One of the doors behind a floor's modifier pedestals (a walkable tower, P55): the bars that are cleared when the route is chosen, the frame
 * that lights up, and where the pedestal in front of it stands. A floor lists its doors in the order the party sees them, left to right, so
 * door {@code i} is route lane {@code i}.
 * @param open the blocks cleared to open the door
 * @param frame the blocks that light up over it
 * @param pedestal where the pedestal for this door stands, in front of it
 * @param stairTop the threshold at the top of this lane's own stair (P58): the party that stands in it is faded out and taken to {@code arrival}; none on
 *                 the last floor of a cycle, which is left by teleport
 * @param arrival where this lane's stair drops the party on the floor above, in the arrival hall; present exactly when {@code stairTop} is
 */
public record FloorDoor(FloorBox open, FloorBox frame, FloorAnchor pedestal, Optional<FloorBox> stairTop, Optional<FloorAnchor> arrival) {

    public FloorDoor {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(pedestal, "pedestal");
        Objects.requireNonNull(stairTop, "stairTop");
        Objects.requireNonNull(arrival, "arrival");
        if (stairTop.isPresent() != arrival.isPresent()) throw new IllegalArgumentException("a door's 'stair_top' and 'arrival' go together");
    }

    /** A door with no stair of its own, which is every door of a floor that is left by teleport. */
    public FloorDoor(FloorBox open, FloorBox frame, FloorAnchor pedestal) {
        this(open, frame, pedestal, Optional.empty(), Optional.empty());
    }

    /** The doors under {@code key}, none when the layout has no such list. */
    public static List<FloorDoor> list(JsonObject root, String key) {
        if (!root.has(key)) return List.of();
        if (!root.get(key).isJsonArray()) throw new IllegalArgumentException("'" + key + "' must be a list of doors");
        JsonArray array = root.getAsJsonArray(key);
        List<FloorDoor> doors = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject door = element.getAsJsonObject();
            doors.add(new FloorDoor(
                    FloorBox.optional(door, "open").orElseThrow(() -> new IllegalArgumentException("a door needs an 'open' box")),
                    FloorBox.optional(door, "frame").orElseThrow(() -> new IllegalArgumentException("a door needs a 'frame' box")),
                    FloorAnchor.fromJson(door, "pedestal"),
                    FloorBox.optional(door, "stair_top"),
                    door.has("arrival") ? Optional.of(FloorAnchor.fromJson(door, "arrival")) : Optional.empty()));
        }
        return List.copyOf(doors);
    }
}
