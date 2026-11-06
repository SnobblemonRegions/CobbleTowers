package com.cobbletowers.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The physical Mystery Bag: a vanilla item that carries its own id in its custom data, so nothing is registered (a server-only mod stays server-only).
 * It never stacks, and the id is what the ledger ({@link MysteryBagBook}) remembers, so a duplicated bag is still only one bag. Right-clicking it opens it
 * ({@link MysteryBagService}).
 */
public final class MysteryBagItem {

    /** The vanilla item the bag is drawn as. */
    public static final Item BASE = Items.LEATHER;

    /**
     * The bag's own artwork ({@code textures/item/mystery_bag.png}, drawn by {@code tools/vendor/make_mystery_bag_texture.py}), requested through Polymer on the
     * base item so a player with the server resource pack sees the sack and everybody else sees the plain base item. Held in its own class so the request is
     * made once, when {@link #bootstrap()} loads it, and so a classpath without Polymer (a unit test) simply gets no custom model.
     */
    private static final class Model {
        static final int VALUE = request();

        private static int request() {
            try {
                PolymerModelData model = PolymerResourcePackUtils.requestModel(BASE, ResourceLocation.fromNamespaceAndPath("cobbletowers", "item/mystery_bag"));
                return model.value();
            } catch (LinkageError absent) {
                return 0;
            }
        }
    }

    private static final String TAG = "cobbletowers";
    private static final String KEY = "mystery_bag";
    private static final String ID = "id";
    private static final Style PLAIN = Style.EMPTY.withItalic(false);

    private MysteryBagItem() {}

    /**
     * Requests the bag's model. Must run at startup, before Polymer builds the resource pack: a model requested later (the first time somebody buys a bag) would
     * never be in the pack.
     */
    public static void bootstrap() {
        modelValue();
    }

    /** The custom model number Polymer allocated for the bag, or 0 when there is none. */
    static int modelValue() {
        return Model.VALUE;
    }

    /** The stack drawn with the custom model {@code value}; a value of 0 or less (no Polymer, so no model) leaves it as the plain base item. */
    static ItemStack withModel(ItemStack stack, int value) {
        if (value > 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(value));
        return stack;
    }

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
        withModel(stack, modelValue());
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
