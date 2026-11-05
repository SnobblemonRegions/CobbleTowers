package com.cobbletowers.encounter;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.FloorLayout;
import com.cobbletowers.definition.RegionalThemeDefinition;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.RulesetResolver;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.persistence.PersistedRun;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * A run's current floor with everything needed to fight on it, resolved together from loaded content and the world.
 * Content is looked up afresh each time so a datapack reload cannot leave it stale.
 */
record FloorSetup(PersistedRun run, TowerContent content, TowerDefinition tower, FloorDefinition floor,
                  FloorLayout layout, RulesetDefinition ruleset,
                  Optional<RegionalThemeDefinition> theme, ServerLevel level, BlockPos origin) {

    /** Empty when the floor cannot be fought on; {@code report} logs why (the floor start does, later calls do not). */
    static Optional<FloorSetup> resolve(MinecraftServer server, PersistedRun run, boolean report) {
        TowerContent content = TowerDefinitionRegistry.content();
        Optional<FloorDefinition> floor = content.floorAt(run.towerId(), run.floorIndex());
        TowerDefinition tower = content.towers().get(run.towerId());
        if (floor.isEmpty() || tower == null) {
            if (report) {
                TowerLog.error("Run {} is on {} floor {}, which is not loaded",
                        run.runId(), run.towerId(), run.floorIndex());
            }
            return Optional.empty();
        }
        RulesetDefinition ruleset = RulesetResolver.forRun(content, run, floor.get().rulesetOverride());
        if (ruleset == null) {
            if (report) {
                TowerLog.error("Floor {} names content that is not loaded (ruleset {})",
                        floor.get().id(), tower.rulesetId());
            }
            return Optional.empty();
        }
        ServerLevel level = TowerDimension.level(server);
        Optional<FloorLayout> chosen = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, run.floorIndex());
        if (level == null || run.cell().isEmpty() || chosen.isEmpty()) {
            if (report) TowerLog.error("Run {} has no built floor to fight on", run.runId());
            return Optional.empty();
        }
        FloorLayout layout = chosen.get();
        // Asked of the preparer, never recomputed: a second calculation put the entry anchor in the void.
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout);
        if (origin.isEmpty()) {
            if (report) {
                TowerLog.error("Floor {} names structure {}, which is not loaded; cannot place anybody",
                        floor.get().id(), layout.structure());
            }
            return Optional.empty();
        }
        Optional<RegionalThemeDefinition> theme = tower.regionalTheme().flatMap(content::regionalTheme);
        return Optional.of(new FloorSetup(run, content, tower, floor.get(), layout, ruleset, theme, level,
                origin.get()));
    }

    /** Where the {@code ordinal}th opponent is shown, spread out so four do not stand inside one another. */
    BlockPos presentation(int ordinal) {
        return layout.presentation().in(origin).offset(ordinal * 4, 0, 0);
    }
}
