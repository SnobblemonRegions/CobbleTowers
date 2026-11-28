package com.cobbletowers.mixin;

import com.cobbletowers.instance.TowerDimension;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A death in the tower keeps the inventory and experience, as if keepInventory were on there. What a player drops in
 * a cell is swept when the cell is cleared, so without this a survival player who died in a run lost everything they
 * carried for good (found 28 Nov 2026). The respawn half is {@link ServerPlayerKeepInventoryMixin}.
 */
@Mixin(Player.class)
public abstract class PlayerKeepInventoryMixin {

    @Redirect(method = "dropEquipment", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean cobbletowers$keepItemsInTower(GameRules rules, GameRules.Key<GameRules.BooleanValue> key) {
        return rules.getBoolean(key) || (key == GameRules.RULE_KEEPINVENTORY && cobbletowers$inTower());
    }

    @Redirect(method = "getBaseExperienceReward", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean cobbletowers$keepExperienceInTower(GameRules rules, GameRules.Key<GameRules.BooleanValue> key) {
        return rules.getBoolean(key) || (key == GameRules.RULE_KEEPINVENTORY && cobbletowers$inTower());
    }

    private boolean cobbletowers$inTower() {
        return ((Player) (Object) this).level().dimension().equals(TowerDimension.LEVEL);
    }
}
