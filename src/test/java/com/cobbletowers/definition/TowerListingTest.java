package com.cobbletowers.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** An event tower is not advertised outside its window (P54): lists show it only while it is open or to a player who has played it. */
class TowerListingTest {

    private static final ResourceLocation EVENT = TestRuns.id("event");
    private static final long OPENS = Instant.parse("2026-10-31T04:00:00Z").toEpochMilli();
    private static final long CLOSES = Instant.parse("2026-11-07T05:00:00Z").toEpochMilli();

    private static TowerContent content() {
        TowerContent base = TestRuns.content();
        TowerDefinition plain = base.towers().get(TestRuns.TOWER);
        Map<ResourceLocation, TowerDefinition> towers = new LinkedHashMap<>(base.towers());
        towers.put(EVENT, new TowerDefinition(EVENT, "Event", 1, 1, plain.rulesetId(), plain.rewardTableId(), plain.floorIds(), plain.milestoneIds(),
                Optional.empty(), Optional.empty(), false, Optional.of(new TowerWindow(OPENS, CLOSES))));
        return TowerContent.of(towers, base.floors(), base.pools(), base.rulesets(), base.milestones(), base.bossPools(), base.modifiers(), base.rewardTables(),
                base.regionalThemes(), base.vendorServices(), base.scoutingProfiles(), base.digests());
    }

    @Test
    void aClosedEventTowerIsNotListedToAPlayerWhoNeverPlayedIt() {
        List<ResourceLocation> listed = content().listedTowerIds(OPENS - 1, id -> false);
        assertTrue(listed.contains(TestRuns.TOWER));
        assertFalse(listed.contains(EVENT));
    }

    @Test
    void itIsListedInsideItsWindow() {
        TowerContent content = content();
        assertTrue(content.listedTowerIds(OPENS, id -> false).contains(EVENT));
        assertTrue(content.listedTowerIds(CLOSES - 1, id -> false).contains(EVENT));
        assertFalse(content.listedTowerIds(CLOSES, id -> false).contains(EVENT), "the window is half open");
    }

    @Test
    void aPlayerWhoPlayedItKeepsSeeingItAfterTheEvent() {
        TowerContent content = content();
        assertTrue(content.listedTowerIds(CLOSES + 1, id -> id.equals(EVENT)).contains(EVENT));
        assertFalse(content.listedTowerIds(CLOSES + 1, id -> false).contains(EVENT));
    }

    @Test
    void anOrdinaryTowerIsAlwaysListed() {
        assertEquals(content().sortedTowerIds().stream().filter(id -> !id.equals(EVENT)).toList(),
                content().listedTowerIds(0, id -> false));
    }
}
