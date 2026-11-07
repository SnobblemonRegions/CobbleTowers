package com.cobbletowers.instance;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;

/**
 * Nobody changes the tower while they are in it. Minecraft only stops a survival player from breaking and placing blocks in adventure mode or inside the
 * overworld's spawn protection, so the tower dimension needs its own rule: in it, a player cannot break a block, place one, use a bucket, flint, a spawn egg,
 * a boat, a minecart, bone meal, an axe, shovel, hoe or shears on the world, or break, rotate or fill a painting, an item frame or an armor stand.
 *
 * <p>What a player <em>can</em> still do is everything the tower is built on: the pedestals and the vendor are clicks on entities of their own, and a battle
 * is not touched. A chest, a button or a door is still used too, with one limit: a click on a block with one of the items above in hand is refused whole (so
 * the block's own use does not run either); with an empty hand or any other item it works. An operator in creative mode is exempt, so the arenas can still be built and mended by hand. Server and
 * client both run these rules, so the break animation never starts for a player who may not break the block.
 */
public final class TowerBlockProtection {

    private TowerBlockProtection() {}

    /** Whether the world is the tower dimension and the player may not change it. */
    public static boolean protectedFor(Level level, Player player) {
        return level.dimension().equals(TowerDimension.LEVEL) && !(player.isCreative() && player.hasPermissions(2));
    }

    /** An item whose use on a block changes the world around it (places something, replaces a block, lights it, grows it, or reshapes it). */
    public static boolean changesBlocks(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BlockItem || item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
                || item instanceof BoneMealItem || item instanceof DiggerItem || item instanceof ShearsItem || item instanceof SpawnEggItem
                || item instanceof HangingEntityItem || item instanceof ArmorStandItem || item instanceof MinecartItem || item instanceof BoatItem
                || item instanceof EndCrystalItem;
    }

    /** An item that acts on what the player is looking at without a click on a block (a bucket fills, a boat is set on the water, an egg is thrown at it). */
    private static boolean usesTheWorldFromTheAir(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BucketItem || item instanceof BoatItem || item instanceof SpawnEggItem;
    }

    public static void install() {
        // Breaking: the event the server asks, and the attack the client starts, so nothing is even begun.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !protectedFor(level, player));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                protectedFor(level, player) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Placing and reshaping: a click on a block with an item that would change it.
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                protectedFor(level, player) && changesBlocks(player.getItemInHand(hand)) ? InteractionResult.FAIL : InteractionResult.PASS);
        // A bucket, a boat or an egg works on what the player looks at, with no block click.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            return protectedFor(level, player) && usesTheWorldFromTheAir(stack) ? InteractionResultHolder.fail(stack) : InteractionResultHolder.pass(stack);
        });
        // Decorations: nothing is broken, turned or emptied. Pedestals, the vendor and Pokémon are other entities and are untouched.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                protectedFor(level, player) && (entity instanceof HangingEntity || entity instanceof ArmorStand) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                protectedFor(level, player) && (entity instanceof HangingEntity || entity instanceof ArmorStand) ? InteractionResult.FAIL : InteractionResult.PASS);
    }
}
