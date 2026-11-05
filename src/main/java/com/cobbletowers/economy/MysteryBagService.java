package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/**
 * The goblin's Mystery Bag as an item (owner decision, 2026-11-05): the vendor sells the bag, the player right-clicks it from the hotbar, and the prize arrives
 * as AscensionLib vouchers. The rules are in {@link MysteryBagOpening}; this wires them to the world: the click, the saved ledger and the payment. Server
 * thread only.
 */
public final class MysteryBagService {

    private MysteryBagService() {}

    /** Subscribed once for the life of the JVM, as every other event install here is. */
    public static void install() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (MysteryBagItem.idOf(stack).isEmpty()) return InteractionResultHolder.pass(stack);
            // Consume the click on both sides so the base item's own use never runs.
            if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) return InteractionResultHolder.success(stack);
            try {
                use(serverPlayer, hand, stack);
            } catch (RuntimeException ex) {
                TowerLog.error("Opening a Mystery Bag failed for {}", player.getGameProfile().getName(), ex);
                serverPlayer.displayClientMessage(Component.literal("The bag would not open. Keep it and try again."), true);
            }
            return InteractionResultHolder.success(serverPlayer.getItemInHand(hand));
        });
    }

    /** The vendor's sale: a fresh bag into the target's inventory (at their feet if it is full). Throws, before anything is given, when bags could not be opened. */
    public static void give(ServerPlayer target) {
        if (!AscensionPrizes.available()) {
            throw new IllegalStateException("Mystery Bags pay in AscensionLib currencies and AscensionLib is not available");
        }
        UUID id = UUID.randomUUID();
        ItemStack bag = MysteryBagItem.create(id);
        if (!target.getInventory().add(bag)) target.drop(bag, false);
        target.sendSystemMessage(Component.literal("Grizelda hands you a Mystery Bag. Right-click it to open it."));
        TowerLog.info("Mystery bag {} sold to {}", id, target.getUUID());
    }

    private static void use(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        UUID bag = MysteryBagItem.idOf(stack).orElseThrow();
        MinecraftServer server = player.getServer();
        MysteryBagStore store = MysteryBagStore.get(server);
        MysteryBagOpening.Payer payer = AscensionPrizes.payerFor(player.getUUID());
        MysteryBagOpening.Result result = MysteryBagOpening.open(store.book(), bag, player.getRandom()::nextInt, (id, prize) -> {
            // The roll is saved before the payment is attempted: a crash between the two must not allow a fresh roll.
            store.setDirty();
            store.checkpoint(server);
            return payer.pay(id, prize);
        });
        switch (result.status()) {
            case OPENED -> {
                store.setDirty();
                store.checkpoint(server);
                player.setItemInHand(hand, ItemStack.EMPTY);
                player.playNotifySound(SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8f, 1.1f);
                player.sendSystemMessage(Component.literal("The bag holds " + describe(result.prize()) + ". It arrives as items in a moment: right-click them to deposit."));
                TowerLog.info("Mystery bag {} opened by {}: {} x{}", bag, player.getUUID(), result.prize().material(), result.prize().amount());
            }
            case ALREADY_OPENED -> {
                // A duplicate of a bag that was opened before: it pays nothing and is taken away.
                player.setItemInHand(hand, ItemStack.EMPTY);
                player.displayClientMessage(Component.literal("This bag was already opened."), true);
                TowerLog.warn("Mystery bag {} was opened again by {}; the copy was removed", bag, player.getUUID());
            }
            case NOT_PAID -> player.displayClientMessage(
                    Component.literal("The bag will not open right now. Keep it and try again in a moment."), true);
        }
    }

    private static String describe(MysteryBag.Prize prize) {
        String label = AscensionMaterials.of(ResourceLocation.fromNamespaceAndPath(AscensionMaterials.NAMESPACE, prize.material()))
                .map(AscensionMaterials.Material::label).orElse(prize.material());
        return prize.amount() > 1 ? prize.amount() + " " + label : label;
    }
}
