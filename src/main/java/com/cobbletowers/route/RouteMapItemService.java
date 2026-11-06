package com.cobbletowers.route;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.ParticipantService;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Keeps a Route Map in the hotbar of every player who is in a tower run with a branching map, and in no one else's inventory. Once a second, and when a
 * player joins, each player is brought to what they should have: exactly one while in such a run, none otherwise. That one rule covers every way a map could
 * outlive its run (a crash, a logout, a death, a run that ended or was left), and removes any copy beyond the first. The map is never put in the way of
 * anything: it takes only a free slot, and where it is not allowed to go ({@code SlotKeyMixin}), be thrown ({@code PlayerDropKeyMixin}) or be used on
 * ({@link #install()}) it simply stays. Server thread only.
 */
public final class RouteMapItemService {

    /** How often every player is checked, in ticks. */
    static final int CHECK_TICKS = 20;

    private static int ticks;

    private RouteMapItemService() {}

    public static void install() {
        RouteMapItem.bootstrap();      // the models have to be requested before Polymer builds the pack
        RouteScreenItems.bootstrap();
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!RouteMapItem.isRouteMap(stack)) return InteractionResultHolder.pass(stack);
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResultHolder.success(stack);
        });
        // A click on something that would take the map out of the player's hand (a decorated pot, an item frame, an allay) opens the map instead. Every other
        // block and entity is left alone, so a chest, a door or the vendor still works with the map selected.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!RouteMapItem.isRouteMap(player.getItemInHand(hand))) return InteractionResult.PASS;
            var block = level.getBlockState(hit.getBlockPos());
            if (!block.is(Blocks.DECORATED_POT) && !block.is(Blocks.COMPOSTER)) return InteractionResult.PASS;
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResult.SUCCESS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!RouteMapItem.isRouteMap(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (!(entity instanceof ItemFrame) && !(entity instanceof Allay)) return InteractionResult.PASS;
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResult.SUCCESS;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> reconcile(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks < CHECK_TICKS) return;
            ticks = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) reconcile(player);
        });
    }

    private static void open(ServerPlayer player) {
        try {
            RouteBookService.open(player).ifPresent(message -> player.displayClientMessage(Component.literal(message), true));
        } catch (RuntimeException ex) {
            TowerLog.error("Opening the route map failed for {}", player.getGameProfile().getName(), ex);
            player.displayClientMessage(Component.literal("The map would not open. Try again in a moment."), true);
        }
    }

    /** Whether the player should be holding a Route Map right now: alive, in the tower, in a live run whose tower has a map. */
    static boolean wants(ServerPlayer player) {
        if (!player.isAlive() || !player.level().dimension().equals(TowerDimension.LEVEL)) return false;
        Optional<PersistedRun> run = ParticipantService.runOf(player.getUUID());
        return run.isPresent() && run.get().state().isLive() && RouteBookService.hasMap(TowerDefinitionRegistry.content(), run.get());
    }

    /** Brings one player to exactly one map (when they should have it) or none. */
    static void reconcile(ServerPlayer player) {
        try {
            boolean want = wants(player);
            Inventory inventory = player.getInventory();
            boolean kept = false;
            // a map picked up by the cursor counts as the one they have
            ItemStack carried = player.containerMenu.getCarried();
            if (RouteMapItem.isRouteMap(carried)) {
                if (want) kept = true;
                else player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!RouteMapItem.isRouteMap(stack)) continue;
                if (want && !kept) {
                    kept = true;
                    if (stack.getCount() != 1) stack.setCount(1);
                } else {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
            if (!want) {
                if (player.containerMenu instanceof RouteScreenMenu) player.closeContainer();
                return;
            }
            if (!kept) give(inventory);
        } catch (RuntimeException ex) {
            TowerLog.error("Keeping the route map in order failed for {}", player.getGameProfile().getName(), ex);
        }
    }

    /** Puts a map in the last free hotbar slot, else the first free slot above it; nothing already held is moved, and a full inventory waits for the next check. */
    private static void give(Inventory inventory) {
        for (int slot = 8; slot >= 0; slot--) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, RouteMapItem.create());
                return;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, RouteMapItem.create());
                return;
            }
        }
    }
}
