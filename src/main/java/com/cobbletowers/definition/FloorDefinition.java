package com.cobbletowers.definition;

import com.cobbletowers.api.tower.FloorView;
import com.cobbletowers.api.tower.MilestoneKind;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One floor: its opponent pool and any rules that differ from the tower's. Implements {@link FloorView} directly.
 * @param rulesetOverride optional, for a floor that bends the tower's rules
 * @param modifierIds reserved for P8; carried, not resolved here
 * @param layout what to paste and where the anchors are; absent on old content, which loads but cannot be prepared
 * @param bossPoolId the bosses this floor may finish with; absent on a milestone floor, which uses the milestone's
 *     handpicked definition
 */
public record FloorDefinition(
        ResourceLocation id,
        int index,
        Optional<MilestoneKind> milestone,
        Optional<ResourceLocation> rulesetOverride,
        List<ResourceLocation> modifierIds,
        Optional<FloorLayout> layout,
        Optional<ResourceLocation> bossPoolId) implements FloorView {

    public FloorDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(milestone, "milestone");
        Objects.requireNonNull(rulesetOverride, "rulesetOverride");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(bossPoolId, "bossPoolId");
        modifierIds = List.copyOf(modifierIds);
        if (index < 1) throw new IllegalArgumentException("index must be >= 1, got " + index);
    }

    public static FloorDefinition fromJson(ResourceLocation id, JsonObject root) {
        String milestone = TowerJson.string(root, "milestone", "").trim();
        return new FloorDefinition(
                id,
                TowerJson.requireInt(root, "index"),
                milestone.isEmpty() ? Optional.empty() : Optional.of(parseMilestone(milestone)),
                TowerJson.optionalId(root, "ruleset_override"),
                TowerJson.ids(root, "modifiers"),
                // Optional: a floor with no layout is content that predates the arenas, and it loads
                // rather than failing -- it simply cannot be prepared until it names one.
                root.has("layout") ? Optional.of(FloorLayout.fromJson(TowerJson.object(root, "layout")))
                        : Optional.empty(),
                // Optional: a milestone floor takes its handpicked boss from the milestone instead,
                // and content written before bosses existed still loads.
                TowerJson.optionalId(root, "boss_pool"));
    }

    private static MilestoneKind parseMilestone(String raw) {
        try {
            return MilestoneKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("field 'milestone' must be boss or champion, got '" + raw + "'");
        }
    }
}
