package com.cobbletowers.ascension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Balance phase 2: on a boss-rush tower the extra opponents an Ascension brings become an enrage. */
class BossRushEnrageTest {

    @Test
    void enrageFollowsTheExtraOpponents() {
        assertEquals(0, AscensionPolicy.enrageEvery(0));
        assertEquals(0, AscensionPolicy.enrageEvery(2));
        assertEquals(5, AscensionPolicy.enrageEvery(3), "one extra opponent: every 5 turns");
        assertEquals(4, AscensionPolicy.enrageEvery(6));
        assertEquals(3, AscensionPolicy.enrageEvery(9));
        assertEquals(3, AscensionPolicy.enrageEvery(100), "capped like the opponents");
    }

    @Test
    void onlyBossRushTowersSendTheEnrage() {
        assertEquals(AscensionFx.enemy(6).size(), AscensionFx.enemy(6, false).size());
        var ops = AscensionFx.enemy(6, true);
        assertEquals(AscensionFx.enemy(6).size() + 1, ops.size());
        var enrage = ops.get(ops.size() - 1).getAsJsonObject();
        assertEquals("enrage", enrage.get("op").getAsString());
        assertEquals("foe", enrage.get("side").getAsString());
        assertEquals(4, enrage.get("every").getAsInt());
        assertTrue(AscensionFx.enemy(1, true).isEmpty() || AscensionFx.enemy(1, true).size() == AscensionFx.enemy(1).size());
    }
}
