package com.cobbletowers.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.cobbletowers.definition.VendorEffect;
import com.cobbletowers.definition.VendorPlace;
import com.cobbletowers.definition.VendorServiceDefinition;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: the counter shop's words for each good and for where a good is sold instead. */
class ShopViewTest {

    private static VendorServiceDefinition sold(Set<VendorPlace> places) {
        return new VendorServiceDefinition(ResourceLocation.fromNamespaceAndPath("cobbletowers", "mystery_bag"), 1, 1, "Mystery Bag", 500,
                VendorEffect.MYSTERY_BAG, 0, 2, places);
    }

    @Test
    @DisplayName("every effect has a description")
    void about() {
        for (VendorEffect effect : VendorEffect.values()) assertFalse(ShopView.about(effect).isBlank(), effect.name());
    }

    @Test
    @DisplayName("a good not sold here says where it is sold instead")
    void where() {
        assertEquals("Sold on the floors", ShopView.where(sold(EnumSet.of(VendorPlace.ATRIUM)), VendorPlace.PREP));
        assertEquals("Sold before floor 1", ShopView.where(sold(EnumSet.of(VendorPlace.PREP)), VendorPlace.ATRIUM));
    }
}
