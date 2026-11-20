package com.cobbletowers.trial;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.FloorDefinition;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.RotatingRecipeDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.TrialPoolDefinition;
import com.cobbletowers.definition.TrialRecipeRegistry;
import com.cobbletowers.instance.CellWarmPool;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import com.cobbletowers.definition.TrialPoolDefinition.Kind;
import com.cobbletowers.definition.TrialPoolRegistry;
import com.cobbletowers.mastery.CycleResult;
import com.cobbletowers.mastery.LeaderboardRules;
import com.cobbletowers.mastery.LeaderboardRules.Board;
import com.cobbletowers.mastery.LeaderboardRules.Entry;
import com.cobbletowers.mastery.LeaderboardRules.Key;
import com.cobbletowers.mastery.LeaderboardRules.Member;
import com.cobbletowers.mastery.MasteryService;
import com.cobbletowers.mastery.MasteryView;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerLeaderboardStore;
import com.cobbletowers.persistence.TowerRunStatsStore;
import com.cobbletowers.persistence.TowerTrialStore;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.trial.TrialClock.Rhythm;
import com.cobbletowers.trial.TrialSchedule.Instance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The daily and weekly trials at run time (P32): which trial it is, who attempted it and what an attempt is worth. A
 * trial is an ordinary run with a fixed seed, playlist, mutators and enemy level. The first run a player starts is
 * the scored attempt; later ones are practice. {@link #onTransition} judges a trial run when it ends.
 */
public final class TrialService {

    /** Floors a scored trial attempt must clear before it earns season points. */
    private static final int MIN_FLOORS_FOR_POINTS = 3;

    private static volatile Rhythm RHYTHM = Rhythm.standard();
    private static volatile Optional<LocalDate> DAY_OVERRIDE = Optional.empty();

    private TrialService() {}

    public static void install() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RHYTHM = TrialConfig.load());
    }

    // ---- what day it is
    // ----------------------------------------------------------------------------------------------

    /**
     * The trial day: the real one, unless an operator pinned it (a test seam, since a test cannot wait for tomorrow).
     */
    public static LocalDate today() {
        return DAY_OVERRIDE.orElseGet(() -> TrialClock.dayOf(System.currentTimeMillis(), RHYTHM));
    }

    public static void overrideDay(Optional<LocalDate> day) {
        DAY_OVERRIDE = day;
    }

    public static long millisUntilReset() {
        return TrialClock.millisUntilReset(System.currentTimeMillis(), RHYTHM);
    }

    /** The trial of this kind today, if a pool of that kind is loaded. */
    public static Optional<Instance> current(Kind kind) {
        Optional<RotatingRecipeDefinition> recipe = TrialRecipeRegistry.ofKind(kind);
        if (recipe.isPresent()) {
            Instance rotating = TrialSchedule.of(recipe.get(), today());
            ResourceLocation tower = rotating.entry().tower();
            if (!TowerContent.isRotating(tower)) return Optional.of(rotating);
            if (TowerDefinitionRegistry.ensureRotating(tower)) {
                prewarm(rotating);
                return Optional.of(rotating);
            }
            TowerLog.warn("The {} rotating trial could not be built; using the hand-written pool instead.", kind);
        }
        return TrialPoolRegistry.ofKind(kind).map(pool -> TrialSchedule.of(pool, today()));
    }

    private static final Set<String> WARMED = ConcurrentHashMap.newKeySet();

    static {
        com.cobbletowers.ServerState.onStop(WARMED::clear);
    }

    /** Asks the cell pool to keep a ready cell for every structure the trial uses, once per trial, so a mixed route does not start cold. */
    private static void prewarm(Instance trial) {
        if (!WARMED.add(trial.id())) return;
        TowerContent content = TowerDefinitionRegistry.content();
        TowerDefinition tower = content.towers().get(trial.entry().tower());
        if (tower == null) return;
        for (ResourceLocation floorId : tower.floorIds()) {
            FloorDefinition floor = content.floors().get(floorId);
            if (floor != null) floor.layout().ifPresent(CellWarmPool::want);
        }
    }

    /** The streak threshold of today's daily: the recipe's, else the pool's. */
    static int dailyStreakMinFloors() {
        return TrialRecipeRegistry.ofKind(Kind.DAILY).map(RotatingRecipeDefinition::streakMinFloors)
                .or(() -> TrialPoolRegistry.ofKind(Kind.DAILY).map(TrialPoolDefinition::streakMinFloors)).orElse(3);
    }

    // ---- attempts
    // ----------------------------------------------------------------------------------------------------

    /** Whether a run of these players would be a scored attempt: none of them has used this trial's attempt. */
    public static boolean wouldBeScored(MinecraftServer server, List<UUID> players, String instanceId) {
        TowerTrialStore store = TowerTrialStore.get(server);
        for (UUID player : players) {
            if (store.attemptOf(player, instanceId).isPresent()) return false;
        }
        return true;
    }

    /** A scored run has been created: its players' attempt is spent, whatever happens next. */
    public static void recordLaunch(MinecraftServer server, PersistedRun run) {
        if (!run.options().isTrial() || !run.options().scored()) return;
        TowerTrialStore store = TowerTrialStore.get(server);
        for (PersistedParticipant participant : run.participants()) {
            store.recordLaunch(participant.playerId(), run.options().trial().orElseThrow(), run.runId());
        }
        store.checkpoint(server);
    }

    // ---- an attempt ends
    // ---------------------------------------------------------------------------------------------

    /**
     * The transition a run just made; a trial run reaching an end state is judged here, before mastery drops its
     * stats.
     */
    public static void onTransition(MinecraftServer server, UUID runId, RunState from, RunState to, long now) {
        try {
            if (!to.isTerminal()) return;
            Optional<PersistedRun> found = TowerRuns.get(runId);
            if (found.isEmpty() || !found.get().options().isTrial()) return;
            finish(server, found.get(), from, to, now);
        } catch (RuntimeException ex) {
            TowerLog.error("Judging the trial attempt of run {} failed", runId, ex);
        }
    }

    /** Floors a trial run cleared when it ended in state {@code to}, having been in {@code from}. */
    static int floorsCleared(PersistedRun run, RunState from, RunState to) {
        int floors = run.options().floorLimit();
        if (to == RunState.COMPLETED) return floors;
        // At an intermission or while resolving, the floor the run is on was cleared; mid-fight it was not.
        boolean onClearedFloor = from == RunState.INTERMISSION || from == RunState.FLOOR_RESOLVING;
        return Math.max(0, Math.min(floors, run.floorIndex() - (onClearedFloor ? 0 : 1)));
    }

    private static void finish(MinecraftServer server, PersistedRun run, RunState from, RunState to, long now) {
        String instanceId = run.options().trial().orElseThrow();
        TowerTrialStore store = TowerTrialStore.get(server);

        List<UUID> scored = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            Optional<TowerTrialStore.Attempt> attempt = store.attemptOf(participant.playerId(), instanceId);
            if (attempt.isPresent() && attempt.get().runId().equals(run.runId()) && !attempt.get().finished()) {
                scored.add(participant.playerId());
            }
        }
        int floors = run.options().floorLimit();
        int cleared = floorsCleared(run, from, to);
        CycleResult result = MasteryService.resultOf(server, run, now);
        int score = TrialScoring.score(cleared, floors, result.activeMillis(), faintsOf(server, run), result.score());

        // Contracts care that a trial was attempted and finished (P32c).
        com.cobbletowers.events.TowerEvents.emit(new com.cobbletowers.events.TowerEvent.TrialFinished(run.runId(),
                run.participants().stream().map(PersistedParticipant::playerId).toList(), instanceId,
                TrialSchedule.kindOf(instanceId).orElse(Kind.DAILY) == Kind.DAILY, !scored.isEmpty(), to == RunState.COMPLETED, cleared));
        if (scored.isEmpty()) {
            if (cleared == 0) return;   // a run that never got going (a failed launch) has nothing to report
            message(server, run, Component.literal("Practice run over: " + cleared + "/" + floors + " floors, would score " + score
                    + ". It does not post.").withStyle(ChatFormatting.GRAY));
            return;
        }

        Kind kind = TrialSchedule.kindOf(instanceId).orElse(Kind.DAILY);
        com.cobbletowers.mastery.TuningCounters.bump(server, "trial." + kind.name().toLowerCase(java.util.Locale.ROOT) + ".finished");
        if (to == RunState.COMPLETED) {
            com.cobbletowers.mastery.TuningCounters.bump(server, "trial." + kind.name().toLowerCase(java.util.Locale.ROOT) + ".completed");
        }
        List<Member> team = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) team.add(MasteryService.member(server, participant.playerId()));
        Key key = new Key(Board.TRIAL, TrialSchedule.boardIdOf(instanceId).orElseThrow(),
                LeaderboardRules.modeOf(result.startedSolo()), "");
        TowerLeaderboardStore boards = TowerLeaderboardStore.get(server);
        int rank = boards.offer(key, new Entry(team, score, run.runId(), 0, result.score(), run.rulesetRevision(),
                run.towerRevision(), run.towerDigest(), now));
        int total = boards.top(key, LeaderboardRules.CAPACITY).size();
        pruneOldBoards(boards);
        boards.checkpoint(server);

        for (UUID player : scored) store.recordResult(player, instanceId, run.runId(), score, cleared);
        if (to == RunState.COMPLETED) com.cobbletowers.region.RegionService.onTrialCleared(server, scored);
        // A scored attempt that got somewhere earns season points (P36b); one that cleared next to nothing does not.
        if (cleared >= MIN_FLOORS_FOR_POINTS) {
            com.cobbletowers.season.SeasonPoints.Source pointsSource = kind == Kind.DAILY
                    ? com.cobbletowers.season.SeasonPoints.Source.DAILY_TRIAL : com.cobbletowers.season.SeasonPoints.Source.WEEKLY_TRIAL;
            for (UUID player : scored) com.cobbletowers.season.SeasonProgressService.award(server, player, pointsSource, 0, false);
        }
        com.cobbletowers.mastery.RunSummaries.trialScore(run.runId(), score);

        // The daily streak (P32): the day qualifies when enough floors were cleared.
        int minFloors = dailyStreakMinFloors();
        Optional<LocalDate> day = TrialSchedule.dayOf(instanceId);
        List<String> streakLines = new ArrayList<>();
        if (kind == Kind.DAILY && day.isPresent() && cleared >= minFloors) {
            for (UUID player : scored) streakLines.addAll(advanceStreak(server, store, player, day.get().toEpochDay()));
        }
        store.checkpoint(server);

        Component line = Component.literal(titleOf(kind) + " complete: " + cleared + "/" + floors + " floors, score " + score
                + (rank > 0 ? ", rank #" + rank + " of " + total : ", just outside the top " + LeaderboardRules.CAPACITY))
                .withStyle(ChatFormatting.GOLD);
        message(server, run, line);
        for (String streakLine : streakLines) message(server, run, Component.literal(streakLine).withStyle(ChatFormatting.AQUA));
        if (!streakLines.isEmpty()) com.cobbletowers.mastery.RunSummaries.streakLine(run.runId(), streakLines.get(0));
        TowerLog.info("Run {} finished trial {}: {}/{} floors, score {}, rank {} of {}", run.runId(), instanceId, cleared, floors,
                score, rank, total);
    }

    /**
     * Records a qualifying day for one player: the streak, a freeze earned or spent, and any milestone's one-time
     * reward.
     */
    private static List<String> advanceStreak(MinecraftServer server, TowerTrialStore store, UUID player, long epochDay) {
        StreakRules.Outcome outcome = StreakRules.qualify(store.streakOf(player), epochDay);
        if (outcome.alreadyCounted()) return List.of();
        store.setStreak(player, outcome.state());
        List<String> lines = new ArrayList<>();
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        String who = online == null ? "A player" : online.getGameProfile().getName();
        lines.add(who + "'s daily streak: " + outcome.state().streak() + (outcome.freezeSpent() ? " (a freeze covered a missed day)" : "")
                + (outcome.freezeEarned() ? " (a streak freeze earned)" : ""));
        for (int milestone : outcome.milestones()) {
            int reward = StreakRules.rewardFor(milestone);
            TowerWalletStore.get(server).credit(player, reward);
            TowerWalletStore.get(server).checkpoint(server);
            lines.add(who + " reached a " + milestone + "-day streak: " + reward + " Tower Tokens");
            com.cobbletowers.season.SeasonProgressService.award(server, player, com.cobbletowers.season.SeasonPoints.Source.STREAK_MILESTONE,
                    milestone, false);
            TowerLog.info("{} reached a {}-day trial streak and is paid {} Tower Tokens", who, milestone, reward);
        }
        return lines;
    }

    private static int faintsOf(MinecraftServer server, PersistedRun run) {
        TowerRunStatsStore.Stats stats = TowerRunStatsStore.get(server).peek(run.runId());
        return stats == null ? 0 : stats.faints;
    }

    private static String titleOf(Kind kind) {
        return kind == Kind.DAILY ? "Daily Trial" : "Weekly Trial";
    }

    private static void message(MinecraftServer server, PersistedRun run, Component line) {
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) player.sendSystemMessage(line);
        }
    }

    // ---- boards
    // ------------------------------------------------------------------------------------------------------

    /** Daily boards are kept for a week and weekly ones for eight; older ones are dropped. */
    private static void pruneOldBoards(TowerLeaderboardStore boards) {
        LocalDate today = today();
        boards.prune(key -> key.board() == Board.TRIAL && isStale(key.tower().getPath(), today));
    }

    static boolean isStale(String boardPath, LocalDate today) {
        int slash = boardPath.indexOf('/');
        if (slash < 0) return false;
        String kind = boardPath.substring(0, slash);
        String period = boardPath.substring(slash + 1);
        try {
            if (kind.equals("daily")) return LocalDate.parse(period).isBefore(today.minusDays(7));
            if (kind.equals("weekly")) {
                LocalDate start = TrialClock.weekStartOf(period).orElse(null);
                return start != null && start.isBefore(today.minusWeeks(8));
            }
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
        return false;
    }

    // ---- words
    // -------------------------------------------------------------------------------------------------------

    /** What a player sees for a trial: what it is, their attempt, and for the daily their streak. */
    public static List<String> describe(MinecraftServer server, UUID player, Kind kind) {
        Optional<Instance> found = current(kind);
        if (found.isEmpty()) return List.of(titleOf(kind) + ": none is set up on this server.");
        Instance trial = found.get();
        List<String> lines = new ArrayList<>();
        var entry = trial.entry();
        lines.add(trial.title() + " (" + trial.floors() + " floors)");
        lines.add("  " + entry.tower().getPath() + (entry.playlist().isPresent() ? ", " + entry.playlist().get().getPath() : "")
                + (entry.modifiers().isEmpty() ? "" : ", " + String.join(", ", entry.modifiers().stream().map(m -> m.getPath()).toList()))
                + (entry.enemyLevel() > 0 ? ", enemies level " + entry.enemyLevel() : ""));
        for (String part : TrialParts.lines(trial, id -> id.getPath())) lines.add("  " + part);
        TowerTrialStore store = TowerTrialStore.get(server);
        Optional<TowerTrialStore.Attempt> attempt = store.attemptOf(player, trial.id());
        if (attempt.isEmpty()) {
            lines.add("  Your scored attempt: not used yet. Play it from the Trials page: " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + ", then confirm and start on the play screen.");
        } else if (!attempt.get().finished()) {
            lines.add("  Your scored attempt is in progress or was abandoned: it cannot be retried.");
        } else {
            lines.add("  Your scored attempt: " + attempt.get().floorsCleared() + "/" + trial.floors() + " floors, score "
                    + attempt.get().score() + ". More runs are practice.");
        }
        if (kind == Kind.DAILY) {
            StreakRules.State streak = store.streakOf(player);
            long todayEpoch = today().toEpochDay();
            StreakRules.Standing standing = StreakRules.standing(streak, todayEpoch);
            lines.add("  Streak: " + StreakRules.effectiveStreak(streak, todayEpoch) + " day(s), best " + streak.best()
                    + ", " + streak.freezes() + " freeze(s) held"
                    + switch (standing) {
                        case SAFE -> ". Today is counted.";
                        case AT_RISK -> ". Clear " + trial.streakMinFloors() + " floors in the next " + TrialClock.describe(millisUntilReset()) + " to keep it.";
                        case FROZEN -> ". A freeze will cover the missed day.";
                        case BROKEN -> ". It has lapsed; a new one starts with your next qualifying day.";
                        case NONE -> ". Clear " + trial.streakMinFloors() + " floors to start one.";
                    });
        }
        lines.add("  Resets in " + TrialClock.describe(kind == Kind.DAILY ? millisUntilReset() : millisUntilWeekReset()) + ".");
        return lines;
    }

    /**
     * One line for the login summary: today's daily trial, whether it is done, and the streak. Empty when there is no
     * daily trial.
     */
    public static Optional<String> summaryLine(MinecraftServer server, UUID player) {
        Optional<Instance> found = current(Kind.DAILY);
        if (found.isEmpty()) return Optional.empty();
        Instance trial = found.get();
        TowerTrialStore store = TowerTrialStore.get(server);
        StreakRules.State streak = store.streakOf(player);
        long todayEpoch = today().toEpochDay();
        StreakRules.Standing standing = StreakRules.standing(streak, todayEpoch);
        Optional<TowerTrialStore.Attempt> attempt = store.attemptOf(player, trial.id());
        String state = attempt.isEmpty() ? "not attempted" : attempt.get().finished()
                ? "done: " + attempt.get().floorsCleared() + "/" + trial.floors() + " floors, score " + attempt.get().score() : "attempt spent";
        String streakText = switch (standing) {
            case NONE -> "no streak yet";
            case SAFE -> "streak " + streak.streak() + ", counted today";
            case AT_RISK -> "streak " + streak.streak() + ", " + TrialClock.describe(millisUntilReset()) + " to keep it";
            case FROZEN -> "streak " + streak.streak() + ", a freeze will cover it";
            case BROKEN -> "streak lapsed (best " + streak.best() + ")";
        };
        return Optional.of("Daily Trial: " + trial.entry().label() + " - " + state + "; " + streakText + ".");
    }

    private static long millisUntilWeekReset() {
        LocalDate nextMonday = TrialClock.weekStart(today()).plusDays(7);
        long daysLeft = nextMonday.toEpochDay() - today().toEpochDay();
        return Math.max(0, millisUntilReset() + (daysLeft - 1) * 24L * 3_600_000L);
    }

    /** One line for the board listing of a trial. */
    public static String boardRow(int rank, Entry entry) {
        return MasteryView.row(Board.TRIAL, rank, entry);
    }
}
