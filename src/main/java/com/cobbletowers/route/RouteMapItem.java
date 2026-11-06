package com.cobbletowers.route;

import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.List;
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
 * The Route Map: a vanilla item that carries a marker in its custom data, so nothing is registered (a server-only mod stays server-only). A player in a tower
 * run holds one in their hotbar and right-clicks it to open the map ({@link RouteMapItemService}); it cannot be dropped, stored or traded, and it is taken
 * away when the run ends. Players without the resource pack see a sheet of paper.
 */
public final class RouteMapItem {

    /** The vanilla item it is drawn as. Paper has no use of its own, and cannot be placed or worn. */
    public static final Item BASE = Items.PAPER;

    private static final String TAG = "cobbletowers";
    private static final String KEY = "route_map";
    private static final Style PLAIN = Style.EMPTY.withItalic(false);

    /** The artwork, requested once when {@link #bootstrap()} loads this holder; a classpath without Polymer (a unit test) gets none. */
    private static final class Model {
        static final int VALUE = request();

        private static int request() {
            try {
                PolymerModelData model = PolymerResourcePackUtils.requestModel(BASE, ResourceLocation.fromNamespaceAndPath("cobbletowers", "item/route/map_item"));
                return model.value();
            } catch (LinkageError absent) {
                return 0;
            }
        }
    }

    private RouteMapItem() {}

    /** Requests the model. Must run at startup, before Polymer builds the resource pack. */
    public static void bootstrap() {
        modelValue();
    }

    static int modelValue() {
        return Model.VALUE;
    }

    public static ItemStack create() {
        ItemStack stack = new ItemStack(BASE);
        CompoundTag inner = new CompoundTag();
        inner.putBoolean(KEY, true);
        CompoundTag root = new CompoundTag();
        root.put(TAG, inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        if (modelValue() > 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelValue()));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Route Map").withStyle(PLAIN.withColor(TextColor.fromRgb(0xE8B830))));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to read the tower's map.").withStyle(PLAIN.withColor(TextColor.fromRgb(0xC1C5CF))),
                Component.literal("It stays with you until the run ends.").withStyle(PLAIN.withColor(TextColor.fromRgb(0x8A8F9C))))));
        return stack;
    }

    /** Whether the stack is a Route Map. */
    public static boolean isRouteMap(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(BASE)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag root = data.copyTag();
        if (!root.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) return false;
        return root.getCompound(TAG).getBoolean(KEY);
    }
}
