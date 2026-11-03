package com.cobbletowers.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.trick.TrickRules;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** The per-day Candy Corn a player was paid, and the cap built on it (audit finding). */
class TowerCandyCornStoreTest {

    @Test
    void countsTodayOnlyAndSurvivesASaveAndLoad() {
        TowerCandyCornStore store = new TowerCandyCornStore();
        UUID player = new UUID(5, 5);
        assertEquals(0, store.paidOn(player, "2026-11-04"));
        assertEquals(15, store.record(player, "2026-11-04", 15));
        assertEquals(25, store.record(player, "2026-11-04", 10));
        assertEquals(25, store.record(player, "2026-11-04", 0), "nothing recorded for nothing");
        assertEquals(25, store.record(player, "2026-11-04", -9), "and never a refund");
        assertEquals(0, store.paidOn(player, "2026-11-05"), "a new day starts at nothing");
        store.record(player, "2026-11-05", 7);
        assertEquals(0, store.paidOn(player, "2026-11-04"), "and the old day is forgotten");
        TowerCandyCornStore restored = TowerCandyCornStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(7, restored.paidOn(player, "2026-11-05"));
        restored.reset(player);
        assertEquals(0, restored.paidOn(player, "2026-11-05"));
    }

    @Test
    void theCapStopsAtOneHundredWhateverTheFloorsPay() {
        assertEquals(100, TrickRules.DAILY_CANDY_CORN_CAP);
        TowerCandyCornStore store = new TowerCandyCornStore();
        UUID player = new UUID(9, 9);
        int paid = 0;
        for (int floor = 0; floor < 60; floor++) {
            int share = TrickRules.candyCornPayable(TrickRules.candyCorn(floor), store.paidOn(player, "d"));
            store.record(player, "d", share);
            paid += share;
        }
        assertEquals(100, paid, "sixty treats still pay a hundred, to the corn");
        assertEquals(0, TrickRules.candyCornPayable(5, 100));
        assertEquals(0, TrickRules.candyCornPayable(5, 250), "over the cap pays nothing, not a negative");
        assertEquals(3, TrickRules.candyCornPayable(5, 97), "the last share is cut to fit");
        assertEquals(5, TrickRules.candyCornPayable(5, 0));
    }
}
