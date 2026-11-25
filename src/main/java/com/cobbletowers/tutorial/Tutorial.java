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

    private record Config(ResourceLocation tower, ResourceLocation playlist, List<ResourceLocation> team, List<Kit> kit) {}

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
            found = new Config(ResourceLocation.parse(o.get("tower").getAsString()), ResourceLocation.parse(o.get("playlist").getAsString()),
                    List.copyOf(team), List.copyOf(kit));
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
