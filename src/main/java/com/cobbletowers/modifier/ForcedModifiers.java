package com.cobbletowers.modifier;

import com.cobbletowers.api.modifier.ModifierType;
import com.cobbletowers.api.modifier.RiskTier;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.encounter.EncounterSeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The modifier an Ascension forces on a run (P30): one per Ascension, not refusable, and only ones that make the run
 * harder. Deterministic from the run seed and Ascension, respects exclusions, requirements and stack limits, and
 * forces nothing if none is eligible. Pure.
 */
public final class ForcedModifiers {

    /** Its own ordinal space, as every draw has (see {@link DraftDraw}). */
    static final int FORCED_ORDINAL_BASE = 4_000_037;

    private ForcedModifiers() {}

    /** Whether a modifier is the kind an Ascension may force. */
    public static boolean forcible(ModifierDefinition modifier) {
        boolean hardens = switch (modifier.type()) {
            case ENEMY, FIELD, PLAYER_CONSTRAINT -> true;
            case REWARD, SCOUTING, CUSTOM -> false;
        };
        return hardens && modifier.risk() != RiskTier.MINOR;
    }

    /** The modifier forced on entering {@code ascension}, given what the run already holds. */
    public static Optional<ModifierDefinition> draw(List<ModifierDefinition> pool, List<ModifierDefinition> held,
                                                    long runSeed, int ascension) {
        List<ModifierDefinition> candidates = ModifierResolver.eligibleFrom(
                pool.stream().filter(ForcedModifiers::forcible).toList(), held);
        if (candidates.isEmpty()) return Optional.empty();
        long seed = EncounterSeed.of(runSeed, ascension, FORCED_ORDINAL_BASE);
        return Optional.of(candidates.get(DraftDraw.pick(candidates, seed)));
    }

    /**
     * Every modifier Ascensions 1 through {@code ascension} force, in order; each draw sees what earlier ones took.
     * Stops quietly when the pool runs dry.
     */
    public static List<ModifierDefinition> drawAll(List<ModifierDefinition> pool, List<ModifierDefinition> held,
                                                   long runSeed, int ascension) {
        List<ModifierDefinition> now = new ArrayList<>(held);
        List<ModifierDefinition> forced = new ArrayList<>();
        for (int a = 1; a <= ascension; a++) {
            Optional<ModifierDefinition> next = draw(pool, now, runSeed, a);
            if (next.isEmpty()) break;
            forced.add(next.get());
            now.add(next.get());
        }
        return List.copyOf(forced);
    }
}
