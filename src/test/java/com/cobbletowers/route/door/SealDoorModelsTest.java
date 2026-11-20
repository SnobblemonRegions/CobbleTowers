package com.cobbletowers.route.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.route.RouteGraph;
import com.cobbletowers.route.RouteMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SealDoorModelsTest {

    private static final Path MODELS = Path.of("src/main/resources/assets/cobbletowers/models/gate_door");

    @Test
    void everyTowerWearsATrimThatExists() {
        for (String tower : new String[] {"neutral", "duskvale", "rootvale", "tideforge", "test", "halloween", "some_new_tower"}) {
            String trim = SealDoorModels.trimOf(ResourceLocation.fromNamespaceAndPath("cobbletowers", tower));
            assertTrue(SealDoorModels.TRIMS.contains(trim), tower + " -> " + trim);
        }
        assertEquals("neutral", SealDoorModels.trimOf(ResourceLocation.fromNamespaceAndPath("cobbletowers", "some_new_tower")));
        assertEquals("hollows_eve", SealDoorModels.trimOf(ResourceLocation.fromNamespaceAndPath("cobbletowers", "halloween")));
    }

    @Test
    void everyLaneKindShowsAStopIcon() {
        for (RouteGraph.Kind kind : RouteGraph.Kind.values()) SealDoorModels.stopOf(kind);
        assertEquals(RouteMap.Stop.REST, SealDoorModels.stopOf(RouteGraph.Kind.REST));
        assertEquals(RouteMap.Stop.BATTLE, SealDoorModels.stopOf(RouteGraph.Kind.BATTLE));
        assertEquals(RouteMap.Stop.MERCHANT, SealDoorModels.stopOf(RouteGraph.Kind.GAMBLER));
    }

    @Test
    void everyModelTheModRequestsIsGenerated() {
        for (String trim : SealDoorModels.TRIMS) {
            for (String part : SealDoorModels.PARTS) assertTrue(Files.isRegularFile(MODELS.resolve(trim).resolve(part + ".json")), trim + "/" + part);
        }
        for (RouteMap.Stop stop : RouteMap.Stop.values()) {
            String name = stop.name().toLowerCase(Locale.ROOT);
            assertTrue(Files.isRegularFile(MODELS.resolve("icon").resolve(name + "_left.json")), name);
            assertTrue(Files.isRegularFile(MODELS.resolve("icon").resolve(name + "_right.json")), name);
        }
    }

    @Test
    void theFrameStandsStillAndEachLeafPairSwingsOnItsSide() {
        assertEquals(0, SealDoorModels.side(0));
        assertEquals(0, SealDoorModels.side(1));
        assertEquals(-1, SealDoorModels.side(2));
        assertEquals(-1, SealDoorModels.side(3));
        assertEquals(1, SealDoorModels.side(4));
        assertEquals(1, SealDoorModels.side(5));
    }
}
