package com.cobbletowers.mixin;

import com.cobbletowers.instance.TowerDimension;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The respawned player takes over the inventory and experience of one who died in the tower (see
 * {@link PlayerKeepInventoryMixin}). The new player already stands where they respawn, outside, so the check reads the
 * dimension of the old one.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerKeepInventoryMixin {

    @Redirect(method = "restoreFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean cobbletowers$keepAfterTowerDeath(GameRules rules, GameRules.Key<GameRules.BooleanValue> key,
                                                     ServerPlayer that, boolean keepEverything) {
        return rules.getBoolean(key)
                || (key == GameRules.RULE_KEEPINVENTORY && that.level().dimension().equals(TowerDimension.LEVEL));
    }
}
