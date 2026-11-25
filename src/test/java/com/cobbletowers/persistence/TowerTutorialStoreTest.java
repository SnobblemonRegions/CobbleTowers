package com.cobbletowers.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TowerTutorialStoreTest {

    @Test
    @DisplayName("Tutorial Tower (25 Nov 2026): the first clear is reported once, so the starter kit is paid once, and a clear is never undone")
    void clearsOnce() {
        TowerTutorialStore store = new TowerTutorialStore();
        UUID player = UUID.randomUUID();
        assertFalse(store.started(player));
        store.markStarted(player);
        assertTrue(store.started(player));
        assertFalse(store.cleared(player));
        assertTrue(store.markCleared(player), "the first clear pays");
        assertFalse(store.markCleared(player), "a replay's clear does not");
        store.markStarted(player);
        assertTrue(store.cleared(player), "starting again never undoes a clear");
    }

    @Test
    @DisplayName("who started and who cleared the tutorial survive a save and a load")
    void roundTrip() {
        TowerTutorialStore store = new TowerTutorialStore();
        UUID started = UUID.randomUUID(), cleared = UUID.randomUUID();
        store.markStarted(started);
        store.markCleared(cleared);
        TowerTutorialStore restored = TowerTutorialStore.load(store.save(new CompoundTag(), null), null);
        assertTrue(restored.started(started) && !restored.cleared(started));
        assertTrue(restored.cleared(cleared));
        assertFalse(restored.markCleared(cleared), "a restored clear still pays nothing more");
    }
}
