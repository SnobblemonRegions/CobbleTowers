package com.cobbletowers.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.ScoutingProfileDefinition.Disguise;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ScoutingDisguiseTest {

    private static ResourceLocation r(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobblemon", path);
    }

    private final Optional<Disguise> rule = Optional.of(new Disguise(2, List.of(r("gastly"), r("litwick"), r("yamask"))));

    @Test
    void noRuleOrAnEarlyFloorMeansNoDecoy() {
        assertFalse(ScoutingDisguise.decoy(Optional.empty(), 5L, 4, 0, r("eevee")).isPresent());
        assertFalse(ScoutingDisguise.decoy(rule, 5L, 1, 0, r("eevee")).isPresent());
    }

    @Test
    void theDecoyIsSeededAndNeverTheRealSpecies() {
        for (long seed = 0; seed < 300; seed++) {
            Optional<ResourceLocation> a = ScoutingDisguise.decoy(rule, seed, 5, 1, r("gastly"));
            assertTrue(a.isPresent());
            assertEquals(a, ScoutingDisguise.decoy(rule, seed, 5, 1, r("gastly")));
            assertFalse(a.get().equals(r("gastly")), "the real species must never be the decoy");
        }
    }

    @Test
    void aPoolOfOnlyTheRealSpeciesGivesNoDecoy() {
        Optional<Disguise> only = Optional.of(new Disguise(0, List.of(r("gastly"))));
        assertFalse(ScoutingDisguise.decoy(only, 1L, 3, 0, r("gastly")).isPresent());
    }

    @Test
    void differentOpponentsOnAFloorCanShowDifferentDecoys() {
        long distinct = java.util.stream.IntStream.range(0, 8)
                .mapToObj(o -> ScoutingDisguise.decoy(rule, 42L, 5, o, r("eevee")).orElseThrow()).distinct().count();
        assertTrue(distinct > 1);
    }
}
