package com.cobbletowers.hub;

import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/**
 * The Season Chest's item models and the floor ring's lit tier mark (owner, 23 Nov 2026), written by {@code tools/tower_gen/season_chest_export.py}.
 * The body and glow are drawn from the chest's foot, the lid from its hinge ({@link #HINGE}), the mark centred on itself.
 */
public final class SeasonChestModels {

    /** The lid's hinge in the chest's frame, in blocks: the body's back top edge (+z is the chest's front). */
    public static final double HINGE_Y = 13 / 16.0, HINGE_Z = -8 / 16.0;
    private static final Map<String, PolymerModelData> MODELS = new HashMap<>();

    static {
        for (String part : new String[] {"body", "lid", "glow", "inlay", "locked", "claimed"}) {
            MODELS.put(part, PolymerResourcePackUtils.requestModel(Items.PAPER, ResourceLocation.fromNamespaceAndPath("cobbletowers", "season_chest/" + part)));
        }
    }

    private SeasonChestModels() {}

    /** Forces the class to load, so every model is requested before Polymer builds the pack. */
    public static void bootstrap() {}

    /** body, lid, glow or inlay; or the screen's icons, locked (a tier ahead) or claimed. */
    public static ItemStack part(String name) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(MODELS.get(name).value()));
        return stack;
    }
}
