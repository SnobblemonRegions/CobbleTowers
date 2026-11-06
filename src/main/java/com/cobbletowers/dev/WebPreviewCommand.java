package com.cobbletowers.dev;

import com.cobbletowers.definition.RentalSetRegistry;
import com.cobbletowers.network.ClientUi;
import com.cobbletowers.network.IntermissionStatePayload;
import com.cobbletowers.network.RentalDraftPayload;
import com.cobbletowers.network.RewardRevealPayload;
import com.cobbletowers.network.VendorCatalogPayload;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalDraw;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /cobbletowers web preview <screen>}: shows one web screen to the operator with sample data, so the page can be looked at
 * without playing a run (P44). Nothing is created on the server: a click on a preview is refused by the real handlers.
 */
public final class WebPreviewCommand {

    private static final List<String> SCREENS = List.of("intermission", "reward", "vendor", "draft", "spectator", "tray");

    private WebPreviewCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers")
                .then(Commands.literal("web")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("preview")
                                .then(Commands.argument("screen", StringArgumentType.word())
                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(SCREENS, b))
                                        .executes(WebPreviewCommand::preview)))));
    }

    private static int preview(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String screen = StringArgumentType.getString(context, "screen");
        CustomPacketPayload payload = switch (screen) {
            case "intermission" -> intermission(player);
            case "reward" -> new RewardRevealPayload(4, List.of(
                    new RewardRevealPayload.Grant(ResourceLocation.fromNamespaceAndPath("cobblemon", "super_potion"), 2, "Super Potion"),
                    new RewardRevealPayload.Grant(ResourceLocation.fromNamespaceAndPath("cobblemon", "oran_berry"), 3, "")));
            case "vendor" -> new VendorCatalogPayload(180, List.of(
                    new VendorCatalogPayload.Entry(ResourceLocation.fromNamespaceAndPath("cobbletowers", "cure_status"), "Cure Status", 10, -1),
                    new VendorCatalogPayload.Entry(ResourceLocation.fromNamespaceAndPath("cobbletowers", "full_heal"), "Full Heal", 25, 2)),
                    List.of(new VendorCatalogPayload.Teammate(player.getUUID(), player.getGameProfile().getName(), true)), "");
            case "draft" -> RentalSetRegistry.all().isEmpty() ? null
                    : RentalDraftPayload.of(new RentalDraft(RentalDraw.draw(RentalSetRegistry.all(), player.getRandom().nextLong(), true)),
                            "", com.cobbletowers.battle.cobblemon.CobblemonMoves::resolve);
            case "spectator" -> new com.cobbletowers.network.SpectatorPanelPayload("Ash", 4, 6, 7, "In battle");
            case "tray" -> new com.cobbletowers.network.HauntedRareTrayPayload(true, "Mimikyu", 42, 35, "hm-", "The vault shakes: it missed!");
            default -> null;
        };
        if (payload == null) {
            source.sendFailure(Component.literal("Screens: " + String.join(", ", SCREENS)));
            return 0;
        }
        if (!ClientUi.canShow(player, payload.type())) {
            source.sendFailure(Component.literal("The web page cannot show that to you (is WebGUI installed and enabled?)"));
            return 0;
        }
        ClientUi.send(player, payload);
        source.sendSuccess(() -> Component.literal("Previewing " + screen).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static CustomPacketPayload intermission(ServerPlayer player) {
        var cards = List.of(
                new IntermissionStatePayload.Card(ResourceLocation.fromNamespaceAndPath("cobbletowers", "far_sight"), "Far Sight", 1, 0,
                        "scouting", List.of("[+] Minor risk  *  +3% to your final payout", "[+] Your scouts see 3 floors further ahead.")),
                new IntermissionStatePayload.Card(ResourceLocation.fromNamespaceAndPath("cobbletowers", "empty_pockets"), "Empty Pockets", 0, 1,
                        "constraint:no_items", List.of("[+] Moderate risk  *  +8% to your final payout", "[-] No items in battle: trust your team.")),
                new IntermissionStatePayload.Card(ResourceLocation.fromNamespaceAndPath("cobbletowers", "downpour"), "Downpour", 0, 1,
                        "weather:raindance", List.of("[+] Moderate risk  *  +8% to your final payout", "Battle weather: rain.")));
        return new IntermissionStatePayload(4, new IntermissionStatePayload.Draft(1, cards, -1, 0),
                List.of(new IntermissionStatePayload.Member(player.getGameProfile().getName(), false, false)), 60, "Floor 4 cleared", true);
    }
}
