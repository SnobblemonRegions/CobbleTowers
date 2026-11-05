package com.cobbletowers.command;

import com.cobbletowers.api.registry.TowerSummary;
import com.cobbletowers.definition.RegionalThemeDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.runtime.RunTransitions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code /cobbletowers definitions} and {@code transitions}: what the server loaded and the state machine it runs.
 * Read-only; the way to see a datapack problem in game.
 */
public final class DefinitionsCommand {

    private DefinitionsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbletowers")
                // Gated here, not on the root: Brigadier keeps a merged literal's first requirement.
                .then(Commands.literal("definitions")
                        .requires(source -> source.hasPermission(2)).executes(DefinitionsCommand::definitions))
                .then(Commands.literal("transitions").executes(DefinitionsCommand::transitions)));
    }

    private static int definitions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TowerContent content = TowerDefinitionRegistry.content();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d tower(s), %d definition file(s) loaded", content.towers().size(), content.definitionCount()))
                .withStyle(ChatFormatting.GOLD), false);
        // Per kind, not just a total. A total says 23 files loaded and leaves an operator who has
        // just written a modifiers/ folder no way to tell whether theirs was one of them.
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d floor(s), %d ruleset(s), %d milestone(s), %d boss pool(s),"
                        + " %d modifier(s), %d regional theme(s), %d vendor service(s), %d scouting profile(s)",
                content.floors().size(), content.rulesets().size(),
                content.milestones().size(), content.bossPools().size(), content.modifiers().size(),
                content.regionalThemes().size(), content.vendorServices().size(),
                content.scoutingProfiles().size())), false);

        for (ResourceLocation towerId : content.sortedTowerIds()) {
            TowerSummary summary = content.summary(towerId).orElseThrow();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %s  \"%s\"  rev %d  %s  %d floor(s), milestones %s",
                    summary.id(), summary.displayName(), summary.revision(),
                    // The first eight hex characters are enough to see that content changed.
                    summary.contentDigest().isEmpty() ? "no digest" : summary.contentDigest().substring(0, 8),
                    summary.floorCount(), summary.milestoneFloors())), false);
            // Not on TowerSummary itself: a themed tower is the exception, not a fact every reader of
            // the public view needs, so this looks the theme up directly (P10).
            TowerDefinition tower = content.towers().get(towerId);
            Optional<RegionalThemeDefinition> theme = tower.regionalTheme().flatMap(content::regionalTheme);
            theme.ifPresent(t -> source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "    regional theme %s: \"%s\", doctrine %s, %d jersey species",
                    t.id(), t.displayName(), t.doctrine(), t.jerseySpeciesIds().size())), false));
        }

        if (content.problems().isEmpty()) {
            source.sendSuccess(() -> Component.literal("  every reference resolves")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("  " + content.problems().size() + " problem(s):")
                    .withStyle(ChatFormatting.RED), false);
            for (String problem : content.problems()) {
                source.sendSuccess(() -> Component.literal("    " + problem).withStyle(ChatFormatting.RED), false);
            }
        }
        return content.towers().size();
    }

    private static int transitions(CommandContext<CommandSourceStack> context) {
        for (String line : RunTransitions.describe()) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return RunTransitions.describe().size();
    }
}
