package com.cobbletowers.definition;

import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.showdown.TowerBattleFx;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * A bespoke floor such as floor 5's boss or floor 10's championship. A {@link MilestoneKind#BOSS} names the
 * CobbleRaids definition it is built from (TDS #52).
 * @param banksRewards whether clearing it banks the run's rewards (TDS #24)
 * @param phases the boss's phases (P58): validated {@code phase} operations in their logical form (side foe = the boss), at most {@link #MAX_PHASES}
 */
public record MilestoneDefinition(
        ResourceLocation id,
        int floorIndex,
        MilestoneKind kind,
        Optional<ResourceLocation> raidDefinitionId,
        boolean banksRewards,
        JsonArray phases) {

    /** The most phases one boss may have. */
    public static final int MAX_PHASES = 3;

    /** A milestone with no phases: every one before P58. */
    public MilestoneDefinition(ResourceLocation id, int floorIndex, MilestoneKind kind,
                               Optional<ResourceLocation> raidDefinitionId, boolean banksRewards) {
        this(id, floorIndex, kind, raidDefinitionId, banksRewards, new JsonArray());
    }

    public MilestoneDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(raidDefinitionId, "raidDefinitionId");
        Objects.requireNonNull(phases, "phases");
        phases = phases.deepCopy();
        if (phases.size() > MAX_PHASES) {
            throw new IllegalArgumentException("a boss has at most " + MAX_PHASES + " phases, got " + phases.size());
        }
        if (floorIndex < 1) throw new IllegalArgumentException("floor must be >= 1, got " + floorIndex);
        if (raidDefinitionId.isEmpty()) {
            // Both kinds need a definition: every floor ends with a CobbleRaids boss, so a milestone without one
            // cannot be completed.
            throw new IllegalArgumentException("a " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + " milestone must name a raid_definition; a milestone floor is finished by it");
        }
    }

    public static MilestoneDefinition fromJson(ResourceLocation id, JsonObject root) {
        return new MilestoneDefinition(
                id,
                TowerJson.requireInt(root, "floor"),
                parseKind(TowerJson.requireString(root, "kind")),
                TowerJson.optionalId(root, "raid_definition"),
                TowerJson.bool(root, "banks_rewards", true),
                phasesOf(root));
    }

    /** The {@code phases} list, each entry validated as a boss phase; one that is not valid refuses the file rather than silently doing nothing. */
    private static JsonArray phasesOf(JsonObject root) {
        JsonArray out = new JsonArray();
        JsonElement raw = root.get("phases");
        if (raw == null) return out;
        if (!raw.isJsonArray()) throw new IllegalArgumentException("field 'phases' must be a list");
        for (JsonElement element : raw.getAsJsonArray()) {
            if (element.isJsonObject()) element.getAsJsonObject().addProperty("op", "phase");
            out.add(TowerBattleFx.validate(element).orElseThrow(() ->
                    new IllegalArgumentException("a phase is not valid (needs side, when.hp_below or when.turn, and at least one valid effect): " + element)));
        }
        return out;
    }

    private static MilestoneKind parseKind(String raw) {
        try {
            return MilestoneKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("field 'kind' must be boss or champion, got '" + raw + "'");
        }
    }
}
