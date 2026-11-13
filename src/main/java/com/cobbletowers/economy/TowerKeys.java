package com.cobbletowers.economy;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.TowerLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.server.level.ServerPlayer;

/**
 * The entry keys: the tower key ({@code cobbletowers:tower_key}, decided 2026-10-05) for an ordinary run and the rental
 * key ({@code cobbletowers:rental_key}) for a rental-draft run, one per player. Required by default;
 * {@code config/cobbletowers-keys.json} (written on first start, re-read when it changes) can set {@code {"required": false}}. {@link TowerKeyPolicy} says which runs cost which
 * key; the lobby takes one only once the run has started.
 */
public final class TowerKeys {

    /** The two keys. */
    public enum Kind {
        TOWER("tower_key", Rarity.UNCOMMON, "Tower Key"),
        RENTAL("rental_key", Rarity.RARE, "Rental Key");

        private final String path;
        private final Rarity rarity;
        private final String label;
        private Item item;

        Kind(String path, Rarity rarity, String label) {
            this.path = path;
            this.rarity = rarity;
            this.label = label;
        }

        public ResourceLocation id() {
            return CobbleTowers.id(path);
        }

        /** The name players see in messages. */
        public String label() {
            return label;
        }
    }

    public static final ResourceLocation ID = CobbleTowers.id("tower_key");

    private static volatile boolean required = TowerKeyPolicy.DEFAULT_REQUIRED;
    private static final int POLL_TICKS = 200;
    private static int pollTicks;
    private static long seenModified;

    private TowerKeys() {}

    /** Server-only item (P43): clients see a trial key with a custom model. */
    private static final class KeyItem extends Item implements PolymerItem {
        private final ResourceLocation id;
        private final PolymerModelData model;

        KeyItem(Item.Properties properties, ResourceLocation id) {
            super(properties);
            this.id = id;
            this.model = PolymerResourcePackUtils.requestModel(Items.TRIAL_KEY, id.withPrefix("item/"));
        }

        @Override
        public Item getPolymerItem(ItemStack stack, ServerPlayer player) {
            return model.item();
        }

        @Override
        public net.minecraft.network.chat.Component getName(ItemStack stack) {
            return com.cobbletowers.ServerLang.itemName(id);
        }

        @Override
        public int getPolymerCustomModelData(ItemStack stack, ServerPlayer player) {
            return model.value();
        }
    }

    /** Registers the item and reads the setting at each server start. Called once at startup. */
    public static synchronized void install() {
        if (Kind.TOWER.item == null) {
            for (Kind kind : Kind.values()) {
                kind.item = Registry.register(BuiltInRegistries.ITEM, kind.id(),
                        new KeyItem(new Item.Properties().rarity(kind.rarity), kind.id()));
            }
            ServerLifecycleEvents.SERVER_STARTING.register(server -> required = load());
            ServerTickEvents.END_SERVER_TICK.register(server -> pollConfig());
            ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> giveBack(player));
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> giveBack(handler.getPlayer()));
        }
    }

    public static boolean required() {
        return required;
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobbletowers-keys.json");
    }

    /** Writes the default file when there is none, so an operator has something to edit. */
    private static void writeDefault(Path file) {
        try {
            Files.writeString(file, "{\n  \"required\": " + TowerKeyPolicy.DEFAULT_REQUIRED + "\n}\n");
        } catch (IOException ex) {
            TowerLog.warn("Could not write the default {}: {}", file, ex.toString());
        }
    }

    /** Reads the setting; a file that cannot be read keeps the current value, so a typo mid-edit cannot flip it. */
    private static boolean load() {
        Path file = configFile();
        if (!Files.exists(file)) {
            writeDefault(file);
            stamp(file);
            return TowerKeyPolicy.DEFAULT_REQUIRED;
        }
        stamp(file);
        try {
            boolean on = TowerKeyPolicy.parseRequired(Files.readString(file));
            TowerLog.info("Tower keys are {} ({})", on ? "required for runs" : "not required", file);
            return on;
        } catch (IOException | RuntimeException ex) {
            TowerLog.error("{} is not usable ({}); keeping tower keys {}", file, ex.toString(), required ? "required" : "not required");
            return required;
        }
    }

    private static void stamp(Path file) {
        try {
            seenModified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException ex) {
            seenModified = 0;
        }
    }

    /** Every ten seconds, picks up a rewritten config file (one stat call, not a read). */
    private static void pollConfig() {
        if (++pollTicks < POLL_TICKS) return;
        pollTicks = 0;
        Path file = configFile();
        try {
            long modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            if (modified != seenModified) required = load();
        } catch (IOException ex) {
            // Next poll tries again.
        }
    }

    /** A dying player does not drop a tower key: it is held on disk and returned on respawn (or at the next login). */
    public static void setAside(net.minecraft.server.MinecraftServer server, java.util.UUID player, int count) {
        var holds = com.cobbletowers.persistence.TowerKeyHoldStore.get(server);
        holds.add(player, count);
        holds.checkpoint(server);
    }

    private static void giveBack(ServerPlayer player) {
        if (Kind.TOWER.item == null) return;
        var holds = com.cobbletowers.persistence.TowerKeyHoldStore.get(player.server);
        int count = holds.take(player.getUUID());
        if (count <= 0) return;
        ItemStack keys = new ItemStack(Kind.TOWER.item, count);
        if (!player.getInventory().add(keys)) player.drop(keys, false);
    }

    /** Gives one key back after a start that took it and then failed. */
    public static void refund(ServerPlayer player, Kind kind) {
        if (kind.item == null) return;
        ItemStack key = new ItemStack(kind.item, 1);
        if (!player.getInventory().add(key)) player.drop(key, false);
    }

    /** Whether this stack is a tower key, which stays with its holder (the rental key can be sold and traded). */
    public static boolean isTradeLocked(ItemStack stack) {
        return Kind.TOWER.item != null && stack.is(Kind.TOWER.item);
    }

    public static int count(Player player) {
        return count(player, Kind.TOWER);
    }

    public static int count(Player player, Kind kind) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(kind.item)) total += stack.getCount();
        }
        return total;
    }

    /** Takes one key from the player's inventory. @return whether there was one to take */
    public static boolean take(Player player) {
        return take(player, Kind.TOWER);
    }

    public static boolean take(Player player, Kind kind) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(kind.item)) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }
}
