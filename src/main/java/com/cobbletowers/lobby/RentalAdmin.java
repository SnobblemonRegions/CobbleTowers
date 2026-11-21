package com.cobbletowers.lobby;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.battle.cobblemon.RentalStorage;
import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.definition.RentalSetRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Operator views and probes for the Rental Draft (P33), for a live test that cannot see a screen. */
public final class RentalAdmin {

    private RentalAdmin() {}

    /**
     * Every rental the player holds as Cobblemon has it: id, species, level, nature, ability, moves, item, tradeable.
     */
    public static List<String> describe(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        for (Pokemon pokemon : RentalStorage.held(player)) {
            lines.add("RENTAL " + pokemon.getUuid() + " " + RentalStorage.describe(pokemon)
                    + " evs=" + evs(pokemon) + " tradeable=" + pokemon.getTradeable());
        }
        lines.add(0, lines.size() + " rentals");
        return lines;
    }

    private static String evs(Pokemon pokemon) {
        StringBuilder out = new StringBuilder();
        for (var stat : com.cobblemon.mod.common.api.pokemon.stats.Stats.Companion.getPERMANENT()) {
            if (out.length() > 0) out.append(',');
            out.append(pokemon.getEvs().getOrDefault(stat));
        }
        return out.toString();
    }

    /**
     * Creates a rental with no run behind it and puts it in the player's party if there is room, else a box: a leak
     * to be swept.
     */
    /** Probe: lifts the player's rentals as a rental run would on this floor, so a test need not play to it. */
    public static List<String> scaleTo(ServerPlayer player, int floor) {
        int raised = com.cobbletowers.rental.RentalScaling.raise(player, floor);
        int level = com.cobbletowers.rental.LevelCapCompat.capped(com.cobbletowers.rental.RentalScaling.levelFor(floor),
                com.cobbletowers.rental.LevelCapCompat.capFor(player));
        return List.of(raised + " rentals rose to level " + level);
    }

    public static List<String> giveStray(ServerPlayer player) {
        List<RentalSetDefinition> all = RentalSetRegistry.all();
        if (all.isEmpty()) return List.of("no rental sets are loaded");
        Pokemon stray = RentalStorage.create(all.get(0), UUID.randomUUID());
        Cobblemon.INSTANCE.getStorage().getParty(player).add(stray);
        return List.of("gave a stray rental " + stray.getUuid());
    }

    /**
     * The species the player's Pokedex knows anything about, each with how much ({@code SEEN} or {@code OWNED}), to
     * show a rental run added none. Empty records Cobblemon creates are left out.
     */
    public static List<String> pokedex(ServerPlayer player) {
        var records = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player).getSpeciesRecords();
        List<String> species = new ArrayList<>();
        records.forEach((id, record) -> {
            var knowledge = record.getKnowledge();
            if (knowledge != com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress.UNREGISTERED) {
                species.add(id.getPath() + ":" + knowledge.name());
            }
        });
        java.util.Collections.sort(species);
        return List.of("DEX " + species.size() + " " + String.join(",", species));
    }

    /** Tries to give every rental a great deal of experience and reports each one's level before and after. */
    public static List<String> tryExperience(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        for (Pokemon pokemon : RentalStorage.held(player)) {
            int before = pokemon.getLevel();
            pokemon.addExperienceWithPlayer(player, new SidemodExperienceSource("cobbletowers"), 1_000_000);
            lines.add("XP " + pokemon.getUuid() + " " + before + " -> " + pokemon.getLevel());
        }
        return lines;
    }
}
