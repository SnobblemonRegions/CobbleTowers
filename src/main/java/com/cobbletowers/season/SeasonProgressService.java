package com.cobbletowers.season;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.SeasonDefinition;
import com.cobbletowers.definition.SeasonTrackDefinition;
import com.cobbletowers.definition.SeasonTrackRegistry;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.economy.CobbleDollars;
import com.cobbletowers.economy.RaidPointsCurrency;
import com.cobbletowers.persistence.PendingTowerReward;
import com.cobbletowers.persistence.TowerPendingRewardStore;
import com.cobbletowers.persistence.TowerSeasonProgressStore;
import com.cobbletowers.reward.RewardDelivery;
import com.cobbletowers.season.SeasonPoints.Progress;
import com.cobbletowers.season.SeasonPoints.Result;
import com.cobbletowers.season.SeasonPoints.Source;
import com.cobbletowers.trial.TrialClock;
import com.cobbletowers.trial.TrialService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Season points and the free track at run time (P36b): awards points and grants each step as it is reached through
 * the pending-reward store. Rules are in {@link SeasonPoints} and {@link SeasonTrackDefinition}. Idempotent: a step
 * is granted only when the stored step count is below the step reached.
 */
public final class SeasonProgressService {

    private SeasonProgressService() {}

    /**
     * A regional tower is one with a regional theme; Neutral and the Test tower never earn season points for a clear.
     */
    public static boolean regional(ResourceLocation towerId) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(towerId);
        return tower != null && tower.regionalTheme().isPresent();
    }

    // ---- earning ----------------------------------------------------------------------------------

    /**
     * A regional cycle clear by these players (spotlight if it was the season's featured region). Ignored for any
     * other tower.
     */
    public static void regionalCleared(MinecraftServer server, ResourceLocation tower, List<UUID> players) {
        if (!regional(tower)) return;
        boolean spotlight = Seasons.activeNumber().map(Seasons::definition).flatMap(SeasonDefinition::spotlight)
                .map(tower::equals).orElse(false);
        for (UUID player : players) award(server, player, Source.REGIONAL_CLEAR, 0, spotlight);
    }

    /**
     * Awards one source to a player; returns the points added (0 when no season is running, or a cap or an earlier
     * count applies).
     */
    public static int award(MinecraftServer server, UUID player, Source source, int param, boolean spotlight) {
        Optional<Integer> season = Seasons.activeNumber();
        if (season.isEmpty()) return 0;
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        Progress before = store.of(player, season.get());
        Result result = SeasonPoints.award(before, source, param, spotlight,
                TrialClock.dayKey(TrialService.today()), TrialClock.weekKey(TrialService.today()));
        if (result.next() == before) return 0;
        settle(server, player, season.get(), result.next());
        if (result.granted() > 0) {
            TowerLog.info("{} earned {} season point(s) from {} (season {}: {} total)", player, result.granted(), source,
                    season.get(), result.next().total());
        }
        return result.granted();
    }

    /** An operator's grant: adds points outside every cap (a live test has no other way to earn a track's worth). */
    public static int addPoints(MinecraftServer server, UUID player, int points) {
        Optional<Integer> season = Seasons.activeNumber();
        if (season.isEmpty() || points < 1) return 0;
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        Progress before = store.of(player, season.get());
        Progress next = new Progress(before.total() + points, before.steps(), before.day(), before.dayTotal(), before.dayCounts(),
                before.once());
        settle(server, player, season.get(), next);
        return points;
    }

    /** Saves the new tally and grants every track step it has reached that has not been granted. */
    private static void settle(MinecraftServer server, UUID player, int season, Progress next) {
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        Progress saved = next;
        countProgress(server, store, player, season, next, track);
        if (track.isPresent() && !com.cobbletowers.track.TrackConfig.current().autoClaim()) {
            // Claimable (P37): reaching a step only records the points; the player claims the prize from the track.
            int before = track.get().stepsFor(store.of(player, season).total());
            int reached = track.get().stepsFor(next.total());
            store.put(player, season, next);
            store.checkpoint(server);
            if (reached > before) {
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (online != null) {
                    online.sendSystemMessage(Component.literal("Season " + season + " track: step " + reached + "/" + track.get().stepCount()
                            + " reached. Claim it from the Progress tab."));
                }
            }
            return;
        }
        if (track.isPresent()) {
            int reached = track.get().stepsFor(next.total());
            if (reached > next.steps()) {
                // Queue first (flushed, and deduplicated by grant, so a replay adds nothing twice), then record the
                // steps, then tell and deliver.
                // A crash after queueing and before the step count is saved replays the grant harmlessly; the other
                // order lost a prize.
                List<Runnable> afterwards = new ArrayList<>();
                for (int step = next.steps() + 1; step <= reached; step++) {
                    // A step already claimed by hand (auto_claim was off earlier) is not granted again.
                    if (store.claimed(player, claimKey(season, step))) continue;
                    afterwards.add(grantStep(server, player, season, track.get(), step, true));
                }
                saved = next.withSteps(reached);
                store.put(player, season, saved);
                store.checkpoint(server);
                afterwards.forEach(Runnable::run);
                return;
            }
        }
        store.put(player, season, saved);
        store.checkpoint(server);
    }

    /** Tallies the points added (by season week) and each track step newly reached, for the tuning report. */
    private static void countProgress(MinecraftServer server, TowerSeasonProgressStore store, UUID player, int season, Progress next,
                                      Optional<SeasonTrackDefinition> track) {
        int before = store.of(player, season).total();
        int added = next.total() - before;
        if (added <= 0) return;
        Seasons.phase().filter(phase -> phase instanceof SeasonSchedule.Active).map(phase -> ((SeasonSchedule.Active) phase).week())
                .ifPresent(week -> com.cobbletowers.mastery.TuningCounters.add(server, "season_points.s" + season + "w" + week, added));
        if (track.isEmpty()) return;
        for (int step = track.get().stepsFor(before) + 1; step <= track.get().stepsFor(next.total()); step++) {
            com.cobbletowers.mastery.TuningCounters.bump(server, "reached_season.s" + season + ":" + step);
        }
    }

    /**
     * Queues a step's rewards and cosmetics and returns what is left to do once the step is recorded: the message and
     * the delivery.
     */
    private static Runnable grantStep(MinecraftServer server, UUID player, int season, SeasonTrackDefinition track, int number,
                                      boolean flush) {
        SeasonTrackDefinition.Step step = track.steps().get(number - 1);
        TowerPendingRewardStore pending = TowerPendingRewardStore.get(server);
        long now = System.currentTimeMillis();
        UUID grantId = UUID.nameUUIDFromBytes(("season:" + season + ":" + number + ":" + player).getBytes(StandardCharsets.UTF_8));
        List<String> given = new ArrayList<>();
        java.util.Map<String, String> tokens = tokensOf(season);
        int index = 0;
        for (SeasonTrackDefinition.Grant grant : step.grants()) {
            ResourceLocation item = ResourceLocation.tryParse(Cosmetics.expand(grant.item(), tokens));
            if (item == null || !grantable(item)) {
                TowerLog.warn("Season {} track step {}: {} is not an item on this server, so it is skipped.", season, number, grant.item());
                continue;
            }
            String components = Cosmetics.expand(grant.components(), tokens);
            String label = Cosmetics.expand(grant.label(), tokens);
            // One id per grant, not per step: the queue deduplicates on (id, item, components), so two grants of the
            // same item in a step
            // (an addon adding diamonds to a step that already has diamonds) would otherwise collapse into the first
            // one.
            UUID grantOf = index == 0 ? grantId : UUID.nameUUIDFromBytes(("season:" + season + ":" + number + ":" + player + ":" + index)
                    .getBytes(StandardCharsets.UTF_8));
            index++;
            pending.addIfAbsent(player, new PendingTowerReward(grantOf, 1, item, grant.amount(), now, components, label));
            given.add(label.isEmpty() ? describe(item, grant.amount()) : label);
        }
        com.cobbletowers.mastery.TuningCounters.bump(server, "granted_season.s" + season + ":" + number);
        Set<String> cosmetics = new HashSet<>();
        for (String name : step.cosmetics()) cosmetics.add("s" + season + ":" + name);
        if (flush) pending.checkpoint(server);
        // The cosmetics go through the one door (P36d): recorded, the first title worn, the earn commands run, the
        // tab list refreshed.
        CosmeticsService.award(server, player, cosmetics);
        TowerLog.info("{} reached season {} track step {}: {}{}", player, season, number, given,
                cosmetics.isEmpty() ? "" : " and cosmetics " + cosmetics);

        return () -> {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online == null) return;
            String reward = given.isEmpty() && cosmetics.isEmpty() ? "" : ": " + describeStep(step, season);
            online.sendSystemMessage(Component.literal("Season " + season + " track, step " + number + "/" + track.stepCount()
                    + (com.cobbletowers.track.TrackConfig.current().autoClaim() ? " reached" : " claimed") + reward));
            if (!given.isEmpty()) RewardDelivery.deliver(server, online);
        };
    }

    // ---- claiming (P37) ---------------------------------------------------------------------------

    public static String claimKey(int season, int step) {
        return "s" + season + ":" + step;
    }

    /**
     * Whether a step has been claimed: recorded as a claim, or granted before claiming existed (a step within {@code
     * Progress.steps}).
     */
    public static boolean claimed(TowerSeasonProgressStore store, UUID player, int season, int step) {
        return step <= store.of(player, season).steps() || store.claimed(player, claimKey(season, step));
    }

    /**
     * How many reached steps of the season in view are still unclaimed. Steps stay claimable through the off-season
     * and lapse when the next season starts.
     */
    public static int unclaimed(MinecraftServer server, UUID player) {
        Optional<Integer> season = Seasons.viewNumber();
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        if (season.isEmpty() || track.isEmpty()) return 0;
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        int reached = track.get().stepsFor(store.of(player, season.get()).total());
        int open = 0;
        for (int step = 1; step <= reached; step++) if (!claimed(store, player, season.get(), step)) open++;
        return open;
    }

    /** Why a claim is refused, or empty when {@code step} of the season in view can be claimed now. */
    public static Optional<String> refusal(MinecraftServer server, UUID player, int step) {
        Optional<Integer> season = Seasons.viewNumber();
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        if (season.isEmpty() || track.isEmpty()) return Optional.of("There is no season track running.");
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        if (step < 1 || step > track.get().stepCount()) return Optional.of("There is no step " + step + ".");
        if (track.get().stepsFor(store.of(player, season.get()).total()) < step) return Optional.of("Step " + step + " is not reached yet.");
        if (claimed(store, player, season.get(), step)) return Optional.of("Step " + step + " is already claimed.");
        return Optional.empty();
    }

    /**
     * Claims one reached step: grants are queued first (deduplicated by a deterministic grant id), then the claim is
     * recorded, then the player is told.
     * @return the refusal, or empty when claimed
     */
    public static Optional<String> claim(MinecraftServer server, UUID player, int step) {
        Optional<String> refused = refusal(server, player, step);
        if (refused.isPresent()) return refused;
        int season = Seasons.viewNumber().orElseThrow();
        SeasonTrackDefinition track = SeasonTrackRegistry.current().orElseThrow();
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        Runnable afterwards = grantStep(server, player, season, track, step, true);
        store.markClaimed(player, claimKey(season, step));
        store.checkpoint(server);
        afterwards.run();
        return Optional.empty();
    }

    /** Claims every reached, unclaimed step in order; returns how many. */
    public static int claimAll(MinecraftServer server, UUID player) {
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        Optional<Integer> season = Seasons.viewNumber();
        if (track.isEmpty() || season.isEmpty()) return 0;
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        List<Runnable> afterwards = new ArrayList<>();
        for (int step = 1; step <= track.get().stepCount(); step++) {
            if (refusal(server, player, step).isPresent()) continue;
            afterwards.add(grantStep(server, player, season.get(), track.get(), step, false));
            store.markClaimed(player, claimKey(season.get(), step));
        }
        if (afterwards.isEmpty()) return 0;
        // One save for the batch: the pending grants first, then the claim records.
        TowerPendingRewardStore.get(server).checkpoint(server);
        store.checkpoint(server);
        afterwards.forEach(Runnable::run);
        return afterwards.size();
    }

    /**
     * The tokens a track grant may use: {@code {season}}, {@code {season_name}} and {@code {color}} (the spotlight
     * region's dye).
     */
    static java.util.Map<String, String> tokensOf(int season) {
        SeasonDefinition definition = Seasons.definition(season);
        String color = definition.spotlight().map(region -> switch (region.getPath()) {
            case "tideforge" -> "blue";
            case "rootvale" -> "green";
            case "duskvale" -> "purple";
            default -> "white";
        }).orElse("white");
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("season", String.valueOf(season));
        tokens.put("season_name", definition.name());
        tokens.put("color", color);
        return tokens;
    }

    /** A currency the delivery credits, or an item this server has. */
    public static boolean grantable(ResourceLocation id) {
        return id.equals(CobbleDollars.ITEM_ID) || id.equals(RaidPointsCurrency.ITEM_ID) || BuiltInRegistries.ITEM.containsKey(id)
                || com.cobbletowers.economy.AscensionMaterials.of(id).isPresent();
    }

    // ---- words ------------------------------------------------------------------------------------

    public static String describe(ResourceLocation item, int amount) {
        if (item.equals(CobbleDollars.ITEM_ID)) return amount + " Tower Tokens";
        if (item.equals(RaidPointsCurrency.ITEM_ID)) return amount + " Raid Points";
        String name = item.getPath().replace('_', ' ');
        return (amount > 1 ? amount + " " : "") + name;
    }

    /** What a step gives, in a phrase: its items and its cosmetics. */
    public static String describeStep(SeasonTrackDefinition.Step step, int season) {
        List<String> parts = new ArrayList<>();
        for (SeasonTrackDefinition.Grant grant : step.grants()) {
            java.util.Map<String, String> tokens = tokensOf(season);
            ResourceLocation item = ResourceLocation.tryParse(Cosmetics.expand(grant.item(), tokens));
            String label = Cosmetics.expand(grant.label(), tokens);
            parts.add(!label.isEmpty() ? label : item == null ? grant.item() : describe(item, grant.amount()));
        }
        for (String cosmetic : step.cosmetics()) parts.add(cosmetic.replace('_', ' '));
        return String.join(", ", parts);
    }

    /** What {@code /tower season track} says. */
    public static List<String> trackLines(MinecraftServer server, UUID player) {
        Optional<Integer> season = Seasons.activeNumber();
        if (season.isEmpty()) return List.of(Seasons.enabled() ? "There is no season track right now: no season is running."
                : "Seasons are switched off on this server.");
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        if (track.isEmpty()) return List.of("This server has no season track.");
        TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
        Progress progress = store.of(player, season.get());
        int reached = track.get().stepsFor(progress.total());
        List<String> lines = new ArrayList<>();
        lines.add("Season " + season.get() + ": " + Seasons.definition(season.get()).name() + " track - step " + reached + "/"
                + track.get().stepCount() + ", " + progress.total() + "/" + track.get().totalPoints() + " points"
                + (progress.dayTotal() > 0 && TrialClock.dayKey(TrialService.today()).equals(progress.day())
                        ? " (" + progress.dayTotal() + "/" + SeasonPoints.DAILY_CAP + " earned today)" : ""));
        if (reached >= track.get().stepCount()) {
            lines.add("The whole track is done.");
        } else {
            for (int next = reached + 1; next <= Math.min(reached + 3, track.get().stepCount()); next++) {
                int away = next * track.get().stepCost() - progress.total();
                lines.add("  step " + next + " in " + away + " point(s): " + describeStep(track.get().steps().get(next - 1), season.get()));
            }
        }
        Set<String> earned = store.cosmeticsOf(player);
        if (!earned.isEmpty()) lines.add("Earned: " + earned.stream().map(name -> name.replace('_', ' ')).collect(Collectors.joining(", ")));
        lines.add("Points come from regional cycle clears (more in the spotlight region), trials, contracts, streak milestones, Echo "
                + "Duels and your club's weekly reward.");
        return lines;
    }

    /** One line for the login summary: where the track stands, or empty when there is nothing to say. */
    public static Optional<String> loginLine(MinecraftServer server, UUID player) {
        Optional<Integer> season = Seasons.activeNumber();
        Optional<SeasonTrackDefinition> track = SeasonTrackRegistry.current();
        if (track.isEmpty()) return Optional.empty();
        int waiting = unclaimed(server, player);
        if (season.isEmpty()) {
            // Off-season: the ended season's unclaimed steps are the only thing worth saying, and they lapse when the
            // next season starts.
            return waiting == 0 || Seasons.viewNumber().isEmpty() ? Optional.empty()
                    : Optional.of("Season " + Seasons.viewNumber().get() + " ended: " + waiting + " track reward(s) to claim before the next season begins "
                            + "(Progress tab in the Tower Hall).");
        }
        if (waiting > 0) {
            return Optional.of("Season " + season.get() + " track: " + waiting + " reward(s) ready to claim (Progress tab in the Tower Hall).");
        }
        Progress progress = TowerSeasonProgressStore.get(server).of(player, season.get());
        int reached = track.get().stepsFor(progress.total());
        if (reached >= track.get().stepCount()) return Optional.of("Season track: complete.");
        int away = (reached + 1) * track.get().stepCost() - progress.total();
        return Optional.of("Season " + season.get() + " track: step " + reached + "/" + track.get().stepCount() + ", " + away
                + " point(s) to " + describeStep(track.get().steps().get(reached), season.get()) + ".");
    }
}
