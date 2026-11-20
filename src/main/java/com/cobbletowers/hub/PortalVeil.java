package com.cobbletowers.hub;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The veil in a hub portal: two thin sheets of the tower's coloured glass filling the opening, full bright, breathing slowly in and
 * out of step with each other, and motes of the tower's colour drifting in it. Display entities only (vanilla clients see them, no
 * pack needed); the opening itself stays air, so walking in is a walk, not a collision. Self-ticking through its chunk attachment;
 * never saved. Main thread only.
 */
final class PortalVeil extends ElementHolder {

    private static final int BREATH = 50;

    private final ServerLevel level;
    private final BlockDisplayElement[] sheets = new BlockDisplayElement[2];
    private final Vec3 centre;
    private final float width;
    private final float height;
    private final boolean wideAlongX;
    private final Vector3f rgb;
    private int age;

    PortalVeil(ServerLevel level, BlockPos origin, HubLayout.Portal portal) {
        this.level = level;
        this.wideAlongX = portal.wideAlongX();
        int across = wideAlongX ? portal.maxX() - portal.minX() + 1 : portal.maxZ() - portal.minZ() + 1;
        this.width = across;
        this.height = portal.maxY() - portal.minY() - 0.5f;
        // the veil hangs in the plaza-side face of the opening
        HubLayout.Spot back = portal.back();
        double faceX = wideAlongX ? (portal.minX() + portal.maxX() + 1) / 2.0
                : (back.x() > portal.maxX() ? portal.maxX() + 0.6 : portal.minX() + 0.4);
        double faceZ = wideAlongX ? (back.z() > portal.maxZ() ? portal.maxZ() + 0.6 : portal.minZ() + 0.4)
                : (portal.minZ() + portal.maxZ() + 1) / 2.0;
        this.centre = new Vec3(origin.getX() + faceX, origin.getY() + portal.minY() + height / 2.0, origin.getZ() + faceZ);
        int c = portal.color();
        this.rgb = new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
        Block glass = glassFor(portal.tower().getPath());
        for (int i = 0; i < 2; i++) {
            BlockDisplayElement sheet = new BlockDisplayElement(glass.defaultBlockState());
            sheet.setBrightness(new Brightness(15, 15));
            sheet.setInterpolationDuration(BREATH);
            sheet.setViewRange(1.5f);
            sheet.setShadowRadius(0);
            sheets[i] = addElement(sheet);
        }
        pose(0, false);
        ChunkAttachment.ofTicking(this, level, centre);
    }

    private static Block glassFor(String tower) {
        return switch (tower) {
            case "neutral" -> Blocks.YELLOW_STAINED_GLASS;
            case "tideforge" -> Blocks.LIGHT_BLUE_STAINED_GLASS;
            case "duskvale" -> Blocks.MAGENTA_STAINED_GLASS;
            case "rootvale" -> Blocks.LIME_STAINED_GLASS;
            default -> Blocks.WHITE_STAINED_GLASS;
        };
    }

    /** The two sheets, a hair apart, one breathing in while the other breathes out. */
    private void pose(int phase, boolean interpolate) {
        for (int i = 0; i < 2; i++) {
            boolean in = (phase + i) % 2 == 0;
            float depth = in ? 0.06f : 0.02f;
            float grow = in ? 0.0f : 0.25f;
            float w = width + grow;
            float h = height + grow;
            float offset = (i == 0 ? -0.08f : 0.08f);
            Vector3f scale = wideAlongX ? new Vector3f(w, h, depth) : new Vector3f(depth, h, w);
            Vector3f shift = wideAlongX ? new Vector3f(-w / 2, -h / 2, offset - depth / 2) : new Vector3f(offset - depth / 2, -h / 2, -w / 2);
            sheets[i].setScale(scale);
            sheets[i].setTranslation(shift);
            if (interpolate) sheets[i].startInterpolation();
        }
    }

    @Override
    protected void onTick() {
        age++;
        if (age % BREATH == 0) pose(age / BREATH, true);
        if (age % 5 == 0 && !getWatchingPlayers().isEmpty()) {
            double spreadX = wideAlongX ? width * 0.35 : 0.05;
            double spreadZ = wideAlongX ? 0.05 : width * 0.35;
            level.sendParticles(new DustParticleOptions(rgb, 1.1f), centre.x, centre.y, centre.z, 4, spreadX, height * 0.35, spreadZ, 0.01);
        }
    }
}
