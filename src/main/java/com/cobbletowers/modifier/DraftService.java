package com.cobbletowers.modifier;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.encounter.EncounterSeed;
import com.cobbletowers.persistence.PersistedDraft;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Opens, takes votes on and settles a draft (TDS #2, #23, #57). Decisions are pure functions over a run; only a few
 * methods write.
 */
public final class DraftService {

    /** TDS #57: every fifth accumulated challenge produces a lock-in. */
    public static final int LOCK_IN_EVERY = 5;

    private DraftService() {}

    // ---------------------------------------------------------------- pure

    /** Whether the next draft is a Lock-In Draft: one per five challenges, derived rather than stored. */
    public static boolean lockInDue(RunModifierState state) {
        return state.challengeCount() / LOCK_IN_EVERY > state.lockedIn().size();
    }

    /** The seed a draft is drawn from and tie-broken with; the same seed so voting order cannot steer the tie. */
    public static long draftSeed(PersistedRun run, int floorIndex) {
        return EncounterSeed.of(run.seed(), floorIndex, DraftDraw.DRAFT_ORDINAL_BASE);
    }

    /** The modifiers a run is carrying, resolved against loaded content, in draft order. */
    public static List<ModifierDefinition> held(TowerContent content, RunModifierState state) {
        List<ModifierDefinition> held = new ArrayList<>(state.accumulated().size());
        for (ResourceLocation id : state.accumulated()) {
            content.modifier(id).ifPresent(held::add);
        }
        return List.copyOf(held);
    }

    /** The relics a run is carrying (P34), resolved against loaded content, in the order found. */
    public static List<ModifierDefinition> relicsHeld(TowerContent content, RunModifierState state) {
        List<ModifierDefinition> held = new ArrayList<>(state.relics().size());
        for (ResourceLocation id : state.relics()) content.modifier(id).ifPresent(held::add);
        return List.copyOf(held);
    }

    /**
     * The tag set bonuses (P45) a run has earned: each applies while the run holds at least its count of relics with its
     * tag. In stable (id) order; a bonus is never drafted and never counts as a challenge.
     */
    public static List<ModifierDefinition> setBonusesActive(TowerContent content, RunModifierState state) {
        java.util.Map<String, Integer> byTag = new java.util.HashMap<>();
        for (ModifierDefinition relic : relicsHeld(content, state)) {
            for (String tag : relic.tags()) byTag.merge(tag, 1, Integer::sum);
        }
        List<ModifierDefinition> active = new ArrayList<>();
        for (ModifierDefinition bonus : content.tagBonuses()) {
            var need = bonus.tagBonus().orElseThrow();
            if (byTag.getOrDefault(need.tag(), 0) >= need.count()) active.add(bonus);
        }
        return List.copyOf(active);
    }

    /** The seed a relic draft at this floor is drawn from, and its tie is broken with. */
    public static long relicSeed(PersistedRun run, int floorIndex) {
        return EncounterSeed.of(run.seed(), floorIndex, DraftDraw.RELIC_ORDINAL_BASE);
    }

    /** The relics offered after clearing this floor: milestone floors only, with room, and legal for the run. */
    public static List<ResourceLocation> relicCardsFor(TowerContent content, PersistedRun run, int floorIndex) {
        RunModifierState state = run.modifiers();
        if (!state.hasRelicRoom() || content.milestoneAt(run.towerId(), floorIndex).isEmpty()) return List.of();
        List<ModifierDefinition> pool = ModifierResolver.eligibleFrom(content.relicPool(),
                relicsHeld(content, state));
        List<ResourceLocation> cards = new ArrayList<>();
        for (ModifierDefinition card : DraftDraw.drawRelics(pool, run.seed(), floorIndex)) cards.add(card.id());
        return List.copyOf(cards);
    }

    /** What a run's modifiers add up to, with locked-in ones counted twice (that is what a lock-in does). */
    public static ModifierEffects effects(TowerContent content, RunModifierState state) {
        List<ModifierDefinition> counted = new ArrayList<>(held(content, state));
        for (ResourceLocation locked : state.lockedIn()) {
            content.modifier(locked).ifPresent(counted::add);
        }
        counted.addAll(relicsHeld(content, state));
        counted.addAll(setBonusesActive(content, state));
        return ModifierEffects.of(counted);
    }

    /**
     * What a run's CUSTOM modifiers (P29) reduce to; locked-in copies do not matter, a behavior is held or it is not.
     */
    public static CustomEffects customs(PersistedRun run) {
        TowerContent content = TowerDefinitionRegistry.content();
        List<ModifierDefinition> all = new ArrayList<>(held(content, run.modifiers()));
        all.addAll(relicsHeld(content, run.modifiers()));
        all.addAll(setBonusesActive(content, run.modifiers()));
        return CustomEffects.of(all);
    }

    /** A run's effects, read from whatever content is loaded now. */
    public static ModifierEffects effects(PersistedRun run) {
        int ascension = ascensionOf(run);
        return effects(TowerDefinitionRegistry.content(), run.modifiers())
                .withAscension(growthAscensionOf(run), ascension);
    }

    /**
     * The Ascension the old difficulty growth counts from (balance phase 2): every cycle for a run with no Threat band, only the cycles past Threat VII for
     * a run with one while AscensionLib has Threat on.
     */
    public static int growthAscensionOf(PersistedRun run) {
        int ascension = ascensionOf(run);
        if (!com.cobbletowers.economy.AscensionLibScouting.threatEnabled()) return ascension;
        return com.cobbletowers.ascension.ThreatPolicy.rampAscension(run.options().entryThreat(), ascension);
    }

    /** Which Ascension a run's current floor is in (0 for the base cycle, and for any tower that does not ascend). */
    public static int ascensionOf(PersistedRun run) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(run.towerId());
        return tower == null ? 0 : tower.ascensionOf(run.floorIndex());
    }

    /**
     * The cards offered at this floor, or empty. A lock-in draws from held modifiers (#57); an ordinary draft from
     * the floor's pool (#58).
     */
    public static List<ResourceLocation> cardsFor(TowerContent content, PersistedRun run, int floorIndex) {
        RunModifierState state = run.modifiers();
        List<ModifierDefinition> pool;
        if (lockInDue(state)) {
            // Only what is not already locked in: offering a locked modifier again would be a card
            // that changes nothing, and three of them would be a draft with no choice in it.
            pool = new ArrayList<>();
            for (ModifierDefinition candidate : held(content, state)) {
                if (!state.lockedIn().contains(candidate.id()) && !pool.contains(candidate)) pool.add(candidate);
            }
        } else {
            pool = ModifierResolver.eligibleFrom(
                    content.draftablePool(run.towerId(), floorIndex), held(content, state));
        }
        if (pool.isEmpty()) return List.of();

        List<ResourceLocation> cards = new ArrayList<>();
        for (ModifierDefinition card : DraftDraw.draw(pool, run.seed(), floorIndex)) cards.add(card.id());
        return List.copyOf(cards);
    }

    /**
     * Whether ordinary drafts are steps of the route (P50). Off with {@code -Dcobbletowers.route=off}, and whenever rooms are off
     * ({@code -Dcobbletowers.events=off}): the draft alone, as before.
     */
    public static boolean routeEnabled() {
        return com.cobbletowers.intermission.IntermissionEvents.enabled()
                && !"off".equalsIgnoreCase(System.getProperty("cobbletowers.route", "on"));
    }

    /** What a run's weekly rule and trial change about the stops of its route. */
    public static com.cobbletowers.route.RouteGraph.Rules routeRules(PersistedRun run) {
        var tower = TowerDefinitionRegistry.content().towers().get(run.towerId());
        return new com.cobbletowers.route.RouteGraph.Rules(com.cobbletowers.trial.TrialRules.hasRule(run, "no_healing_rooms"),
                com.cobbletowers.trial.TrialRules.hasRule(run, "relics_every_second_floor"), com.cobbletowers.trial.TrialRules.roomBias(run),
                tower != null && tower.window().isPresent());   // an event tower may have the Trick or Treat vault
    }

    /**
     * The step after this floor as a draft: the cards that ride on the lanes the party can reach, each a stop of some kind. The
     * kind a stop shows is what it will do: a room that could do nothing now (a Shrine with no relic room) is a plain battle.
     */
    static PersistedDraft routeDraft(TowerContent content, PersistedRun run, List<ResourceLocation> cards,
                                     java.util.function.ToLongFunction<UUID> balance) {
        int floor = run.floorIndex();
        var tower = content.towers().get(run.towerId());
        var spot = tower != null && tower.routeMap() ? mapSpot(content, tower, run) : null;
        var kinds = spot != null ? List.<com.cobbletowers.route.RouteGraph.Kind>of()
                : com.cobbletowers.route.RouteGraph.kindsAt(run.seed(), floor, routeRules(run));
        List<ResourceLocation> offered = new ArrayList<>();
        List<Integer> lanes = new ArrayList<>();
        List<String> named = new ArrayList<>();
        List<String> arenas = new ArrayList<>();
        List<String> places = new ArrayList<>();
        int detour = detourFor(content, run, floor, places);
        List<Integer> reach = new ArrayList<>(spot != null ? spot.reachable() : com.cobbletowers.route.RouteGraph.reachable(run.modifiers().lastLane()));
        // the map names the stop of a lane; the per-step draw names all of them up front
        java.util.function.IntFunction<com.cobbletowers.route.RouteGraph.Kind> kindAt = lane -> spot != null
                ? com.cobbletowers.route.RouteMapView.kindOf(spot.map().stopAt(spot.row() + 1, lane)) : kinds.get(lane);
        if (reach.stream().noneMatch(lane -> lane < cards.size() && (spot != null || lane < kinds.size()))) reach = List.of(0);
        for (int lane : reach) {
            if (lane >= cards.size() || (spot == null && lane >= kinds.size())) continue;
            var kind = kindAt.apply(lane);
            if (kind.room().isPresent() && !com.cobbletowers.intermission.IntermissionEvents.canOffer(content, run, floor, kind.room().get(), balance)) {
                kind = com.cobbletowers.route.RouteGraph.Kind.BATTLE;
            }
            offered.add(cards.get(lane));
            lanes.add(lane);
            named.add(kind.name());
            arenas.add(lane == detour ? places.get(0) : "");
        }
        return PersistedDraft.openingRoute(floor, offered, lanes, named, arenas);
    }

    /** Where the run stands on its tower's branching map (P56); only for a tower with {@code route_map}. */
    public static com.cobbletowers.route.RouteMapView.Spot mapSpot(TowerContent content, com.cobbletowers.definition.TowerDefinition tower, PersistedRun run) {
        return com.cobbletowers.route.RouteMapView.at(run.seed(), run.floorIndex(), tower.floorCount(), tower.window().isPresent(),
                row -> content.milestoneAt(run.towerId(), row).isPresent(), run.modifiers()::laneAt);
    }

    /**
     * The lane of this step whose battle is a detour to another region's building (-1 for none); {@code places} gets that building's tower id.
     * No detour for a trial (its floors are the shared route), a tower that is not in the catalog, a walkable tower (its floors have a stair), or when the next floor is a milestone.
     */
    static int detourFor(TowerContent content, PersistedRun run, int floor, List<String> places) {
        if (run.options().isTrial() || !content.towers().containsKey(run.towerId())) return -1;
        // A walkable tower is climbed, not left: no lane may send the party to another building and back.
        if (content.floorAt(run.towerId(), floor).flatMap(f -> f.layout()).map(layout -> layout.walkable()).orElse(false)) return -1;
        if (content.milestoneAt(run.towerId(), floor + 1).isPresent() || content.floorAt(run.towerId(), floor + 1).flatMap(f -> f.layout()).isEmpty()) return -1;
        int lane = com.cobbletowers.route.RouteGraph.detourLane(run.seed(), floor);
        if (lane < 0) return -1;
        List<ResourceLocation> others = new ArrayList<>();
        for (var tower : content.catalogTowers()) {
            if (tower.id().equals(run.towerId()) || tower.id().getPath().equals("test") || tower.window().isPresent()) continue;
            if (content.floorAt(tower.id(), floor + 1).flatMap(f -> f.layout()).isPresent()) others.add(tower.id());
        }
        others.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        int pick = com.cobbletowers.route.RouteGraph.detourPlace(run.seed(), floor, others.size());
        if (pick < 0) return -1;
        places.add(others.get(pick).toString());
        return lane;
    }

    /** Who is entitled to vote: everybody still in the run who could fight (TDS #23). */
    public static List<UUID> voters(PersistedRun run) {
        List<UUID> voters = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) {
            if (participant.state().canFight()) voters.add(participant.playerId());
        }
        return List.copyOf(voters);
    }

    /** Whether everybody entitled to vote has. */
    public static boolean everybodyVoted(PersistedRun run, PersistedDraft draft) {
        List<UUID> voters = voters(run);
        if (voters.isEmpty()) return true;
        for (UUID voter : voters) {
            if (!draft.votes().containsKey(voter)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------- writing

    /**
     * Opens a draft if the run should have one. Called on arrival at INTERMISSION; idempotent, so an open draft keeps
     * its votes.
     */
    public static Optional<PersistedDraft> open(MinecraftServer server, UUID runId, long now) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return Optional.empty();
        PersistedRun run = found.get();
        if (run.modifiers().draft().isPresent() && !run.modifiers().draft().get().resolved()) {
            return run.modifiers().draft();
        }

        TowerContent content = TowerDefinitionRegistry.content();
        List<ResourceLocation> cards = cardsFor(content, run, run.floorIndex());
        if (cards.isEmpty()) {
            // Nothing to draft, but a milestone may still have a relic to give.
            List<ResourceLocation> relics = relicCardsFor(content, run, run.floorIndex());
            if (!relics.isEmpty()) {
                PersistedDraft relicDraft = PersistedDraft.openingRelics(run.floorIndex(), relics);
                TowerRuns.save(server, run.withModifiers(run.modifiers().withDraft(relicDraft), now), true);
                TowerLog.info("Run {} opened a RELIC draft at floor {}: {}", runId, run.floorIndex(), relics);
                return Optional.of(relicDraft);
            }
            TowerLog.info("Run {} has no modifier to draft at floor {}", runId, run.floorIndex());
            return Optional.empty();
        }

        boolean lockIn = lockInDue(run.modifiers());
        // a lock-in picks from what is held, and a milestone pays a relic: neither is a step of the route
        boolean step = routeEnabled() && !lockIn && content.milestoneAt(run.towerId(), run.floorIndex()).isEmpty();
        PersistedDraft draft = step ? routeDraft(content, run, cards, balances(server)) : PersistedDraft.opening(run.floorIndex(), lockIn, cards);
        // Checkpointed, because TDS #145 lists intermission among the forced checkpoints and a draft
        // lost to a crash would come back as three different cards.
        TowerRuns.save(server, run.withModifiers(run.modifiers().withDraft(draft), now), true);
        TowerLog.info("Run {} opened a{} draft at floor {}: {}{}", runId, lockIn ? " LOCK-IN" : "",
                run.floorIndex(), cards, draft.route() ? " stops " + draft.kinds() : "");
        return Optional.of(draft);
    }

    /** Records a vote and settles once everybody has voted. @return the draft, or empty when none is open */
    public static Optional<PersistedDraft> vote(MinecraftServer server, UUID runId, UUID playerId,
                                                int cardIndex, long now) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return Optional.empty();
        PersistedRun run = found.get();
        Optional<PersistedDraft> open = run.modifiers().draft().filter(draft -> !draft.resolved());
        if (open.isEmpty()) return Optional.empty();
        if (cardIndex < 0 || cardIndex >= open.get().cards().size()) return open;

        PersistedDraft voted = open.get().withVote(playerId, cardIndex);
        TowerRuns.save(server, run.withModifiers(run.modifiers().withDraft(voted), now), false);

        if (everybodyVoted(run, voted)) return Optional.of(settle(server, runId, now));
        return Optional.of(voted);
    }

    /**
     * Settles the open draft whether or not everybody voted; an unvoted draft still resolves (see {@link DraftVote}).
     */
    public static PersistedDraft settle(MinecraftServer server, UUID runId, long now) {
        PersistedRun run = TowerRuns.get(runId).orElseThrow(
                () -> new IllegalStateException("no run " + runId));
        PersistedDraft draft = run.modifiers().draft()
                .orElseThrow(() -> new IllegalStateException("run " + runId + " has no draft to settle"));
        if (draft.resolved()) return draft;

        DraftVote.Result result = DraftVote.resolve(draft.votes(), draft.cards().size(),
                draft.event() ? com.cobbletowers.intermission.IntermissionEvents.seedOf(run, draft.floorIndex())
                        : draft.relic() ? relicSeed(run, draft.floorIndex()) : draftSeed(run, draft.floorIndex()));
        PersistedDraft resolved = draft.resolvedAs(result.cardIndex(), result.byTieBreak());
        ResourceLocation won = resolved.cards().get(result.cardIndex());
        String kind = draft.event() ? "event" : draft.relic() ? "relic" : "draft";
        for (ResourceLocation card : resolved.cards()) com.cobbletowers.mastery.TuningCounters.bump(server, kind + "_offered." + card);
        com.cobbletowers.mastery.TuningCounters.bump(server, kind + "_taken." + won);

        if (draft.event()) {
            return settleEvent(server, run, runId, resolved, won, result.byTieBreak(), now);
        }

        RunModifierState next = draft.relic()
                ? run.modifiers().withRelic(won, draft.floorIndex() + 1).withDraft(resolved)
                : draft.lockIn()
                        ? run.modifiers().lockingIn(won).withDraft(resolved)
                        : run.modifiers().accumulating(won, draft.floorIndex() + 1).withDraft(resolved);

        if (draft.route()) {
            next = next.withRouteStep(draft.floorIndex(), draft.lanes().get(result.cardIndex()), draft.kinds().get(result.cardIndex()));
            // A walkable tower: the door of the chosen lane lights up and opens, but only once every pick is made (openDoorIfDone): a lane whose room
            // asks something of the party keeps its door shut until the party has answered, so nobody stands at an open door with a pick still to make.
            String arena = draft.arenas().get(result.cardIndex());
            if (!arena.isEmpty()) {
                ResourceLocation place = ResourceLocation.tryParse(arena);
                if (place != null) next = next.withArena(draft.floorIndex() + 1, place);
            }
            TowerLog.info("Run {} took lane {} ({}) at floor {}", runId, draft.lanes().get(result.cardIndex()),
                    draft.kinds().get(result.cardIndex()), draft.floorIndex());
        }

        if (draft.relic()) {
            PersistedRun afterRelic = run.withModifiers(next, now);
            TowerLog.info("Run {} found relic {} at floor {}{}", runId, won, draft.floorIndex(),
                    result.byTieBreak() ? " on a seed tie-break" : "");
            // A regional milestone may also offer an Echo Duel, once the relic is chosen.
            Optional<com.cobbletowers.intermission.IntermissionEvents.Room> duel =
                    com.cobbletowers.intermission.IntermissionEvents.roomFor(TowerDefinitionRegistry.content(), afterRelic,
                            draft.floorIndex(), com.cobbletowers.echo.EchoService.duelAvailable(server, afterRelic, draft.floorIndex()),
                            balances(server));
            if (duel.isPresent()) {
                afterRelic = afterRelic.withModifiers(next.withDraft(PersistedDraft.openingEvent(draft.floorIndex(),
                        com.cobbletowers.intermission.IntermissionEvents.cardsOf(duel.get()))), now);
                TowerLog.info("Run {} opened an EVENT room ({}) at floor {}", runId, duel.get(), draft.floorIndex());
            }
            TowerRuns.save(server, afterRelic, true);
            openDoorIfDone(server, afterRelic, draft.floorIndex());
            return resolved;
        }

        // A milestone boss also pays a relic: once the challenge is settled, put the relics on the table.
        // The run cannot leave the intermission while a draft is open, so the relic choice cannot be skipped.
        TowerContent content = TowerDefinitionRegistry.content();
        PersistedRun after = run.withModifiers(next, now);
        List<ResourceLocation> relics = relicCardsFor(content, after, draft.floorIndex());
        if (!relics.isEmpty()) {
            after = after.withModifiers(next.withDraft(PersistedDraft.openingRelics(draft.floorIndex(), relics)), now);
            TowerLog.info("Run {} opened a RELIC draft at floor {}: {}", runId, draft.floorIndex(), relics);
        } else {
            // No relic to give: a non-milestone floor may have an event room instead.
            boolean echo = com.cobbletowers.echo.EchoService.duelAvailable(server, after, draft.floorIndex());
            // a route step opens the room of the stop that was taken; a draft before routes rolls one as it always did
            Optional<com.cobbletowers.intermission.IntermissionEvents.Room> room = draft.route()
                    ? com.cobbletowers.intermission.IntermissionEvents.roomForNode(content, after, draft.floorIndex(), echo, balances(server),
                            com.cobbletowers.route.RouteGraph.Kind.parse(draft.kinds().get(result.cardIndex())).room())
                    : com.cobbletowers.intermission.IntermissionEvents.roomFor(content, after, draft.floorIndex(), echo, balances(server));
            if (room.isPresent()) {
                after = after.withModifiers(next.withDraft(PersistedDraft.openingEvent(draft.floorIndex(),
                        com.cobbletowers.intermission.IntermissionEvents.cardsOf(room.get()))), now);
                TowerLog.info("Run {} opened an EVENT room ({}) at floor {}", runId, room.get(), draft.floorIndex());
            }
        }
        TowerRuns.save(server, after, true);
        openDoorIfDone(server, after, draft.floorIndex());
        TowerLog.info("Run {} drafted {}{} at floor {}{}", runId, won, draft.lockIn() ? " (LOCKED IN)" : "",
                draft.floorIndex(), result.byTieBreak() ? " on a seed tie-break" : "");
        TowerDefinitionRegistry.content().modifier(won).ifPresent(modifier ->
                com.cobbletowers.events.TowerEvents.emit(new com.cobbletowers.events.TowerEvent.Drafted(runId,
                        run.participants().stream().map(PersistedParticipant::playerId).toList(), won, modifier.risk())));
        return resolved;
    }

    /** What each player has in their CobbleDollars wallet, for the rooms that charge (P45). */
    private static java.util.function.ToLongFunction<UUID> balances(MinecraftServer server) {
        var wallets = com.cobbletowers.persistence.TowerWalletStore.get(server);
        return wallets::balanceOf;
    }

    /** Applies the option an event room's vote chose (P34b). */
    private static PersistedDraft settleEvent(MinecraftServer server, PersistedRun run, UUID runId,
                                              PersistedDraft resolved, ResourceLocation won, boolean tie, long now) {
        var outcome = com.cobbletowers.intermission.IntermissionEvents.Option.fromCard(won).map(option ->
                com.cobbletowers.intermission.IntermissionEvents.resolve(TowerDefinitionRegistry.content(), run,
                        resolved.floorIndex(), option, balances(server)));
        RunModifierState base = outcome.map(com.cobbletowers.intermission.IntermissionEvents.Outcome::state)
                .orElse(run.modifiers());
        TowerRuns.save(server, run.withModifiers(base.withDraft(resolved), now), true);
        TowerLog.info("Run {} event choice {} at floor {}{}: {}", runId, won, resolved.floorIndex(),
                tie ? " on a seed tie-break" : "", outcome.map(com.cobbletowers.intermission.IntermissionEvents.Outcome::message).orElse("unknown option"));
        if (com.cobbletowers.intermission.IntermissionEvents.Option.fromCard(won)
                .filter(option -> option == com.cobbletowers.intermission.IntermissionEvents.Option.TRICK_OPEN).isPresent()) {
            com.cobbletowers.trick.TrickOrTreatService.open(server, run.withModifiers(base.withDraft(resolved), now), resolved.floorIndex());
            return resolved;
        }
        if (outcome.isPresent() && outcome.get().duel()) {
            com.cobbletowers.echo.EchoService.beginDuel(server, run.withModifiers(base.withDraft(resolved), now),
                    resolved.floorIndex());
            return resolved;
        }
        // The run is saved first: a crash between the two leaves a free relic, never a paid-for nothing.
        outcome.filter(o -> o.walletEach() != 0).ifPresent(o -> {
            var wallets = com.cobbletowers.persistence.TowerWalletStore.get(server);
            for (PersistedParticipant participant : run.participants()) {
                if (o.walletEach() > 0) wallets.credit(participant.playerId(), o.walletEach());
                else wallets.debit(participant.playerId(), -o.walletEach());
            }
            wallets.checkpoint(server);
        });
        outcome.ifPresent(o -> {
            for (PersistedParticipant participant : run.participants()) {
                net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
                if (player == null) continue;
                if (o.healParty()) com.cobbletowers.economy.VendorServices.apply(
                        com.cobbletowers.definition.VendorEffect.FULL_HEAL, player);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(o.message()));
            }
        });
        openDoorIfDone(server, run.withModifiers(base.withDraft(resolved), now), resolved.floorIndex());
        return resolved;
    }

    /**
     * Whether the door of the lane taken on {@code floorIndex} may open now: a lane was taken, and no pick is still open (an event room's choice or a
     * relic draft is another pick). A battle lane has nothing more to ask, so its door opens as soon as the lane is chosen, as it always did.
     */
    public static boolean doorMayOpen(RunModifierState state, int floorIndex) {
        return state.laneAt(floorIndex) >= 0 && !state.hasOpenDraft();
    }

    /** Opens the door of the lane taken on this floor if every pick is made. Never throws: the vote must not fail over a door. */
    private static void openDoorIfDone(MinecraftServer server, PersistedRun run, int floorIndex) {
        if (!doorMayOpen(run.modifiers(), floorIndex)) return;
        com.cobbletowers.route.TowerDoors.chosen(server, run, run.modifiers().laneAt(floorIndex));
    }

    /** Clears a settled draft as the run leaves its intermission; an open one stays and blocks it. */
    public static void clearIfSettled(MinecraftServer server, UUID runId, long now) {
        TowerRuns.get(runId).ifPresent(run -> {
            if (run.modifiers().draft().isPresent() && run.modifiers().draft().get().resolved()) {
                TowerRuns.save(server, run.withModifiers(run.modifiers().withoutDraft(), now), false);
            }
        });
    }
}
