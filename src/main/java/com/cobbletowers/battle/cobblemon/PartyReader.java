package com.cobbletowers.battle.cobblemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** Reads a player's live Cobblemon party into the plain values the run rules work with. */
public final class PartyReader {

    private PartyReader() {}

    /** The party as the web Hall draws it: display name, sprite name, level, fainted, types. Party order. */
    public static List<com.cobbletowers.network.TowerHallStatePayload.Mon> cards(ServerPlayer player) {
        List<com.cobbletowers.network.TowerHallStatePayload.Mon> cards = new ArrayList<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) cards.add(card(pokemon));
        return cards;
    }

    /**
     * What a lobby shows of the Pokemon a player is bringing: the ones they registered (party or box), else the first
     * {@code limit} of the party.
     */
    public static List<com.cobbletowers.network.TowerHallStatePayload.Mon> registeredCards(ServerPlayer player, List<java.util.UUID> chosen,
                                                                                         int limit) {
        List<com.cobbletowers.network.TowerHallStatePayload.Mon> cards = new ArrayList<>();
        if (chosen.isEmpty()) {
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                if (cards.size() >= limit) break;
                cards.add(card(pokemon));
            }
            return cards;
        }
        java.util.Map<java.util.UUID, Pokemon> owned = new java.util.HashMap<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) owned.put(pokemon.getUuid(), pokemon);
        for (com.cobblemon.mod.common.api.storage.pc.PCBox box : Cobblemon.INSTANCE.getStorage().getPC(player).getBoxes()) {
            for (Pokemon pokemon : box.getNonEmptySlots().values()) owned.put(pokemon.getUuid(), pokemon);
        }
        for (java.util.UUID id : chosen) {
            Pokemon pokemon = owned.get(id);
            if (pokemon != null && cards.size() < limit) cards.add(card(pokemon));
        }
        return cards;
    }

    private static com.cobbletowers.network.TowerHallStatePayload.Mon card(Pokemon pokemon) {
        com.cobblemon.mod.common.pokemon.Species species = pokemon.getSpecies();
        List<String> types = new ArrayList<>();
        for (com.cobblemon.mod.common.api.types.ElementalType type : species.getTypes()) types.add(type.getName());
        return new com.cobbletowers.network.TowerHallStatePayload.Mon(pokemon.getDisplayName(false).getString(),
                com.cobbletowers.menu.HallExtras.spriteName(species.getResourceIdentifier().getPath()), pokemon.getLevel(), pokemon.isFainted(),
                com.cobbletowers.menu.HallExtras.hpPercent(pokemon.getCurrentHealth(), pokemon.getMaxHealth()), types);
    }

    /** The party's health as the Gilded Merchant's counter shows it (P59): HP percent, fainted, status. Party order. */
    public static List<com.cobbletowers.network.VendorCatalogPayload.Mon> health(ServerPlayer player) {
        List<com.cobbletowers.network.VendorCatalogPayload.Mon> mons = new ArrayList<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            var container = pokemon.getStatus();
            String status = container == null ? "" : container.getStatus().getShowdownName();
            mons.add(new com.cobbletowers.network.VendorCatalogPayload.Mon(pokemon.getDisplayName(false).getString(),
                    pokemon.isFainted() ? 0 : com.cobbletowers.menu.HallExtras.hpPercent(pokemon.getCurrentHealth(), pokemon.getMaxHealth()),
                    pokemon.isFainted(), status == null ? "" : status));
        }
        return mons;
    }

    /** Party order, fainted members included: whether they matter is the ruleset's call. */
    public static List<PartyMember> members(ServerPlayer player) {
        List<PartyMember> members = new ArrayList<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            com.cobblemon.mod.common.pokemon.Species species = pokemon.getSpecies();
            List<String> types = new ArrayList<>();
            for (com.cobblemon.mod.common.api.types.ElementalType type : species.getTypes()) types.add(type.getName());
            // Labels are Cobblemon's own (legendary, mythical, ultra_beast, paradox...); fully evolved means nothing
            // further to evolve into.
            members.add(new PartyMember(pokemon.getUuid(), pokemon.getLevel(), pokemon.isFainted(), species.getName(),
                    types, species.getEvolutions().isEmpty(), java.util.Set.copyOf(species.getLabels())));
        }
        return members;
    }
}
