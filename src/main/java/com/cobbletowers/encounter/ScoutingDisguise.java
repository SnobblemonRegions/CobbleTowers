package com.cobbletowers.encounter;

import com.cobbletowers.definition.ScoutingProfileDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * The decoy a disguised floor shows in place of the real opponent (P54): a species drawn by the run seed from the profile's pool, never the real one.
 * Presentation only; the battle is the opponent that was drawn.
 */
final class ScoutingDisguise {

    /** Its own ordinal space, like every other draw. */
    static final int ORDINAL_BASE = 7_000_077;

    private ScoutingDisguise() {}

    /** The decoy for this opponent, or empty when the floor is not disguised. Same inputs, same decoy, so a replay shows the same lie. */
    static Optional<ResourceLocation> decoy(Optional<ScoutingProfileDefinition.Disguise> disguise, long runSeed, int floorIndex, int ordinal,
                                            ResourceLocation real) {
        if (disguise.isEmpty() || floorIndex < disguise.get().fromFloor()) return Optional.empty();
        List<ResourceLocation> pool = new ArrayList<>(disguise.get().decoys());
        pool.remove(real);
        if (pool.isEmpty()) return Optional.empty();
        long seed = EncounterSeed.of(runSeed, floorIndex, ORDINAL_BASE + ordinal);
        return Optional.of(pool.get((int) Math.floorMod(seed, pool.size())));
    }

    private static Optional<ScoutingProfileDefinition.Disguise> rule(FloorSetup setup) {
        return setup.content().scoutingProfileFor(setup.tower().id()).flatMap(ScoutingProfileDefinition::disguise);
    }

    /** The species a Scouter is shown: the decoy where the floor is disguised, else the real one. */
    static ResourceLocation shown(FloorSetup setup, int ordinal, ResourceLocation real) {
        return decoy(rule(setup), setup.run().seed(), setup.run().floorIndex(), ordinal, real).orElse(real);
    }

    static boolean disguised(FloorSetup setup, int ordinal, ResourceLocation real) {
        return decoy(rule(setup), setup.run().seed(), setup.run().floorIndex(), ordinal, real).isPresent();
    }
}
