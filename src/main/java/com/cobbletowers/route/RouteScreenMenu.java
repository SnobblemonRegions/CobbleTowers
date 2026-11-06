package com.cobbletowers.route;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The chest the route screen is drawn on: nine columns by six rows, then the player's inventory and hotbar, all 90 slots backed by one container of its own, so
 * nothing in it is the player's. Every slot refuses everything, and every click does nothing except the two page buttons, so there is nothing to take, put
 * back, swap or throw. The items in it are the hover targets of {@link RouteScreenItems}; the picture is the title ({@link RouteScreenLayout}). Server only: the
 * player's game sees an ordinary 9 x 6 chest.
 */
public final class RouteScreenMenu extends AbstractContainerMenu {

    private final SimpleContainer view = new SimpleContainer(RouteScreenFont.SLOTS);
    private int page;
    private long renderedAt = Long.MIN_VALUE / 2;

    RouteScreenMenu(int containerId, RouteScreenLayout.View first) {
        super(MenuType.GENERIC_9x6, containerId);
        for (int i = 0; i < RouteScreenFont.SLOTS; i++) {
            addSlot(new Slot(view, i, 0, 0) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
        show(first);
    }

    /** Fills the slots with the tips of a page. */
    void show(RouteScreenLayout.View next) {
        page = next.page();
        view.clearContent();
        for (RouteScreenLayout.Tip tip : next.tips()) {
            if (tip.slot() >= 0 && tip.slot() < RouteScreenFont.SLOTS) view.setItem(tip.slot(), RouteScreenItems.tip(tip.name(), tip.lore()));
        }
    }

    int page() {
        return page;
    }

    /** Whether this screen was drawn or turned within the last few ticks (a click on a page button can be sent as fast as a modified game likes). */
    boolean drawnWithin(long now, int ticks) {
        return now - renderedAt < ticks;
    }

    void drawnAt(long now) {
        renderedAt = now;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // The game's own copy of the player's inventory is what this chest's lower 36 slots are drawn from, so the contents the server sent for them (nothing, or a
        // button) were written over it. Closing sends nothing for them, so the real inventory is sent again here or the hotbar would stay blank until something changed.
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.inventoryMenu.sendAllDataToRemote();
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        // Only the two page buttons do anything, and only on a plain click; the carried item and every slot stay as they are. The server then
        // resends whatever the player's game guessed the click did.
        if (!(player instanceof ServerPlayer serverPlayer) || type != ClickType.PICKUP) return;
        if (slotId == RouteScreenFont.BACK_SLOT) RouteScreenService.turn(serverPlayer, this, -1);
        else if (slotId == RouteScreenFont.NEXT_SLOT) RouteScreenService.turn(serverPlayer, this, 1);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}
