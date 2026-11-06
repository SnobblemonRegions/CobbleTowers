package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
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
                announce(player, result.prize());
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

    /**
     * Tells the player what the bag held so that nobody misses it: a framed banner in chat with the prize in bold and its rarity's colour, a line over the
     * hotbar, and for an epic or legendary prize a title across the screen and a bigger sound.
     */
    private static void announce(ServerPlayer player, MysteryBag.Prize prize) {
        String label = labelOf(prize);
        MysteryBagMessage.Rarity rarity = MysteryBagMessage.rarityOf(prize.material());
        for (Component line : MysteryBagMessage.banner(prize, label)) player.sendSystemMessage(line);
        String thing = prize.amount() > 1 ? prize.amount() + "× " + label : label;
        player.displayClientMessage(Component.literal("Mystery Bag: " + thing).withStyle(net.minecraft.network.chat.Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(rarity.rgb())).withBold(true)), true);
        if (rarity.titled()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(MysteryBagMessage.title(prize, label)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(MysteryBagMessage.subtitle(prize)));
        }
        switch (rarity) {
            case COMMON, UNCOMMON -> player.playNotifySound(SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8f, 1.1f);
            case RARE -> {
                player.playNotifySound(SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8f, 1.1f);
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.4f);
            }
            case EPIC -> {
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9f, 1.0f);
                player.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0f, 0.8f);
            }
            case LEGENDARY -> {
                player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 0.7f);
            }
        }
    }

    private static String labelOf(MysteryBag.Prize prize) {
        return AscensionMaterials.of(ResourceLocation.fromNamespaceAndPath(AscensionMaterials.NAMESPACE, prize.material()))
                .map(AscensionMaterials.Material::label).orElse(prize.material());
    }
}
