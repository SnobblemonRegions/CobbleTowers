package com.cobbletowers.modifier;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.menu.ModifierMenuText;
import com.cobbletowers.network.ClientUi;
import com.cobbletowers.network.RelicRevealPayload;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shows the party each relic it gains (P59): a card on the bridge's HUD with the relic's art and what it does, or the same words in chat for a
 * player without the bridge. Fed by {@link com.cobbletowers.runtime.TowerRuns#save}, which sees every relic arrive whatever gave it (a room, a
 * milestone, a draft, the gambler, a treat), so no source has to remember to announce.
 */
public final class RelicReveal {

    private RelicReveal() {}

    /** The relics in {@code after} that {@code before} did not hold, in the order they were gained. A run that did not exist yet gained nothing. */
    public static List<ResourceLocation> gained(PersistedRun before, PersistedRun after) {
        if (before == null || after == null) return List.of();
        Set<ResourceLocation> had = Set.copyOf(before.modifiers().relics());
        List<ResourceLocation> gained = new ArrayList<>();
        for (ResourceLocation relic : after.modifiers().relics()) if (!had.contains(relic)) gained.add(relic);
        return gained;
    }

    /** The card's colour family, from the relic's first tag. */
    static String kindOf(ModifierDefinition relic) {
        for (String tag : relic.tags()) {
            switch (tag) {
                case "offense": return "battle";
                case "sustain": return "sustain";
                case "tempo": return "tempo";
                case "economy": return "fortune";
                case "scouting": return "scouting";
                case "boss": return "champion";
                default: break;
            }
        }
        return "battle";
    }

    public static void announce(MinecraftServer server, PersistedRun run, List<ResourceLocation> gained) {
        if (server == null || gained.isEmpty()) return;
        var modifiers = TowerDefinitionRegistry.content().modifiers();
        int held = run.modifiers().relics().size();
        for (ResourceLocation id : gained) {
            ModifierDefinition relic = modifiers.get(id);
            if (relic == null) continue;
            RelicRevealPayload payload = new RelicRevealPayload(id, relic.displayName(), kindOf(relic), List.copyOf(ModifierMenuText.lines(relic)),
                    run.floorIndex() + 1, held);
            TowerLog.info("Run {}: relic {} gained, shown to the party", run.runId(), id);
            for (PersistedParticipant participant : run.participants()) {
                if (!participant.state().isInRun()) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player == null) continue;
                if (ClientUi.canShow(player, RelicRevealPayload.TYPE)) ClientUi.send(player, payload);
                else tellInChat(player, payload);
            }
        }
    }

    private static void tellInChat(ServerPlayer player, RelicRevealPayload relic) {
        player.sendSystemMessage(Component.literal("Relic gained: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(relic.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        for (String line : relic.lines()) {
            ChatFormatting colour = line.startsWith(ModifierMenuText.GOOD) ? ChatFormatting.GREEN
                    : line.startsWith(ModifierMenuText.BAD) ? ChatFormatting.RED : ChatFormatting.GRAY;
            player.sendSystemMessage(Component.literal("  " + ModifierMenuText.plain(line)).withStyle(colour));
        }
    }
}
