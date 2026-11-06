package com.cobbletowers.command;

import com.cobbletowers.route.RouteBookService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /cobbletowers route}: opens the tower's route map as a book, with the trail so far and what each open lane leads to. */
public final class RouteCommand {

    private RouteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers").then(Commands.literal("route").executes(context -> {
            var player = context.getSource().getPlayerOrException();
            RouteBookService.open(player).ifPresent(line -> player.sendSystemMessage(Component.literal(line)));
            return 1;
        })));
    }
}
