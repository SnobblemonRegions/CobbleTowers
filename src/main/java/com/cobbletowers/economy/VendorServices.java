package com.cobbletowers.economy;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.definition.VendorEffect;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a vendor purchase does (TDS #20 C): a recovery of a target's live party, using the same live-party read as {@code recallParties} and
 * {@code TowerEncounters.levelsOf}, or, for the Mystery Bag alone, the item itself ({@link MysteryBagService}; it is opened later, by right-clicking it).
 */
public final class VendorServices {

    private VendorServices() {}

    public static void apply(VendorEffect effect, ServerPlayer target) {
        switch (effect) {
            case FULL_HEAL -> {
                for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(target)) {
                    pokemon.setCurrentHealth(pokemon.getMaxHealth());
                }
            }
            case CURE_STATUS -> {
                for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(target)) {
                    pokemon.setStatus(null);
                }
            }
            case MYSTERY_BAG -> MysteryBagService.give(target);
        }
    }
}
