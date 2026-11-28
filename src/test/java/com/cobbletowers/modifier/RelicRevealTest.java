package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.TestRuns;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: the relic card is shown for every relic a run gains, whatever gave it, and only for those. */
class RelicRevealTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final ResourceLocation MASK = ResourceLocation.fromNamespaceAndPath("cobbletowers", "berserkers_mask");
    private static final ResourceLocation PURSE = ResourceLocation.fromNamespaceAndPath("cobbletowers", "coin_purse");

    private static PersistedRun holding(ResourceLocation... relics) {
        RunModifierState state = RunModifierState.EMPTY;
        for (ResourceLocation relic : relics) state = state.withRelic(relic, 1);
        return TestRuns.fresh(RUN).withModifiers(state, 0L);
    }

    @Test
    @DisplayName("a newly held relic is gained; one already held is not")
    void gained() {
        assertEquals(List.of(PURSE), RelicReveal.gained(holding(MASK), holding(MASK, PURSE)));
        assertEquals(List.of(), RelicReveal.gained(holding(MASK), holding(MASK)));
    }

    @Test
    @DisplayName("a swap (the Mystic Pool) shows the relic that came in")
    void swap() {
        assertEquals(List.of(PURSE), RelicReveal.gained(holding(MASK), holding(PURSE)));
    }

    @Test
    @DisplayName("the HUD strip is resent when the relics change at all (a gain, a loss, a swap) and not otherwise")
    void shelf() {
        assertEquals(true, RelicReveal.shelfChanged(holding(MASK), holding(MASK, PURSE)));
        assertEquals(true, RelicReveal.shelfChanged(holding(MASK, PURSE), holding(MASK)));
        assertEquals(true, RelicReveal.shelfChanged(holding(MASK), holding(PURSE)));
        assertEquals(false, RelicReveal.shelfChanged(holding(MASK), holding(MASK)));
        assertEquals(true, RelicReveal.shelfChanged(null, holding(MASK)), "a run that starts with a relic shows it");
        assertEquals(false, RelicReveal.shelfChanged(null, holding()), "an empty new run sends nothing");
    }

    @Test
    @DisplayName("a run being created, or loaded after a restart, gains nothing to show")
    void noPrevious() {
        assertEquals(List.of(), RelicReveal.gained(null, holding(MASK)));
    }
}
