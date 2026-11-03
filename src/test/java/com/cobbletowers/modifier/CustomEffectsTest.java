package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.CustomBehavior;
import com.cobbletowers.definition.ModifierDefinition;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomEffectsTest {

    private static ModifierDefinition custom(String name, String type, String effect) {
        String json = "{\"schema_version\":1,\"type\":\"" + type + "\",\"display_name\":\"X\",\"effect\":" + effect + "}";
        return ModifierDefinition.fromJson(ResourceLocation.fromNamespaceAndPath("cobbletowers", name),
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static CustomEffects held(CustomBehavior... behaviors) {
        return new CustomEffects(java.util.Set.of(behaviors));
    }

    @Test
    @DisplayName("a custom modifier must name a coded behavior, and an unknown one is refused at load")
    void unknownBehaviorRefused() {
        assertThrows(IllegalArgumentException.class, () -> custom("a", "custom", "{\"custom\":\"nonsense\"}"));
        assertEquals(CustomBehavior.BLACK_MARKET,
                CustomEffects.of(List.of(custom("b", "custom", "{\"custom\":\"black_market\"}")))
                        .behaviors().iterator().next());
    }

    @Test
    @DisplayName("type CUSTOM with no behavior is refused, and a behavior under another type is not counted as that type")
    void typeMustMatch() {
        assertThrows(IllegalArgumentException.class, () -> custom("a", "custom", "{\"reward_percent\":150}"));
        assertThrows(IllegalArgumentException.class, () -> custom("b", "reward", "{\"custom\":\"black_market\"}"));
    }

    @Test
    @DisplayName("nothing held means nothing changes")
    void noneChangesNothing() {
        CustomEffects none = CustomEffects.NONE;
        assertFalse(none.healsEachIntermission());
        assertEquals(100, none.vendorPricePercent());
        assertEquals(100, none.rewardPercent(7L, 3, 1));
        assertTrue(none.battleOps().isEmpty());
    }

    @Test
    @DisplayName("Black Market halves vendor prices; Field Hospital heals; Glass Cannon queues boosts and a HP cut")
    void flagsAndOps() {
        assertEquals(50, held(CustomBehavior.BLACK_MARKET).vendorPricePercent());
        assertTrue(held(CustomBehavior.FIELD_HOSPITAL).healsEachIntermission());
        var ops = held(CustomBehavior.GLASS_CANNON).battleOps();
        assertEquals(3, ops.size());
        assertEquals("damage", ops.get(0).getAsJsonObject().get("op").getAsString());
        assertEquals(140, ops.get(0).getAsJsonObject().get("percent").getAsInt());
        assertEquals("self", ops.get(0).getAsJsonObject().get("side").getAsString());
        assertEquals(60, ops.get(1).getAsJsonObject().get("percent").getAsInt());
        assertEquals("cap", ops.get(2).getAsJsonObject().get("op").getAsString(), "the relic cap rides last");
    }

    @Test
    @DisplayName("the Wheel is deterministic, only ever pays x4 or x0.5, and hits the jackpot about 30% of the time")
    void wheel() {
        CustomEffects wheel = held(CustomBehavior.FORTUNES_WHEEL);
        int jackpots = 0;
        for (int n = 0; n < 4000; n++) {
            int percent = wheel.rewardPercent(12345L, 1 + n / 100, n);
            assertEquals(percent, wheel.rewardPercent(12345L, 1 + n / 100, n), "same spin, same result");
            assertTrue(percent == 400 || percent == 50, "got " + percent);
            if (percent == 400) jackpots++;
        }
        assertTrue(jackpots > 1000 && jackpots < 1400, "jackpots " + jackpots);
    }

    @Test
    @DisplayName("the relic behaviors each add one op, stack with each other, and carry a single cap")
    void relicBoosts() {
        assertEquals(2, held(CustomBehavior.SWIFT_START).battleOps().size());
        assertEquals(2, held(CustomBehavior.IRON_HIDE).battleOps().size());
        assertEquals(2, held(CustomBehavior.WAR_BANNER).battleOps().size());
        assertEquals(4, held(CustomBehavior.SWIFT_START, CustomBehavior.IRON_HIDE, CustomBehavior.WAR_BANNER)
                .battleOps().size());
    }
}
