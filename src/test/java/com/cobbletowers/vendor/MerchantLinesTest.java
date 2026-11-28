package com.cobbletowers.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.definition.VendorPlace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P59: what the Gilded Merchant says, most urgent first. */
class MerchantLinesTest {

    private static MerchantLines.Context floor(int fainted, int hurt, String lowest, int statused, long balance) {
        return new MerchantLines.Context(VendorPlace.ATRIUM, 4, fainted, hurt, lowest, statused, balance, 10);
    }

    @Test
    @DisplayName("an empty purse beats everything: he can sell nothing")
    void broke() {
        assertEquals(MerchantLines.BROKE, MerchantLines.pick(floor(2, 1, "Ludicolo", 1, 5)));
    }

    @Test
    @DisplayName("then a faint, then a status, then low HP, naming the most hurt")
    void urgency() {
        assertEquals(MerchantLines.fainted(1), MerchantLines.pick(floor(1, 1, "Ludicolo", 1, 413)));
        assertEquals(MerchantLines.STATUS, MerchantLines.pick(floor(0, 1, "Ludicolo", 1, 413)));
        assertEquals(MerchantLines.hurt("Ludicolo"), MerchantLines.pick(floor(0, 2, "Ludicolo", 0, 413)));
    }

    @Test
    @DisplayName("a healthy party hears the prep room's line before floor 1, and is left to browse on a floor")
    void healthy() {
        assertEquals(MerchantLines.PREP, MerchantLines.pick(new MerchantLines.Context(VendorPlace.PREP, 0, 0, 0, "", 0, 413, 10)));
        assertEquals(MerchantLines.HEALTHY, MerchantLines.pick(floor(0, 0, "", 0, 413)));
    }

    @Test
    @DisplayName("with nothing sold here, a light purse is not called broke")
    void nothingSold() {
        assertEquals(MerchantLines.HEALTHY, MerchantLines.pick(new MerchantLines.Context(VendorPlace.ATRIUM, 4, 0, 0, "", 0, 0, 0)));
    }
}
