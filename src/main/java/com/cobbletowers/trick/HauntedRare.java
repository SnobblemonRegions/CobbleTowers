package com.cobbletowers.trick;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.TowerRuns;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Haunted rare (P58): a treat that holds a rare Pokemon lets every player throw at it, {@value CatchRules#THROWS} throws each, by clicking the
 * chat line or running {@code /cobbletowers rare}. A catch goes straight to the player's PC (the run party is never touched). The state is in memory
 * only and is forgotten with the intermission; a restart costs the rare, never anything the party owned.
 */
public final class HauntedRare {

    private static final class Rare {
        final int floor;
        final String species;
        final int level;
        final Map<UUID, Integer> thrown = new HashMap<>();
        final Set<UUID> caught = new HashSet<>();

        Rare(int floor, String species, int level) {
            this.floor = floor;
            this.species = species;
            this.level = level;
        }
    }

    private static final Map<UUID, Rare> BY_RUN = new HashMap<>();

    private HauntedRare() {}

    /** A treat of this floor was opened: if the seed put a rare in it, tells every online player and opens the throws. */
    public static void offer(MinecraftServer server, PersistedRun run, int floor) {
        if (!CatchRules.treatHasRare(run.seed(), floor) || BY_RUN.containsKey(run.runId())) return;
        Rare rare = new Rare(floor, CatchRules.species(run.seed(), floor), CatchRules.level(floor));
        BY_RUN.put(run.runId(), rare);
        TowerLog.info("Run {} floor {}: a Haunted {} (level {}) hides in the treat", run.runId(), floor, rare.species, rare.level);
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            player.sendSystemMessage(Component.literal("A Haunted " + pretty(rare.species) + " (Lv " + rare.level + ") hides among the candy! You have "
                    + CatchRules.THROWS + " throws. ").withStyle(ChatFormatting.GOLD).append(throwButton()));
        }
    }

    private static Component throwButton() {
        return Component.literal("[Throw]").withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobbletowers rare")));
    }

    /** One throw by this player. Returns the line to show them. */
    public static String throwBy(MinecraftServer server, ServerPlayer player) {
        Optional<PersistedRun> mine = TowerRuns.forPlayer(player.getUUID());
        if (mine.isEmpty()) return "You are not in a run.";
        PersistedRun run = mine.get();
        Rare rare = BY_RUN.get(run.runId());
        if (rare == null) return "There is no Haunted rare here.";
        UUID id = player.getUUID();
        if (rare.caught.contains(id)) return "You already caught it.";
        int used = rare.thrown.getOrDefault(id, 0);
        if (used >= CatchRules.THROWS) return "You are out of throws.";
        int number = used + 1;
        rare.thrown.put(id, number);
        int chance = CatchRules.chancePercent(number, rare.floor);
        boolean landed = CatchRules.lands(chance, run.seed(), rare.floor, id.getLeastSignificantBits(), number);
        if (!landed) {
            int left = CatchRules.THROWS - number;
            if (left > 0) {
                player.sendSystemMessage(Component.literal("It slipped away! (" + chance + "%) " + left + " throw" + (left == 1 ? "" : "s") + " left. ")
                        .withStyle(ChatFormatting.GRAY).append(throwButton()));
                return "";
            }
            return "It slipped away for good.";
        }
        if (!grant(player, rare)) {
            rare.thrown.put(id, used);   // nothing was taken: the throw is given back
            return "Your PC could not take it; clear a box and throw again.";
        }
        rare.caught.add(id);
        TowerLog.info("Run {}: {} caught the Haunted {} on throw {}", run.runId(), player.getGameProfile().getName(), rare.species, number);
        return "Caught! The Haunted " + pretty(rare.species) + " went to your PC.";
    }

    private static boolean grant(ServerPlayer player, Rare rare) {
        try {
            Pokemon pokemon = PokemonProperties.Companion.parse("cobblemon:" + rare.species + " level=" + rare.level, " ", "=").create();
            return Cobblemon.INSTANCE.getStorage().getPC(player).add(pokemon);
        } catch (RuntimeException ex) {
            TowerLog.error("Could not grant the Haunted {} to {}", rare.species, player.getGameProfile().getName(), ex);
            return false;
        }
    }

    private static String pretty(String species) {
        return species.substring(0, 1).toUpperCase() + species.substring(1);
    }

    public static void forget(UUID runId) {
        BY_RUN.remove(runId);
    }

    public static void clear() {
        BY_RUN.clear();
    }
}
