package com.cobbletowers.definition;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One tower: floors in order under a ruleset. Validated in the constructor, so an invalid tower cannot exist. A run
 * records {@code schemaVersion}, {@code revision} and the registry's digest, so an edit is distinguishable from a
 * renumber (TDS #40).
 * @param floorIds every floor, in play order
 * @param milestoneIds milestone definitions used; their floor indices must exist
 * @param regionalTheme a regional theme id; carried, resolved elsewhere
 * @param ascension whether the tower continues past its last floor into harder cycles (P30)
 * @param scoutingProfile the reveal-threshold profile (P12); empty means everything reveals (TDS #49)
 * @param pedestalVotes whether the intermission shows the modifier cards as clickable hologram pedestals (P55)
 * @param routeMap whether the route is one branching map drawn from the run seed, not a fresh draw per step (P56)
 * @param bossRush whether every floor is its boss alone, with no opponent wave first (balance phase 2); on unless a definition
 *                 says {@code "boss_rush": false}, since towers have no basic battles
 */
public record TowerDefinition(
        ResourceLocation id,
        String displayName,
        int schemaVersion,
        int revision,
        ResourceLocation rulesetId,
        ResourceLocation rewardTableId,
        List<ResourceLocation> floorIds,
        List<ResourceLocation> milestoneIds,
        Optional<ResourceLocation> regionalTheme,
        Optional<ResourceLocation> scoutingProfile,
        boolean ascension,
        Optional<TowerWindow> window,
        boolean pedestalVotes,
        boolean routeMap,
        boolean bossRush) {

    /** A tower whose floors open with an opponent wave, which is every tower before balance phase 2. */
    public TowerDefinition(ResourceLocation id, String displayName, int schemaVersion, int revision,
                           ResourceLocation rulesetId, ResourceLocation rewardTableId, List<ResourceLocation> floorIds,
                           List<ResourceLocation> milestoneIds, Optional<ResourceLocation> regionalTheme,
                           Optional<ResourceLocation> scoutingProfile, boolean ascension, Optional<TowerWindow> window,
                           boolean pedestalVotes, boolean routeMap) {
        this(id, displayName, schemaVersion, revision, rulesetId, rewardTableId, floorIds, milestoneIds, regionalTheme,
                scoutingProfile, ascension, window, pedestalVotes, routeMap, false);
    }

    /** A tower whose route is the per-step draw, which is every tower before P56. */
    public TowerDefinition(ResourceLocation id, String displayName, int schemaVersion, int revision,
                           ResourceLocation rulesetId, ResourceLocation rewardTableId, List<ResourceLocation> floorIds,
                           List<ResourceLocation> milestoneIds, Optional<ResourceLocation> regionalTheme,
                           Optional<ResourceLocation> scoutingProfile, boolean ascension, Optional<TowerWindow> window,
                           boolean pedestalVotes) {
        this(id, displayName, schemaVersion, revision, rulesetId, rewardTableId, floorIds, milestoneIds, regionalTheme,
                scoutingProfile, ascension, window, pedestalVotes, false);
    }

    /** A tower whose modifier vote is the menu alone, which is every tower before P55. */
    public TowerDefinition(ResourceLocation id, String displayName, int schemaVersion, int revision,
                           ResourceLocation rulesetId, ResourceLocation rewardTableId, List<ResourceLocation> floorIds,
                           List<ResourceLocation> milestoneIds, Optional<ResourceLocation> regionalTheme,
                           Optional<ResourceLocation> scoutingProfile, boolean ascension, Optional<TowerWindow> window) {
        this(id, displayName, schemaVersion, revision, rulesetId, rewardTableId, floorIds, milestoneIds, regionalTheme,
                scoutingProfile, ascension, window, false);
    }

    /** A tower open all the time, which is every tower before P54. */
    public TowerDefinition(ResourceLocation id, String displayName, int schemaVersion, int revision,
                           ResourceLocation rulesetId, ResourceLocation rewardTableId, List<ResourceLocation> floorIds,
                           List<ResourceLocation> milestoneIds, Optional<ResourceLocation> regionalTheme,
                           Optional<ResourceLocation> scoutingProfile, boolean ascension) {
        this(id, displayName, schemaVersion, revision, rulesetId, rewardTableId, floorIds, milestoneIds, regionalTheme,
                scoutingProfile, ascension, Optional.empty());
    }

    /** A tower that ends at its last floor, which is what every tower was before P30. */
    public TowerDefinition(ResourceLocation id, String displayName, int schemaVersion, int revision,
                           ResourceLocation rulesetId, ResourceLocation rewardTableId, List<ResourceLocation> floorIds,
                           List<ResourceLocation> milestoneIds, Optional<ResourceLocation> regionalTheme,
                           Optional<ResourceLocation> scoutingProfile) {
        this(id, displayName, schemaVersion, revision, rulesetId, rewardTableId, floorIds, milestoneIds, regionalTheme,
                scoutingProfile, false);
    }

    /** The only shape this build understands. A newer file is skipped with a message, not guessed at. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public TowerDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rulesetId, "rulesetId");
        Objects.requireNonNull(rewardTableId, "rewardTableId");
        Objects.requireNonNull(regionalTheme, "regionalTheme");
        Objects.requireNonNull(scoutingProfile, "scoutingProfile");
        Objects.requireNonNull(window, "window");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name must not be blank");
        }
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version " + schemaVersion + " is not supported; expected "
                    + SUPPORTED_SCHEMA_VERSION);
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1, got " + revision);
        floorIds = List.copyOf(floorIds);
        milestoneIds = List.copyOf(milestoneIds);
        if (floorIds.isEmpty()) throw new IllegalArgumentException("a tower needs at least one floor");
        if (new HashSet<>(floorIds).size() != floorIds.size()) {
            throw new IllegalArgumentException("floors must not repeat: " + floorIds);
        }
        if (new HashSet<>(milestoneIds).size() != milestoneIds.size()) {
            throw new IllegalArgumentException("milestones must not repeat: " + milestoneIds);
        }
    }

    /**
     * Whether a new run may start at {@code nowMillis}; a tower without a window is always open. A rig opens event
     * towers early with {@code -Dcobbletowers.ignoreWindows=true}.
     */
    public boolean isOpen(long nowMillis) {
        return Boolean.getBoolean("cobbletowers.ignoreWindows") || window.map(w -> w.contains(nowMillis)).orElse(true);
    }

    public int floorCount() {
        return floorIds.size();
    }

    /**
     * The tower floor a run floor is, for content lookups. Run floors past the last are later cycles of an ascending
     * tower; otherwise returned as is.
     */
    public int contentFloor(int runFloor) {
        return ascension ? com.cobbletowers.ascension.AscensionPolicy.towerFloorOf(runFloor, floorCount()) : runFloor;
    }

    /** Which cycle a run floor is in (0 is the base cycle); always 0 for a tower that does not ascend. */
    public int ascensionOf(int runFloor) {
        return ascension ? com.cobbletowers.ascension.AscensionPolicy.ascensionOf(runFloor, floorCount()) : 0;
    }

    public static TowerDefinition fromJson(ResourceLocation id, JsonObject root) {
        return new TowerDefinition(
                id,
                TowerJson.requireString(root, "display_name"),
                TowerJson.requireInt(root, "schema_version"),
                TowerJson.integer(root, "revision", 1),
                TowerJson.requireId(root, "ruleset"),
                TowerJson.requireId(root, "reward_table"),
                TowerJson.requireIds(root, "floors"),
                TowerJson.ids(root, "milestones"),
                TowerJson.optionalId(root, "regional_theme"),
                TowerJson.optionalId(root, "scouting_profile"),
                TowerJson.bool(root, "ascension", false),
                root.has("window") ? Optional.of(TowerWindow.parse(
                        TowerJson.requireString(TowerJson.object(root, "window"), "start"),
                        TowerJson.requireString(TowerJson.object(root, "window"), "end"))) : Optional.empty(),
                TowerJson.bool(root, "pedestal_votes", false),
                TowerJson.bool(root, "route_map", false),
                TowerJson.bool(root, "boss_rush", true));
    }
}
