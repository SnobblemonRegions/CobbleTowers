package com.cobbletowers.reward;

import com.cobbletowers.TowerLog;
import com.cobbleraids.api.points.CobbleRaidsPoints;
import com.cobbletowers.economy.CobbleDollars;
import com.cobbletowers.economy.RaidPointsCurrency;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.network.RewardRevealPayload;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.persistence.PendingTowerReward;
import com.cobbletowers.persistence.TowerPendingRewardStore;
import com.cobbletowers.persistence.TowerWalletStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Hands a player whatever the tower owes them as soon as they can receive it: a roll from {@link RewardValuation} is
 * simply delivered. What does not fit in the inventory is held, never dropped: it is handed over on its own the next time the player joins or steps
 * out of the tower with room for it, and {@code /tower claim} takes it at any time.
 */
public final class RewardDelivery {

    private RewardDelivery() {}

    /** Subscribed once, for the life of the JVM, the way every other event install here is. */
    public static void install() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> guarded(() -> {
            ServerPlayer player = handler.getPlayer();
            deliver(server, player);
            if (!player.level().dimension().equals(TowerDimension.LEVEL)) claimHeld(server, player);
        }));
        // Stepping out of the tower is when a player can do something about a full inventory.
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (origin.dimension().equals(TowerDimension.LEVEL) && !destination.dimension().equals(TowerDimension.LEVEL)) {
                guarded(() -> claimHeld(destination.getServer(), player));
            }
        });
    }

    /**
     * Drains {@code player}'s queue into their inventory. What does not fit is held for {@code /tower claim}; a reward
     * whose item no longer exists is discarded with an error; one that throws is held too, so it is never retried on
     * its own.
     */
    public static int deliver(MinecraftServer server, ServerPlayer player) {
        TowerPendingRewardStore store = TowerPendingRewardStore.get(server);
        List<PendingTowerReward> queue = store.drain(player.getUUID());
        if (queue.isEmpty()) return 0;

        List<PendingTowerReward> delivered = new ArrayList<>(queue.size());
        int heldBefore = store.heldFor(player.getUUID()).size();
        boolean creditedWallet = false;
        java.util.Set<ResourceLocation> ownedArmor = null;
        for (PendingTowerReward reward : queue) {
            try {
                if (!ArmorDuplicates.setOf(reward.item()).isEmpty()) {
                    // Tower armor the player already has is swapped for a missing piece of its set (until the set is whole).
                    if (ownedArmor == null) ownedArmor = ownedArmor(server, player, store);
                    ResourceLocation chosen = ArmorDuplicates.choose(reward.item(), ownedArmor,
                            reward.runId().getMostSignificantBits() ^ reward.grantedAt() ^ reward.item().hashCode());
                    if (!chosen.equals(reward.item())) {
                        reward = new PendingTowerReward(reward.runId(), reward.floorIndex(), chosen, reward.amount(), reward.grantedAt(),
                                reward.components(), reward.label());
                    }
                    ownedArmor.add(reward.item());
                }
                if (reward.item().equals(CobbleDollars.ITEM_ID)) {
                    // A player's mastery of the tower the reward was earned in (P31) adds to their CobbleDollars.
                    int bonus = com.cobbletowers.mastery.MasteryService.perksForRun(server, player.getUUID(), reward.runId())
                            .cobbleDollarBonusPercent();
                    int credited = (int) Math.min(Integer.MAX_VALUE, reward.amount() + (long) reward.amount() * bonus / 100);
                    TowerWalletStore.get(server).credit(player.getUUID(), credited);
                    // Listed as what was paid (bonus included) and named: a currency is not an item, so the id alone reads as air.
                    delivered.add(shown(reward, credited, "CobbleDollars"));
                    creditedWallet = true;
                } else if (reward.item().equals(RaidPointsCurrency.ITEM_ID)) {
                    // CobbleRaids' own currency, credited through its public API (P21).
                    int points = com.cobbletowers.armor.ArmorBonusEffects.raidPoints(player, reward.amount(), reward.runId());
                    // Points a tower run earned count against CobbleRaids' daily tower cap. Season and mastery grants
                    // and admin grants carry an id that is no run, and stay uncapped: they are one-off rewards. A run
                    // retired past the retention window pays uncapped too, the same edge its mastery bonus already has.
                    boolean fromRun = com.cobbletowers.runtime.TowerRuns.get(reward.runId()).isPresent();
                    int credited = fromRun
                            ? com.cobbletowers.economy.RaidPointsCredit.awardFromRun(server, player.getUUID(), points)
                            : points;
                    if (!fromRun) CobbleRaidsPoints.award(server, player.getUUID(), points);
                    // Listed as what was credited; a reward the cap took entirely is not listed as "0 Raid Points".
                    if (credited > 0) delivered.add(shown(reward, credited, "Raid Points"));
                } else if (com.cobbletowers.economy.AscensionMaterials.of(reward.item()).isPresent()) {
                    // An AscensionLib material (dust, facet, mark, scouter, core): its own stack, named as the library names it.
                    var material = com.cobbletowers.economy.AscensionMaterials.of(reward.item()).get();
                    PendingTowerReward built = new PendingTowerReward(reward.runId(), reward.floorIndex(), reward.item(), reward.amount(),
                            reward.grantedAt(), material.stack(), material.label());
                    file(store, player, built, giveWithComponents(player, built), delivered);
                } else if (!reward.components().isEmpty()) {
                    // A card (P33b): an item that is its data. Discarded, with an error, if the mod that owns it is gone.
                    file(store, player, reward, giveWithComponents(player, reward), delivered);
                } else {
                    // The 777 Unique (AscensionLib) can enlarge an item reward; what is listed to the player is what was
                    // given. The key is the reward itself, so the rounding of a fraction is the same however often
                    // delivery is retried.
                    int amount = com.cobbletowers.economy.AscensionLibItemBonus.scale(player.getUUID(), reward.amount(),
                            reward.runId() + "|" + reward.floorIndex() + "|" + reward.item() + "|" + reward.grantedAt());
                    PendingTowerReward scaled = amount == reward.amount() ? reward : new PendingTowerReward(reward.runId(),
                            reward.floorIndex(), reward.item(), amount, reward.grantedAt(), reward.components(), reward.label());
                    file(store, player, scaled, give(player, reward.item(), amount), delivered);
                }
            } catch (RuntimeException ex) {
                TowerLog.error("A pending tower reward ({}) failed to deliver; holding it for /tower claim", reward.item(), ex);
                store.hold(player.getUUID(), reward);
            }
        }
        store.checkpoint(server);
        if (creditedWallet) TowerWalletStore.get(server).checkpoint(server);
        if (!delivered.isEmpty()) {
            player.sendSystemMessage(Component.literal("Tower rewards delivered: " + summarize(delivered)));
            TowerNetworking.sendRewardReveal(player, revealOf(delivered));
        }
        if (store.heldFor(player.getUUID()).size() > heldBefore) {
            player.sendSystemMessage(Component.literal(
                    "Some rewards did not fit in your inventory. Make room, then use /tower claim.").withStyle(ChatFormatting.GOLD));
        }
        return delivered.size();
    }

    /** Every tower armor piece the player has: worn, carried, in the ender chest, or still waiting to be claimed. */
    private static java.util.Set<ResourceLocation> ownedArmor(MinecraftServer server, ServerPlayer player, TowerPendingRewardStore store) {
        java.util.Set<ResourceLocation> owned = new java.util.HashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) noteArmor(owned, inventory.getItem(i));
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) noteArmor(owned, ender.getItem(i));
        for (PendingTowerReward held : store.heldFor(player.getUUID())) {
            if (!ArmorDuplicates.setOf(held.item()).isEmpty()) owned.add(held.item());
        }
        return owned;
    }

    private static void noteArmor(java.util.Set<ResourceLocation> owned, ItemStack stack) {
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!ArmorDuplicates.setOf(id).isEmpty()) owned.add(id);
    }

    /**
     * Files one placed reward. {@code left} is how many did not fit; -1 means the item no longer resolves, so the
     * reward is discarded (the placing code logged why).
     */
    private static void file(TowerPendingRewardStore store, ServerPlayer player, PendingTowerReward reward, int left,
                             List<PendingTowerReward> delivered) {
        if (left < 0) return;
        if (left < reward.amount()) delivered.add(reward);
        if (left > 0) store.hold(player.getUUID(), remainder(reward, left));
    }

    private static PendingTowerReward remainder(PendingTowerReward reward, int amount) {
        return new PendingTowerReward(reward.runId(), reward.floorIndex(), reward.item(), amount, reward.grantedAt(),
                reward.components(), reward.label());
    }

    /** {@code /tower claim}: tries again to place what was held. @return how many rewards were placed, in full or part */
    public static int claim(MinecraftServer server, ServerPlayer player) {
        TowerPendingRewardStore store = TowerPendingRewardStore.get(server);
        List<PendingTowerReward> held = store.takeHeld(player.getUUID());
        List<PendingTowerReward> placed = new ArrayList<>();
        for (PendingTowerReward reward : held) {
            int left;
            try {
                // Already final stacks (any bonus was applied when they were first rolled): never scaled again.
                left = reward.components().isEmpty() ? give(player, reward.item(), reward.amount())
                        : giveWithComponents(player, reward);
            } catch (RuntimeException ex) {
                TowerLog.error("A held tower reward ({}) failed to claim", reward.item(), ex);
                left = reward.amount();
            }
            if (left < 0) left = reward.amount();   // its item is not available now: it stays held
            if (left < reward.amount()) placed.add(reward);
            if (left > 0) store.hold(player.getUUID(), remainder(reward, left));
        }
        store.checkpoint(server);
        if (!placed.isEmpty()) player.sendSystemMessage(Component.literal("Claimed: " + summarize(placed)));
        return placed.size();
    }

    /**
     * Hands over held rewards that now fit, then reminds the player of what still does not. Called when they join and when they step out of the tower.
     * Before, those moments only reminded, and a reward waited for {@code /tower claim} even once the inventory had room: live data from 12 Nov 2026
     * had one player's 21 rewards held since 27 Oct.
     */
    public static void claimHeld(MinecraftServer server, ServerPlayer player) {
        if (TowerPendingRewardStore.get(server).heldFor(player.getUUID()).isEmpty()) return;
        claim(server, player);
        remindHeld(server, player);
    }

    /** Tells the player if rewards are still waiting for room in their inventory. */
    public static void remindHeld(MinecraftServer server, ServerPlayer player) {
        int waiting = TowerPendingRewardStore.get(server).heldFor(player.getUUID()).size();
        if (waiting == 0) return;
        player.sendSystemMessage(Component.literal("You have " + waiting + " unclaimed tower reward"
                + (waiting == 1 ? "" : "s") + ". Make room in your inventory, then use /tower claim.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** The reward as it is listed to the player: what was actually paid, under a readable name. */
    private static PendingTowerReward shown(PendingTowerReward reward, int amount, String name) {
        return new PendingTowerReward(reward.runId(), reward.floorIndex(), reward.item(), amount, reward.grantedAt(),
                reward.components(), name);
    }

    /** The screen payload, sent alongside the chat line; a client without the channel still gets text. */
    private static RewardRevealPayload revealOf(List<PendingTowerReward> delivered) {
        int throughFloor = delivered.stream().mapToInt(PendingTowerReward::floorIndex).max().orElse(0);
        List<RewardRevealPayload.Grant> grants = new ArrayList<>(delivered.size());
        for (PendingTowerReward reward : delivered) {
            grants.add(new RewardRevealPayload.Grant(reward.item(), reward.amount(), reward.label()));
        }
        return new RewardRevealPayload(throughFloor, List.copyOf(grants));
    }

    /** Into the inventory. @return how many of the stack did not fit */
    private static int hand(ServerPlayer player, ItemStack stack) {
        player.getInventory().add(stack);
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    /**
     * Places one reward in the inventory. Returns how many did not fit, or -1, granting nothing, when the item no
     * longer resolves ({@code BuiltInRegistries.ITEM.get} returns air for an unknown id).
     */
    private static int give(ServerPlayer player, ResourceLocation itemId, int amount) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (!BuiltInRegistries.ITEM.getKey(item).equals(itemId)) {
            TowerLog.error("A tower reward names item {}, which is not registered; it cannot be given.", itemId);
            return -1;
        }
        int remaining = amount;
        int left = 0;
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int chunk = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(chunk);
            left += hand(player, stack);
            remaining -= chunk;
        }
        return left;
    }

    /** As {@link #give}, for the stack the reward's components describe; -1 if it does not resolve. */
    private static int giveWithComponents(ServerPlayer player, PendingTowerReward reward) {
        java.util.Optional<ItemStack> built = com.cobbletowers.rental.CardStacks.parse(player.registryAccess(), reward.components());
        if (built.isEmpty()) {
            TowerLog.error("A tower reward ({}) could not be turned into an item. Is the mod that owns {} installed?",
                    reward.label().isEmpty() ? reward.item() : reward.label(), reward.item());
            return -1;
        }
        int remaining = reward.amount();
        int left = 0;
        while (remaining > 0) {
            ItemStack stack = built.get().copy();
            stack.setCount(Math.min(remaining, stack.getMaxStackSize()));
            remaining -= stack.getCount();
            left += hand(player, stack);
        }
        return left;
    }

    private static String summarize(List<PendingTowerReward> rewards) {
        StringBuilder builder = new StringBuilder();
        for (PendingTowerReward reward : rewards) {
            if (builder.length() > 0) builder.append(", ");
            if (!reward.label().isEmpty()) {
                builder.append(reward.label());
                if (reward.amount() > 1) builder.append(" x").append(reward.amount());
            } else {
                builder.append(reward.item()).append(" x").append(reward.amount());
            }
        }
        return builder.toString();
    }

    private static void guarded(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            TowerLog.error("CobbleTowers could not deliver a pending tower reward", ex);
        }
    }
}
