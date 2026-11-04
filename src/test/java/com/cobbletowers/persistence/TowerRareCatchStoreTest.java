package com.cobbletowers.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** The per-day count of Haunted rares a player caught (P58). */
class TowerRareCatchStoreTest {

    @Test
    void countsTodayOnlyAndSurvivesASaveAndLoad() {
        TowerRareCatchStore store = new TowerRareCatchStore();
        UUID player = new UUID(7, 7);
        assertEquals(0, store.catchesOn(player, "2026-11-02"));
        assertEquals(1, store.record(player, "2026-11-02"));
        assertEquals(2, store.record(player, "2026-11-02"));
        assertEquals(0, store.catchesOn(player, "2026-11-03"), "a new day starts at nothing");
        store.record(player, "2026-11-03");
        assertEquals(0, store.catchesOn(player, "2026-11-02"), "and the old day is forgotten");
        TowerRareCatchStore restored = TowerRareCatchStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(1, restored.catchesOn(player, "2026-11-03"));
        restored.reset(player);
        assertEquals(0, restored.catchesOn(player, "2026-11-03"));
    }

    @Test
    void aFailedGrantGivesTheCountBackAndNeverGoesBelowNothing() {
        TowerRareCatchStore store = new TowerRareCatchStore();
        UUID player = new UUID(8, 8);
        store.record(player, "2026-11-04");
        store.record(player, "2026-11-04");
        assertEquals(1, store.undo(player, "2026-11-04"));
        assertEquals(1, store.catchesOn(player, "2026-11-04"));
        assertEquals(0, store.undo(player, "2026-11-04"));
        assertEquals(0, store.undo(player, "2026-11-04"), "undoing with nothing recorded stays at nothing");
        assertEquals(0, store.catchesOn(player, "2026-11-04"));
    }
}
