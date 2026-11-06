package com.cobbletowers.intermission;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.network.IntermissionStatePayload;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.persistence.PersistedDraft;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.RunLifecycle;
import com.cobbletowers.runtime.RunTransitionService;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything a team does between floors (P17): vote on the modifier, use the vendor, ready up, vote to cash out; a
 * countdown then opens the next floor. Commands and the screen share these methods. Rounds live in memory ({@link
 * IntermissionRound}).
 */
public final class IntermissionService {

    private static final Map<UUID, IntermissionRound> ROUNDS = new HashMap<>();

    static {
        ServerState.onStop(IntermissionService::clear);
    }

    private IntermissionService() {}

    public static void install() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ROUNDS.isEmpty()) return;
            try {
                tick(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("An intermission tick failed", ex);
            }
        });
    }

    public static void clear() {
        ROUNDS.clear();
    }

    /**
     * Arrival at INTERMISSION, from the same place the transition service revives people and opens the draft. Runs
     * before banking so the reward reveal lands on top of this screen.
     */
    public static void onArrival(MinecraftServer server, UUID runId) {
        ROUNDS.put(runId, new IntermissionRound());
        TowerRuns.get(runId).ifPresent(run -> {
            // A walkable tower: the floor is won, so the way to the vendor's room opens, and anybody left on the gallery ledge comes back.
            com.cobbletowers.route.TowerGates.rescueFromGallery(server, run);
            com.cobbletowers.route.TowerGates.openA(server, run);
            com.cobbletowers.vendor.VendorNpc.spawn(server, run);
            PedestalService.sync(server, run);
            if (com.cobbletowers.modifier.DraftService.customs(run).healsEachIntermission()) {
                for (PersistedParticipant participant : members(run)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                    if (player == null) continue;
                    com.cobbletowers.economy.VendorServices.apply(com.cobbletowers.definition.VendorEffect.FULL_HEAL, player);
                    player.displayClientMessage(Component.literal("Field Hospital: your party is fully healed."), true);
                }
            }
            broadcast(server, run, cycleEndMessage(run), true);
        });
    }

    // ---- actions -----------------------------------------------------------------------------

    public static String pick(MinecraftServer server, ServerPlayer player, int cardIndex) {
        Optional<PersistedRun> run = intermissionRunOf(player);
        if (run.isEmpty()) return "You are not at an intermission.";
        if (run.get().modifiers().draft().filter(draft -> !draft.resolved()).isEmpty()) return "There is nothing to vote on.";
        DraftService.vote(server, run.get().runId(), player.getUUID(), cardIndex, System.currentTimeMillis());
        PedestalService.noteVote(run.get().runId(), cardIndex);
        PedestalService.refresh(server, run.get().runId());
        return afterChange(server, run.get().runId(), "Vote recorded.");
    }

    public static String ready(MinecraftServer server, ServerPlayer player, boolean value) {
        Optional<PersistedRun> run = intermissionRunOf(player);
        if (run.isEmpty()) return "You are not at an intermission.";
        if (value && run.get().modifiers().hasOpenDraft()) return "Vote on the modifier before you ready up.";
        if (value && com.cobbletowers.echo.EchoDuels.active(server, run.get().runId())) {
            return "Finish the Echo Duel before you ready up.";
        }
        IntermissionRound round = ROUNDS.computeIfAbsent(run.get().runId(), id -> new IntermissionRound());
        round.setReady(player.getUUID(), value);
        return afterChange(server, run.get().runId(), value ? "You are ready." : "You are not ready.");
    }

    /** {@code true} votes to cash out, {@code false} to stay. */
    public static String cashOut(MinecraftServer server, ServerPlayer player, boolean value) {
        Optional<PersistedRun> run = intermissionRunOf(player);
        if (run.isEmpty()) return "You can only cash out at an intermission.";
        if (com.cobbletowers.echo.EchoDuels.active(server, run.get().runId())) return "Finish the Echo Duel first.";
        IntermissionRound round = ROUNDS.computeIfAbsent(run.get().runId(), id -> new IntermissionRound());
        round.voteCashOut(player.getUUID(), value);
        return afterChange(server, run.get().runId(), value ? "You voted to cash out." : "You voted to keep going.");
    }

    public static String vendor(MinecraftServer server, ServerPlayer player) {
        Optional<PersistedRun> run = intermissionRunOf(player);
        if (run.isEmpty()) return "The vendor is only open at an intermission.";
        if (com.cobbletowers.definition.PlaylistRegistry.vendorClosed(run.get())) return "The vendor is closed in this mode.";
        TowerNetworking.sendVendorCatalog(server, player, run.get());
        return "";
    }

    /** Sends the player's screen again without opening it: an open screen updates, a closed one stays closed (a vendor purchase changed the party's health). */
    public static void refresh(MinecraftServer server, ServerPlayer player) {
        intermissionRunOf(player).ifPresent(run -> send(server, run, player, "", false));
    }

    public static void openScreen(MinecraftServer server, ServerPlayer player, String message) {
        intermissionRunOf(player).ifPresent(run -> send(server, run, player, message, true));
    }

    /**
     * One line for {@code runs show}: who is ready, who is cashing out, and whether the next floor is counting down.
     */
    public static String describe(PersistedRun run) {
        IntermissionRound round = ROUNDS.get(run.runId());
        if (round == null) return "no intermission round";
        List<UUID> electorate = DraftService.voters(run);
        long ready = electorate.stream().filter(round::isReady).count();
        long cashing = electorate.stream().filter(round::votedCashOut).count();
        return ready + "/" + electorate.size() + " ready, " + cashing + " cashing out"
                + (round.counting() ? ", counting down" : "");
    }

    /** A plain-words status for a player at an intermission, or null when they are not at one. */
    public static String statusOf(ServerPlayer player) {
        Optional<PersistedRun> found = intermissionRunOf(player);
        if (found.isEmpty()) return null;
        PersistedRun run = found.get();
        String vote = run.modifiers().hasOpenDraft()
                ? "Modifier vote still open: open the menu to vote. " : "";
        return "Floor " + run.floorIndex() + " cleared. " + vote + describe(run)
                + ". Reopen the menu any time with /tower (or the Tower Menu key).";
    }

    /** Clickable shortcuts, so a closed menu is one click from open again. */
    private static Component shortcuts(PersistedRun run) {
        MutableComponent line = Component.literal("Tower: ").withStyle(ChatFormatting.GOLD);
        line.append(button("[Open menu]", "/tower"));
        int cards = run.modifiers().draft().filter(d -> !d.resolved()).map(d -> d.cards().size()).orElse(0);
        return line;
    }

    private static Component button(String label, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    /** True when {@code player} is in a run that is at an intermission. */
    public static boolean isAtIntermission(ServerPlayer player) {
        return intermissionRunOf(player).isPresent();
    }

    // ---- the decision ------------------------------------------------------------------------

    private static String afterChange(MinecraftServer server, UUID runId, String reply) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty() || found.get().state() != RunState.INTERMISSION) return reply;
        PersistedRun run = found.get();
        IntermissionRound round = ROUNDS.computeIfAbsent(runId, id -> new IntermissionRound());
        List<UUID> electorate = DraftService.voters(run);

        if (round.cashOutPasses(electorate)) {
            ROUNDS.remove(runId);
            RunTransitionService.apply(server, runId, RunEvent.CASH_OUT_CHOSEN, System.currentTimeMillis());
            notifyAll(server, run, "The team cashed out.");
            return "The team cashed out.";
        }
        String message = round.cashOutTied(electorate) ? "The cash-out vote is tied, so the team keeps playing." : "";
        if (!round.counting() && !run.modifiers().hasOpenDraft() && round.allReady(electorate)) {
            round.beginCountdown(System.currentTimeMillis(), LobbyService.COUNTDOWN_MILLIS);
            message = (message.isEmpty() ? "" : message + " ") + "Everyone is ready. The next floor opens in " + LobbyService.COUNTDOWN_MILLIS / 1000 + " seconds.";
        }
        broadcast(server, run, message, false);
        return reply;
    }

    static void tick(MinecraftServer server, long now) {
        for (UUID runId : List.copyOf(ROUNDS.keySet())) {
            // One run's fault must not stall the countdowns of the others.
            try {
                tickRun(server, runId, now);
            } catch (RuntimeException ex) {
                TowerLog.error("The intermission of run " + runId + " failed to tick", ex);
            }
        }
    }

    private static void tickRun(MinecraftServer server, UUID runId, long now) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty() || found.get().state() != RunState.INTERMISSION) {
            ROUNDS.remove(runId);
            return;
        }
        IntermissionRound round = ROUNDS.get(runId);
        if (round.walking()) {
            tickWalk(server, found.get(), round, now);
            return;
        }
        // Linear progression: with no modifier vote open the way up opens by itself; readying up is no longer needed.
        if (!round.counting() && !round.autoWalkTried() && !found.get().modifiers().hasOpenDraft()
                && !com.cobbletowers.echo.EchoDuels.active(server, runId)) {
            round.markAutoWalkTried();
            if (beginWalk(server, found.get(), round, now, com.cobbletowers.route.WalkRules.AUTO_WALK_MILLIS)) return;
        }
        if (!round.counting()) return;
        if (!round.countdownDue(now)) {
            int left = round.secondsLeft(now);
            if (!round.announce(left)) return;
            for (PersistedParticipant participant : members(found.get())) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player != null) player.displayClientMessage(Component.literal("Next floor in " + left + "..."), true);
            }
            return;
        }
        round.cancelCountdown();
        if (com.cobbletowers.echo.EchoDuels.active(server, runId)) return;   // an Echo Duel holds the floor
        // A playlist's party clauses (P32) hold every floor, not only the first: a party that stopped complying
        // waits.
        List<String> problems = PlaylistGuard.problems(server, found.get());
        if (!problems.isEmpty()) {
            TowerLog.info("Run {} cannot open its next floor: {}", runId, String.join("; ", problems));
            broadcast(server, found.get(), "Cannot open the next floor: " + String.join("; ", problems), false);
            return;
        }
        if (beginWalk(server, found.get(), round, now, com.cobbletowers.route.WalkRules.WALK_MILLIS)) return;
        openNext(server, runId, found.get(), now);
    }

    /**
     * A walkable tower is left on foot: the stair gate opens and the next floor begins when the whole team is at the arena door, or after
     * {@link com.cobbletowers.route.WalkRules#WALK_MILLIS}, when whoever is left behind is taken there. Anything else (the last floor of a cycle,
     * a detour to another building, a tower with no gates) opens the next floor at once, as it always did.
     * @return whether the team is now walking
     */
    private static boolean beginWalk(MinecraftServer server, PersistedRun run, IntermissionRound round, long now, long walkMillis) {
        if (run.cell().isEmpty()) return false;
        var content = TowerDefinitionRegistry.content();
        var current = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, run.floorIndex());
        var next = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, run.floorIndex() + 1);
        if (current.isEmpty() || !com.cobbletowers.route.WalkRules.onFoot(current.get(), next)) return false;
        com.cobbletowers.route.TowerDoors.ensureOpen(server, run);
        round.beginWalk(now, walkMillis);
        if (current.get().laneStairs()) {
            // P58: the way up is the stair behind the door the vote opened; there is no shared gate.
            round.beginClimb(com.cobbletowers.route.TowerDoors.openLane(server, run));
            broadcast(server, run, "The door is open. Climb its stair to the next floor: you go up together, when the whole team stands on the landing.", false);
            return true;
        }
        com.cobbletowers.route.TowerGates.openB(server, run);
        broadcast(server, run, "The gate to the stair is open. Climb to the next floor: the fight starts when the whole team is at the arena door.", false);
        return true;
    }

    /** The run's participants who can fight and are online, in the order the arrival spreads them. */
    private static List<ServerPlayer> onlineFighters(MinecraftServer server, PersistedRun run) {
        List<ServerPlayer> out = new java.util.ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) out.add(player);
        }
        return out;
    }

    /**
     * The lane stair (P58). While climbing: when the whole team is on the landing (or the deadline passes) the screen fades; a moment later everyone
     * is taken to the lane's arrival hall, and the walk becomes the short walk into the arena. Returns whether the climb still holds the tick.
     */
    private static boolean tickClimb(MinecraftServer server, PersistedRun run, IntermissionRound round, long now) {
        if (!round.climbing()) return false;
        List<ServerPlayer> fighters = onlineFighters(server, run);
        if (round.fading()) {
            if (round.fadeDue(now)) {
                int lane = round.climbLane();
                String kind = com.cobbletowers.route.RoomDresser.kindOf(run, lane);
                int moved = com.cobbletowers.route.LaneStairs.transfer(server, run, lane, kind, fighters);
                if (moved == 0) {
                    // The stair could not be used (the next floor is not placed): fall back to opening the next floor as it always was.
                    round.cancelWalk();
                    TowerLog.warn("Run {}: lane {} of floor {} could not take the team up; opening the next floor directly", run.runId(), lane, run.floorIndex());
                    openNext(server, run.runId(), run, now);
                    return true;
                }
                round.markTransferred(now, com.cobbletowers.route.LaneStairs.ARRIVAL_WALK_MILLIS);
            }
            return true;
        }
        if (round.walkDue(now) || com.cobbletowers.route.LaneStairs.everyoneOnLanding(server, run, round.climbLane(), fighters)) {
            com.cobbletowers.route.LaneStairs.fadeOut(fighters);
            round.beginFade(now, com.cobbletowers.route.LaneStairs.FADE_OUT_MILLIS);
            return true;
        }
        int left = round.walkSecondsLeft(now);
        if (round.announce(left) && left % 15 == 0 && left > 0) {
            for (ServerPlayer player : fighters) player.displayClientMessage(Component.literal("Climb to the landing: " + left + "s"), true);
        }
        return true;
    }

    private static void tickWalk(MinecraftServer server, PersistedRun run, IntermissionRound round, long now) {
        if (com.cobbletowers.echo.EchoDuels.active(server, run.runId())) return;
        if (tickClimb(server, run, round, now)) return;
        if (!round.walkDue(now) && !everyoneArrived(server, run)) {
            int left = round.walkSecondsLeft(now);
            if (round.announce(left) && left % 15 == 0 && left > 0) {
                for (PersistedParticipant participant : run.participants()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                    if (player != null) player.displayClientMessage(Component.literal("Climb to the next floor: " + left + "s"), true);
                }
            }
            return;
        }
        round.cancelWalk();
        List<String> problems = PlaylistGuard.problems(server, run);
        if (!problems.isEmpty()) {
            TowerLog.info("Run {} cannot open its next floor: {}", run.runId(), String.join("; ", problems));
            broadcast(server, run, "Cannot open the next floor: " + String.join("; ", problems), false);
            return;
        }
        openNext(server, run.runId(), run, now);
    }

    /** Every fighter who is online is standing at the next floor's entry anchor. Nobody online counts as not there, so the deadline decides. */
    private static boolean everyoneArrived(MinecraftServer server, PersistedRun run) {
        var content = TowerDefinitionRegistry.content();
        var next = com.cobbletowers.route.FloorLayouts.layoutOf(content, run, run.floorIndex() + 1);
        if (next.isEmpty() || run.cell().isEmpty()) return false;
        var origin = com.cobbletowers.instance.CellPreparer.originFor(server, run.cell().getAsInt(), next.get());
        if (origin.isEmpty()) return false;
        var entry = next.get().entry().in(origin.get());
        int fighters = 0;
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            fighters++;
            if (!com.cobbletowers.route.WalkRules.arrived(player.getX(), player.getY(), player.getZ(),
                    entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5)) return false;
        }
        return fighters > 0;
    }

    private static void openNext(MinecraftServer server, UUID runId, PersistedRun run, long now) {
        RunLifecycle.Opened result = RunLifecycle.openNextFloor(server, runId, now);
        switch (result) {
            case OPENED -> ROUNDS.remove(runId);
            case BLOCKED -> broadcast(server, run, "The next floor cannot open yet.", false);
            case FAILED -> {
                ROUNDS.remove(runId);
                notifyAll(server, run, "The next floor could not be opened; the run is parked for recovery.");
            }
        }
    }

    // ---- state shown to the screen -------------------------------------------------------------

    private static Optional<PersistedRun> intermissionRunOf(ServerPlayer player) {
        return TowerRuns.forPlayer(player.getUUID()).filter(run -> run.state() == RunState.INTERMISSION);
    }

    /** Spectators remain part of the team; players who left must receive no more run UI or healing. */
    static List<PersistedParticipant> members(PersistedRun run) {
        return run.participants().stream().filter(participant -> participant.state().isInRun()).toList();
    }

    private static void notifyAll(MinecraftServer server, PersistedRun run, String message) {
        for (PersistedParticipant participant : members(run)) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) player.sendSystemMessage(Component.literal(message));
        }
    }

    /**
     * At the end of an Ascension cycle (P30) the intermission is the decision point; says so once. Empty elsewhere.
     */
    static String cycleEndMessage(PersistedRun run) {
        var tower = com.cobbletowers.definition.TowerDefinitionRegistry.content().towers().get(run.towerId());
        if (tower == null || !tower.ascension()
                || !com.cobbletowers.ascension.AscensionPolicy.isCycleEnd(run.floorIndex(), tower.floorCount())) return "";
        int next = tower.ascensionOf(run.floorIndex()) + 1;
        return "Cycle complete! Cash out now, or ready up to ascend to Ascension " + next + ": harder enemies, better rewards.";
    }

    private static void broadcast(MinecraftServer server, PersistedRun run, String message, boolean open) {
        for (PersistedParticipant participant : members(run)) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            if (!message.isEmpty()) player.sendSystemMessage(Component.literal(message));
            if (open) player.sendSystemMessage(shortcuts(run));
            send(server, run, player, message, open);
        }
    }

    private static void send(MinecraftServer server, PersistedRun run, ServerPlayer viewer, String message, boolean open) {
        if (members(run).stream().noneMatch(participant -> participant.playerId().equals(viewer.getUUID()))) return;
        // A screen that opens by itself (a floor cleared) must show even if the player's page looks open from its pings.
        if (open) com.cobbletowers.webgui.WebGuiBridge.userAsked(viewer);
        TowerNetworking.sendIntermissionState(viewer, payloadOf(server, run, viewer, message, open));
    }

    /** What the intermission page receives for {@code viewer}; also read by the dev tools that dump it. */
    public static IntermissionStatePayload payloadOf(MinecraftServer server, PersistedRun run, ServerPlayer viewer, String message, boolean open) {
        IntermissionRound round = ROUNDS.computeIfAbsent(run.runId(), id -> new IntermissionRound());
        IntermissionStatePayload.Draft draft = draftOf(run, viewer.getUUID());

        List<IntermissionStatePayload.Member> members = new ArrayList<>();
        for (PersistedParticipant participant : members(run)) {
            ServerPlayer other = server.getPlayerList().getPlayer(participant.playerId());
            String name = other == null ? participant.playerId().toString().substring(0, 8)
                    : other.getGameProfile().getName();
            members.add(new IntermissionStatePayload.Member(name, round.isReady(participant.playerId()),
                    round.votedCashOut(participant.playerId())));
        }
        return new IntermissionStatePayload(run.floorIndex(), draft, members,
                round.secondsLeft(System.currentTimeMillis()), message, open, extrasOf(run, viewer));
    }

    /** The team's health and the run's place in its tower, for the Frontier Desk page; decoration, so a failure sends none. */
    private static IntermissionStatePayload.Extras extrasOf(PersistedRun run, ServerPlayer viewer) {
        try {
            var content = com.cobbletowers.definition.TowerDefinitionRegistry.content();
            var tower = content.towers().get(run.towerId());
            if (tower == null) return IntermissionStatePayload.Extras.NONE;
            List<Integer> milestones = new ArrayList<>();
            for (int floor = 1; floor <= tower.floorCount(); floor++) {
                if (content.milestoneAt(run.towerId(), floor).isPresent()) milestones.add(floor);
            }
            return IntermissionExtras.of(tower.displayName(), run.floorIndex(), tower.floorCount(), milestones,
                    com.cobbletowers.battle.cobblemon.PartyReader.cards(viewer), routeOf(content, run));
        } catch (RuntimeException ex) {
            com.cobbletowers.TowerLog.warn("Intermission extras for {} could not be built: {}", viewer.getGameProfile().getName(), ex.toString());
            return IntermissionStatePayload.Extras.NONE;
        }
    }

    /** The route between floors for the page: the stops on the table and what scouting shows ahead. None when this is not a route step. */
    private static IntermissionStatePayload.Route routeOf(com.cobbletowers.definition.TowerContent content, PersistedRun run) {
        if (!com.cobbletowers.modifier.DraftService.routeEnabled()) return IntermissionStatePayload.Route.NONE;
        var draft = run.modifiers().draft().filter(PersistedDraft::route);
        int ahead = 1 + Math.max(0, com.cobbletowers.modifier.DraftService.effects(run).scoutingBonus());
        var route = com.cobbletowers.route.RouteView.of(run.seed(), run.floorIndex(), run.modifiers().lastLane(),
                draft.map(PersistedDraft::lanes).orElse(List.of()), draft.map(PersistedDraft::kinds).orElse(List.of()),
                draft.map(PersistedDraft::arenas).orElse(List.of()), ahead,
                com.cobbletowers.modifier.DraftService.routeRules(run), floor -> content.milestoneAt(run.towerId(), floor).isPresent());
        var tower = content.towers().get(run.towerId());
        if (tower == null || !tower.routeMap() || route.now().isEmpty()) return route;
        // a tower with a branching map shows the whole map; the per-step look-ahead would disagree with it
        var spot = com.cobbletowers.modifier.DraftService.mapSpot(content, tower, run);
        return new IntermissionStatePayload.Route(spot.lane(), route.now(), List.of(),
                com.cobbletowers.route.RouteMapView.graph(spot, spot.row() + 1 + ahead));
    }

    static IntermissionStatePayload.Draft draftOf(PersistedRun run, UUID viewer) {
        Optional<PersistedDraft> found = run.modifiers().draft();
        if (found.isEmpty()) return new IntermissionStatePayload.Draft(0, List.of(), -1, -1);
        PersistedDraft draft = found.get();
        List<IntermissionStatePayload.Card> cards = new ArrayList<>();
        for (int i = 0; i < draft.cards().size(); i++) {
            ModifierDefinition modifier = TowerDefinitionRegistry.content().modifiers().get(draft.cards().get(i));
            int index = i;
            int votes = (int) draft.votes().values().stream().filter(vote -> vote == index).count();
            String name = modifier != null ? modifier.displayName()
                    : IntermissionEvents.Option.fromCard(draft.cards().get(i)).map(o -> IntermissionEvents.labelAt(o, draft.floorIndex()))
                            .orElse(draft.cards().get(i).toString());
            var option = modifier != null ? java.util.Optional.<IntermissionEvents.Option>empty()
                    : IntermissionEvents.Option.fromCard(draft.cards().get(i));
            int risk = modifier != null ? modifier.risk().ordinal() : -1;
            String theme = modifier != null ? com.cobbletowers.menu.ModifierArt.theme(modifier)
                    : option.isPresent() ? com.cobbletowers.menu.ModifierArt.EVENT : com.cobbletowers.menu.ModifierArt.UNKNOWN;
            List<String> lines = modifier != null ? com.cobbletowers.menu.ModifierMenuText.lines(modifier)
                    : List.of(name);
            if (draft.route() && modifier != null) {
                var stop = com.cobbletowers.route.RouteGraph.Kind.parse(draft.kinds().get(i));
                String then = stop.room().map(room -> "Then: " + com.cobbletowers.route.RouteText.roomName(room) + " room on the way.").orElse("Then: straight to the next battle.");
                lines = new ArrayList<>(lines);
                lines.add(then);
                String arena = draft.arenas().get(i);
                if (!arena.isEmpty()) {
                    net.minecraft.resources.ResourceLocation place = net.minecraft.resources.ResourceLocation.tryParse(arena);
                    com.cobbletowers.definition.TowerDefinition tower = place == null ? null : TowerDefinitionRegistry.content().towers().get(place);
                    if (tower != null) lines.add("Fought in " + tower.displayName() + ".");
                }
            }
            cards.add(new IntermissionStatePayload.Card(draft.cards().get(i), name, votes, risk, theme, lines));
        }
        return new IntermissionStatePayload.Draft(draft.resolved() ? 2 : 1, cards,
                draft.chosen().orElse(-1), draft.votes().getOrDefault(viewer, -1));
    }
}
