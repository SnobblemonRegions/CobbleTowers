package com.cobbletowers.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The physical Mystery Bag: a vanilla item that carries its own id in its custom data, so nothing is registered (a server-only mod stays server-only).
 * It never stacks, and the id is what the ledger ({@link MysteryBagBook}) remembers, so a duplicated bag is still only one bag. Right-clicking it opens it
 * ({@link MysteryBagService}).
 */
public final class MysteryBagItem {

    /** The vanilla item the bag is drawn as. */
    public static final Item BASE = Items.LEATHER;

    private static final String TAG = "cobbletowers";
    private static final String KEY = "mystery_bag";
    private static final String ID = "id";
    private static final Style PLAIN = Style.EMPTY.withItalic(false);

    private MysteryBagItem() {}

    /** A fresh bag with the given id. */
    public static ItemStack create(UUID id) {
        ItemStack stack = new ItemStack(BASE);
        CompoundTag inner = new CompoundTag();
        inner.putUUID(ID, id);
        CompoundTag bag = new CompoundTag();
        bag.put(KEY, inner);
        CompoundTag root = new CompoundTag();
        root.put(TAG, bag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Mystery Bag").withStyle(PLAIN.withColor(TextColor.fromRgb(0xE8A33D))));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to open.").withStyle(PLAIN.withColor(TextColor.fromRgb(0xC1C5CF))),
                Component.literal("Holds a random Ascension currency. Most bags hold dust;").withStyle(PLAIN.withColor(TextColor.fromRgb(0x8A8F9C))),
                Component.literal("the rare ones are very rare. It can be traded.").withStyle(PLAIN.withColor(TextColor.fromRgb(0x8A8F9C))))));
        return stack;
    }

    /** The id of the bag, empty when the stack is not a Mystery Bag. */
    public static Optional<UUID> idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag root = data.copyTag();
        if (!root.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag bag = root.getCompound(TAG);
        if (!bag.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag inner = bag.getCompound(KEY);
        return inner.hasUUID(ID) ? Optional.of(inner.getUUID(ID)) : Optional.empty();
    }
}
