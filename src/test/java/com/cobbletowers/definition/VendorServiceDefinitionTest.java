package com.cobbletowers.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing a vendor service, and every rule that makes one invalid (TDS #16, #19, #20). */
class VendorServiceDefinitionTest {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("cobbletowers", "full_heal");

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    @DisplayName("a service reads its price, effect and per-run cap")
    void parses() {
        VendorServiceDefinition service = VendorServiceDefinition.fromJson(ID, json("""
                {"schema_version": 1, "display_name": "Full Heal", "price_cobble_dollars": 25,
                 "effect": "full_heal", "max_purchases_per_run": 3}"""));

        assertEquals("Full Heal", service.displayName());
        assertEquals(25, service.priceCobbleDollars());
        assertEquals(VendorEffect.FULL_HEAL, service.effect());
        assertEquals(3, service.maxPurchasesPerRun());
    }

    @Test
    @DisplayName("max_purchases_per_run defaults to 0, meaning unlimited")
    void unlimitedByDefault() {
        VendorServiceDefinition service = VendorServiceDefinition.fromJson(ID, json("""
                {"schema_version": 1, "display_name": "Cure Status", "price_cobble_dollars": 10,
                 "effect": "cure_status"}"""));

        assertEquals(0, service.maxPurchasesPerRun());
    }

    @Test
    @DisplayName("the mystery bag is a capped, priced service with its own effect")
    void readsTheMysteryBag() {
        VendorServiceDefinition service = VendorServiceDefinition.fromJson(ID, json("""
                {"schema_version": 1, "display_name": "Mystery Bag", "price_cobble_dollars": 15,
                 "effect": "mystery_bag", "max_purchases_per_run": 2}"""));

        assertEquals(VendorEffect.MYSTERY_BAG, service.effect());
        assertEquals(2, service.maxPurchasesPerRun());
    }

    @Test
    @DisplayName("an unknown effect is refused, not defaulted")
    void unknownEffectRefused() {
        assertThrows(IllegalArgumentException.class, () -> VendorServiceDefinition.fromJson(ID, json("""
                {"schema_version": 1, "display_name": "Mystery", "price_cobble_dollars": 10,
                 "effect": "revive_early"}""")));
    }

    @Test
    @DisplayName("a price below 1 is refused: nothing here is ever free")
    void priceMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> VendorServiceDefinition.fromJson(ID, json("""
                {"schema_version": 1, "display_name": "Free Heal", "price_cobble_dollars": 0,
                 "effect": "full_heal"}""")));
    }
}
