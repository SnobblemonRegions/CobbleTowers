package com.cobbletowers.vendor;

import com.cobbletowers.battle.cobblemon.PartyReader;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.VendorEffect;
import com.cobbletowers.definition.VendorPlace;
import com.cobbletowers.definition.VendorServiceDefinition;
import com.cobbletowers.network.VendorCatalogPayload;
import com.cobbletowers.persistence.PersistedRun;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds what the Gilded Merchant's counter shows besides the catalog (P59): the tower and floor for the plaque, every good with what it does
 * (those sold elsewhere too, greyed on the counter), each teammate's party health, and the merchant's line.
 */
public final class ShopView {

    private ShopView() {}

    /** What each effect does, in the shop's words. Data-driven goods all use one of these effects. */
    static String about(VendorEffect effect) {
        return switch (effect) {
            case FULL_HEAL -> "Every Pokémon back to full HP, fainted ones too.";
            case CURE_STATUS -> "Clears burn, poison, sleep, freeze and paralysis from the whole party.";
            case MYSTERY_BAG -> "A sealed bag of tower loot.";
        };
    }

    /** Where a good not sold at {@code place} can be bought instead. */
    static String where(VendorServiceDefinition service, VendorPlace place) {
        if (place == VendorPlace.PREP && service.soldAt(VendorPlace.ATRIUM)) return "Sold on the floors";
        if (place == VendorPlace.ATRIUM && service.soldAt(VendorPlace.PREP)) return "Sold before floor 1";
        return "Not sold here";
    }

    public static VendorCatalogPayload.Shop build(MinecraftServer server, ServerPlayer player, PersistedRun run, TowerContent content,
                                                  VendorPlace place, long balance, List<UUID> team) {
        TowerDefinition tower = content.towers().get(run.towerId());
        String towerName = tower == null ? "" : tower.displayName();
        int floors = tower == null ? 0 : tower.floorIds().size();
        int floor = place == VendorPlace.PREP ? 0 : run.floorIndex() + 1;

        List<VendorCatalogPayload.Good> goods = new ArrayList<>();
        int cheapest = 0;
        for (VendorServiceDefinition service : content.vendorCatalog()) {
            boolean here = service.soldAt(place);
            int price = com.cobbletowers.armor.ArmorBonusEffects.vendorPrice(player, service.priceCobbleDollars());
            if (here && (cheapest == 0 || price < cheapest)) cheapest = price;
            goods.add(new VendorCatalogPayload.Good(service.id(), service.displayName(), price, about(service.effect()), here,
                    here ? "" : where(service, place)));
        }

        List<VendorCatalogPayload.Party> parties = new ArrayList<>();
        for (UUID id : team) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            parties.add(new VendorCatalogPayload.Party(id, member == null ? List.of() : PartyReader.health(member)));
        }

        List<VendorCatalogPayload.Mon> mine = PartyReader.health(player);
        int fainted = 0, hurt = 0, statused = 0, lowest = 101;
        String lowestName = "";
        for (VendorCatalogPayload.Mon mon : mine) {
            if (mon.fainted()) fainted++;
            else if (mon.hp() < 100) {
                hurt++;
                if (mon.hp() < lowest) { lowest = mon.hp(); lowestName = mon.name(); }
            }
            if (!mon.status().isEmpty()) statused++;
        }
        String line = MerchantLines.pick(new MerchantLines.Context(place, floor, fainted, hurt, lowestName, statused, balance, cheapest));
        return new VendorCatalogPayload.Shop(towerName, floor, floors, place == VendorPlace.PREP ? "prep" : "atrium", line, goods, parties);
    }
}
