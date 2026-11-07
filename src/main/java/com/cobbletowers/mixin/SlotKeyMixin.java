package com.cobbletowers.mixin;

import com.cobbletowers.economy.TowerKeys;
import com.cobbletowers.route.RouteMapItem;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A tower key only fits a player's own inventory or ender chest, so it cannot be left in a chest, shulkers or a trade
 * container for someone else to take. Quick-move and hotbar swaps ask the slot too.
 */
@Mixin(Slot.class)
public abstract class SlotKeyMixin {

    @Shadow
    @Final
    public Container container;

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void cobbletowers$keepKey(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // the Route Map fits only the player's own inventory: not an ender chest, a chest, a crafting grid or a trade
        if (RouteMapItem.isRouteMap(stack) && !(container instanceof Inventory)) {
            cir.setReturnValue(false);
            return;
        }
        if (container instanceof Inventory || container instanceof PlayerEnderChestContainer) return;
        if (TowerKeys.isTradeLocked(stack)) cir.setReturnValue(false);
    }
}
