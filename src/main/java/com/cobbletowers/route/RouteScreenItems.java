package com.cobbletowers.route;

import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The items in the route screen's slots: a vanilla item drawn as nothing (an empty model from the resource pack), carrying only the name and lore a tooltip
 * shows. The picture the player sees is the screen's title; these are the hover targets under it. They live only in the menu, never in an inventory.
 */
public final class RouteScreenItems {

    static final Item BASE = Items.PAPER;

    /** The empty model, requested once when {@link #bootstrap()} loads this holder; a classpath without Polymer (a unit test) gets none. */
    private static final class Model {
        static final int VALUE = request();

        private static int request() {
            try {
                PolymerModelData model = PolymerResourcePackUtils.requestModel(BASE, ResourceLocation.fromNamespaceAndPath("cobbletowers", "item/route/blank"));
                return model.value();
            } catch (LinkageError absent) {
                return 0;
            }
        }
    }

    private RouteScreenItems() {}

    /** Requests the model. Must run at startup, before Polymer builds the resource pack. */
    public static void bootstrap() {
        modelValue();
    }

    static int modelValue() {
        return Model.VALUE;
    }

    /** A hover target: drawn as nothing, named and described by the tooltip. */
    public static ItemStack tip(Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(BASE);
        if (modelValue() > 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelValue()));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, name);
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }
}
