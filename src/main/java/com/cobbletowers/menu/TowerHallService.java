package com.cobbletowers.menu;

import com.cobbletowers.battle.cobblemon.PartyStorage;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.TrialPoolDefinition.Kind;
import com.cobbletowers.intermission.IntermissionService;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.network.TowerHallActionPayload;
import com.cobbletowers.network.TowerHallStatePayload;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.trial.TrialSchedule;
import com.cobbletowers.trial.TrialService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Presentation adapter only. Opening/browsing never creates or changes a lobby. */
public final class TowerHallService {
    private TowerHallService() {}

    public static void open(MinecraftServer server, ServerPlayer player, String message) {
        open(server, player, message, null);
    }

    /** The Hall, opened on {@code focus} when it is given (a hub host's dialog or a tower's portal); otherwise as usual. */
    public static void open(MinecraftServer server, ServerPlayer player, String message, ResourceLocation focus) {
        if (PartyStorage.inBattle(player)) {
            player.sendSystemMessage(Component.literal("Finish your battle before opening Tower Hall."));
            return;
        }
        if (!com.cobbletowers.network.ClientUi.canShow(player, TowerHallStatePayload.TYPE)) {
            if (IntermissionService.isAtIntermission(player)) IntermissionService.openScreen(server, player, message);
            else LobbyService.openScreenWithMessage(server, player, message);
            return;
        }
        var towers = TowerDefinitionRegistry.content().openCatalogTowers(System.currentTimeMillis()).stream()
                .sorted(Comparator.comparing(t -> t.displayName()))
                .limit(256)
                .map(t -> new TowerHallStatePayload.Destination(t.id(), t.displayName(), t.floorIds().size(),
                        t.milestoneIds().size(), t.ascension(), t.window().map(w -> w.endMillis()).orElse(0L))).toList();
        List<TowerHallStatePayload.Trial> trials = new ArrayList<>();
        for (Kind kind : Kind.values()) TrialService.current(kind).ifPresent(t -> {
            List<String> rules = new ArrayList<>();
            rules.add(t.floors() + " floors / " + t.periodKey());
            rules.add("Mode: " + t.entry().playlist().flatMap(PlaylistRegistry::get)
                    .map(p -> p.displayName()).orElse("Standard"));
            rules.add(t.entry().enemyLevel() > 0 ? "Enemy level: " + t.entry().enemyLevel() : "Enemy level follows tower rules");
            rules.add("Starting modifiers: " + (t.entry().modifiers().isEmpty() ? "None" :
                    String.join(", ", t.entry().modifiers().stream().map(id -> modifierName(id)).toList())));
            rules.addAll(com.cobbletowers.trial.TrialParts.lines(t, id -> modifierName(id)));
            rules.add("First launch is scored; later attempts are practice. Team eligibility is checked in the lobby.");
            trials.add(new TowerHallStatePayload.Trial(kind.name().toLowerCase(Locale.ROOT), revision(t),
                    t.title(), t.entry().tower(), rules));
        });
        var play = LobbyService.playState(server, player, LobbyService.lobbyOf(player.getUUID()).orElse(null), message, false);
        String status = TowerRuns.forPlayer(player.getUUID()).filter(r -> !r.isRetired())
                .map(r -> "Floor " + r.floorIndex() + " / " + r.state().name().replace('_', ' ')).orElse("");
        com.cobbletowers.network.ClientUi.send(player, new TowerHallStatePayload(towers, trials, play, status,
                IntermissionService.isAtIntermission(player), focus == null ? safeExtras(server, player)
                        : com.cobbletowers.menu.HallExtras.withFocus(safeExtras(server, player), focus)));
    }

    /** The extras are decoration: whatever goes wrong reading them, the Hall still opens, without them. */
    private static TowerHallStatePayload.Extras safeExtras(MinecraftServer server, ServerPlayer player) {
        try {
            return extras(server, player);
        } catch (RuntimeException ex) {
            com.cobbletowers.TowerLog.warn("Hall extras for {} could not be built: {}", player.getGameProfile().getName(), ex.toString());
            return TowerHallStatePayload.Extras.NONE;
        }
    }

    /** The team, the run's tower and the player's mastery standing per tower, for the Frontier Desk Hall (P51). */
    private static TowerHallStatePayload.Extras extras(MinecraftServer server, ServerPlayer player) {
        var store = com.cobbletowers.persistence.TowerMasteryStore.get(server);
        List<TowerHallStatePayload.Standing> standings = new ArrayList<>();
        for (ResourceLocation tower : TowerDefinitionRegistry.content().sortedTowerIds()) {
            var progress = store.progressOf(player.getUUID(), tower);
            int level = progress.level();
            standings.add(HallExtras.standing(tower, level, progress.ascensionReached(),
                    com.cobbletowers.mastery.MasteryPerks.rankOf(tower, level),
                    com.cobbletowers.mastery.MasteryPerks.nextRankAt(tower, level)));
        }
        String runTower = TowerRuns.forPlayer(player.getUUID()).filter(r -> !r.isRetired())
                .map(r -> r.towerId().toString()).orElse("");
        var base = HallExtras.of(runTower, com.cobbletowers.battle.cobblemon.PartyReader.cards(player), standings);
        return withLobbyMode(player, base);
    }

    /** If the player's lobby has a mode with party clauses (Level Cap, Monotype...), says whether the team fits it. */
    private static TowerHallStatePayload.Extras withLobbyMode(ServerPlayer player, TowerHallStatePayload.Extras base) {
        var lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isEmpty()) return base;
        var playlist = lobby.get().playlist().flatMap(PlaylistRegistry::get);
        if (playlist.isEmpty()) return base;
        var picked = lobby.get().chosenOf(player.getUUID());
        List<com.cobbletowers.runtime.PartyValidation.PartyMember> members;
        if (picked.isEmpty()) {
            members = com.cobbletowers.battle.cobblemon.PartyReader.members(player);
        } else {
            var snapshot = PartyStorage.snapshot(player);
            members = new ArrayList<>();
            for (UUID id : picked) snapshot.member(id).ifPresent(members::add);
        }
        return HallExtras.withMode(base, playlist.get().displayName(),
                HallExtras.modeProblems(members, LobbyService.registerLimit(lobby.get()), playlist.get().party()));
    }

    static String revision(TrialSchedule.Instance trial) {
        // Includes loaded rules, not just the period: reloads invalidate an old confirmation too.
        return UUID.nameUUIDFromBytes(trial.toString()
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static void handle(MinecraftServer server, ServerPlayer player, TowerHallActionPayload request) {
        if (PartyStorage.inBattle(player)) {
            player.sendSystemMessage(Component.literal("Finish your battle before using Tower Hall."));
            return;
        }
        switch (request.action()) {
            case "refresh" -> open(server, player, "");
            case "lobby" -> LobbyService.openScreen(server, player);
            case "resume" -> {
                if (IntermissionService.isAtIntermission(player)) IntermissionService.openScreen(server, player, "");
                else open(server, player, "The run has moved on. Return at the next intermission.");
            }
            case "tower" -> {
                ResourceLocation id = ResourceLocation.tryParse(request.argument());
                if (id == null || !TowerDefinitionRegistry.content().towers().containsKey(id)) {
                    open(server, player, "That tower is no longer available.");
                    return;
                }
                String reply = LobbyService.select(server, player, id);
                LobbyService.openScreenWithMessage(server, player, reply);
            }
            case "trial" -> {
                Kind kind = switch (request.argument()) { case "daily" -> Kind.DAILY; case "weekly" -> Kind.WEEKLY; default -> null; };
                if (kind == null) return;
                var trial = TrialService.current(kind);
                if (trial.isEmpty() || !revision(trial.get()).equals(request.revision())) {
                    open(server, player, "This trial changed. Review the new briefing before selecting it.");
                    return;
                }
                String reply = LobbyService.selectTrial(server, player, kind);
                LobbyService.openScreenWithMessage(server, player, reply);
            }
            default -> { /* Unknown actions are never interpreted as commands. */ }
        }
    }

    /** A modifier's display name, or its id when it is not loaded. */
    private static String modifierName(net.minecraft.resources.ResourceLocation id) {
        return TowerDefinitionRegistry.content().modifier(id).map(m -> m.displayName()).orElse(id.toString());
    }
}
