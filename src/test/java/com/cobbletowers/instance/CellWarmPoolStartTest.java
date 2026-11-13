package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.FloorAnchor;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerWindow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the warm pool builds at start-up, so the first run of each tower after a restart does not paste on the server thread. */
class CellWarmPoolStartTest {

    private static final long NOW = 1_794_441_600_000L; // 12 Nov 2026

    private static FloorLayout layout(String structure) {
        FloorAnchor at = new FloorAnchor(1, 10, 1, 0);
        return new FloorLayout(TestRuns.id(structure), at, at, at, at, Optional.empty(), Optional.empty());
    }

    /** Towers by name, each with three floors in one building; {@code window} closes a tower when given. */
    private static TowerContent content(Map<String, String> buildingByTower, Map<String, TowerWindow> windows) {
        TowerContent base = TestRuns.content();
        Map<ResourceLocation, TowerDefinition> towers = new LinkedHashMap<>();
        Map<ResourceLocation, FloorDefinition> floors = new LinkedHashMap<>();
        buildingByTower.forEach((name, building) -> {
            List<ResourceLocation> ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ResourceLocation id = TestRuns.id(name + "_f" + i);
                floors.put(id, new FloorDefinition(id, i, Optional.empty(), Optional.empty(), List.of(), Optional.of(layout(building)), Optional.empty()));
                ids.add(id);
            }
            ResourceLocation towerId = TestRuns.id(name);
            towers.put(towerId, new TowerDefinition(towerId, name, 1, 3, TestRuns.id("standard"), base.rewardTables().keySet().iterator().next(),
                    ids, List.of(), Optional.empty(), Optional.empty(), false, Optional.ofNullable(windows.get(name)), false));
        });
        return TowerContent.of(towers, floors, base.rulesets(), Map.of(), Map.of(), Map.of(), base.rewardTables(), Map.of(), Map.of(), Map.of(),
                Map.of());
    }

    @Test
    @DisplayName("every open tower's first floor is kept ready, one per building")
    void openTowersFirstFloors() {
        var buildings = new LinkedHashMap<String, String>();
        buildings.put("neutral", "battle_tower_neutral");
        buildings.put("rootvale", "battle_tower_rootvale");
        buildings.put("trial", "battle_tower_neutral"); // shares a building: one entry, not two
        List<FloorLayout> layouts = CellWarmPool.startLayouts(content(buildings, Map.of()), NOW);
        // Compared sorted: TowerContent keeps its towers in an unordered map, so the order varies from one JVM to the next.
        assertEquals(List.of(TestRuns.id("battle_tower_neutral"), TestRuns.id("battle_tower_rootvale")),
                layouts.stream().map(FloorLayout::structure).sorted().toList());
    }

    @Test
    @DisplayName("a closed event tower is not built for")
    void closedEventTowerIsSkipped() {
        var buildings = new LinkedHashMap<String, String>();
        buildings.put("neutral", "battle_tower_neutral");
        buildings.put("halloween", "battle_tower_halloween");
        var ended = new TowerWindow(NOW - 20L * 24 * 3600 * 1000, NOW - 1000);
        List<FloorLayout> layouts = CellWarmPool.startLayouts(content(buildings, Map.of("halloween", ended)), NOW);
        assertEquals(List.of(TestRuns.id("battle_tower_neutral")), layouts.stream().map(FloorLayout::structure).toList());
    }
}
