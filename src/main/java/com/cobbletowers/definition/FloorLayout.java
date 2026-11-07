package com.cobbletowers.definition;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * What a floor is built from and where its anchors are (TDS section 12). The schematics carry no markers, so anchors
 * are declared here and checked against the structure's real size offline and the pasted result at runtime.
 * @param structure the template, from data/&lt;namespace&gt;/structure/&lt;name&gt;.nbt
 * @param entry where the party arrives
 * @param presentation where the opposing Cobblemon is shown
 * @param spectator where a knocked-out player watches from (TDS #25)
 * @param exit the way to the next floor
 * @param gateA the blocks that keep the party out of the intermission room until the floor is won (a walkable tower), if any
 * @param gateB the blocks that keep the party off the stair until everyone is ready, if any; a floor that has one is left on foot
 * @param doors the doors behind the pedestals, left to right: lane {@code i} of the route is door {@code i}; none on a floor with no doors
 */
public record FloorLayout(
        ResourceLocation structure,
        FloorAnchor entry,
        FloorAnchor presentation,
        FloorAnchor spectator,
        FloorAnchor exit,
        Optional<FloorBox> gateA,
        Optional<FloorBox> gateB,
        List<FloorDoor> doors) {

    /** A floor with gates but no route doors. */
    public FloorLayout(ResourceLocation structure, FloorAnchor entry, FloorAnchor presentation, FloorAnchor spectator, FloorAnchor exit,
                       Optional<FloorBox> gateA, Optional<FloorBox> gateB) {
        this(structure, entry, presentation, spectator, exit, gateA, gateB, List.of());
    }

    /** A floor with no gates, which is every floor that is left by teleport. */
    public FloorLayout(ResourceLocation structure, FloorAnchor entry, FloorAnchor presentation, FloorAnchor spectator, FloorAnchor exit) {
        this(structure, entry, presentation, spectator, exit, Optional.empty(), Optional.empty(), List.of());
    }

    public FloorLayout {
        Objects.requireNonNull(gateA, "gateA");
        Objects.requireNonNull(gateB, "gateB");
        doors = List.copyOf(doors);
        if (doors.size() > 3) throw new IllegalArgumentException("a floor has at most three doors, one per route lane, got " + doors.size());
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(spectator, "spectator");
        Objects.requireNonNull(exit, "exit");
    }

    public static FloorLayout fromJson(JsonObject root) {
        return new FloorLayout(
                TowerJson.requireId(root, "structure"),
                FloorAnchor.fromJson(root, "entry"),
                FloorAnchor.fromJson(root, "presentation"),
                FloorAnchor.fromJson(root, "spectator"),
                FloorAnchor.fromJson(root, "exit"),
                FloorBox.optional(root, "gate_a"),
                FloorBox.optional(root, "gate_b"),
                FloorDoor.list(root, "doors"));
    }

    /** Whether every door has a stair of its own (P58): the party climbs the lane it voted for and is taken up from its landing. */
    public boolean laneStairs() {
        return !doors.isEmpty() && doors.stream().allMatch(door -> door.stairTop().isPresent() && door.arrival().isPresent());
    }

    /** Whether this floor is left on foot rather than by teleport: it has a stair gate (the old shared stair) or a stair for every lane. */
    public boolean walkable() {
        return gateB.isPresent() || laneStairs();
    }

    /** Every anchor with the name it is known by, for validation and for messages. */
    public Map<String, FloorAnchor> anchors() {
        return Map.of("entry", entry, "presentation", presentation, "spectator", spectator, "exit", exit);
    }

    /** The anchors in a fixed order, so a report reads the same way twice. */
    public List<String> anchorNames() {
        return List.of("entry", "presentation", "spectator", "exit");
    }
}
