package com.cobbletowers.definition;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * One thing the Tower Supply Vendor sells for CobbleDollars (TDS #16, #19, #20). {@link VendorEffect} is a closed set
 * of effects on a target's live party, never an item stack (TDS #20 C).
 * @param maxPurchasesPerRun how often one run may buy it (TDS #19); 0 means unlimited
 */
public record VendorServiceDefinition(
        ResourceLocation id,
        int schemaVersion,
        int revision,
        String displayName,
        int priceCobbleDollars,
        VendorEffect effect,
        int maxPurchasesPerRun) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public VendorServiceDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(effect, "effect");
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version " + schemaVersion + " is not supported; expected "
                    + SUPPORTED_SCHEMA_VERSION);
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1, got " + revision);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name must not be blank");
        }
        if (priceCobbleDollars < 1) {
            throw new IllegalArgumentException("price_cobble_dollars must be >= 1, got " + priceCobbleDollars);
        }
        if (maxPurchasesPerRun < 0) {
            throw new IllegalArgumentException("max_purchases_per_run must be >= 0, got " + maxPurchasesPerRun);
        }
    }

    public static VendorServiceDefinition fromJson(ResourceLocation id, JsonObject root) {
        return new VendorServiceDefinition(
                id,
                TowerJson.requireInt(root, "schema_version"),
                TowerJson.integer(root, "revision", 1),
                TowerJson.requireString(root, "display_name"),
                TowerJson.requireInt(root, "price_cobble_dollars"),
                parseEffect(TowerJson.requireString(root, "effect")),
                TowerJson.integer(root, "max_purchases_per_run", 0));
    }

    private static VendorEffect parseEffect(String raw) {
        try {
            return VendorEffect.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("field 'effect' must be full_heal, cure_status or mystery_bag, got '" + raw + "'");
        }
    }
}
