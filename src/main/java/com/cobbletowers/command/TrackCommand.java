package com.cobbletowers.command;

import com.cobbletowers.network.TrackActionPayload;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.track.TrackService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Operator test seam for the battle tracks (P37): {@code /cobbletowers trackadmin claim|claimall <player> ...} runs
 * the same code as the Progress tab's claim buttons, since a live test has no client to press them. {@code wallet}
 * reads a player's CobbleDollars.
 */
public final class TrackCommand {

    private TrackCommand() {}

    public static void registerAdmin(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers")
                .then(Commands.literal("trackadmin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("claim")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("lane", StringArgumentType.word())
                                                .then(Commands.argument("tower", ResourceLocationArgument.id())
                                                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                                                .executes(context -> run(context, "claim")))))))
                        .then(Commands.literal("wallet")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            long balance = TowerWalletStore.get(context.getSource().getServer())
                                                    .balanceOf(player.getUUID());
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Tower Tokens: " + balance), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("claimall")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("lane", StringArgumentType.word())
                                                .then(Commands.argument("tower", ResourceLocationArgument.id())
                                                        .executes(context -> run(context, "claim_all"))))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, String action) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String lane = StringArgumentType.getString(context, "lane");
        String tower = ResourceLocationArgument.getId(context, "tower").toString();
        int number = action.equals("claim") ? IntegerArgumentType.getInteger(context, "number") : 0;
        String message = TrackService.apply(source.getServer(), player,
                new TrackActionPayload(action, lane, tower, number));
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
