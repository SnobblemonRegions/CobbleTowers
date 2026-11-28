package com.cobbletowers.command;

import com.cobbletowers.instance.CellCleanup;
import com.cobbletowers.instance.CellGrid;
import com.cobbletowers.instance.CellTickets;
import com.cobbletowers.instance.CellWarmPool;
import com.cobbletowers.instance.InstanceAllocator;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.CellQuarantine;
import com.cobbletowers.persistence.CellStateStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * {@code /cobbletowers cells}: which cells exist, who holds them and which are out of service. {@code list} and
 * {@code show} read; {@code verify}, {@code quarantine} and {@code clear} are the operator's side of TDS #35.
 */
public final class CellsCommand {

    private CellsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers")
                // Gated here, not on the root: Brigadier keeps a merged literal's first requirement.
                .then(Commands.literal("cells")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(CellsCommand::list))
                        .then(Commands.literal("show")
                                .then(cellArgument().executes(CellsCommand::show)))
                        .then(Commands.literal("verify")
                                .then(cellArgument().executes(CellsCommand::verify)))
                        .then(Commands.literal("quarantine")
                                .then(cellArgument()
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(CellsCommand::quarantine))))
                        .then(Commands.literal("clear")
                                .then(cellArgument().executes(CellsCommand::clear)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> cellArgument() {
        return Commands.argument("cell", IntegerArgumentType.integer(0, CellGrid.MAX_CELLS - 1));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Map<Integer, CellQuarantine> quarantined = CellStateStore.get(source.getServer()).quarantined();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "%d of %d cells leased, %d quarantined, %d warm, dimension %s",
                        InstanceAllocator.leasedCount(), CellGrid.MAX_CELLS, quarantined.size(),
                        CellWarmPool.readyCount(),
                        TowerDimension.isLoaded(source.getServer()) ? "loaded" : "MISSING"))
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d cell(s) held loaded, %d chunk(s) each, %d tower chunk(s) in all",
                CellTickets.heldCount(), CellTickets.chunksPerCell(),
                CellTickets.heldCount() * CellTickets.chunksPerCell())), false);

        for (int cell = 0; cell < CellGrid.MAX_CELLS; cell++) {
            final int index = cell;
            InstanceAllocator.runIn(cell).ifPresent(runId -> source.sendSuccess(() -> Component.literal(
                    "  " + index + "  leased by " + runId), false));
        }
        for (CellQuarantine entry : quarantined.values()) {
            source.sendSuccess(() -> Component.literal("  " + entry.cell() + "  quarantined" + (entry.manual() ? " by hand (clear it yourself)" : " (retried every 5 min)")
                    + ": " + entry.reason()).withStyle(ChatFormatting.RED), false);
        }
        return InstanceAllocator.leasedCount();
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int cell = IntegerArgumentType.getInteger(context, "cell");
        BlockPos origin = CellGrid.originOf(cell);
        BlockPos center = CellGrid.centerOf(cell);

        source.sendSuccess(() -> Component.literal("Cell " + cell).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  origin %d %d %d  centre %d %d %d  interior %d blocks",
                origin.getX(), origin.getY(), origin.getZ(), center.getX(), center.getY(), center.getZ(),
                CellGrid.INTERIOR)), false);
        source.sendSuccess(() -> Component.literal("  chunks " + CellGrid.minChunk(cell) + " .. "
                + CellGrid.maxChunk(cell)), false);
        source.sendSuccess(() -> Component.literal("  " + InstanceAllocator.runIn(cell)
                .map(runId -> "leased by " + runId).orElse("free")
                + (CellWarmPool.isWarm(cell) ? " (warm, built and waiting)" : "")
                + (CellTickets.isHeld(cell) ? ", chunks held" : ", chunks not held")), false);
        CellStateStore.get(source.getServer()).quarantined().values().stream()
                .filter(entry -> entry.cell() == cell)
                .forEach(entry -> source.sendSuccess(() -> Component.literal("  quarantined: " + entry.reason())
                        .withStyle(ChatFormatting.RED), false));
        return 1;
    }

    private static int verify(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int cell = IntegerArgumentType.getInteger(context, "cell");
        CellCleanup.Report report = CellCleanup.verify(source.getServer(), cell);

        source.sendSuccess(() -> Component.literal("Cell " + cell + ": " + report.summary())
                .withStyle(report.isClean() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return report.isClean() ? 1 : 0;
    }

    private static int quarantine(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int cell = IntegerArgumentType.getInteger(context, "cell");
        String reason = StringArgumentType.getString(context, "reason");
        InstanceAllocator.quarantine(source.getServer(), cell, reason);
        source.sendSuccess(() -> Component.literal("Cell " + cell + " is out of service: " + reason)
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int cell = IntegerArgumentType.getInteger(context, "cell");
        if (!InstanceAllocator.clearQuarantine(source.getServer(), cell)) {
            source.sendFailure(Component.literal("Cell " + cell + " was not quarantined"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cell " + cell + " is back in service")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
