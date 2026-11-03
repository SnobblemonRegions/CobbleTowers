package com.cobbletowers.encounter;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobbletowers.battle.cobbleraids.RaidSpecies;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.RegionalThemeDefinition;
import com.cobbletowers.definition.ScoutingProfileDefinition;
import com.cobbletowers.economy.AscensionLibScouting;
import com.cobbletowers.economy.ScoutingTiers;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.network.ScoutingRevealPayload;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.persistence.PersistedRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Scouting for a floor: the AscensionLib encounters players can spend a Scouter on, and the reveal and title sent. */
final class FloorScouting {

    /** Each player's current opponent encounter, keyed {@code run/player}; ended with that battle. */
    private static final Map<String, String> OPPONENT_SCOUTING = new LinkedHashMap<>();

    /** The ordinal the floor boss's decoy is drawn with, apart from every opponent's. */
    private static final int BOSS_ORDINAL = 99;

    private FloorScouting() {}

    /** Lets players scout this opponent; the id is unique per opponent and arms the battle. */
    static String declareOpponent(FloorSetup setup, UUID runId, UUID playerId, int floorIndex, EncounterSnapshot snapshot) {
        String id = runId + "-f" + floorIndex + "-o" + snapshot.ordinal();
        String previous = OPPONENT_SCOUTING.put(runId + "/" + playerId, id);
        if (previous != null && !previous.equals(id)) AscensionLibScouting.end(previous);
        AscensionLibScouting.declare(id, List.of(playerId), 0, tierOf(setup.run(), floorIndex, false, false), false,
                ScoutingDisguise.shown(setup, snapshot.ordinal(), snapshot.species()).toString(), snapshot.level());
        return id;
    }

    static void endOpponent(UUID runId, UUID playerId) {
        String id = OPPONENT_SCOUTING.remove(runId + "/" + playerId);
        if (id != null) AscensionLibScouting.end(id);
    }

    static void endRun(UUID runId) {
        String prefix = runId + "/";
        for (String key : List.copyOf(OPPONENT_SCOUTING.keySet())) {
            if (key.startsWith(prefix)) AscensionLibScouting.end(OPPONENT_SCOUTING.remove(key));
        }
    }

    static void clear() {
        OPPONENT_SCOUTING.clear();
    }

    /** Lets the party scout the floor's boss; a boss with no species cannot be scouted. */
    static void declareBoss(MinecraftServer server, FloorSetup setup, BossDraw.Boss boss,
                            List<ServerPlayer> standing, UUID encounterId) {
        Optional<String> species = RaidSpecies.of(server, boss.definition());
        if (species.isEmpty()) return;
        boolean milestone = setup.content().milestoneAt(setup.run().towerId(), setup.run().floorIndex()).isPresent();
        boolean champion = setup.tower().contentFloor(setup.run().floorIndex()) == setup.tower().floorCount();
        AscensionLibScouting.declare(encounterId.toString(), standing.stream().map(ServerPlayer::getUUID).toList(), 0,
                tierOf(setup.run(), setup.run().floorIndex(), milestone, champion), true,
                ScoutingDisguise.shown(setup, BOSS_ORDINAL, ResourceLocation.parse(species.get())).toString(), boss.level());
    }

    /** The enemy tier for a fight of this run: its Threat band when it picked one and the library has Threat on, else the floor tiers. */
    private static String tierOf(PersistedRun run, int floorIndex, boolean milestone, boolean champion) {
        return ScoutingTiers.tierFor(run.options().entryThreat(), com.cobbletowers.modifier.DraftService.ascensionOf(run), floorIndex, milestone,
                champion, AscensionLibScouting.threatEnabled());
    }

    /** Sent alongside the encounter; never gates it. */
    static void sendReveal(ServerPlayer player, FloorSetup setup, ModifierEffects effects,
                           EncounterSnapshot snapshot) {
        PersistedRun run = setup.run();
        Optional<ScoutingProfileDefinition> profile = setup.content().scoutingProfileFor(setup.tower().id());
        if (profile.isEmpty()) return;
        List<ScoutingRevealPayload.Category> revealed = new ArrayList<>();
        for (ScoutingProfileDefinition.RevealCategory category : profile.get().categories()) {
            if (!ScoutingReveal.isRevealed(category, run.floorIndex(), effects.scoutingBonus())) continue;
            revealed.add(new ScoutingRevealPayload.Category(category.name(),
                    category.name().equals("typing") && ScoutingDisguise.disguised(setup, snapshot.ordinal(), snapshot.species())
                            ? "???" : valueOf(category.name(), setup.floor(), snapshot)));
        }
        TowerNetworking.sendScoutingReveal(player, new ScoutingRevealPayload(run.floorIndex(), List.copyOf(revealed)));
    }

    /** Display text for a scouting category. An unknown category is shown revealed without a value. */
    private static String valueOf(String category, FloorDefinition floor, EncounterSnapshot snapshot) {
        return switch (category) {
            case "typing" -> typingOf(snapshot.species());
            case "threat_level" -> "Level " + snapshot.level();
            case "field_conditions" -> floor.modifierIds().isEmpty() ? "none"
                    : floor.modifierIds().stream().map(ResourceLocation::getPath).collect(Collectors.joining(", "));
            default -> "revealed";
        };
    }

    private static String typingOf(ResourceLocation species) {
        Species resolved = PokemonSpecies.INSTANCE.getByIdentifier(species);
        if (resolved == null) return "unknown";
        StringBuilder types = new StringBuilder(resolved.getPrimaryType().getName());
        if (resolved.getSecondaryType() != null) types.append('/').append(resolved.getSecondaryType().getName());
        return types.toString();
    }

    /** Plain vanilla title packets, so no client mod is needed. */
    static void announceJersey(ServerPlayer player, RegionalThemeDefinition theme, EncounterSnapshot snapshot) {
        if (snapshot.jerseyNumber().isEmpty()) return;
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(theme.displayName() + " -- " + theme.doctrine())));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("#" + snapshot.jerseyNumber().getAsInt() + " " + snapshot.species().getPath())));
    }
}
