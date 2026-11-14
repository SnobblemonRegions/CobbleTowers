package com.cobbletowers.vendor;

import com.cobbletowers.CobbleTowers;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Gilded Merchant's body, drawn and animated entirely from the server: one Polymer item display per part of {@link MerchantRig}, each
 * carrying that bone's own item model, riding the (invisible) vendor mob. Every {@link #STEP} ticks the server poses the rig and sends each
 * display its new transformation; the client interpolates between them, so the idle loop and the bow play smoothly with no client mod.
 * The mob stays the click box, as with Grizelda ({@link GoblinDisplay}).
 */
final class MerchantDisplay {

    /** Ticks between poses; also each display's interpolation time, so motion is continuous. */
    static final int STEP = 2;
    /** Above the hood's crest, where a name tag would sit (the mob is invisible, so its own tag is not drawn). */
    private static final Vec3 NAME_AT = new Vec3(0, 2.6, 0);

    private static final Map<MerchantRig.Part, PolymerModelData> MODELS = new LinkedHashMap<>();
    private static final Map<Entity, Body> BODIES = new WeakHashMap<>();

    static {
        for (MerchantRig.Part part : MerchantRig.get().parts()) {
            MODELS.put(part, PolymerResourcePackUtils.requestModel(Items.PAPER, CobbleTowers.id(part.model())));
        }
    }

    private MerchantDisplay() {}

    /** Forces the class to load so the models are requested before Polymer builds the pack. */
    static void bootstrap() {}

    /** Hides {@code vendor} and draws the merchant on it. Idempotent per entity. */
    static void attach(Mob vendor) {
        synchronized (BODIES) {
            if (BODIES.containsKey(vendor)) return;
        }
        Body body = new Body(vendor);
        EntityAttachment.ofTicking(body, vendor);
        // a vendor spawned before the merchant existed is a plain visible cleric: hide it the way new ones are (a LivingEntity recomputes
        // invisibility from its effects every tick, so setInvisible alone would be undone), and leave the name to the text display
        if (!vendor.hasEffect(MobEffects.INVISIBILITY)) {
            vendor.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        }
        vendor.setInvisible(true);
        vendor.setCustomNameVisible(false);
        synchronized (BODIES) {
            BODIES.put(vendor, body);
        }
    }

    /** Plays the bow on {@code vendor}'s body, if it has one. */
    static void greet(Entity vendor) {
        Body body;
        synchronized (BODIES) {
            body = BODIES.get(vendor);
        }
        if (body != null) body.greet();
    }

    private static final class Body extends ElementHolder {
        private final Mob vendor;
        private final Map<MerchantRig.Part, ItemDisplayElement> elements = new LinkedHashMap<>();
        private final int phase;
        private int ticks;
        private int greetStart = -1;

        Body(Mob vendor) {
            this.vendor = vendor;
            this.phase = Math.floorMod(vendor.getUUID().hashCode(), 80);   // two merchants never breathe in step
            for (Map.Entry<MerchantRig.Part, PolymerModelData> m : MODELS.entrySet()) {
                ItemStack stack = new ItemStack(m.getValue().item());
                stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(m.getValue().value()));
                ItemDisplayElement element = new ItemDisplayElement(stack);
                element.setModelTransformation(ItemDisplayContext.NONE);
                element.setViewRange(1.5f);
                element.setShadowRadius(0f);
                element.setInterpolationDuration(STEP);
                if (m.getKey().glow()) element.setBrightness(new Brightness(15, 15));   // eyes and lantern stay lit in a dark shop
                elements.put(m.getKey(), element);
                addElement(element);
            }
            TextDisplayElement name = new TextDisplayElement(Component.literal(VendorNpcRules.MERCHANT_NAME));
            name.setBillboardMode(Display.BillboardConstraints.CENTER);
            name.setOffset(NAME_AT);
            name.setViewRange(0.5f);
            addElement(name);
            pose();
        }

        void greet() {
            greetStart = ticks;
        }

        @Override
        protected void onTick() {
            ticks++;
            if (ticks % STEP == 0) pose();
        }

        private void pose() {
            MerchantRig rig = MerchantRig.get();
            double greetSeconds = -1;
            if (greetStart >= 0) {
                greetSeconds = (ticks - greetStart) / 20.0;
                if (greetSeconds > rig.animation(MerchantRig.GREET).length()) {
                    greetStart = -1;
                    greetSeconds = -1;
                }
            }
            Map<MerchantRig.Part, Matrix4f> pose = rig.pose((ticks + phase) / 20.0, greetSeconds, vendor.getYRot());
            for (Map.Entry<MerchantRig.Part, ItemDisplayElement> e : elements.entrySet()) {
                e.getValue().setTransformation(pose.get(e.getKey()));
                e.getValue().startInterpolationIfDirty();
            }
        }
    }
}
