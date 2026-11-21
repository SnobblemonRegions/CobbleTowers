package com.cobbletowers.battle.cobblemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.rental.RentalSpread;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The Cobblemon side of the Rental Draft (P33): builds, parties, finds and deletes lent Pokemon. Every rental carries
 * a persistent tag, and only tagged Pokemon are ever deleted.
 */
public final class RentalStorage {

    /** Set on every rental, saved with the Pokemon, and never set on a player's own. */
    public static final String TAG = "cobbletowers_rental";

    /**
     * AscensionLib's contract for a lent Pokemon: a persistent boolean with this name locks every upgrade and craft,
     * so materials are not spent on a Pokemon deleted at run end.
     */
    public static final String ASCENSION_LOCK_TAG = "ascensionlib_craft_locked";

    private RentalStorage() {}

    public static boolean isRental(Pokemon pokemon) {
        return pokemon.getPersistentData().getBoolean(TAG);
    }

    /** Builds the Pokemon a set describes, with the id it will be known by. Not yet in anyone's storage. */
    public static Pokemon create(RentalSetDefinition set, UUID id) {
        return create(set, id, RentalSpread.of(set));
    }

    /** As above, with the IVs and EVs its card rolled. */
    public static Pokemon create(RentalSetDefinition set, UUID id, RentalSpread spread) {
        PokemonProperties properties = new PokemonProperties();
        properties.setSpecies(set.species());
        properties.setLevel(set.level());
        properties.setNature(set.nature());
        properties.setAbility(set.ability());
        properties.setMoves(set.moves());
        properties.setTradeable(false);
        set.item().ifPresent(item -> properties.setHeldItem(item.toString()));
        Pokemon pokemon = properties.create();
        pokemon.setUuid(id);

        Stats[] order = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
        for (int i = 0; i < order.length; i++) {
            pokemon.getIvs().set(order[i], spread.ivs().get(i));
            pokemon.getEvs().set(order[i], spread.evs().get(i));
        }
        pokemon.setOriginalTrainer("Battle Tower");
        pokemon.getPersistentData().putBoolean(TAG, true);
        pokemon.getPersistentData().putBoolean(ASCENSION_LOCK_TAG, true);
        pokemon.heal();
        return pokemon;
    }

    /** Puts these Pokemon in the first slots of an (emptied) party, in order. Returns how many landed. */
    public static int fillParty(ServerPlayer player, List<Pokemon> rentals) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        // A rental above the player's level cap (CobbleLvlCap) would sit out every battle, so it is lent at the cap.
        int cap = com.cobbletowers.rental.LevelCapCompat.capFor(player);
        int placed = 0;
        for (int slot = 0; slot < rentals.size() && slot < 6; slot++) {
            try {
                Pokemon rental = rentals.get(slot);
                if (rental.getLevel() > cap) {
                    rental.setLevel(com.cobbletowers.rental.LevelCapCompat.capped(rental.getLevel(), cap));
                    rental.heal();
                }
                party.set(new PartyPosition(slot), rental);
                placed++;
            } catch (RuntimeException ex) {
                TowerLog.error("Could not place rental {} for {}", rentals.get(slot).getUuid(), player.getUUID(), ex);
            }
        }
        return placed;
    }

    /** Cheap command-boundary check, including rentals whose journal could not be read. */
    public static boolean hasRentalInParty(ServerPlayer player) {
        return hasRentalInParty(player, List.of());
    }

    public static boolean hasRentalInParty(ServerPlayer player, java.util.Collection<UUID> recordedRentals) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = party.get(slot);
            if (pokemon != null && (isRental(pokemon) || recordedRentals.contains(pokemon.getUuid()))) return true;
        }
        return false;
    }

    /** Every tagged Pokemon the player holds, in the party or the boxes. */
    public static List<Pokemon> held(ServerPlayer player) {
        List<Pokemon> found = new ArrayList<>();
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = party.get(slot);
            if (pokemon != null && isRental(pokemon)) found.add(pokemon);
        }
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        for (PCBox box : pc.getBoxes()) {
            for (Pokemon pokemon : box.getNonEmptySlots().values()) {
                if (isRental(pokemon)) found.add(pokemon);
            }
        }
        return found;
    }

    /** The ids of every tagged Pokemon the player holds. */
    public static List<UUID> tagged(ServerPlayer player) {
        return held(player).stream().map(Pokemon::getUuid).toList();
    }

    /** Deletes every tagged rental Pokemon of the player whose id the predicate accepts. Returns how many. */
    public static int removeAll(ServerPlayer player, Predicate<UUID> which) {
        int removed = 0;
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        for (UUID id : tagged(player)) {
            if (!which.test(id)) continue;
            Pokemon pokemon = party.get(id);
            boolean fromParty = pokemon != null;
            if (pokemon == null) pokemon = pc.get(id);
            if (pokemon == null || !isRental(pokemon)) continue;
            boolean gone = fromParty ? party.remove(pokemon) : pc.remove(pokemon);
            if (gone) removed++;
        }
        return removed;
    }

    /**
     * What a rental is, for a test or a log: species, level, nature, ability, moves and held item as Cobblemon holds
     * them.
     */
    public static String describe(Pokemon pokemon) {
        List<String> moves = new ArrayList<>();
        for (var move : pokemon.getMoveSet().getMoves()) moves.add(move.getName());
        ItemStack held = pokemon.heldItem();
        ResourceLocation item = held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem());
        return pokemon.getSpecies().getName().toLowerCase(java.util.Locale.ROOT) + " level=" + pokemon.getLevel()
                + " nature=" + pokemon.getNature().getName().getPath() + " ability=" + pokemon.getAbility().getName()
                + " moves=" + String.join(",", moves) + (item == null ? "" : " held_item=" + item);
    }
}
