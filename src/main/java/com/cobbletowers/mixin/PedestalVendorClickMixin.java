package com.cobbletowers.mixin;

import com.cobbletowers.TowerLog;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.intermission.PedestalRules;
import com.cobbletowers.intermission.PedestalService;
import com.cobbletowers.vendor.VendorNpc;
import com.cobbletowers.vendor.VendorNpcRules;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A click on a tower pedestal or the vendor is taken straight from the packet, before any other mod's entity-click handler can intercept it, and any click
 * counts (right or left, either hand): they are part of the tower's interface, not creatures. Every other click is left alone.
 *
 * <p>The same checks vanilla makes still apply (the target inside the border and within reach). One right-click sends up to four packets (at a point and
 * plain, for each hand); the first acts and the rest, on the same entity within a few ticks, are consumed without acting.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PedestalVendorClickMixin {

    /** Packets of one click arrive within this many ticks of each other. */
    private static final int SAME_CLICK_TICKS = 5;

    /** Per player: the entity id (high half) and server tick (low half) of their last pedestal or vendor click. */
    private static final Map<UUID, Long> cobbletowers$lastClick = new ConcurrentHashMap<>();

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V", at = @At("HEAD"), cancellable = true)
    private void cobbletowers$pedestalOrVendor(ServerboundInteractPacket packet, CallbackInfo ci) {
        // The first call arrives on the network thread; vanilla hands the packet to the server thread, where it comes back here.
        if (player == null || player.getServer() == null || !player.getServer().isSameThread()) return;
        if (!player.level().dimension().equals(TowerDimension.LEVEL)) return;
        Entity target = packet.getTarget(player.serverLevel());
        if (target == null) return;
        boolean pedestal = target.getTags().contains(PedestalRules.TAG);
        if (!pedestal && !target.getTags().contains(VendorNpcRules.VENDOR_TAG)) return;
        ci.cancel();
        if (!player.serverLevel().getWorldBorder().isWithinBounds(target.blockPosition()) || !player.canInteractWithEntity(target.getBoundingBox(), 3.0)) return;
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
                use();
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 location) {
                use();
            }

            @Override
            public void onAttack() {
                use();
            }

            private void use() {
                long now = player.server.getTickCount();
                Long last = cobbletowers$lastClick.put(player.getUUID(), ((long) target.getId() << 32) | (now & 0xFFFFFFFFL));
                if (last != null && (int) (last >> 32) == target.getId() && now - (last & 0xFFFFFFFFL) < SAME_CLICK_TICKS) return;
                try {
                    if (pedestal) PedestalService.click(player, target);
                    else VendorNpc.use(player, target);
                } catch (RuntimeException ex) {
                    TowerLog.error("A click on a tower {} by {} failed", pedestal ? "pedestal" : "vendor", player.getGameProfile().getName(), ex);
                }
            }
        });
    }
}
