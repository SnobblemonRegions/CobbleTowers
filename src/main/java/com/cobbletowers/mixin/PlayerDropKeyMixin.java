package com.cobbletowers.mixin;

import com.cobbletowers.economy.TowerKeys;
import com.cobbletowers.route.RouteMapItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A tower key cannot be tossed to another player: a deliberate drop goes back into the inventory. A death drop is
 * cancelled and the key returned on respawn, so a key is neither lost nor left for someone else.
 */
@Mixin(Player.class)
public abstract class PlayerDropKeyMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void cobbletowers$keepKey(ItemStack stack, boolean randomly, boolean byPlayer,
                                      CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof ServerPlayer holder && RouteMapItem.isRouteMap(stack)) {
            // The Route Map is never in the world: a toss goes back into the inventory, and a death drop is simply lost (the next run gets a fresh one).
            if (byPlayer) holder.getInventory().add(stack);
            cir.setReturnValue(null);
            return;
        }
        if (!((Object) this instanceof ServerPlayer player) || !TowerKeys.isTradeLocked(stack)) return;
        if (!byPlayer) {
            // Death drop: the key is kept for the respawn instead of lying in the world.
            if (player.isDeadOrDying()) {
                TowerKeys.setAside(player.server, player.getUUID(), stack.getCount());
                cir.setReturnValue(null);
            }
            return;
        }
        player.getInventory().add(stack);
        // Anything that did not fit falls through to the normal drop rather than vanishing.
        if (stack.isEmpty()) cir.setReturnValue(null);
    }
}
