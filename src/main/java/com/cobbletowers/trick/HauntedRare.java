package com.cobbletowers.trick;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbletowers.TowerLog;
import com.cobbletowers.network.ClientUi;
import com.cobbletowers.network.HauntedRareTrayPayload;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerRareCatchStore;
import com.cobbletowers.trial.TrialClock;
import com.cobbletowers.trial.TrialService;
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
 * vault, the chat line or running {@code /cobbletowers rare}. The web page shows a click-through tray with the chance and the throws. A catch goes
 * straight to the player's PC (the run party is never touched). The state is in memory only and is forgotten with the intermission; a restart costs
 * the rare, never anything the party owned.
 */
public final class HauntedRare {

    private static final class Rare {
        final MinecraftServer server;
        final int floor;
        final String species;
        final int level;
        final Set<UUID> members = new HashSet<>();
        final Map<UUID, StringBuilder> marks = new HashMap<>();
        final Set<UUID> caught = new HashSet<>();

        Rare(MinecraftServer server, int floor, String species, int level) {
            this.server = server;
            this.floor = floor;
            this.species = species;
            this.level = level;
        }

        int used(UUID id) {
            StringBuilder m = marks.get(id);
            return m == null ? 0 : m.length();
        }
    }

    private static final Map<UUID, Rare> BY_RUN = new HashMap<>();

    private static int caughtToday(MinecraftServer server, UUID player) {
        return TowerRareCatchStore.get(server).catchesOn(player, TrialClock.dayKey(TrialService.today()));
    }

    private static final String LIMIT_LINE = "You have already caught " + CatchRules.DAILY_CATCH_LIMIT + " Haunted rares today. Come back tomorrow.";

    private HauntedRare() {}

    /** Whether a rare is out for this run (a click on the vault then throws). */
    public static boolean active(UUID runId) {
        return BY_RUN.containsKey(runId);
    }

    /** A treat of this floor was opened: if the seed put a rare in it, tells every online player and opens the throws. */
    public static void offer(MinecraftServer server, PersistedRun run, int floor) {
        if (!CatchRules.treatHasRare(run.seed(), floor) || BY_RUN.containsKey(run.runId())) return;
        Rare rare = new Rare(server, floor, CatchRules.species(run.seed(), floor), CatchRules.level(floor));
        BY_RUN.put(run.runId(), rare);
        TowerLog.info("Run {} floor {}: a Haunted {} (level {}) hides in the treat", run.runId(), floor, rare.species, rare.level);
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            rare.members.add(player.getUUID());
            if (!CatchRules.mayCatch(caughtToday(server, player.getUUID()))) {
                player.sendSystemMessage(Component.literal("A Haunted " + pretty(rare.species) + " hides among the candy. " + LIMIT_LINE).withStyle(ChatFormatting.GRAY));
                tray(player, rare, LIMIT_LINE);
                continue;
            }
            player.sendSystemMessage(Component.literal("A Haunted " + pretty(rare.species) + " (Lv " + rare.level + ") hides among the candy! You have "
                    + CatchRules.THROWS + " throws: right-click the vault or ").withStyle(ChatFormatting.GOLD).append(throwButton()));
            tray(player, rare, "A Haunted " + pretty(rare.species) + " hides in the candy. Right-click the vault to throw.");
        }
    }

    private static Component throwButton() {
        return Component.literal("[Throw]").withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobbletowers rare")));
    }

    /** The marks letters for a player, padded to the throw count: {@code h} landed, {@code m} missed, {@code -} to come. */
    static String marksOf(StringBuilder thrown) {
        StringBuilder out = new StringBuilder(thrown == null ? "" : thrown);
        while (out.length() < CatchRules.THROWS) out.append('-');
        return out.toString();
    }

    private static void tray(ServerPlayer player, Rare rare, String message) {
        UUID id = player.getUUID();
        int next = Math.min(rare.used(id) + 1, CatchRules.THROWS);
        ClientUi.send(player, new HauntedRareTrayPayload(true, pretty(rare.species), rare.level, CatchRules.chancePercent(next, rare.floor),
                marksOf(rare.marks.get(id)), message));
    }

    /** One throw by this player. Returns the line to show them (also put on the tray). */
    public static String throwBy(MinecraftServer server, ServerPlayer player) {
        String line = throwUnlogged(player);
        TowerLog.info("Haunted rare: {} threw: {}", player.getGameProfile().getName(), line);
        return line;
    }

    private static String throwUnlogged(ServerPlayer player) {
        Optional<PersistedRun> mine = TowerRuns.forPlayer(player.getUUID());
        if (mine.isEmpty()) return "You are not in a run.";
        PersistedRun run = mine.get();
        Rare rare = BY_RUN.get(run.runId());
        if (rare == null) return "There is no Haunted rare here.";
        UUID id = player.getUUID();
        if (rare.caught.contains(id)) return "You already caught it.";
        if (!CatchRules.mayCatch(caughtToday(player.server, id))) return LIMIT_LINE;
        int used = rare.used(id);
        if (used >= CatchRules.THROWS) return "You are out of throws.";
        int number = used + 1;
        int chance = CatchRules.chancePercent(number, rare.floor);
        boolean landed = CatchRules.lands(chance, run.seed(), rare.floor, id.getLeastSignificantBits(), number);
        if (landed && !grant(player, rare)) {
            String line = "Your PC could not take it; clear a box and throw again.";
            tray(player, rare, line);
            return line;   // nothing was taken: the throw is not used
        }
        rare.marks.computeIfAbsent(id, k -> new StringBuilder()).append(landed ? 'h' : 'm');
        String line;
        if (landed) {
            rare.caught.add(id);
            TowerRareCatchStore.get(player.server).record(id, TrialClock.dayKey(TrialService.today()));
            TowerLog.info("Run {}: {} caught the Haunted {} on throw {}", run.runId(), player.getGameProfile().getName(), rare.species, number);
            line = "Caught! The Haunted " + pretty(rare.species) + " went to your PC.";
        } else if (number < CatchRules.THROWS) {
            int left = CatchRules.THROWS - number;
            line = "It slipped away! (" + chance + "%) " + left + " throw" + (left == 1 ? "" : "s") + " left.";
        } else {
            line = "It slipped away for good.";
        }
        tray(player, rare, line);
        return line;
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

    /** The intermission is over: the tray comes down for everyone it was shown to. */
    public static void forget(UUID runId) {
        Rare rare = BY_RUN.remove(runId);
        if (rare == null) return;
        for (UUID member : rare.members) {
            ServerPlayer player = rare.server.getPlayerList().getPlayer(member);
            if (player != null) ClientUi.send(player, HauntedRareTrayPayload.closed());
        }
    }

    public static void clear() {
        BY_RUN.clear();
    }
}
