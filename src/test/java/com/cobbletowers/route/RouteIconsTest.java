package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.route.RouteMap.Stop;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route map's stop icons: every stop has a model that ships in the jar, every model is well formed, and a player without the pack still sees a vanilla item. */
class RouteIconsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject modelOf(Stop stop) {
        String name = RouteIcons.iconOf(stop).model();
        var in = RouteIconsTest.class.getResourceAsStream("/assets/cobbletowers/models/item/route/" + name + ".json");
        assertNotNull(in, "models/item/route/" + name + ".json is missing");
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("every stop has an icon, and no two stops share a model")
    void everyStopHasItsOwnIcon() {
        Set<String> seen = new HashSet<>();
        for (Stop stop : Stop.values()) {
            assertNotNull(RouteIcons.iconOf(stop), stop + " has no icon");
            assertTrue(seen.add(RouteIcons.iconOf(stop).model()), stop + " shares a model");
        }
        assertEquals(Stop.values().length, seen.size());
    }

    @Test
    @DisplayName("every model ships in the jar, is made of boxes inside the model limits, and points every face at a cell of the palette")
    void modelsAreWellFormed() {
        for (Stop stop : Stop.values()) {
            JsonObject model = modelOf(stop);
            JsonArray elements = model.getAsJsonArray("elements");
            assertTrue(elements.size() >= 5, stop + " has too few parts");
            for (var element : elements) {
                JsonObject box = element.getAsJsonObject();
                for (String corner : new String[] {"from", "to"}) {
                    for (var v : box.getAsJsonArray(corner)) {
                        double n = v.getAsDouble();
                        assertTrue(n >= -16 && n <= 32, stop + " has a box outside the model limits: " + n);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    assertTrue(box.getAsJsonArray("to").get(i).getAsDouble() > box.getAsJsonArray("from").get(i).getAsDouble(), stop + " has a flat box");
                }
                for (var face : box.getAsJsonObject("faces").entrySet()) {
                    for (var u : face.getValue().getAsJsonObject().getAsJsonArray("uv")) {
                        assertTrue(u.getAsDouble() >= 0 && u.getAsDouble() <= 16, stop + " has a uv outside the palette");
                    }
                }
            }
            assertEquals("cobbletowers:item/route/palette", model.getAsJsonObject("textures").get("p").getAsString());
            assertTrue(model.getAsJsonObject("display").has("gui"), stop + " has no gui display, so a display entity would draw it unrotated");
        }
    }

    @Test
    @DisplayName("the palette ships in the jar as a 32 x 32 PNG")
    void paletteShips() throws Exception {
        try (var in = RouteIconsTest.class.getResourceAsStream("/assets/cobbletowers/textures/item/route/palette.png")) {
            assertNotNull(in, "textures/item/route/palette.png is missing");
            byte[] png = in.readAllBytes();
            assertEquals(0x89, png[0] & 0xFF, "not a PNG");
            assertEquals(32, ByteBuffer.wrap(png, 16, 4).getInt());
            assertEquals(32, ByteBuffer.wrap(png, 20, 4).getInt());
        }
    }

    @Test
    @DisplayName("without the pack a stop is drawn as its vanilla item, so the map still reads")
    void fallbackIsAVanillaItem() {
        for (Stop stop : Stop.values()) {
            ItemStack plain = RouteIcons.withModel(new ItemStack(RouteIcons.iconOf(stop).fallback()), 0);
            assertEquals(null, plain.get(DataComponents.CUSTOM_MODEL_DATA), stop + ": a value of 0 must leave the plain item");
            assertTrue(!plain.isEmpty());
        }
    }

    @Test
    @DisplayName("a model number is put on the stack so a player with the pack sees the icon")
    void modelNumberIsApplied() {
        ItemStack stack = RouteIcons.withModel(new ItemStack(RouteIcons.iconOf(Stop.VAULT).fallback()), 4242);
        assertEquals(4242, stack.get(DataComponents.CUSTOM_MODEL_DATA).value());
    }

    @Test
    @DisplayName("loading the icons without Polymer on the classpath (a unit test) is harmless")
    void worksWithoutPolymer() {
        RouteIcons.bootstrap();
        for (Stop stop : Stop.values()) assertTrue(!RouteIcons.stackFor(stop).isEmpty());
    }
}
