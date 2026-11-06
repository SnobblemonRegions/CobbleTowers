package com.cobbletowers.route;

import com.cobbletowers.TowerLog;
import com.cobbletowers.route.RouteMap.Stop;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/**
 * The route map's stop icons: a small box model per stop ({@code models/item/route/*.json}, made by {@code tools/vendor/make_route_icons.py}), shown through
 * Polymer on a vanilla item. The vanilla item is the fallback: a player who has not taken the server resource pack sees a sword for a Battle, a campfire for
 * a Rest and so on, so the map still reads. Nothing is registered, so a server-only mod stays server-only.
 */
public final class RouteIcons {

    /** The model a stop uses (under {@code item/route/}) and the vanilla item it falls back to. */
    public record Icon(String model, Item fallback) {}

    private static final Map<Stop, Icon> ICONS = new EnumMap<>(Stop.class);

    static {
        ICONS.put(Stop.START, new Icon("start", Items.COMPASS));
        ICONS.put(Stop.BATTLE, new Icon("battle", Items.IRON_SWORD));
        ICONS.put(Stop.ELITE, new Icon("elite", Items.NETHERITE_SWORD));
        ICONS.put(Stop.REST, new Icon("rest", Items.CAMPFIRE));
        ICONS.put(Stop.SHRINE, new Icon("shrine", Items.ENCHANTING_TABLE));
        ICONS.put(Stop.FORGE, new Icon("forge", Items.ANVIL));
        ICONS.put(Stop.LIBRARY, new Icon("library", Items.BOOKSHELF));
        ICONS.put(Stop.VAULT, new Icon("vault", Items.CHEST));
        ICONS.put(Stop.POOL, new Icon("pool", Items.HEART_OF_THE_SEA));
        ICONS.put(Stop.MERCHANT, new Icon("shop", Items.EMERALD));
        ICONS.put(Stop.TRICK_OR_TREAT, new Icon("treat", Items.JACK_O_LANTERN));
        ICONS.put(Stop.BOSS, new Icon("boss", Items.WITHER_SKELETON_SKULL));
    }

    /**
     * The custom model numbers Polymer allocated, requested once when {@link #bootstrap()} loads this holder, and so a classpath without Polymer (a unit test)
     * simply gets no custom models.
     */
    private static final class Models {
        static final Map<Stop, Integer> VALUES = request();

        private static Map<Stop, Integer> request() {
            Map<Stop, Integer> out = new EnumMap<>(Stop.class);
            for (Map.Entry<Stop, Icon> e : ICONS.entrySet()) {
                try {
                    var data = PolymerResourcePackUtils.requestModel(e.getValue().fallback(), path(e.getValue()));
                    out.put(e.getKey(), data.value());
                } catch (LinkageError absent) {
                    out.put(e.getKey(), 0);
                }
            }
            return out;
        }
    }

    private RouteIcons() {}

    static ResourceLocation path(Icon icon) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", "item/route/" + icon.model());
    }

    public static Icon iconOf(Stop stop) {
        return ICONS.get(stop);
    }

    public static Iterable<Stop> stops() {
        return ICONS.keySet();
    }

    /** Requests every model. Must run at startup, before Polymer builds the resource pack: a model requested later would never be in the pack. */
    public static void bootstrap() {
        for (Map.Entry<Stop, Integer> e : Models.VALUES.entrySet()) {
            TowerLog.info("Route icon {} -> custom model data {}", e.getKey(), e.getValue());
        }
    }

    /** The custom model number for a stop, or 0 when there is none (no Polymer). */
    static int modelValue(Stop stop) {
        Integer value = Models.VALUES.get(stop);
        return value == null ? 0 : value;
    }

    /** The stack a display shows for a stop: its vanilla fallback item, drawn with the stop's own model when there is one. */
    public static ItemStack stackFor(Stop stop) {
        return withModel(new ItemStack(ICONS.get(stop).fallback()), modelValue(stop));
    }

    /** The stack drawn with the custom model {@code value}; a value of 0 or less leaves it as the plain fallback item. */
    static ItemStack withModel(ItemStack stack, int value) {
        if (value > 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(value));
        return stack;
    }
}
