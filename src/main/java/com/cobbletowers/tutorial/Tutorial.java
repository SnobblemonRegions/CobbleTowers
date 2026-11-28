package com.cobbletowers.tutorial;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.definition.RentalSetRegistry;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.persistence.TowerAscensionStore;
import com.cobbletowers.persistence.TowerFreeRunStore;
import com.cobbletowers.persistence.TowerSeasonProgressStore;
import com.cobbletowers.persistence.TowerTutorialStore;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalSpread;
import com.cobbletowers.runtime.TowerRuns;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Tutorial Tower (owner, 25 Nov 2026): a brand-new player's first walk into a tower's portal takes them here instead, a short tower
 * on a lent team; a host in the hub replays it. Its tower, playlist, lent team and one-time starter kit are in
 * {@code data/cobbletowers/cobbletowers/hub/tutorial.json}. See {@code docs/design/tutorial-tower.md}. Server thread only.
 */
public final class Tutorial {

    private record Config(ResourceLocation tower, ResourceLocation playlist, List<ResourceLocation> team, List<Kit> kit,
                          Map<String, Long> materials) {}

    /** One stack of the starter kit. */
    public record Kit(ResourceLocation item, int count) {}

    private static volatile Config config;

    private Tutorial() {}

    private static Config config() {
        Config found = config;
        if (found != null) return found;
        try (InputStream in = Tutorial.class.getResourceAsStream("/data/cobbletowers/cobbletowers/hub/tutorial.json")) {
            if (in == null) throw new IllegalStateException("The Tutorial Tower's hub/tutorial.json is missing from the jar");
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<ResourceLocation> team = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("team")) team.add(ResourceLocation.parse(e.getAsString()));
            List<Kit> kit = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("kit")) {
                JsonObject k = e.getAsJsonObject();
                kit.add(new Kit(ResourceLocation.parse(k.get("item").getAsString()), Math.max(1, k.get("count").getAsInt())));
            }
            Map<String, Long> materials = new java.util.LinkedHashMap<>();       // AscensionLib materials, paid as vouchers
            if (o.has("materials")) {
                for (var e : o.getAsJsonObject("materials").entrySet()) materials.put(e.getKey(), Math.max(1L, e.getValue().getAsLong()));
            }
            found = new Config(ResourceLocation.parse(o.get("tower").getAsString()), ResourceLocation.parse(o.get("playlist").getAsString()),
                    List.copyOf(team), List.copyOf(kit), Map.copyOf(materials));
        } catch (java.io.IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not read the Tutorial Tower's hub/tutorial.json", ex);
        }
        config = found;
        return found;
    }

    /** The tutorial's tower. */
    public static ResourceLocation tower() {
        return config().tower();
    }

    public static boolean isTutorial(ResourceLocation tower) {
        return tower != null && tower.equals(config().tower());
    }

    // ---- the coach: a few lines at each step of the run (owner, 25 Nov 2026), chat and action bar, never a screen ----------------------

    private static final int GOLD = 0xF2C744, INK = 0xE8EAF2;

    /** The boss each floor fields and the lent Pokemon that answers it: the tutorial teaches the type matchup as it goes. */
    private static final String[][] FLOORS = {
            {"Emboar", "Fire", "Blastoise", "Water"},
            {"Decidueye", "Grass and Ghost", "Charizard", "Fire"},
            {"Quaquaval", "Water", "Venusaur", "Grass"}};

    /** A tutorial run moved from one state to the next: the coach says what this step is, once, to everyone in it. */
    public static void coach(MinecraftServer server, com.cobbletowers.persistence.PersistedRun run,
                             com.cobbletowers.api.tower.RunState from, com.cobbletowers.api.tower.RunState to) {
        if (!isTutorial(run.towerId()) || from == to) return;
        List<String> lines = new ArrayList<>();
        String bar = "";
        int floor = run.floorIndex();
        switch (to) {
            case PREPARING -> {
                lines.add("Tutorial: this is the preparation room. The stand sells supplies for the run (paid in Tower Tokens).");
                lines.add("When you are ready, step into the gold ring by the great door, then walk through it.");
                bar = "Step into the gold ring by the great door";
            }
            case ENCOUNTER_ACTIVE -> {
                if (floor >= 1 && floor <= FLOORS.length) {
                    String[] f = FLOORS[floor - 1];
                    lines.add("Floor " + floor + " of " + FLOORS.length + ": each floor is one boss battle. This boss is " + f[0] + " (" + f[1] + ").");
                    lines.add("Your " + f[2] + " (" + f[3] + ") is strong against it: send it out first. Fainted Pokemon come back between floors.");
                    bar = "Send out " + f[2] + " against " + f[0];
                }
            }
            case INTERMISSION -> {
                lines.add("Floor cleared! Between floors your party is healed. Ahead are doors, each with a pedestal showing what lies behind it.");
                lines.add("Walk up to a door to choose your way on. A relic pedestal gives a bonus that lasts the whole run.");
                bar = "Pick a door: walk up to its pedestal";
            }
            default -> { }
        }
        if (lines.isEmpty()) return;
        for (var participant : run.participants()) {
            if (!participant.state().isInRun()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            for (int i = 0; i < lines.size(); i++) player.sendSystemMessage(Component.literal(lines.get(i)).withColor(i == 0 ? GOLD : INK));
            if (!bar.isEmpty()) player.displayClientMessage(Component.literal(bar).withColor(GOLD), true);
        }
    }

    /** Whether a run is a Tutorial Tower run (false for a run no longer known). */
    public static boolean isTutorialRun(UUID runId) {
        return runId != null && TowerRuns.get(runId).map(run -> isTutorial(run.towerId())).orElse(false);
    }

    /**
     * A tutorial run was cleared: each player in it who had not cleared it before is paid the starter kit, once. The clear is written to
     * disk first, so a crash between the two can cost the kit but never pay it twice; the kit goes through the tower's own pending
     * rewards, so a full inventory or a logout keeps it until it can be handed over.
     */
    public static void onCleared(MinecraftServer server, UUID runId, long now) {
        var found = TowerRuns.get(runId);
        if (found.isEmpty()) return;
        var run = found.get();
        TowerTutorialStore tutorial = TowerTutorialStore.get(server);
        var pending = com.cobbletowers.persistence.TowerPendingRewardStore.get(server);
        List<UUID> paid = new ArrayList<>();
        for (var participant : run.participants()) {
            if (!participant.state().isInRun()) continue;
            if (tutorial.markCleared(participant.playerId())) paid.add(participant.playerId());
        }
        if (paid.isEmpty()) return;
        tutorial.checkpoint(server);
        for (UUID id : paid) {
            for (Kit k : kit()) pending.add(id, new com.cobbletowers.persistence.PendingTowerReward(runId, run.floorIndex(), k.item(), k.count(), now));
        }
        pending.checkpoint(server);
        // the AscensionLib materials (Scouters) as vouchers, keyed by player so they are paid once whatever happens
        if (!config().materials().isEmpty()) {
            for (UUID id : paid) {
                UUID operation = UUID.nameUUIDFromBytes(("cobbletowers:tutorial_kit:" + id).getBytes(StandardCharsets.UTF_8));
                String result = com.cobbletowers.economy.AscensionPrizes.grantVouchers(operation, id, config().materials());
                if (!"GRANTED".equals(result)) TowerLog.warn("The tutorial kit's materials {} for {} were not paid: {}", config().materials(), id, result);
            }
        }
        for (UUID id : paid) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            player.sendSystemMessage(Component.literal("Tutorial cleared! Your starter kit is yours. Every tower's host at its gate explains "
                    + "how to go in.").withColor(0xF2C744));
            com.cobbletowers.reward.RewardDelivery.deliver(server, player);
        }
    }

    /** Whether a playlist is the tutorial's own (never offered as a mode for another tower). */
    public static boolean isTutorialPlaylist(ResourceLocation playlist) {
        return playlist != null && playlist.equals(config().playlist());
    }

    /** The starter kit, as configured. */
    public static List<Kit> kit() {
        return config().kit();
    }

    /**
     * Whether a player walking into a tower's portal alone is taken into the tutorial instead: they have never been sent into it, and have
     * no sign of having played (an Ascension record, a season tally, or a free daily run used).
     */
    public static boolean firstTimer(MinecraftServer server, UUID player) {
        if (TowerTutorialStore.get(server).started(player)) return false;
        return !TowerAscensionStore.get(server).knows(player) && !TowerSeasonProgressStore.get(server).knows(player)
                && TowerFreeRunStore.get(server).lastFreeDay(player).isEmpty();
    }

    /**
     * Starts the tutorial for one player: a team of one on the tutorial's lent team, and the usual countdown. Returns what to tell them
     * when it cannot start (empty when it did).
     */
    public static Optional<String> start(MinecraftServer server, ServerPlayer player, boolean firstTime) {
        if (TowerRuns.forPlayer(player.getUUID()).filter(run -> !run.isRetired()).isPresent()) return Optional.of("You are already in a tower run.");
        if (LobbyService.lobbyOf(player.getUUID()).isPresent()) return Optional.of("You are in a team already: /tower leave it first.");
        if (lentTeam().isEmpty()) {
            TowerLog.warn("The Tutorial Tower's lent team names no loaded rental set");
            return Optional.of("The tutorial is not set up on this server.");
        }
        String said = LobbyService.startFromParty(server, player, tower(), List.of(player.getUUID()), 0, 0,
                Optional.of(config().playlist()), Map.of());
        if (!"Starting.".equals(said)) return Optional.of(said);
        TowerTutorialStore.get(server).markStarted(player.getUUID());
        if (firstTime) {
            player.sendSystemMessage(Component.literal("First time in a tower? A short Tutorial Tower comes first: three floors, on a lent team "
                    + "(Charizard, Blastoise and Venusaur), with a starter kit for clearing it.").withColor(0xF2C744));
        }
        return Optional.empty();
    }

    /** The lent team's sets, in order (those not loaded are left out). */
    private static List<RentalSetDefinition> lentTeam() {
        List<RentalSetDefinition> sets = new ArrayList<>();
        for (ResourceLocation id : config().team()) RentalSetRegistry.get(id).ifPresent(sets::add);
        return sets;
    }

    /** The lent team for one run: fresh ids, each Pokemon at its set's own tier and spread. Empty if no set of it is loaded. */
    public static Optional<RentalDraft.Team> team() {
        List<RentalSetDefinition> sets = lentTeam();
        if (sets.isEmpty()) return Optional.empty();
        List<UUID> ids = new ArrayList<>();
        List<Boolean> god = new ArrayList<>();
        List<RentalSetDefinition.Rarity> tiers = new ArrayList<>();
        List<RentalSpread> spreads = new ArrayList<>();
        for (RentalSetDefinition set : sets) {
            ids.add(UUID.randomUUID());
            god.add(false);
            tiers.add(set.rarity());
            spreads.add(RentalSpread.of(set));
        }
        return Optional.of(new RentalDraft.Team(sets, ids, god, tiers, spreads));
    }

    /** The starter kit's stacks (items not on the server are left out). */
    public static List<ItemStack> kitStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (Kit k : kit()) {
            Item item = BuiltInRegistries.ITEM.get(k.item());
            if (item != Items.AIR) out.add(new ItemStack(item, k.count()));
        }
        return out;
    }
}
