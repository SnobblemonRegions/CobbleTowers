package com.cobbletowers.economy;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.VendorEffect;
import com.cobbletowers.persistence.PendingTowerReward;
import com.cobbletowers.persistence.TowerPendingRewardStore;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a vendor purchase does (TDS #20 C): a recovery of a target's live party, using the same live-party read as {@code recallParties} and
 * {@code TowerEncounters.levelsOf}, or, for the mystery bag alone, a prize from AscensionLib's currencies.
 */
public final class VendorServices {

    private VendorServices() {}

    /** The recoveries. The mystery bag needs a run, so it goes through {@link #openBag} instead. */
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
            case MYSTERY_BAG -> throw new IllegalArgumentException("the mystery bag is opened with a run, through openBag");
        }
    }

    /**
     * Rolls the bag on the server and queues the prize for {@code target} as an ordinary pending tower reward, so an inventory with no room holds it for
     * {@code /tower claim} instead of losing it. Throws, before anything is queued, when AscensionLib is not installed (its currencies would be plain
     * vanilla items) or the rolled material is unknown, so the purchase is refunded.
     * @return what the bag held
     */
    public static MysteryBag.Prize openBag(MinecraftServer server, ServerPlayer target, UUID runId, int floorIndex) {
        if (!FabricLoader.getInstance().isModLoaded(AscensionMaterials.NAMESPACE)) {
            throw new IllegalStateException("the mystery bag pays in AscensionLib currencies and AscensionLib is not installed");
        }
        MysteryBag.Prize prize = MysteryBag.roll(target.getRandom()::nextInt);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(AscensionMaterials.NAMESPACE, prize.material());
        if (AscensionMaterials.of(id).isEmpty()) throw new IllegalStateException("the mystery bag rolled " + id + ", which is not a known material");
        TowerPendingRewardStore.get(server).add(target.getUUID(),
                new PendingTowerReward(runId, Math.max(1, floorIndex), id, prize.amount(), System.currentTimeMillis()));
        TowerLog.info("Run {}: a mystery bag for {} held {} x{}", runId, target.getUUID(), id, prize.amount());
        try {
            // Delivered now, with the reveal screen and the chat line; if this fails the prize is still queued and arrives at the next delivery.
            com.cobbletowers.reward.RewardDelivery.deliver(server, target);
        } catch (RuntimeException ex) {
            TowerLog.error("Delivering the mystery bag prize of {} failed; it stays queued", target.getUUID(), ex);
        }
        return prize;
    }
}
