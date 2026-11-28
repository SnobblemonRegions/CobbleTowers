package com.cobbletowers.command;

import com.cobbletowers.hub.HubLayout;
import com.cobbletowers.hub.HubPortals;
import com.cobbletowers.hub.HubStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Operator tools for the Tower Hub (owner, 19 Nov 2026). {@code /cobbletowers hub build}: stand where the warp should land and the
 * whole hub is pasted round you, its pad under your feet; the mod remembers where, and lists where each tower's host NPC stands and
 * the command its dialog runs. {@code where} lists them again; {@code forget} stops the portals (the blocks stay).
 */
public final class HubCommand {

    /** The paste skips neighbour updates, like a tower cell: water stands still and joined walls stay as stored. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private HubCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers").then(Commands.literal("hub")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("build").executes(HubCommand::build))
                .then(Commands.literal("where").executes(HubCommand::where))
                .then(Commands.literal("forget").executes(context -> {
                    HubStore.get(context.getSource().getServer()).clear();
                    HubPortals.showVeils(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("The hub's portals are off. Its blocks are where they were."), true);
                    return 1;
                }))));
    }

    private static int build(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        HubLayout layout = HubLayout.load();
        BlockPos stand = BlockPos.containing(source.getPosition());
        BlockPos origin = stand.offset(-layout.pad().x(), -layout.pad().y(), -layout.pad().z());
        if (origin.getY() < level.getMinBuildHeight() || origin.getY() + layout.sizeY() > level.getMaxBuildHeight()) {
            source.sendFailure(Component.literal("The hub is " + layout.sizeY() + " blocks tall with its pad " + layout.pad().y()
                    + " above its base: stand between y " + (level.getMinBuildHeight() + layout.pad().y()) + " and y "
                    + (level.getMaxBuildHeight() - layout.sizeY() + layout.pad().y()) + "."));
            return 0;
        }
        var template = level.getStructureManager().get(layout.structure());
        if (template.isEmpty()) {
            source.sendFailure(Component.literal("The structure " + layout.structure() + " is not in the mod."));
            return 0;
        }
        StructureTemplate structure = template.get();
        source.sendSuccess(() -> Component.literal("Building the Tower Hub (" + layout.sizeX() + " x " + layout.sizeY() + " x "
                + layout.sizeZ() + "); the server pauses a few seconds."), true);
        int cleared = clear(level, origin, layout);
        structure.placeInWorld(level, origin, origin, new StructurePlaceSettings().setKnownShape(true), level.getRandom(), PLACE_FLAGS);
        if (cleared > 0) source.sendSuccess(() -> Component.literal("Cleared " + cleared + " block(s) that stood where the hub goes."), false);
        HubStore.get(source.getServer()).set(level.dimension().location(), origin);
        HubPortals.showVeils(source.getServer());
        report(source, level.dimension().location().toString(), origin, layout);
        return 1;
    }

    /**
     * Empties the hub's box first: the structure stores no air, so a rebuild over an older hub (or over terrain) would keep whatever
     * the new one leaves open. Section by section, skipping the empty ones, so a box of open sky costs next to nothing.
     */
    private static int clear(ServerLevel level, BlockPos origin, HubLayout layout) {
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var cursor = new BlockPos.MutableBlockPos();
        int x0 = origin.getX(), y0 = origin.getY(), z0 = origin.getZ();
        int x1 = x0 + layout.sizeX() - 1, y1 = y0 + layout.sizeY() - 1, z1 = z0 + layout.sizeZ() - 1;
        int cleared = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                var chunk = level.getChunk(cx, cz);
                var sections = chunk.getSections();
                for (int s = 0; s < sections.length; s++) {
                    if (sections[s].hasOnlyAir()) continue;
                    int baseY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(s));
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < y0 || y > y1) continue;
                        for (int lx = 0; lx < 16; lx++) {
                            int x = (cx << 4) + lx;
                            if (x < x0 || x > x1) continue;
                            for (int lz = 0; lz < 16; lz++) {
                                int z = (cz << 4) + lz;
                                if (z < z0 || z > z1 || sections[s].getBlockState(lx, ly, lz).isAir()) continue;
                                level.setBlock(cursor.set(x, y, z), air, PLACE_FLAGS);
                                cleared++;
                            }
                        }
                    }
                }
            }
        }
        return cleared;
    }

    private static int where(CommandContext<CommandSourceStack> context) {
        var placed = HubStore.get(context.getSource().getServer()).placed();
        if (placed.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No hub has been built. Stand where the warp should land and run /cobbletowers hub build."));
            return 0;
        }
        report(context.getSource(), placed.get().dimension().toString(), placed.get().origin(), HubLayout.load());
        return 1;
    }

    private static void report(CommandSourceStack source, String dimension, BlockPos origin, HubLayout layout) {
        List<String> lines = new ArrayList<>();
        HubLayout.Spot pad = layout.pad();
        lines.add("Tower Hub in " + dimension + ". Warp pad: " + at(origin, pad) + ".");
        for (HubLayout.Portal portal : layout.portals()) {
            lines.add(portal.tower().getPath() + " host: " + at(origin, portal.host()) + "; its dialog runs: cobbletowers play keeper <player> "
                    + portal.tower());
        }
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
    }

    private static String at(BlockPos origin, HubLayout.Spot spot) {
        return (origin.getX() + spot.x()) + " " + (origin.getY() + spot.y()) + " " + (origin.getZ() + spot.z()) + " facing " + Math.round(spot.yaw());
    }
}
