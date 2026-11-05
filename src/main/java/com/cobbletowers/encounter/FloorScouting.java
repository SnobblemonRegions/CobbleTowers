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
    /** The ordinal the floor boss's decoy is drawn with, apart from every opponent's. */
    private static final int BOSS_ORDINAL = 99;

    private FloorScouting() {}

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
}
