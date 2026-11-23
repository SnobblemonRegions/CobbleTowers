package com.cobbletowers.hub;

import com.cobbletowers.network.TrackActionPayload;
import com.cobbletowers.network.TrackStatePayload;
import com.cobbletowers.network.TrackStatePayload.Lane;
import com.cobbletowers.network.TrackStatePayload.Node;
import com.cobbletowers.track.TrackService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.util.Unit;

/**
 * The Season Chest's screen (owner, 23 Nov 2026): the season track drawn in an ordinary 9 x 6 chest, so every player can use it with no mod.
 * The tiers wind through the top five rows ({@link SeasonChestLayout}) over empty slots: a claimed tier is a green check medal, a ready one
 * shows its reward glinting and is claimed with a click, the next one shows its reward and how far along it is, and the rest are lock tiles
 * naming their reward and the points still to go. (Glass panes were tried first: grey ones vanish against the slots.) Claims go through {@link TrackService#apply}, which checks them again. Server thread only.
 */
final class SeasonChest {

    private static final int GREEN = 0x6BE07A, GOLD = 0xF2C744, WHITE = 0xFFFFFF, GREY = 0x8A8F9C, DIM = 0xA0A6B4, INK = 0xDCE0EC;
    /** Ticks between two redraws for one player: a click can be sent as fast as a modified game likes. */
    private static final int REDRAW_TICKS = 3;

    private SeasonChest() {}

    /** Opens the screen on the page where the player's next tier is. */
    static void open(ServerPlayer player) {
        render(player, -1);
    }

    private static void render(ServerPlayer player, int page) {
        Lane lane = TrackService.build(player.server, player, "", "").season();
        List<Node> nodes = lane.present() ? lane.nodes() : List.of();
        int next = firstOpen(nodes);
        int shown = page < 0 ? SeasonChestLayout.pageOf(Math.max(0, next), nodes.size()) : page;
        List<ItemStack> items = draw(lane, nodes, shown);
        long now = player.level().getGameTime();
        if (player.containerMenu instanceof Menu open) {
            open.drawnAt = now;
            open.show(items, shown);
            open.sendAllDataToRemote();
            return;
        }
        Component title = Component.literal(lane.present() ? lane.title() : "Season Chest");
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
            Menu menu = new Menu(id);
            menu.drawnAt = now;
            menu.show(items, shown);
            return menu;
        }, title));
    }

    /** The first tier not yet claimed (ready or locked), or -1 when every tier is claimed. */
    private static int firstOpen(List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).state() != TrackStatePayload.CLAIMED) return i;
        return -1;
    }

    private static List<ItemStack> draw(Lane lane, List<Node> nodes, int page) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 54; i++) out.add(ItemStack.EMPTY);
        int nextLocked = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).state() == TrackStatePayload.LOCKED) {
                nextLocked = i;
                break;
            }
        }
        int total = lane.current() * lane.need() + lane.into();
        for (int k = 0; k < SeasonChestLayout.PER_PAGE; k++) {
            int i = page * SeasonChestLayout.PER_PAGE + k;
            if (i >= nodes.size()) break;
            out.set(SeasonChestLayout.slotOf(k), tier(nodes.get(i), i == nextLocked, lane, total));
        }
        int pages = SeasonChestLayout.pages(nodes.size());
        if (page > 0) out.set(SeasonChestLayout.BACK_SLOT, named(Items.ARROW, line("Back", WHITE), List.of(line("Page " + page + " of " + pages, DIM))));
        if (page < pages - 1) {
            out.set(SeasonChestLayout.NEXT_SLOT, named(Items.ARROW, line("Next", WHITE), List.of(line("Page " + (page + 2) + " of " + pages, DIM))));
        }
        out.set(SeasonChestLayout.INFO_SLOT, info(lane, nodes));
        long ready = nodes.stream().filter(n -> n.state() == TrackStatePayload.CLAIMABLE).count();
        if (ready > 0) {
            ItemStack all = named(Items.CHEST, line("Claim all · " + ready + (ready == 1 ? " reward" : " rewards"), GOLD), List.of(line("Click to take them all", INK)));
            all.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            out.set(SeasonChestLayout.CLAIM_ALL_SLOT, all);
        }
        return out;
    }

    private static ItemStack tier(Node node, boolean nextLocked, Lane lane, int total) {
        String reward = node.reward().isEmpty() ? "No reward" : node.reward();
        return switch (node.state()) {
            case TrackStatePayload.CLAIMED -> named(SeasonChestModels.part("claimed"), line("Tier " + node.number() + "  ✔", GREEN), List.of(line(reward, INK), line("Claimed", GREEN)));
            case TrackStatePayload.CLAIMABLE -> {
                ItemStack s = named(icon(node), line("Tier " + node.number() + "  ·  ready", GOLD), List.of(line(reward, INK), line("Click to claim", GOLD)));
                s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                yield s;
            }
            case TrackStatePayload.LOCKED -> {
                int toGo = Math.max(0, node.number() * lane.need() - total);
                if (nextLocked) {
                    yield named(icon(node), line("Tier " + node.number() + "  ·  next", WHITE),
                            List.of(line(reward, INK), line(bar(lane.into(), lane.need()) + "  " + lane.into() + " / " + lane.need() + " points", DIM)));
                }
                List<Component> lore = new ArrayList<>(List.of(line(reward, DIM)));
                if (lane.need() > 0) lore.add(line(toGo + " more points", GREY));
                yield named(SeasonChestModels.part("locked"), line("Tier " + node.number(), GREY), lore);
            }
            default -> named(SeasonChestModels.part("locked"), line("Tier " + node.number(), GREY),
                    node.note().isEmpty() ? List.of(line(reward, DIM)) : List.of(line(reward, DIM), line(node.note(), GREY)));
        };
    }

    private static ItemStack info(Lane lane, List<Node> nodes) {
        if (!lane.present()) return named(Items.CLOCK, line("No season running", GREY), List.of(line("The chest fills when a season starts", DIM)));
        List<Component> lore = new ArrayList<>();
        lore.add(line(lane.subtitle(), INK));
        lore.add(line("Tier " + lane.current() + " of " + nodes.size() + " reached", DIM));
        if (lane.endsInMillis() > 0) lore.add(line(capital(lane.timerLabel()) + " " + days(lane.endsInMillis()), DIM));
        return named(Items.CLOCK, line(lane.title(), GOLD), lore);
    }

    private static String capital(String s) {
        String low = s.toLowerCase(java.util.Locale.ROOT);
        return low.isEmpty() ? low : Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    private static String days(long millis) {
        long hours = Math.max(0, millis) / 3_600_000L;
        return hours >= 24 ? hours / 24 + "d " + hours % 24 + "h" : hours + "h " + (Math.max(0, millis) / 60_000L) % 60 + "m";
    }

    /** Ten cells, filled as far as the next tier is reached. */
    private static String bar(int into, int need) {
        int filled = need <= 0 ? 0 : Math.min(10, into * 10 / need);
        return "■".repeat(filled) + "□".repeat(10 - filled);
    }

    static ItemStack icon(Node node) {
        ResourceLocation id = node.icon().isEmpty() ? null : ResourceLocation.tryParse(node.icon());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return new ItemStack(item == Items.AIR ? Items.PAPER : item);
    }

    private static Component line(String text, int rgb) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(rgb).withItalic(false));
    }

    private static ItemStack named(Item item, Component name, List<Component> lore) {
        return named(new ItemStack(item), name, lore);
    }

    private static ItemStack named(ItemStack stack, Component name, List<Component> lore) {
        stack.set(DataComponents.CUSTOM_NAME, name);
        stack.set(DataComponents.LORE, new ItemLore(lore));
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
        return stack;
    }

    private static void clicked(ServerPlayer player, Menu menu, int slot) {
        if (player.level().getGameTime() - menu.drawnAt < REDRAW_TICKS) return;
        if (slot == SeasonChestLayout.BACK_SLOT) {
            render(player, Math.max(0, menu.page - 1));
            return;
        }
        if (slot == SeasonChestLayout.NEXT_SLOT) {
            render(player, menu.page + 1);
            return;
        }
        String action;
        int number = 0;
        if (slot == SeasonChestLayout.CLAIM_ALL_SLOT) {
            action = "claim_all";
        } else {
            int k = SeasonChestLayout.indexAt(slot);
            if (k < 0) return;
            Lane lane = TrackService.build(player.server, player, "", "").season();
            int i = menu.page * SeasonChestLayout.PER_PAGE + k;
            if (!lane.present() || i >= lane.nodes().size() || lane.nodes().get(i).state() != TrackStatePayload.CLAIMABLE) return;
            action = "claim";
            number = lane.nodes().get(i).number();
        }
        // no reveal screen: it would replace this one; the chest shows the claimed tiers turn to medals, and chat lists what was given
        String[] result = new String[1];
        TrackActionPayload request = new TrackActionPayload(action, "season", "", number);
        com.cobbletowers.reward.RewardDelivery.withoutReveal(() -> result[0] = TrackService.apply(player.server, player, request));
        String message = result[0];
        boolean took = message.startsWith("Claimed");
        if (!took && action.equals("claim_all")) return;                   // nothing ready: the button was stale
        player.displayClientMessage(Component.literal(message).withColor(took ? GOLD : GREY), true);
        if (took) player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.4f);
        render(player, menu.page);
        RecordsHall.refreshFor(player);
    }

    /** The chest: 54 slots of its own over the player's inventory; nothing can be taken, put in, swapped or thrown. */
    static final class Menu extends AbstractContainerMenu {

        private final SimpleContainer view = new SimpleContainer(54);
        private int page;
        private long drawnAt = Long.MIN_VALUE / 2;

        Menu(int containerId) {
            super(MenuType.GENERIC_9x6, containerId);
            for (int i = 0; i < 54; i++) {
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
        }

        void show(List<ItemStack> items, int page) {
            this.page = page;
            for (int i = 0; i < 54; i++) view.setItem(i, items.get(i));
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            // The chest's bottom half is drawn over the game's copy of the player's inventory; send the real one again (as the route screen does).
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.inventoryMenu.sendAllDataToRemote();
        }

        @Override
        public void clicked(int slotId, int button, ClickType type, Player player) {
            if (player instanceof ServerPlayer serverPlayer && type == ClickType.PICKUP) SeasonChest.clicked(serverPlayer, this, slotId);
            sendAllDataToRemote();                                          // undo whatever the player's game guessed the click did
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
}
