package com.cobbletowers.command;

import com.cobbletowers.trick.HauntedRare;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /cobbletowers rare}: one throw at the Haunted rare of a treat (P58). The chat line's [Throw] button runs it. */
public final class RareCommand {

    private RareCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers").then(Commands.literal("rare").executes(context -> {
            var player = context.getSource().getPlayerOrException();
            String line = HauntedRare.throwBy(context.getSource().getServer(), player);
            if (!line.isEmpty()) player.sendSystemMessage(Component.literal(line));
            return 1;
        })));
    }
}
