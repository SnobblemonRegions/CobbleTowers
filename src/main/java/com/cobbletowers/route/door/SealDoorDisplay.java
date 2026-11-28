package com.cobbletowers.route.door;

import com.cobbletowers.halloween.door.DoorMotion;
import com.cobbletowers.halloween.door.DoorPose;
import com.cobbletowers.route.RouteMap;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * One Split Seal door: the six parts (frame, its glow, each leaf and its glow) and the two icon halves, each half posed with its leaf. The glow
 * parts show only while the door is lit. Self-ticking through its chunk attachment; never saved. Main thread only.
 */
final class SealDoorDisplay extends ElementHolder {

    /** A client without the pack would see sheets of paper; it gets the wall and gap only. */
    static volatile boolean requirePack = true;

    private final ItemDisplayElement[] parts = new ItemDisplayElement[SealDoorModels.PARTS.length];
    private final ItemDisplayElement[] icon = new ItemDisplayElement[2];
    private final ItemStack[] glowStacks = new ItemStack[SealDoorModels.PARTS.length];
    private final DoorMotion motion;
    private final int turns;
    private boolean lit;

    SealDoorDisplay(ServerLevel level, Vec3 corner, int quarterTurns, String trim, RouteMap.Stop stop, boolean open, boolean lit) {
        this.turns = quarterTurns;
        this.motion = new DoorMotion(open);
        this.lit = lit;
        for (int i = 0; i < parts.length; i++) {
            boolean glow = i % 2 == 1;
            ItemStack stack = SealDoorModels.part(trim, i);
            if (glow) glowStacks[i] = stack;
            ItemDisplayElement element = element(glow && !lit ? ItemStack.EMPTY : stack);
            if (glow) element.setBrightness(new Brightness(15, 15));
            parts[i] = addElement(element);
        }
        icon[0] = addElement(element(SealDoorModels.icon(stop, -1)));
        icon[1] = addElement(element(SealDoorModels.icon(stop, 1)));
        pose(false);
        ChunkAttachment.ofTicking(this, level, corner);
    }

    private static ItemDisplayElement element(ItemStack stack) {
        ItemDisplayElement element = new ItemDisplayElement(stack);
        element.setModelTransformation(ItemDisplayContext.NONE);
        element.setInterpolationDuration(1);
        element.setTeleportDuration(0);
        element.setDisplaySize(9, 10);
        element.setViewRange(1);
        element.setShadowRadius(0);
        return element;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl handler) {
        return (!requirePack || PolymerResourcePackUtils.hasMainPack(handler.getPlayer())) && super.startWatching(handler);
    }

    @Override
    protected void onTick() {
        if (motion.tick()) pose(true);
    }

    void setOpen(boolean open) {
        motion.setOpen(open);
    }

    void setLit(boolean lit) {
        if (this.lit == lit) return;
        this.lit = lit;
        for (int i = 1; i < parts.length; i += 2) parts[i].setItem(lit ? glowStacks[i] : ItemStack.EMPTY);
    }

    private void pose(boolean interpolate) {
        for (int i = 0; i < parts.length; i++) {
            parts[i].setTransformation(DoorPose.grand(turns, SealDoorModels.side(i), motion.angle()));
            if (interpolate) parts[i].startInterpolation();
        }
        for (int s = 0; s < 2; s++) {
            icon[s].setTransformation(DoorPose.grand(turns, s == 0 ? -1 : 1, motion.angle()));
            if (interpolate) icon[s].startInterpolation();
        }
    }
}
