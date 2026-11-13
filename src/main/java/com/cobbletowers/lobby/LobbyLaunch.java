package com.cobbletowers.lobby;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.battle.cobblemon.PartyReader;
import com.cobbletowers.battle.cobblemon.PartyStorage;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.RulesetResolver;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.economy.TowerKeyPolicy;
import com.cobbletowers.economy.TowerKeys;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerFreeRunStore;
import com.cobbletowers.trial.TrialClock;
import com.cobbletowers.persistence.RunOptions;
import com.cobbletowers.persistence.TowerAscensionStore;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.runcode.RunCode;
import com.cobbletowers.runtime.PartyValidation;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import com.cobbletowers.runtime.PlaylistRules;
import com.cobbletowers.runtime.RunFactory;
import com.cobbletowers.runtime.RunLifecycle;
import com.cobbletowers.runtime.RunTransitionService;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.storage.PartyArrangement;
import com.cobbletowers.storage.PartyJournalService;
import com.cobbletowers.storage.RentalPartyService;
import com.cobbletowers.trial.TrialSchedule;
import com.cobbletowers.trial.TrialService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What happens when a lobby's countdown ends: check everyone, create the run, move the parties in, allocate and open
 * floor 1. Everything checkable without touching a player's world is checked first, so a team that cannot start finds
 * out before a run record exists and stays together to retry.
 */
final class LobbyLaunch {

    /** Who is going and what each brings; {@code problems} is why the launch cannot go ahead. */
    private record Plan(List<UUID> players, Map<UUID, List<UUID>> parties, Map<UUID, List<UUID>> locks,
                        Map<UUID, RentalDraft.Team> rentalTeams, boolean rentalRun, List<String> problems) {}

    /** The run record and the numbers it was created with. */
    private record Created(PersistedRun run, long seed, int startAt) {}

    private LobbyLaunch() {}

    static void launch(MinecraftServer server, TowerLobby lobby, long now) {
        lobby.cancelCountdown();
        if (isPreparing(lobby)) {
            LobbyService.broadcast(server, lobby, "Your arena is still being prepared...");
            return;
        }
        TowerContent content = TowerDefinitionRegistry.content();
        TowerDefinition tower = content.towers().get(lobby.tower());
        RulesetDefinition ruleset = RulesetResolver.forTower(content, tower, lobby.playlist());
        if (ruleset == null) {
            LobbyService.broadcast(server, lobby, "That tower is no longer available.");
            return;
        }

        Plan plan = inspect(server, lobby, ruleset);
        checkAscension(server, lobby, plan);
        // The entry item (decided 2026-10-05): one key per player, checked here and taken only once the run
        // has really started, so a launch that fails costs nothing.
        Optional<TowerKeys.Kind> keyKind = TowerKeyPolicy.keyFor(TowerKeys.required(), lobby.trial().isPresent(), plan.rentalRun());
        if (keyKind.isPresent()) checkKeys(server, plan, keyKind.get());
        if (!plan.problems().isEmpty()) {
            TowerLog.info("The lobby of {} could not start {}: {}", lobby.host(), lobby.tower(),
                    String.join("; ", plan.problems()));
            LobbyService.broadcast(server, lobby, "Cannot start: " + String.join("; ", plan.problems()));
            return;
        }

        Optional<Created> created = create(server, lobby, content, plan, now);
        if (created.isEmpty()) {
            LobbyService.broadcast(server, lobby, "That tower is no longer available.");
            return;
        }
        UUID runId = created.get().run().runId();
        TowerRuns.save(server, created.get().run(), true);
        Started started;
        try {
            if (lobby.trial().isEmpty()) announceCode(server, lobby, plan, created.get());

            if (!lockParties(server, lobby, plan, runId, now)) return;
            started = setUp(server, lobby, plan, runId, now);
        } catch (RuntimeException ex) {
            // The returns above undo themselves; a throw would leave the run registered with nothing started and the
            // team's Pokemon moved, and the team unable to try again ("already in a run").
            failAfterThrow(server, lobby, plan, runId, now, ex);
            return;
        }
        switch (started) {
            case OPENED -> finishLaunch(server, lobby, plan, runId, keyKind);
            case BUILDING -> {
                // No ready arena: it is built a slice per tick rather than in one stall, and the launch finishes when it is up.
                PENDING.put(runId, new PendingLaunch(lobby, plan, keyKind));
                LobbyService.broadcast(server, lobby, "Your arena is being prepared...");
            }
            case FAILED -> { }
        }
    }

    /** What setting a run up came to. */
    private enum Started { OPENED, BUILDING, FAILED }

    /** A launch waiting on its arena: what it needs to finish. */
    private record PendingLaunch(TowerLobby lobby, Plan plan, Optional<TowerKeys.Kind> keyKind) {}

    private static final Map<UUID, PendingLaunch> PENDING = new LinkedHashMap<>();

    static {
        com.cobbletowers.ServerState.onStop(PENDING::clear);
    }

    /** Whether any launch is waiting on its arena (the lobby tick keeps running for it even with no lobby open). */
    static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** Whether this lobby is already waiting on its arena: a second launch would start a second run. */
    static boolean isPreparing(TowerLobby lobby) {
        return PENDING.values().stream().anyMatch(pending -> pending.lobby() == lobby);
    }

    /** Every tick: finishes the launches whose arena is now built. */
    static void tickPending(MinecraftServer server, long now) {
        if (PENDING.isEmpty()) return;
        for (Map.Entry<UUID, PendingLaunch> entry : List.copyOf(PENDING.entrySet())) {
            UUID runId = entry.getKey();
            PendingLaunch pending = entry.getValue();
            try {
                if (TowerRuns.get(runId).map(run -> run.state().isTerminal()).orElse(true)) {
                    // Ended while its arena was being built (the team left, an operator abandoned it): its cell was given back with it.
                    PENDING.remove(runId);
                    continue;
                }
                Optional<RunTransitionService.Outcome> allocated = RunLifecycle.pollAllocation(server, runId, now);
                if (allocated.isEmpty()) continue;
                PENDING.remove(runId);
                if (afterAllocation(server, pending.lobby(), pending.plan(), runId, now, allocated.get()) == Started.OPENED) {
                    finishLaunch(server, pending.lobby(), pending.plan(), runId, pending.keyKind());
                }
            } catch (RuntimeException ex) {
                PENDING.remove(runId);
                failAfterThrow(server, pending.lobby(), pending.plan(), runId, now, ex);
            }
        }
    }

    private static void failAfterThrow(MinecraftServer server, TowerLobby lobby, Plan plan, UUID runId, long now, RuntimeException ex) {
        TowerLog.error("Starting run " + runId + " threw; abandoning it", ex);
        com.cobbletowers.report.TowerTelemetry.endCause(runId, "start_failed");
        RunTransitionService.apply(server, runId, RunEvent.ABANDON_REQUESTED, now);
        restoreParties(server, plan.players());
        LobbyService.broadcast(server, lobby, "Cannot start: something went wrong on the server. Try again.");
    }

    /** Once the run has really started: keys, trial notes, and the lobby is done. */
    private static void finishLaunch(MinecraftServer server, TowerLobby lobby, Plan plan, UUID runId, Optional<TowerKeys.Kind> keyKind) {
        // The attempt is spent only now that the run has really started: a launch that failed above costs nothing.
        keyKind.ifPresent(kind -> takeKeys(server, plan, runId, kind));
        TowerRuns.get(runId).ifPresent(started -> {
            TrialService.recordLaunch(server, started);
            if (started.options().isTrial()) {
                String note = started.options().scored() ? "This is your scored attempt."
                        : "This run is practice and will not post.";
                for (UUID id : plan.players()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) player.sendSystemMessage(Component.literal(note));
                }
            }
        });
        LobbyService.dissolve(server, lobby, "");
        TowerLog.info("Lobby of {} started run {} on {}", plan.players().size(), runId, lobby.tower());
    }

    /** Looks at every team member: online, not in a run, and a party (own or drafted) that passes the rules. */
    private static Plan inspect(MinecraftServer server, TowerLobby lobby, RulesetDefinition ruleset) {
        // Starting early: the unanswered are dropped, and so is anyone who has gone offline.
        for (UUID id : lobby.pending()) lobby.remove(id);
        Plan plan = new Plan(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                RentalDraftService.isRentalLobby(lobby), new ArrayList<>());
        for (UUID id : lobby.team()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                if (!id.equals(lobby.host())) lobby.remove(id);
                else plan.problems().add("The host is offline.");
                continue;
            }
            if (LobbyService.inRun(id)) {
                plan.problems().add(LobbyService.name(player) + " is already in a tower run.");
                continue;
            }
            if (plan.rentalRun()) inspectRental(player, plan);
            else inspectOwn(player, lobby, ruleset, plan);
        }
        return plan;
    }

    /** A rental run (P33): the party is the drafted team, so the player's own Pokemon are not judged at all. */
    private static void inspectRental(ServerPlayer player, Plan plan) {
        UUID id = player.getUUID();
        Optional<RentalDraft> draft = RentalDraftService.draftOf(id).filter(RentalDraft::complete);
        if (draft.isEmpty()) {
            plan.problems().add(LobbyService.name(player) + " has not finished their draft (open the draft on the play screen)");
            return;
        }
        if (!RentalPartyService.canMakeRoom(player)) {
            plan.problems().add(LobbyService.name(player)
                    + " needs free box space for their own Pokemon while they play");
            return;
        }
        RentalDraft.Team team = draft.get().finish(UUID::randomUUID);
        plan.rentalTeams().put(id, team);
        plan.players().add(id);
        plan.parties().put(id, team.ids());
    }

    private static void inspectOwn(ServerPlayer player, TowerLobby lobby, RulesetDefinition ruleset, Plan plan) {
        UUID id = player.getUUID();
        String name = LobbyService.name(player);
        List<UUID> picked = lobby.chosenOf(id);
        List<PartyMember> party;
        if (picked.isEmpty()) {
            party = PartyReader.members(player);
        } else {
            PartyStorage.Snapshot snapshot = PartyStorage.snapshot(player);
            party = new ArrayList<>();
            for (UUID pokemon : picked) snapshot.member(pokemon).ifPresent(party::add);
            if (party.size() != picked.size()) {
                plan.problems().add(name + " chose a Pokemon that is no longer theirs");
                return;
            }
        }
        PartyValidation.Result checked = PartyValidation.validate(party, ruleset);
        checked.problems().forEach(problem -> plan.problems().add(name + " " + problem));
        // The playlist's clauses (P32) apply to what would be registered.
        lobby.playlist().flatMap(PlaylistRegistry::get).ifPresent(playlist ->
                PlaylistRules.problems(
                        party.stream().filter(member -> checked.registered().contains(member.id())).toList(),
                        playlist).forEach(problem -> plan.problems().add(name + ": " + problem)));
        if (!picked.isEmpty()) {
            // Dry-run before anyone is moved: a plan that cannot be carried out is refused up front.
            PartyArrangement.Plan arrangement = PartyJournalService.dryRun(player, checked.registered());
            if (!arrangement.ok()) {
                plan.problems().add(name + " cannot register that party (" + arrangement.failure() + ")");
            }
            plan.locks().put(id, checked.registered());
        }
        plan.players().add(id);
        plan.parties().put(id, checked.registered());
    }

    /** Re-checked at the start: someone who accepted after the host chose may not have reached it. */
    private static void checkAscension(MinecraftServer server, TowerLobby lobby, Plan plan) {
        int startAt = lobby.ascension();
        if (startAt <= 0) return;
        TowerAscensionStore records = TowerAscensionStore.get(server);
        for (UUID id : plan.players()) {
            if (records.recordOf(id, lobby.tower()) < startAt) {
                ServerPlayer shallow = server.getPlayerList().getPlayer(id);
                plan.problems().add((shallow == null ? "A teammate" : LobbyService.name(shallow))
                        + " has not reached Ascension " + startAt);
            }
        }
    }

    /** Whether the player's first ordinary run of the trial day is still free. */
    private static boolean hasFreeRun(MinecraftServer server, UUID id, TowerKeys.Kind kind) {
        return kind == TowerKeys.Kind.TOWER && TowerKeyPolicy.freeRunAvailable(TowerFreeRunStore.get(server).lastFreeDay(id),
                TrialClock.dayKey(TrialService.today()));
    }

    private static void checkKeys(MinecraftServer server, Plan plan, TowerKeys.Kind kind) {
        for (UUID id : plan.players()) {
            ServerPlayer keyholder = server.getPlayerList().getPlayer(id);
            if (keyholder != null && hasFreeRun(server, id, kind)) continue;
            if (keyholder == null || TowerKeys.count(keyholder, kind) < 1) {
                plan.problems().add((keyholder == null ? "A teammate" : LobbyService.name(keyholder))
                        + " needs a " + kind.label() + " to start (ask a moderator if you have none)");
            }
        }
    }

    private static void takeKeys(MinecraftServer server, Plan plan, UUID runId, TowerKeys.Kind kind) {
        for (UUID id : plan.players()) {
            ServerPlayer keyholder = server.getPlayerList().getPlayer(id);
            if (keyholder != null && hasFreeRun(server, id, kind)) {
                TowerFreeRunStore.get(server).markUsed(id, TrialClock.dayKey(TrialService.today()));
                keyholder.sendSystemMessage(Component.literal("Today's first run is free. Runs after it use a Tower Key."));
                continue;
            }
            // The run is open and cannot be undone here, so a key that vanished in the gap is logged, not enforced.
            if (keyholder != null && TowerKeys.take(keyholder, kind)) {
                keyholder.sendSystemMessage(Component.literal("Used 1 " + kind.label() + "."));
            } else {
                TowerLog.warn("Run {} started but player {} had no {} left to take", runId, id, kind.label());
            }
        }
    }

    /** Creates the run. A trial (P32) fixes the seed, mutators and enemy level for everybody and starts at floor 1. */
    private static Optional<Created> create(MinecraftServer server, TowerLobby lobby, TowerContent content, Plan plan,
                                            long now) {
        long seed = lobby.seed().orElseGet(() -> server.overworld().getRandom().nextLong());
        int startAt = lobby.ascension();
        // Balance phase 2: the band picked at the door rides on the run; with AscensionLib's Threat off the run keeps the floor tiers.
        RunOptions options = RunOptions.of(lobby.playlist())
                .withEntryThreat(com.cobbletowers.economy.AscensionLibScouting.threatEnabled() ? lobby.entryThreat() : 0);
        List<ResourceLocation> trialModifiers = List.of();
        List<ResourceLocation> startingRelics = List.of();
        if (lobby.trial().isPresent()) {
            TrialSchedule.Instance trial = lobby.trial().get();
            boolean scored = TrialService.wouldBeScored(server, plan.players(), trial.id());
            options = new RunOptions(lobby.playlist(), Optional.of(trial.id()), trial.floors(), scored,
                    trial.entry().enemyLevel());
            trialModifiers = trial.entry().modifiers();
            startingRelics = trial.roll().flatMap(com.cobbletowers.trial.RotatingRoll::startingRelic).map(List::of).orElse(List.of());
            seed = trial.seed();
            startAt = 0;
        }
        long chosenSeed = seed;
        int chosenStart = startAt;
        return RunFactory.create(content, lobby.tower(), plan.players(), plan.parties(), startAt, options,
                trialModifiers, startingRelics, seed, now).map(run -> new Created(run, chosenSeed, chosenStart));
    }

    /** Printed for every ordinary run (P35): a friend who enters it plays the same run. */
    private static void announceCode(MinecraftServer server, TowerLobby lobby, Plan plan, Created created) {
        String code = RunCode.encode(lobby.tower(), lobby.playlist(), created.startAt(), created.seed());
        for (UUID id : plan.players()) LobbyService.rememberCode(id, code);
        LobbyService.broadcast(server, lobby,
                "Run code: " + code + " (share it: the same code starts the same run from the play screen)");
        TowerLog.info("Run {} started with code {}", created.run().runId(), code);
    }

    /**
     * Moves registered Pokemon into the party. Journaled and flushed first, per player, so a failure or crash from
     * here on can be undone; a failure undoes the ones already done.
     * @return false when it failed (the run is abandoned and the team told)
     */
    private static boolean lockParties(MinecraftServer server, TowerLobby lobby, Plan plan, UUID runId, long now) {
        for (UUID id : plan.players()) {
            RentalDraft.Team lent = plan.rentalTeams().get(id);
            List<UUID> target = plan.locks().get(id);
            if (target == null && lent == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            // A rental run (P33) lends the drafted team through the same journal; anything else moves the player's
            // own Pokemon.
            Object result = lent != null
                    ? (player == null ? RentalPartyService.Lock.FAILED
                            : RentalPartyService.lock(server, runId, player, lent))
                    : (player == null ? PartyJournalService.Lock.FAILED
                            : PartyJournalService.lockIn(server, runId, player, target));
            if (result == PartyJournalService.Lock.FAILED || result == PartyJournalService.Lock.NOT_OWNED
                    || result == PartyJournalService.Lock.NO_ROOM || result == PartyJournalService.Lock.TOO_MANY
                    || result == RentalPartyService.Lock.FAILED || result == RentalPartyService.Lock.NO_ROOM
                    || result == RentalPartyService.Lock.IN_BATTLE) {
                com.cobbletowers.report.TowerTelemetry.endCause(runId, "start_failed");
                RunTransitionService.apply(server, runId, RunEvent.ABANDON_REQUESTED, now);
                restoreParties(server, plan.players());
                LobbyService.broadcast(server, lobby, "Cannot start: " + (player == null ? "a player went offline"
                        : LobbyService.name(player) + " could not have their party arranged (" + result + ")")
                        + ". Try again.");
                return false;
            }
        }
        return true;
    }

    /** Validates the parties, allocates a cell and opens floor 1; on any failure undoes what it can. BUILDING: finished by {@link #tickPending}. */
    private static Started setUp(MinecraftServer server, TowerLobby lobby, Plan plan, UUID runId, long now) {
        RunTransitionService.Outcome validated = RunLifecycle.validateParty(server, runId, now,
                id -> Optional.ofNullable(server.getPlayerList().getPlayer(id)).map(PartyReader::members));
        if (!(validated instanceof RunTransitionService.Move move)
                || move.next().state() != RunState.ALLOCATING_INSTANCE) {
            // Pre-checked above, so this is a party that changed in the gap. The run is abandoned by the rejection;
            // the team is left to try again.
            restoreParties(server, plan.players());
            LobbyService.broadcast(server, lobby,
                    "Cannot start: a party changed while the run was being set up. Try again.");
            return Started.FAILED;
        }

        RunLifecycle.Allocation allocation = RunLifecycle.beginAllocation(server, runId, now);
        if (allocation instanceof RunLifecycle.Building) return Started.BUILDING;
        return afterAllocation(server, lobby, plan, runId, now, ((RunLifecycle.Allocated) allocation).outcome());
    }

    /** The rest of {@link #setUp} once the run has its cell (or has failed to get one): open floor 1, or undo. */
    private static Started afterAllocation(MinecraftServer server, TowerLobby lobby, Plan plan, UUID runId, long now,
                                           RunTransitionService.Outcome allocated) {
        if (!(allocated instanceof RunTransitionService.Move)) {
            // Frees a team held by a run that never got a cell, so they can retry.
            RunTransitionService.apply(server, runId, RunEvent.RECOVERY_ABANDONED, now);
            restoreParties(server, plan.players());
            LobbyService.broadcast(server, lobby,
                    "Cannot start: no tower space is free right now. Try again in a moment.");
            return Started.FAILED;
        }

        if (!openFloor(server, runId, now)) {
            // The run is parked by openFloor; it holds the team until recovery or an operator settles it.
            LobbyService.dissolve(server, lobby, "The floor could not be opened; the run is parked for recovery.");
            return Started.FAILED;
        }
        return Started.OPENED;
    }

    /** PREPARING -> FLOOR_READY, then the floor itself; parks the run on failure. */
    private static boolean openFloor(MinecraftServer server, UUID runId, long now) {
        if (!(RunTransitionService.apply(server, runId, RunEvent.PREPARATION_COMPLETE, now)
                instanceof RunTransitionService.Move)) {
            RunTransitionService.apply(server, runId, RunEvent.TECHNICAL_FAILURE, now);
            return false;
        }
        return RunLifecycle.beginFloor(server, runId, now);
    }

    /** Puts every listed player's Pokemon back at once, after a start that did not happen. */
    private static void restoreParties(MinecraftServer server, List<UUID> players) {
        for (UUID id : players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) PartyJournalService.restoreNow(server, player);
        }
    }
}
