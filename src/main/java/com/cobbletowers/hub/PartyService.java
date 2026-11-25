package com.cobbletowers.hub;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.economy.AscensionLibScouting;
import com.cobbletowers.lobby.LobbyService;
import com.cobbletowers.network.ClientUi;
import com.cobbletowers.network.PartyHudPayload;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Tower Hub's parties in the world (owner, 20 Nov 2026), everything {@link PartyBook} does not: the gestures, the warp pad's ring, the
 * gates, the blocks that light and the roster card on each player's HUD. Server thread only; in memory only.
 * <ul>
 *   <li>Sneak + right-click a player in the hub: invite, accept, or (a leader) remove.</li>
 *   <li>Stand on the warp pad with others for {@value #PAD_DWELL} ms: one party (not in the {@value #ARRIVAL_GRACE} ms after arriving).</li>
 *   <li>Sneak + right-click the pad's centre stone: leave. Away from the hub (and not in a run) for {@value #AWAY_MILLIS} ms: leave.</li>
 *   <li>A party near a gate holds it, and its Gate Altar takes the party's picks: members on the altar's ring are ready (a strip lights
 *       for each), the leader's clicks on the altar step the Threat runes (after VI back to none), every member at the gate votes on a
 *       crystal, First floor or Best (a tie goes the leader's way), and the leader walking into the portal takes the ready members in
 *       ({@link #walkIn}).</li>
 * </ul>
 */
public final class PartyService {

    private static final int EVERY = 4;
    static final long ARRIVAL_GRACE = 5_000L;
    static final long PAD_DWELL = 3_000L;
    static final long AWAY_MILLIS = 60_000L;
    /** A gate nobody of its party has stood near for this long is free for another party. */
    static final long GATE_RELEASE = 5_000L;
    /** How far either side of the portal's mouth (along the gate's face) still counts as being at the gate. */
    private static final int NEAR = 6;
    /** Within this many blocks of a gate's altar is being at that gate. */
    private static final int ALTAR_NEAR = 9;

    static final PartyBook BOOK = new PartyBook();

    /** A party holding a gate: when one of it was last at the gate, and when it last did anything there (a click, a step on the ring). */
    private record Hold(Party party, long lastSeen, long lastActive) {}

    /**
     * A holding party that has done nothing at its gate this long gives the gate up to another party standing on the altar's ring (audit,
     * 25 Nov 2026: one idle player could keep a gate from everyone).
     */
    static final long IDLE_RELEASE = 60_000L;
    /** Each player's last altar click, in server ticks: a click is answered at most once per {@value #ALTAR_CLICK_TICKS} ticks. */
    private static final Map<UUID, Integer> ALTAR_CLICKED = new HashMap<>();
    private static final int ALTAR_CLICK_TICKS = 5;
    /** Who has been told about an invite from whom, and when: the chat line is sent once per pair per {@value #INVITE_NOTE_MILLIS} ms. */
    private static final Map<UUID, Map<UUID, Long>> INVITE_NOTED = new HashMap<>();
    private static final long INVITE_NOTE_MILLIS = 15_000L;

    /** The holding party did something at this gate: its hold is kept from going idle. */
    private static void active(HubLayout.Portal portal, UUID who) {
        Hold hold = HOLDS.get(portal.tower());
        if (hold != null && hold.party().contains(who)) HOLDS.put(portal.tower(), new Hold(hold.party(), hold.lastSeen(), System.currentTimeMillis()));
    }

    /** {@link #active} for a gate named by its tower (the draft board reports its own clicks). */
    static void active(ResourceLocation tower, UUID who) {
        Hold hold = HOLDS.get(tower);
        if (hold != null && hold.party().contains(who)) HOLDS.put(tower, new Hold(hold.party(), hold.lastSeen(), System.currentTimeMillis()));
    }

    private static final Map<UUID, Long> ARRIVED = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> LAST_DIM = new HashMap<>();
    private static final Map<UUID, Long> ON_PAD_SINCE = new LinkedHashMap<>();
    private static final Map<UUID, Long> AWAY_SINCE = new HashMap<>();
    private static final Map<ResourceLocation, Hold> HOLDS = new HashMap<>();
    private static final Map<UUID, PartyHudPayload> SENT = new HashMap<>();
    private static final Map<UUID, Integer> CLICKED = new HashMap<>();
    /** Each gate's altar, drawn while its chunk is loaded. */
    private static final Map<ResourceLocation, GateAltar> ALTARS = new HashMap<>();
    /** Each party's Ascension votes: true for Best, false for First floor. */
    private static final Map<Party, Map<UUID, Boolean>> VOTES = new HashMap<>();
    /**
     * Who is ready, and at which gate (owner's test, 20 Nov 2026): stepping on the altar's ring makes a member ready, and they stay ready
     * while they are anywhere at that gate, so the party can set its run at the altar and then walk to the portal together.
     */
    private static final Map<UUID, ResourceLocation> READY = new HashMap<>();
    /** Who is ready at a trial shrine, and which ("daily" / "weekly"): stepping on its ring, kept while near it. */
    private static final Map<UUID, String> TRIAL_READY = new HashMap<>();
    /** Each shrine's plate. */
    private static final Map<String, TrialPlate> PLATES = new HashMap<>();
    private static final Map<String, BlockState> STATES = new HashMap<>();

    private PartyService() {}

    /** The built hub, if there is one and its world is loaded. */
    record Hub(ServerLevel level, BlockPos origin, HubLayout layout) {
        BlockPos rel(BlockPos world) {
            return world.subtract(origin);
        }
    }

    static Optional<Hub> hub(MinecraftServer server) {
        Optional<HubStore.Placed> placed = HubStore.get(server).placed();
        if (placed.isEmpty()) return Optional.empty();
        return HubPortals.level(server, placed.get()).map(level -> new Hub(level, placed.get().origin(), HubLayout.load()));
    }

    public static Optional<Party> partyOf(UUID player) {
        return BOOK.partyOf(player);
    }

    public static void install() {
        AltarModels.bootstrap();                            // the altar's models are requested before Polymer builds the pack
        TrophyModels.bootstrap();                           // and the Records Hall trophy cases' too
        SeasonChestModels.bootstrap();                      // and its Season Chest's
        DraftModels.bootstrap();                            // and the Rental Draft's cards at the gates
        UseEntityCallback.EVENT.register(PartyService::onUseEntity);
        UseBlockCallback.EVENT.register(PartyService::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EVERY != 0) return;
            try {
                tick(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("The Tower Hub's party tick failed", ex);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.execute(() -> {
                BOOK.forgetInvites(id);
                LAST_POS.remove(id);
                LAST_DIM.remove(id);
                ON_PAD_SINCE.remove(id);
                SENT.remove(id);
                CLICKED.remove(id);
                ALTAR_CLICKED.remove(id);
                ARRIVED.remove(id);
                INVITE_NOTED.remove(id);
                INVITE_NOTED.values().forEach(m -> m.remove(id));
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clear());
    }

    static void clear() {
        BOOK.clear();
        ARRIVED.clear();
        LAST_POS.clear();
        LAST_DIM.clear();
        ON_PAD_SINCE.clear();
        AWAY_SINCE.clear();
        HOLDS.clear();
        SENT.clear();
        CLICKED.clear();
        ALTAR_CLICKED.clear();
        INVITE_NOTED.clear();
        ALTARS.values().forEach(GateAltar::destroy);
        ALTARS.clear();
        VOTES.clear();
        READY.clear();
        TRIAL_READY.clear();
        PLATES.values().forEach(TrialPlate::destroy);
        PLATES.clear();
        RecordsHall.clear();
        GateDrafts.clear();
    }

    // ---- gestures ------------------------------------------------------------------------------------------------------------------

    private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer actor)
                || !(entity instanceof ServerPlayer target) || !actor.isShiftKeyDown()) return InteractionResult.PASS;
        MinecraftServer server = actor.server;
        Optional<Hub> hub = hub(server);
        if (hub.isEmpty() || world != hub.get().level()) return InteractionResult.PASS;
        // the client sends an interact-at and then an interact for one click: answer the first only
        Integer last = CLICKED.put(actor.getUUID(), server.getTickCount());
        if (last != null && server.getTickCount() - last < 6) return InteractionResult.SUCCESS;
        if (inRun(actor.getUUID()) || inRun(target.getUUID())) return InteractionResult.PASS;
        PartyBook.Result result = BOOK.click(actor.getUUID(), target.getUUID(), System.currentTimeMillis());
        String them = name(target), me = name(actor);
        switch (result.click()) {
            case INVITED, REINVITED -> {
                bar(actor, "Invited " + them + ". They sneak + right-click you to join.");
                // told once per pair per 15 s (audit, 25 Nov 2026: a repeated sneak-click put a line in their chat every few ticks)
                long nowMs = System.currentTimeMillis();
                Map<UUID, Long> noted = INVITE_NOTED.computeIfAbsent(target.getUUID(), k -> new HashMap<>());
                Long noteAt = noted.get(actor.getUUID());
                if (noteAt == null || nowMs - noteAt >= INVITE_NOTE_MILLIS) {
                    noted.put(actor.getUUID(), nowMs);
                    target.sendSystemMessage(Component.literal(me + " invited you to their party: sneak + right-click them to join (a minute)."));
                    bar(target, me + " invited you: sneak + right-click them to join");
                }
            }
            case JOINED -> {
                for (UUID id : result.party().members()) {
                    ServerPlayer member = server.getPlayerList().getPlayer(id);
                    if (member != null) bar(member, me + " joined " + (id.equals(actor.getUUID()) ? name(server, result.party().leader()) + "'s" : "the") + " party");
                }
                actor.sendSystemMessage(Component.literal("You joined " + them + "'s party. Sneak + right-click the warp pad's centre stone to leave."));
            }
            case REMOVED -> {
                bar(actor, "Removed " + them + " from the party.");
                target.sendSystemMessage(Component.literal(me + " removed you from the party."));
            }
            case NOT_LEADER -> bar(actor, "Only your party's leader invites and removes.");
            case YOU_ARE_IN_A_PARTY -> bar(actor, "Leave your party first: sneak + right-click the warp pad's centre stone.");
            case THEY_ARE_IN_A_PARTY -> bar(actor, them + " is already in a party.");
            case FULL -> bar(actor, "A party is at most " + Party.MAX + " players.");
            case SELF -> { }
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer actor)) return InteractionResult.PASS;
        Optional<Hub> found = hub(actor.server);
        if (found.isEmpty() || world != found.get().level()) return InteractionResult.PASS;
        Hub hub = found.get();
        BlockPos rel = hub.rel(hit.getBlockPos());
        HubLayout.Spot pad = hub.layout().pad();
        if (rel.getX() == pad.x() && rel.getY() == pad.y() - 1 && rel.getZ() == pad.z()) {
            if (!actor.isShiftKeyDown()) return InteractionResult.PASS;
            leave(actor.server, actor.getUUID(), "You left the party.", " left the party.");
            return InteractionResult.SUCCESS;
        }
        if (!world.getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.BARRIER)) return InteractionResult.PASS;
        for (HubLayout.Portal portal : hub.layout().portals()) {        // a Gate Altar and its boards: their collision takes the clicks
            GateAltar altar = ALTARS.get(portal.tower());
            if (altar == null) continue;
            GateAltar.Hit part = altar.hitAt(hit.getLocation());
            if (part == null) continue;
            // one click per 5 ticks per player (audit, 25 Nov 2026: a modified game could spam the altar's chime and burst for everyone)
            int tick = actor.server.getTickCount();
            Integer last = ALTAR_CLICKED.get(actor.getUUID());
            if (last != null && tick - last < ALTAR_CLICK_TICKS) return InteractionResult.SUCCESS;
            ALTAR_CLICKED.put(actor.getUUID(), tick);
            switch (part.part()) {
                case THREAT -> clickThreat(actor, portal);
                case FIRST -> clickVote(actor, portal, false);
                case BEST -> clickVote(actor, portal, true);
                case TILE -> clickMode(actor, portal, AltarModels.layout().tiles().modes().get(part.tile()));
                case RULES -> {
                    if (!GateDrafts.click(actor, portal.tower(), hit.getLocation())) {
                        bar(actor, "The rules of the mode your party chose: pick one on the board across the path.");
                    }
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static void leave(MinecraftServer server, UUID id, String toThem, String toRest) {
        Optional<Party> left = BOOK.leave(id);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (left.isEmpty()) {
            if (player != null) bar(player, "You are not in a party.");
            return;
        }
        if (player != null) player.sendSystemMessage(Component.literal(toThem));
        String who = name(server, id);
        for (UUID rest : left.get().members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(rest);
            if (member != null) bar(member, who + toRest + (left.get().size() < 2 ? " The party ended." : ""));
        }
        if (PartyBook.dissolved(left.get())) HOLDS.values().removeIf(h -> h.party() == left.get());
    }

    /** The party holding this gate, if the player is in it; otherwise says why the altar does nothing. */
    private static Optional<Party> holder(ServerPlayer actor, HubLayout.Portal portal) {
        Optional<Party> party = BOOK.partyOf(actor.getUUID());
        Hold hold = HOLDS.get(portal.tower());
        boolean free = hold == null || PartyBook.dissolved(hold.party()) || !BOOK.parties().contains(hold.party());
        if (party.isEmpty()) {
            // a player alone who clicks a free gate's altar or boards takes the gate as a party of one, as stepping on its ring does
            // (owner, 25 Nov 2026): a click is a deliberate act, so it claims nothing for anyone just passing by
            if (!free || inRun(actor.getUUID())) {
                bar(actor, "Another party is at this gate: wait for them to go in.");
                return Optional.empty();
            }
            long now = System.currentTimeMillis();
            Party solo = BOOK.solo(actor.getUUID());
            HOLDS.put(portal.tower(), new Hold(solo, now, now));
            return Optional.of(solo);
        }
        if (hold == null || hold.party() != party.get()) {
            bar(actor, hold == null ? "Bring your party to this gate first." : "Another party is at this gate.");
            return Optional.empty();
        }
        return party;
    }

    /** The altar's Threat runes: the leader's click steps the Threat up one; after VI it goes back to none. */
    static void clickThreat(ServerPlayer actor, HubLayout.Portal portal) {
        if (!AscensionLibScouting.threatEnabled()) {
            bar(actor, "Threat bands are not switched on on this server.");
            return;
        }
        Optional<Party> party = holder(actor, portal);
        if (party.isEmpty()) return;
        if (!party.get().leader().equals(actor.getUUID())) {
            bar(actor, "Your leader sets the Threat. Vote on a crystal: First floor or Best.");
            return;
        }
        active(portal, actor.getUUID());
        int value = (party.get().threat() + 1) % (com.cobbletowers.ascension.ThreatPolicy.MAX_ENTRY + 1);
        party.get().setThreat(value);
        bar(actor, value == 0 ? "No Threat band: the tower uses its floor tiers." : "Threat " + LobbyService.threatNumeral(value)
                + " (advised " + LobbyService.threatNumeral(AscensionLibScouting.recommendedThreat(party.get().members())) + ")");
    }

    /** A mode tile: the leader picks the party's mode for this gate's run (Solo Gauntlet only for a party of one). */
    static void clickMode(ServerPlayer actor, HubLayout.Portal portal, String mode) {
        Optional<Party> party = holder(actor, portal);
        if (party.isEmpty()) return;
        if (!party.get().leader().equals(actor.getUUID())) {
            bar(actor, "Your leader picks the mode.");
            return;
        }
        Optional<com.cobbletowers.definition.PlaylistDefinition> playlist = playlistOf(mode);
        if (!mode.equals("standard") && playlist.isEmpty()) {
            bar(actor, GateAltar.MODE_NAMES.getOrDefault(mode, mode) + " is not on this server.");
            return;
        }
        if (playlist.isPresent() && playlist.get().maxPlayers() > 0 && party.get().size() > playlist.get().maxPlayers()) {
            bar(actor, playlist.get().displayName() + " is for " + playlist.get().maxPlayers() + " player"
                    + (playlist.get().maxPlayers() == 1 ? "" : "s") + ": your party is larger.");
            return;
        }
        active(portal, actor.getUUID());
        party.get().setMode(mode);
        bar(actor, "Mode: " + GateAltar.MODE_NAMES.getOrDefault(mode, mode) + (playlist.map(p -> ". " + p.description()).orElse(".")));
    }

        /** Whether a party's mode is a Rental Draft (its playlist lends the team). */
    static boolean rental(String mode) {
        return playlistOf(mode).map(com.cobbletowers.definition.PlaylistDefinition::rental).orElse(false);
    }

static Optional<com.cobbletowers.definition.PlaylistDefinition> playlistOf(String mode) {
        if (mode.equals("standard")) return Optional.empty();
        return com.cobbletowers.definition.PlaylistRegistry.get(com.cobbletowers.CobbleTowers.id(mode));
    }

    /** An altar crystal: a member's vote, for the party's best Ascension ({@code best}) or the first floor. */
    static void clickVote(ServerPlayer actor, HubLayout.Portal portal, boolean best) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(portal.tower());
        if (tower == null || !tower.ascension()) {
            bar(actor, towerName(portal.tower()) + " does not ascend: every run starts from the first floor.");
            return;
        }
        Optional<Party> party = holder(actor, portal);
        if (party.isEmpty()) return;
        if (best && LobbyService.maxAscension(actor.server, portal.tower(), party.get().members()) == 0) {
            bar(actor, "Nobody's best is above the first floor yet: everyone must have reached an Ascension for it.");
            return;
        }
        active(portal, actor.getUUID());
        Map<UUID, Boolean> votes = VOTES.computeIfAbsent(party.get(), k -> new HashMap<>());
        votes.put(actor.getUUID(), best);
        bar(actor, "You voted for " + (best ? "your party's best Ascension." : "the first floor."));
    }

    /** The members of the party standing at this gate, the leader first. */
    private static List<UUID> atGate(MinecraftServer server, Hub hub, HubLayout.Portal portal, Party party) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : party.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null && member.level() == hub.level() && near(hub, portal, member)) out.add(id);
        }
        return out;
    }

    /**
     * The Ascension pick: Best when more of the voters at the gate chose it, First floor when more chose that, a tie goes the leader's way
     * (First floor if the leader has not voted). {@code settled} once everyone at the gate has voted.
     */
    record Pick(boolean best, int forFirst, int forBest, int voters, boolean settled) {}

    static Pick pick(MinecraftServer server, Hub hub, HubLayout.Portal portal, Party party) {
        List<UUID> here = atGate(server, hub, portal, party);
        Map<UUID, Boolean> votes = VOTES.getOrDefault(party, Map.of());
        int first = 0, best = 0;
        for (UUID id : here) {
            Boolean v = votes.get(id);
            if (v == null) continue;
            if (v) best++;
            else first++;
        }
        boolean leaderBest = Boolean.TRUE.equals(votes.get(party.leader()));
        boolean pickBest = best > first || (best == first && best > 0 && leaderBest);
        return new Pick(pickBest, first, best, here.size(), !here.isEmpty() && first + best == here.size());
    }

    // ---- going in ------------------------------------------------------------------------------------------------------------------

    /** A party member walked into a portal (and was stepped back). The leader takes everyone gathered at the gate in. */
    static void walkIn(MinecraftServer server, Hub hub, ServerPlayer player, HubLayout.Portal portal, Party party) {
        active(portal, player.getUUID());
        if (!party.leader().equals(player.getUUID())) {
            bar(player, "Your leader takes the party in: wait here at the gate.");
            return;
        }
        if (LobbyService.lobbyOf(player.getUUID()).filter(l -> l.counting()).isPresent()) {
            bar(player, "Your party is already going in.");
            return;
        }
        // a Rental Draft lobby made by an earlier walk-in: this walk-in starts it once everyone has drafted and readied
        Optional<com.cobbletowers.lobby.TowerLobby> drafting = LobbyService.lobbyOf(player.getUUID())
                .filter(l -> l.host().equals(player.getUUID()) && l.tower().equals(portal.tower()));
        if (drafting.isPresent()) {
            player.sendSystemMessage(Component.literal(LobbyService.start(server, player)));
            return;
        }
        Hold hold = HOLDS.get(portal.tower());
        if (hold != null && hold.party() != party) {
            bar(player, "Another party is at this gate: wait for them to go in.");
            return;
        }
        List<UUID> team = new ArrayList<>();
        for (UUID id : party.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (id.equals(player.getUUID()) || (member != null && member.level() == hub.level() && ready(id, portal))) team.add(id);
        }
        if (team.size() < 2 && party.size() > 1) {
            bar(player, "Nobody else is ready yet: each member steps on the altar's ring first.");
            return;
        }
        int ascension = pick(server, hub, portal, party).best() ? LobbyService.maxAscension(server, portal.tower(), team) : 0;
        Optional<ResourceLocation> playlist = playlistOf(party.mode()).map(com.cobbletowers.definition.PlaylistDefinition::id);
        // Rental Draft (owner, 23 Nov 2026): everyone going in drafts on the gate's board first; a finished draft is their ready
        Map<UUID, com.cobbletowers.rental.RentalDraft> drafted = new java.util.HashMap<>();
        if (rental(party.mode())) {
            List<String> waiting = GateDrafts.waiting(server, team, portal.tower());
            if (!waiting.isEmpty()) {
                bar(player, "Still drafting: " + String.join(", ", waiting) + ". Stand on the altar's ring to draft on the board.");
                return;
            }
            for (UUID id : team) GateDrafts.finished(id, portal.tower()).ifPresent(d -> drafted.put(id, d));
        }
        String said = LobbyService.startFromParty(server, player, portal.tower(), team, party.threat(), ascension, playlist, drafted);
        // the gate drafts stay until the party is gone: a start that fails (a missing Rental Key) must not cost anyone their team
        if ("Drafting.".equals(said)) {
            bar(player, "Rental Draft: everyone drafts their team on the screen, readies up, then walk in again.");
            return;
        }
        if (!"Starting.".equals(said)) {
            player.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.RED));
            return;
        }
        String tower = towerName(portal.tower());
        for (UUID id : party.members()) {
            if (team.contains(id)) continue;
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null) member.sendSystemMessage(Component.literal("Your party is going into " + tower + " without you: you were not on the altar's ring."));
        }
    }

    /**
     * A tower host's line on the player's party, when it holds that tower's gate (owner, 25 Nov 2026): the mode, the Threat, the start and
     * who is ready, as the altar shows them.
     */
    public static Optional<String> gateStatus(MinecraftServer server, UUID player, ResourceLocation tower) {
        Optional<Party> party = BOOK.partyOf(player);
        Hold hold = HOLDS.get(tower);
        if (party.isEmpty() || hold == null || hold.party() != party.get()) return Optional.empty();
        Party p = party.get();
        int ready = 0;
        for (UUID id : p.members()) if (tower.equals(READY.get(id))) ready++;
        StringBuilder line = new StringBuilder("Your party: ").append(GateAltar.MODE_NAMES.getOrDefault(p.mode(), p.mode()));
        if (AscensionLibScouting.threatEnabled()) line.append("  ·  ").append(p.threat() == 0 ? "no Threat" : "Threat " + LobbyService.threatNumeral(p.threat()));
        long best = VOTES.getOrDefault(p, Map.of()).values().stream().filter(Boolean::booleanValue).count();
        long first = VOTES.getOrDefault(p, Map.of()).size() - best;
        if (best + first > 0) line.append("  ·  ").append(best > first ? "voting Best" : "voting First floor");
        line.append("  ·  ").append(ready).append(" of ").append(p.size()).append(" ready");
        return Optional.of(line.toString());
    }

    /**
     * A player with no party and no team walked into a gate's portal (owner, 25 Nov 2026: no screen in the hub): they go in alone, as a
     * party of one with its defaults (Standard, no Threat, the first floor), unless another party holds the gate.
     */
    static void walkInAlone(MinecraftServer server, Hub hub, ServerPlayer player, HubLayout.Portal portal) {
        Hold hold = HOLDS.get(portal.tower());
        if (hold != null && !PartyBook.dissolved(hold.party()) && BOOK.parties().contains(hold.party())) {
            bar(player, "Another party is at this gate: wait for them to go in.");
            return;
        }
        long now = System.currentTimeMillis();
        Party solo = BOOK.solo(player.getUUID());
        HOLDS.put(portal.tower(), new Hold(solo, now, now));
        READY.put(player.getUUID(), portal.tower());
        walkIn(server, hub, player, portal, solo);
    }

    /** Ready: standing on the gate's altar ring (a hub built before the altar: near the spot in front of the portal). */
    private static boolean gathered(Hub hub, HubLayout.Portal portal, ServerPlayer player) {
        if (onPad(hub, player)) return false;                // the pad's south arm lies on Duskvale's ring: the pad readies nobody
        if (portal.gate().isPresent()) {
            return portal.gate().get().ready(player.getX() - hub.origin().getX(), player.getY() - hub.origin().getY(), player.getZ() - hub.origin().getZ());
        }
        BlockPos rel = hub.rel(player.blockPosition());
        HubLayout.Spot back = portal.back();
        return rel.distSqr(new BlockPos(back.x(), back.y(), back.z())) <= 36;
    }

    /** Ready for this gate: stepped on its altar's ring and still at the gate. */
    private static boolean ready(UUID id, HubLayout.Portal portal) {
        return portal.tower().equals(READY.get(id));
    }

    /** Keeps the ready list: on the ring makes ready; leaving the gate, the party or the server clears it. */
    private static void keepReady(MinecraftServer server, Hub hub) {
        for (Party party : BOOK.parties()) {
            for (UUID id : party.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                if (member == null || member.level() != hub.level()) continue;
                for (HubLayout.Portal portal : hub.layout().portals()) {
                    if (gathered(hub, portal, member) && !portal.tower().equals(READY.get(id))) {
                        READY.put(id, portal.tower());
                        active(portal, id);
                        bar(member, "Ready for " + gateName(portal.tower()) + ": stay at the gate; your leader takes the party in.");
                    }
                }
            }
        }
        READY.entrySet().removeIf(e -> {
            ServerPlayer member = server.getPlayerList().getPlayer(e.getKey());
            if (member == null || member.level() != hub.level() || BOOK.partyOf(e.getKey()).isEmpty()) return true;
            return hub.layout().portals().stream().filter(p -> p.tower().equals(e.getValue())).noneMatch(p -> near(hub, p, member));
        });
    }

    // ---- the Trials Pavilion ---------------------------------------------------------------------------------------------------------

    private static boolean onRing(Hub hub, HubLayout.Shrine shrine, ServerPlayer p) {
        return shrine.ready(p.getX() - hub.origin().getX(), p.getY() - hub.origin().getY(), p.getZ() - hub.origin().getZ());
    }

    private static boolean nearShrine(Hub hub, HubLayout.Shrine shrine, ServerPlayer p) {
        double dx = p.getX() - hub.origin().getX() - shrine.readyX(), dz = p.getZ() - hub.origin().getZ() - shrine.readyZ();
        return dx * dx + dz * dz <= 100;
    }

    private static com.cobbletowers.definition.TrialPoolDefinition.Kind kindOf(HubLayout.Shrine shrine) {
        return shrine.daily() ? com.cobbletowers.definition.TrialPoolDefinition.Kind.DAILY : com.cobbletowers.definition.TrialPoolDefinition.Kind.WEEKLY;
    }

    /** The shrines' plates and readiness: stepping on a ring readies a player for that trial and says whether their attempt is scored. */
    private static void shrines(MinecraftServer server, Hub hub) {
        for (HubLayout.Shrine shrine : hub.layout().shrines()) {
            for (ServerPlayer p : hub.level().players()) {
                if (onRing(hub, shrine, p) && !shrine.kind().equals(TRIAL_READY.get(p.getUUID()))) {
                    TRIAL_READY.put(p.getUUID(), shrine.kind());
                    var trial = com.cobbletowers.trial.TrialService.current(kindOf(shrine));
                    boolean scored = trial.isPresent() && com.cobbletowers.trial.TrialService.wouldBeScored(server, List.of(p.getUUID()), trial.get().id());
                    bar(p, "Ready for the " + shrine.kind() + " trial: " + (scored ? "your scored attempt is waiting." : "you have used your attempt: practice only."));
                }
            }
            BlockPos at = hub.origin().offset(shrine.plate().x(), shrine.plate().y(), shrine.plate().z());
            if (!hub.level().hasChunk(at.getX() >> 4, at.getZ() >> 4)) continue;
            TrialPlate plate = PLATES.get(shrine.kind());
            if (plate == null || plate.getAttachment() == null) {
                if (plate != null) plate.destroy();
                plate = new TrialPlate(hub.level(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), shrine.daily());
                PLATES.put(shrine.kind(), plate);
            }
            if (server.getTickCount() % 20 == 0) plate.refresh(kindOf(shrine));
            shrineDraft(server, hub, shrine);
        }
        TRIAL_READY.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || p.level() != hub.level()) return true;
            return hub.layout().shrines().stream().filter(s -> s.kind().equals(e.getValue())).noneMatch(s -> nearShrine(hub, s, p));
        });
    }

    /** Today's trial at this shrine, when it is a Rental Draft: everyone ready at it drafts on the shrine's board (owner, 25 Nov 2026). */
    private static void shrineDraft(MinecraftServer server, Hub hub, HubLayout.Shrine shrine) {
        Optional<com.cobbletowers.trial.TrialSchedule.Instance> trial = com.cobbletowers.trial.TrialService.current(kindOf(shrine));
        if (trial.isEmpty() || !trialRental(trial.get())) return;
        List<UUID> drafters = new ArrayList<>();
        for (Map.Entry<UUID, String> e : TRIAL_READY.entrySet()) if (e.getValue().equals(shrine.kind())) drafters.add(e.getKey());
        GateDrafts.shrine(server, hub, shrine, GateDrafts.shrineKey(shrine.kind(), trial.get().seed()), trial.get().seed(), drafters, id -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            return p != null && onRing(hub, shrine, p);
        }, id -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            return p != null && nearShrine(hub, shrine, p);
        });
    }

    private static boolean trialRental(com.cobbletowers.trial.TrialSchedule.Instance trial) {
        return trial.entry().playlist().flatMap(com.cobbletowers.definition.PlaylistRegistry::get)
                .map(com.cobbletowers.definition.PlaylistDefinition::rental).orElse(false);
    }

    /** A walk into a shrine: a party's leader takes its ready members in; a player with no party goes alone; a member is told to wait. */
    static void trialWalkIn(MinecraftServer server, Hub hub, ServerPlayer player, HubLayout.Shrine shrine) {
        if (inRun(player.getUUID())) {
            bar(player, "You are already in a tower run.");
            return;
        }
        Optional<Party> party = BOOK.partyOf(player.getUUID());
        List<UUID> team = new ArrayList<>();
        if (party.isPresent()) {
            if (!party.get().leader().equals(player.getUUID())) {
                bar(player, "Your leader takes the party in: stand on the shrine's ring.");
                return;
            }
            for (UUID id : party.get().members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                if (id.equals(player.getUUID()) || (member != null && shrine.kind().equals(TRIAL_READY.get(id)) && nearShrine(hub, shrine, member))) team.add(id);
            }
            if (team.size() < 2 && party.get().size() > 1) {
                bar(player, "Nobody else is ready yet: each member steps on the shrine's ring first.");
                return;
            }
        } else {
            team.add(player.getUUID());
        }
        Optional<com.cobbletowers.lobby.TowerLobby> drafting = LobbyService.lobbyOf(player.getUUID()).filter(l -> l.host().equals(player.getUUID()) && l.trial().isPresent());
        if (drafting.isPresent()) {
            player.sendSystemMessage(Component.literal(LobbyService.start(server, player)));
            return;
        }
        // a Rental Draft trial (owner, 25 Nov 2026): everyone going in drafts on the shrine's board first; a finished draft is their ready
        Map<UUID, com.cobbletowers.rental.RentalDraft> drafted = new HashMap<>();
        Optional<com.cobbletowers.trial.TrialSchedule.Instance> trial = com.cobbletowers.trial.TrialService.current(kindOf(shrine));
        if (trial.isPresent() && trialRental(trial.get())) {
            ResourceLocation key = GateDrafts.shrineKey(shrine.kind(), trial.get().seed());
            if (!shrine.kind().equals(TRIAL_READY.get(player.getUUID()))) {
                bar(player, "Step on the shrine's ring first: this trial is a Rental Draft, drafted on the board over the arch.");
                return;
            }
            List<String> waiting = GateDrafts.waiting(server, team, key);
            if (!waiting.isEmpty()) {
                bar(player, "Still drafting: " + String.join(", ", waiting) + ". Stand on the shrine's ring to draft on the board.");
                return;
            }
            for (UUID id : team) GateDrafts.finished(id, key).ifPresent(d -> drafted.put(id, d));
        }
        String said = LobbyService.startTrialFromParty(server, player, kindOf(shrine), team, drafted);
        if ("Drafting.".equals(said)) return;
        if (!"Starting.".equals(said)) player.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.RED));
    }

    /** At the gate: near its altar, or in the portal's mouth. */
    private static boolean near(Hub hub, HubLayout.Portal portal, ServerPlayer player) {
        if (onPad(hub, player)) return false;
        if (portal.gate().isEmpty()) return gathered(hub, portal, player);
        HubLayout.Gate gate = portal.gate().get();
        BlockPos rel = hub.rel(player.blockPosition());
        HubLayout.Altar altar = gate.altar();
        int ax = rel.getX() - altar.x(), az = rel.getZ() - altar.z();
        if (ax * ax + az * az <= ALTAR_NEAR * ALTAR_NEAR && Math.abs(rel.getY() - altar.y()) <= 3) return true;
        int ex = portal.wideAlongX() ? NEAR : 0, ez = portal.wideAlongX() ? 0 : NEAR;
        return rel.getX() >= gate.gatherMin().x() - ex && rel.getX() <= gate.gatherMax().x() + ex
                && rel.getZ() >= gate.gatherMin().z() - ez && rel.getZ() <= gate.gatherMax().z() + ez
                && rel.getY() >= gate.gatherMin().y() - 1 && rel.getY() <= gate.gatherMax().y() + 1;
    }

    /**
     * On the warp pad, rim and all. The pad is at no gate (owner's test, 25 Nov 2026): it stands a few steps from Duskvale's altar, so
     * everyone who arrived counted as at that gate, and a party there held it.
     */
    private static boolean onPad(Hub hub, ServerPlayer player) {
        HubLayout.Spot pad = hub.layout().pad();
        double r = hub.layout().ring().present() ? hub.layout().ring().r() + 0.5 : 5.8;
        double dx = player.getX() - (hub.origin().getX() + pad.x() + 0.5), dz = player.getZ() - (hub.origin().getZ() + pad.z() + 0.5);
        return dx * dx + dz * dz <= r * r;
    }

    // ---- the tick ------------------------------------------------------------------------------------------------------------------

    static void tick(MinecraftServer server, long now) {
        Optional<Hub> found = hub(server);
        if (found.isEmpty()) {
            if (!ALTARS.isEmpty()) clear();
            return;
        }
        Hub hub = found.get();
        if (hub.level().players().isEmpty() && BOOK.isEmpty() && SENT.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Vec3 pos = player.position();
            Vec3 was = LAST_POS.put(id, pos);
            ResourceKey<Level> dimWas = LAST_DIM.put(id, player.level().dimension());
            if (player.level() == hub.level() && (was == null || dimWas != player.level().dimension() || was.distanceToSqr(pos) > 64)) {
                ARRIVED.put(id, now);
            }
        }
        for (UUID[] lapsed : BOOK.expire(now)) {
            ServerPlayer inviter = server.getPlayerList().getPlayer(lapsed[1]);
            if (inviter != null) bar(inviter, "Your invite to " + name(server, lapsed[0]) + " lapsed.");
        }
        pad(server, hub, now);
        away(server, hub, now);
        keepReady(server, hub);
        GateDrafts.begin();                                 // one pass over every draft board: the shrines' and the gates'
        shrines(server, hub);
        RecordsHall.tick(server, hub);
        gates(server, hub, now);
        GateDrafts.end();
        hud(server, hub);
    }

    private static void pad(MinecraftServer server, Hub hub, long now) {
        HubLayout.Ring ring = hub.layout().ring();
        HubLayout.Spot pad = hub.layout().pad();
        double cx = hub.origin().getX() + pad.x() + 0.5, cz = hub.origin().getZ() + pad.z() + 0.5, py = hub.origin().getY() + pad.y();
        double r = ring.present() ? ring.r() + 0.3 : 5.5;
        List<ServerPlayer> on = new ArrayList<>();
        for (ServerPlayer player : hub.level().players()) {
            if (player.isSpectator() || inRun(player.getUUID())) continue;
            double dx = player.getX() - cx, dz = player.getZ() - cz;
            if (dx * dx + dz * dz <= r * r && Math.abs(player.getY() - py) < 2) on.add(player);
        }
        List<UUID> ids = on.stream().map(ServerPlayer::getUUID).toList();
        ON_PAD_SINCE.keySet().retainAll(ids);
        for (UUID id : ids) ON_PAD_SINCE.putIfAbsent(id, now);
        List<UUID> ready = new ArrayList<>();
        ON_PAD_SINCE.forEach((id, since) -> {
            long from = Math.max(since, ARRIVED.getOrDefault(id, 0L) + ARRIVAL_GRACE);
            if (now - from >= PAD_DWELL) ready.add(id);
        });
        if (!ready.isEmpty()) {
            List<UUID> joined = BOOK.gather(ready, now);
            for (UUID id : joined) {
                Party party = BOOK.partyOf(id).orElseThrow();
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && !party.leader().equals(id)) {
                    player.sendSystemMessage(Component.literal("You joined " + name(server, party.leader())
                            + "'s party on the warp pad. Sneak + right-click the pad's centre stone to leave."));
                }
                for (UUID other : party.members()) {
                    ServerPlayer member = server.getPlayerList().getPlayer(other);
                    if (member != null && !other.equals(id)) bar(member, name(server, id) + " joined the party");
                }
            }
        }
        if (ring.present()) {
            int lit = Math.min(4, on.size());
            for (int[] cell : ring.cells()) paint(hub, cell[0], ring.y(), cell[1], cell[2] < lit ? ring.lit() : ring.unlit());
        }
    }

    private static void away(MinecraftServer server, Hub hub, long now) {
        for (Party party : BOOK.parties()) {
            for (UUID id : party.members()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                boolean gone = player == null || (player.level() != hub.level() && !inRun(id));
                if (!gone) {
                    AWAY_SINCE.remove(id);
                    continue;
                }
                long since = AWAY_SINCE.computeIfAbsent(id, k -> now);
                if (now - since < AWAY_MILLIS) continue;
                AWAY_SINCE.remove(id);
                leave(server, id, "You left your party: you were away from the Tower Hub.", " left the party (away from the hub).");
            }
        }
        AWAY_SINCE.keySet().removeIf(id -> BOOK.partyOf(id).isEmpty());
    }

    private static void gates(MinecraftServer server, Hub hub, long now) {
        HubLayout.Ring ring = hub.layout().ring();
        boolean threatOn = AscensionLibScouting.threatEnabled();
        for (HubLayout.Portal portal : hub.layout().portals()) {
            Map<Party, Integer> near = new LinkedHashMap<>();
            java.util.Set<Party> onRing = new java.util.HashSet<>();
            Hold current = HOLDS.get(portal.tower());
            for (ServerPlayer player : hub.level().players()) {
                if (!near(hub, portal, player)) continue;
                Optional<Party> party = BOOK.partyOf(player.getUUID());
                boolean stepped = gathered(hub, portal, player);
                // a player alone on a gate's altar ring is a party of one (owner, 23 Nov 2026): the altar and the walk-in work for them too.
                // Only on the ring (audit, 25 Nov 2026): walking past a gate claims nothing.
                if (party.isEmpty() && stepped && !inRun(player.getUUID())) party = Optional.of(BOOK.solo(player.getUUID()));
                if (party.isEmpty()) continue;
                // a party of one takes a gate only by stepping on its ring or clicking its altar, never by walking near it: it ends when it
                // leaves its own gate instead of moving on to the next one it passes (owner's test, 25 Nov 2026)
                if (party.get().size() == 1 && !stepped && (current == null || current.party() != party.get())) continue;
                near.merge(party.get(), 1, Integer::sum);
                if (stepped) onRing.add(party.get());
            }
            Hold hold = HOLDS.get(portal.tower());
            if (hold != null && (PartyBook.dissolved(hold.party()) || !BOOK.parties().contains(hold.party()))) hold = null;
            if (hold != null && near.containsKey(hold.party())) hold = new Hold(hold.party(), now, hold.lastActive());
            else if (hold != null && now - hold.lastSeen() > GATE_RELEASE) hold = null;
            // a holder idle this long gives way to another party waiting on the ring
            Party idle = null;
            if (hold != null && now - hold.lastActive() > IDLE_RELEASE) {
                Party holder = hold.party();
                if (onRing.stream().anyMatch(p -> p != holder)) {
                    idle = holder;
                    hold = null;
                }
            }
            if (hold == null && !near.isEmpty()) {
                Party skip = idle;
                Optional<Party> first = near.entrySet().stream().filter(e -> e.getKey() != skip)
                        .filter(e -> skip == null || onRing.contains(e.getKey())).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
                if (first.isPresent()) {
                    hold = new Hold(first.get(), now, now);
                    if (idle != null) {
                        for (UUID id : idle.members()) {
                            ServerPlayer member = server.getPlayerList().getPlayer(id);
                            if (member != null) bar(member, "Your party was idle at " + gateName(portal.tower()) + " gate: another party has it now.");
                        }
                    }
                }
            }
            if (hold == null) HOLDS.remove(portal.tower());
            else HOLDS.put(portal.tower(), hold);
            if (portal.gate().isEmpty()) continue;
            HubLayout.Gate gate = portal.gate().get();
            Party party = hold == null ? null : hold.party();
            altar(server, hub, portal, gate, party, threatOn);
            if (party != null && rental(party.mode())) {
                GateDrafts.gate(server, hub, portal, gate, party, id -> ready(id, portal), id -> {
                    ServerPlayer m = server.getPlayerList().getPlayer(id);
                    return m != null && near(hub, portal, m);
                }, p -> active(portal, p.getUUID()));
            }
        }
        // a party of one holding no gate ends: its player left the gate, or a bigger party took it (one that grew by an invite is a party
        // like any other; a party that drops to one member is already ended by PartyBook.leave)
        for (Party solo : List.copyOf(BOOK.parties())) {
            if (solo.size() == 1 && HOLDS.values().stream().noneMatch(h -> h.party() == solo)) BOOK.leave(solo.leader());
        }
        VOTES.keySet().removeIf(p -> PartyBook.dissolved(p) || !BOOK.parties().contains(p));
        for (Map.Entry<Party, Map<UUID, Boolean>> e : VOTES.entrySet()) e.getValue().keySet().retainAll(e.getKey().members());
    }

    /** The gate's altar, drawn if missing, showing the holding party's picks (or what it is for, when nobody holds the gate). */
    private static void altar(MinecraftServer server, Hub hub, HubLayout.Portal portal, HubLayout.Gate gate, Party party, boolean threatOn) {
        HubLayout.Altar spot = gate.altar();
        BlockPos at = hub.origin().offset(spot.x(), spot.y(), spot.z());
        if (!hub.level().hasChunk(at.getX() >> 4, at.getZ() >> 4)) return;
        GateAltar altar = ALTARS.get(portal.tower());
        if (altar == null || altar.getAttachment() == null) {
            if (altar != null) altar.destroy();
            altar = new GateAltar(hub.level(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), spot.yaw(), portal.tower().getPath());
            altar.hint((p, part) -> hintFor(p, portal, part));
            ALTARS.put(portal.tower(), altar);
        }
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(portal.tower());
        boolean ascends = tower != null && tower.ascension();
        Component title = Component.literal(gateName(portal.tower()) + " gate").withColor(portal.color());
        if (party == null) {
            altar.show(new GateAltar.Shown(0, 0, 0, 0, null, title,
                    Component.literal("Gather your party round the altar to set the run").withColor(0xC8C8D2), -1,
                    Component.literal("Pick a mode").withColor(0xF2C744),
                    Component.literal("A party at this gate picks its mode on the board across the path.").withColor(0xE6E8F0),
                    Component.literal("Standard, Hardcore, Level Cap 50, Monotype, Underdog, Solo Gauntlet, Rental Draft").withColor(0xA0A6B4)));
            return;
        }
        int threat = threatOn ? party.threat() : 0;
        Pick pick = pick(server, hub, portal, party);
        int ready = 0;
        for (UUID id : party.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null && member.level() == hub.level() && ready(id, portal)) ready++;
        }
        int best = LobbyService.maxAscension(server, portal.tower(), atGateOrAll(server, hub, portal, party));
        StringBuilder line = new StringBuilder();
        if (threatOn) line.append(threat == 0 ? "No Threat" : "Threat " + LobbyService.threatNumeral(threat)).append("  ·  ");
        if (ascends) line.append(pick.best() ? "Best: Asc " + best : "First floor").append("  ·  ");
        line.append(ready).append(" of ").append(party.size()).append(" ready");
        Boolean settled = ascends && pick.settled() ? pick.best() : null;
        String mode = party.mode();
        if (!mode.equals("standard")) line.insert(0, GateAltar.MODE_NAMES.getOrDefault(mode, mode) + "  ·  ");
        Optional<com.cobbletowers.definition.PlaylistDefinition> playlist = playlistOf(mode);
        int modeIndex = AltarModels.layout().tiles().modes().indexOf(mode);
        String rules = playlist.map(com.cobbletowers.definition.PlaylistDefinition::description).orElse("The tower as it stands: no house rules.");
        altar.show(new GateAltar.Shown(threat, Math.min(4, ready), pick.forFirst(), pick.forBest(), settled, title,
                Component.literal(line.toString()).withColor(0xE6E8F0), modeIndex,
                Component.literal(GateAltar.MODE_NAMES.getOrDefault(mode, mode).toUpperCase(java.util.Locale.ROOT))
                        .withColor(mode.equals("hardcore") ? 0xFF7A6E : 0xF2C744),
                Component.literal(rules).withColor(0xE8EAF2),
                Component.literal("Chosen by " + name(server, party.leader()) + " (leader)  ·  right-click a tile to change").withColor(0xA0A6B4)));
    }

    private static String hintFor(ServerPlayer p, HubLayout.Portal portal, GateAltar.Hit hit) {
        Optional<Party> party = BOOK.partyOf(p.getUUID());
        Hold hold = HOLDS.get(portal.tower());
        boolean ours = party.isPresent() && hold != null && hold.party() == party.get();
        boolean leads = ours && party.get().leader().equals(p.getUUID());
        return switch (hit.part()) {
            case TILE -> {
                String mode = AltarModels.layout().tiles().modes().get(hit.tile());
                String name = GateAltar.MODE_NAMES.getOrDefault(mode, mode);
                String what = playlistOf(mode).map(com.cobbletowers.definition.PlaylistDefinition::description).orElse("No house rules.");
                yield (leads ? "Right-click: " + name + ". " : name + ": ") + what;
            }
            case RULES -> GateDrafts.drafting(p.getUUID()) ? "" : "The rules of your party's mode";
            case THREAT -> !ours ? "The Threat runes: a party at this gate sets them"
                    : party.get().leader().equals(p.getUUID()) ? "Right-click the altar: raise the Threat (after VI it goes back to none)"
                    : "Your leader sets the Threat";
            case FIRST -> ours ? "Right-click to vote: start from the first floor" : "First floor: start from the bottom";
            case BEST -> ours ? "Right-click to vote: start at your party's best Ascension" : "Best: start at your party's highest Ascension";
        };
    }

    /** The gathered members, or the whole party when none of them stands at the gate yet (for the Best a party could start at). */
    private static List<UUID> atGateOrAll(MinecraftServer server, Hub hub, HubLayout.Portal portal, Party party) {
        List<UUID> here = atGate(server, hub, portal, party);
        return here.isEmpty() ? party.members() : here;
    }

    // ---- the roster card -----------------------------------------------------------------------------------------------------------

    private static void hud(MinecraftServer server, Hub hub) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PartyHudPayload want = hudFor(server, hub, player);
            PartyHudPayload had = SENT.get(player.getUUID());
            if (want.equals(had) || (had == null && want.members().isEmpty())) continue;
            // only a card that reached the client counts as sent: one who just joined is not ready for a moment, and is tried again
            if (!ClientUi.canShow(player, PartyHudPayload.TYPE)) continue;
            ClientUi.send(player, want);
            SENT.put(player.getUUID(), want);
        }
    }

    static PartyHudPayload hudFor(MinecraftServer server, Hub hub, ServerPlayer viewer) {
        long now = System.currentTimeMillis();
        Optional<Party> found = BOOK.partyOf(viewer.getUUID());
        List<PartyHudPayload.Member> rows = new ArrayList<>();
        UUID leader = found.map(Party::leader).orElse(viewer.getUUID());
        List<UUID> invited = BOOK.invitesBy(leader, now);
        if (found.isEmpty() && invited.isEmpty()) return PartyHudPayload.EMPTY;
        Map.Entry<ResourceLocation, Hold> held = found.isEmpty() ? null
                : HOLDS.entrySet().stream().filter(e -> e.getValue().party() == found.get()).findFirst().orElse(null);
        HubLayout.Portal portal = held == null ? null
                : hub.layout().portals().stream().filter(p -> p.tower().equals(held.getKey())).findFirst().orElse(null);
        List<UUID> members = found.map(Party::members).orElse(List.of(viewer.getUUID()));
        int here = 0;
        for (UUID id : members) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            String state;
            if (inRun(id)) state = "run";
            else if (member == null || member.level() != hub.level()) state = "away";
            else if (portal != null && ready(id, portal)) {
                state = "gate";
                here++;
            } else state = "hub";
            rows.add(new PartyHudPayload.Member(id, name(server, id), id.equals(leader), state));
        }
        for (UUID id : invited) rows.add(new PartyHudPayload.Member(id, name(server, id), false, "invited"));
        PartyHudPayload.Gate gate = PartyHudPayload.Gate.NONE;
        if (portal != null) {
            TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(portal.tower());
            Party party = found.get();
            String threat = !AscensionLibScouting.threatEnabled() ? "" : party.threat() == 0 ? "off" : LobbyService.threatNumeral(party.threat());
            if (!party.mode().equals("standard")) {                // the mode rides on the card's Threat line
                String m = GateAltar.MODE_NAMES.getOrDefault(party.mode(), party.mode());
                threat = threat.isEmpty() ? m : m + " " + (threat.equals("off") ? "" : threat);
            }
            Pick pick = pick(server, hub, portal, party);
            int best = LobbyService.maxAscension(server, portal.tower(), atGateOrAll(server, hub, portal, party));
            String ascension = tower == null || !tower.ascension() ? ""
                    : (pick.best() ? "Asc " + best : "Floor 1") + " (" + (pick.forFirst() + pick.forBest()) + "/" + pick.voters() + ")";
            gate = new PartyHudPayload.Gate(portal.tower().getPath(), gateName(portal.tower()), portal.color(), here, members.size(), threat, ascension);
        }
        return new PartyHudPayload(List.copyOf(rows), gate);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------------

    private static void paint(Hub hub, int x, int y, int z, String block) {
        BlockPos pos = hub.origin().offset(x, y, z);
        if (!hub.level().isLoaded(pos)) return;
        BlockState want = STATES.computeIfAbsent(block, name -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name)).defaultBlockState());
        if (hub.level().getBlockState(pos) != want) hub.level().setBlock(pos, want, Block.UPDATE_CLIENTS);
    }

    private static boolean inRun(UUID player) {
        return TowerRuns.forPlayer(player).filter(run -> !run.isRetired()).isPresent();
    }

    private static void bar(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return name(online);
        var cache = server.getProfileCache();
        return cache == null ? id.toString().substring(0, 8)
                : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    /** The short name a gate goes by: "Neutral" for "Neutral Tower", so "Neutral gate" fits the roster card and the altar's plate. */
    static String gateName(ResourceLocation id) {
        String name = towerName(id);
        return name.endsWith(" Tower") ? name.substring(0, name.length() - " Tower".length()) : name;
    }

    private static String towerName(ResourceLocation id) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(id);
        return tower == null ? id.toString() : tower.displayName();
    }
}
