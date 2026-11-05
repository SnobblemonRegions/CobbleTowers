package com.cobbletowers.trial;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.MilestoneDefinition;
import com.cobbletowers.definition.RotatingRecipeDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TrialPoolDefinition.Kind;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Builds the tower a rotating trial plays on (P46): copies of real floors, so every structure, pool and boss already
 * exists. Trial floor n is the source region's floor n, so difficulty and its boss and champion floors carry over;
 * only the floor ids are new, and each copy carries its source tower's ruleset as a floor override, which is how a
 * region's rules (P38) apply to its own segment. Pure: the tower is a function of the recipe, the period and the
 * loaded content.
 */
public final class RotatingTowers {

    private RotatingTowers() {}

    /** A rotating tower and the floors it adds to the content. */
    public record Built(TowerDefinition tower, List<FloorDefinition> floors) {}

    /** What a rotating tower id names. */
    public record Parsed(Kind kind, String periodKey, long number) {}

    private static final ResourceLocation STANDARD = CobbleTowers.id("standard");

    /** {@code cobbletowers:rotating/daily-2026-10-14}. */
    public static ResourceLocation idFor(Kind kind, String periodKey) {
        return CobbleTowers.id(TowerContent.ROTATING_PREFIX + kind.name().toLowerCase(Locale.ROOT) + "-" + periodKey);
    }

    /** The kind, period and period number a rotating tower id names, or empty for any other id. */
    public static Optional<Parsed> parse(ResourceLocation id) {
        if (!id.getNamespace().equals(CobbleTowers.MOD_ID) || !TowerContent.isRotating(id)) return Optional.empty();
        String name = id.getPath().substring(TowerContent.ROTATING_PREFIX.length());
        try {
            if (name.startsWith("daily-")) {
                String key = name.substring("daily-".length());
                LocalDate day = LocalDate.parse(key);
                return Optional.of(new Parsed(Kind.DAILY, key, day.toEpochDay()));
            }
            if (name.startsWith("weekly-")) {
                String key = name.substring("weekly-".length());
                return TrialClock.weekStartOf(key).map(start -> new Parsed(Kind.WEEKLY, key, start.toEpochDay() / 7));
            }
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Builds the tower for a period, or says why it cannot be (a region not loaded, a source tower too short). */
    public static Built build(RotatingRecipeDefinition recipe, RotatingRoll roll, ResourceLocation towerId, TowerContent base) {
        String slug = towerId.getPath();
        List<FloorDefinition> floors = new ArrayList<>();
        List<ResourceLocation> milestoneIds = new ArrayList<>();
        TowerDefinition last = null;
        for (int floorNumber = 1; floorNumber <= recipe.floors(); floorNumber++) {
            final int n = floorNumber;
            ResourceLocation region = roll.regionAt(recipe, n);
            TowerDefinition source = base.towers().get(region);
            if (source == null) throw new IllegalArgumentException("region " + region + " is not loaded");
            FloorDefinition from = base.floorAt(region, n)
                    .filter(floor -> floor.index() == n)
                    .orElseThrow(() -> new IllegalArgumentException(region + " has no floor " + n));
            ResourceLocation override = from.rulesetOverride().orElse(source.rulesetId());
            floors.add(new FloorDefinition(CobbleTowers.id(slug + "/floor_" + String.format(Locale.ROOT, "%02d", n)), n,
                    from.milestone(),
                    override.equals(STANDARD) ? Optional.empty() : Optional.of(override), from.modifierIds(), from.layout(),
                    from.bossPoolId()));
            if (from.milestone().isPresent()) {
                Optional<MilestoneDefinition> milestone = base.milestoneAt(region, n);
                if (milestone.isEmpty()) throw new IllegalArgumentException(region + " floor " + n + " is a milestone floor with no milestone");
                milestoneIds.add(milestone.get().id());
            }
            last = source;
        }
        // one reward table and theme for the whole tower: the last segment's region (the boss floor's)
        TowerDefinition tower = new TowerDefinition(towerId, recipe.displayName(), TowerDefinition.SUPPORTED_SCHEMA_VERSION, 1,
                STANDARD, last.rewardTableId(), floors.stream().map(FloorDefinition::id).toList(), milestoneIds,
                last.regionalTheme(), last.scoutingProfile(), false);
        return new Built(tower, floors);
    }
}
