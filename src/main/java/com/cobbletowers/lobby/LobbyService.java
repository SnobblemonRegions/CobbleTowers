package com.cobbletowers.lobby;

import com.cobbletowers.ServerState;
import com.cobbletowers.CobbleTowers;
import com.cobbletowers.TowerLog;
import com.cobbletowers.battle.cobblemon.PartyReader;
import com.cobbletowers.battle.cobblemon.PartyStorage;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.definition.RulesetResolver;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.PlaylistDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.network.PlayStatePayload;
import com.cobbletowers.network.TowerHallStatePayload;
import com.cobbletowers.network.RegistrationStatePayload;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import com.cobbletowers.runtime.TowerRuns;
import com.cobbletowers.storage.PartyArrangement;
import com.cobbletowers.rental.RentalDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything a player does to get into a run: pick a tower, invite a team, answer an invite, start. Commands and the
 * play screen share these methods, each returning the sentence to show. Lobbies live in memory ({@link TowerLobby});
 * a rejected start leaves the team together to retry.
 */
public final class LobbyService {

    /** A short pause after the host confirms, so nobody is moved mid-action. */
    public static final long COUNTDOWN_MILLIS = 5_000L;

    private static final Map<UUID, TowerLobby> BY_HOST = new LinkedHashMap<>();

    private LobbyService() {}

    public static void install() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (BY_HOST.isEmpty()) return;
            try {
                tick(server, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                TowerLog.error("A tower lobby tick failed", ex);
            }
        });
        // DISCONNECT fires on a Netty thread; the lobbies are plain server-thread state.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            com.cobbletowers.runtime.ServerThread.run(server, () -> {
                try {
                    onDisconnect(server, id);
                } catch (RuntimeException ex) {
                    TowerLog.error("Could not tidy the lobby of a disconnecting player", ex);
                }
            });
        });
    }

    /** A player dropped. A host going ends their team; anyone else just steps out. */
    static void onDisconnect(MinecraftServer server, UUID id) {
        TowerLobby hosted = BY_HOST.get(id);
        if (hosted != null) {
            dissolve(server, hosted, "The host left, so the team was ended.");
            return;
        }
        Optional<TowerLobby> found = lobbyOf(id);
        if (found.isEmpty()) return;
        TowerLobby lobby = found.get();
        lobby.remove(id);
        RentalDraftService.clear(id);
        ServerPlayer gone = server.getPlayerList().getPlayer(id);
        broadcast(server, lobby, (gone == null ? "A player" : name(gone)) + " left the team.");
    }

    /** Memory hygiene at shutdown, like {@code TowerRuns.onServerStopped}. */
    public static void clear() {
        BY_HOST.clear();
        LAST_CODES.clear();
    }

    /** One line per forming team: host, tower, how many are ready and pending, and whether it is counting down. */
    public static List<String> describeAll() {
        return BY_HOST.values().stream().map(lobby -> lobby.host() + " " + lobby.tower() + " "
                + lobby.team().size() + " ready, " + lobby.pending().size() + " pending"
                + (lobby.counting() ? ", counting down" : "")).toList();
    }

    public static Optional<TowerLobby> lobbyOf(UUID player) {
        return BY_HOST.values().stream().filter(lobby -> lobby.contains(player)).findFirst();
    }

    // ---- actions -----------------------------------------------------------------------------

    public static String select(MinecraftServer server, ServerPlayer player, ResourceLocation towerId) {
        if (!TowerDefinitionRegistry.content().towers().containsKey(towerId)) {
            return "No tower " + towerId + " is loaded.";
        }
        if (!TowerDefinitionRegistry.content().towers().get(towerId).isOpen(System.currentTimeMillis())) {
            return towerName(towerId) + " is not open right now.";
        }
        if (inRun(player.getUUID())) return "You are already in a tower run.";

        Optional<TowerLobby> existing = lobbyOf(player.getUUID());
        if (existing.isPresent() && !existing.get().host().equals(player.getUUID())) {
            return "Only the host can change the tower. Leave first to host your own.";
        }
        if (existing.isPresent() && existing.get().counting()) return "The run is already starting.";

        if (existing.isPresent() && existing.get().tower().equals(towerId)) {
            // The same tower again changes nothing: a new offer would send everyone back to "invited" and throw away
            // the mode,
            // the drafts and the ready-ups, which looks like a brand-new lobby to a team that only wanted to look at
            // this one.
            return "That is already your tower: " + towerName(towerId) + ".";
        }
        TowerLobby lobby = existing.orElseGet(() -> {
            TowerLobby created = new TowerLobby(player.getUUID(), towerId);
            BY_HOST.put(player.getUUID(), created);
            return created;
        });
        RentalDraftService.clearAll(lobby);
        lobby.selectTower(towerId);
        broadcast(server, lobby, "");
        return "Tower set to " + towerName(towerId) + ".";
    }

    public static String invite(MinecraftServer server, ServerPlayer host, ServerPlayer target) {
        TowerLobby lobby = BY_HOST.get(host.getUUID());
        if (lobby == null) return inRun(host.getUUID()) ? inRunMessage() : "Pick a tower first.";
        if (lobby.counting()) return "The run is already starting.";
        if (target.getUUID().equals(host.getUUID())) return "You are already on your own team.";
        if (inRun(target.getUUID())) return name(target) + " is already in a tower run.";
        if (lobbyOf(target.getUUID()).isPresent()) return name(target) + " is already in a team.";

        int teamCap = lobby.playlist().flatMap(PlaylistRegistry::get).map(PlaylistDefinition::maxPlayers).orElse(0);
        if (teamCap > 0 && 1 + lobby.invitees().size() >= teamCap) {
            return playlistName(lobby.playlist()) + " allows " + teamCap + " player" + (teamCap == 1 ? "" : "s") + ".";
        }
        TowerLobby.Result result = lobby.invite(target.getUUID(), System.currentTimeMillis());
        if (result == TowerLobby.Result.FULL) return "A team is at most " + TowerLobby.MAX_PLAYERS + " players.";
        if (result != TowerLobby.Result.OK) return name(target) + " is already invited.";

        target.sendSystemMessage(Component.literal(name(host) + " invited you to " + towerName(lobby.tower())
                + ". Accept " + name(host) + "'s invite on the play screen, or with /tower accept (expires in 3 minutes)."));
        broadcast(server, lobby, "");
        return "Invited " + name(target) + ".";
    }

    public static String accept(MinecraftServer server, ServerPlayer player, UUID hostId) {
        TowerLobby lobby = BY_HOST.get(hostId);
        if (lobby == null) return "That team no longer exists.";
        if (inRun(player.getUUID())) return "You are already in a tower run.";
        TowerLobby.Result result = lobby.accept(player.getUUID(), System.currentTimeMillis());
        if (result != TowerLobby.Result.OK) return "That invite has lapsed.";
        broadcast(server, lobby, name(player) + " joined the team.");
        // A rental run needs every member's own draft, so put their packs in front of them now.
        RentalDraftService.prompt(server, lobby, player);
        return "You joined the team for " + towerName(lobby.tower()) + ".";
    }

    public static String decline(MinecraftServer server, ServerPlayer player, UUID hostId) {
        TowerLobby lobby = BY_HOST.get(hostId);
        if (lobby == null || lobby.decline(player.getUUID()) != TowerLobby.Result.OK) return "You have no such invite.";
        broadcast(server, lobby, name(player) + " declined.");
        sendState(server, player, null, "");
        return "Declined.";
    }

    /** What to say to a player with no team; a player already in a run is told that instead. */
    public static String noTeam(ServerPlayer player) {
        return inRun(player.getUUID()) ? inRunMessage() : "You are not in a team.";
    }

    private static String inRunMessage() {
        return "You are in a tower run already (the team waiting room closes once it starts). Use /tower to see where you are.";
    }

    /** A host leaving ends the team; anyone else just steps out of it. */
    public static String leave(MinecraftServer server, ServerPlayer player) {
        Optional<TowerLobby> found = lobbyOf(player.getUUID());
        if (found.isEmpty()) {
            // In a live run, leaving is leaving the run (the same as `runs leave`); the exit sweep then takes them
            // home. Only after that does the "the run is over, take me home" path apply.
            if (inRun(player.getUUID())
                    && com.cobbletowers.runtime.TowerPresence.leave(server, player, System.currentTimeMillis())) {
                return "You left the run. You will be taken home in a moment.";
            }
            String home = com.cobbletowers.runtime.RunExitService.leaveNow(server, player);
            return home != null ? home : noTeam(player);
        }
        TowerLobby lobby = found.get();
        if (lobby.host().equals(player.getUUID())) {
            dissolve(server, lobby, name(player) + " ended the team.");
            return "You ended the team.";
        }
        lobby.remove(player.getUUID());
        RentalDraftService.clear(player.getUUID());
        sendState(server, player, null, "");
        broadcast(server, lobby, name(player) + " left the team.");
        return "You left the team.";
    }

    /** The host picks today's trial (P32); its tower, playlist, mutators, level and seed fix the lobby. */
    public static String selectTrial(MinecraftServer server, ServerPlayer player,
                                     com.cobbletowers.definition.TrialPoolDefinition.Kind kind) {
        if (inRun(player.getUUID())) return "You are already in a tower run.";
        Optional<TowerLobby> existing = lobbyOf(player.getUUID());
        if (existing.isPresent() && !existing.get().host().equals(player.getUUID())) {
            return "Only the host can choose the trial. Leave first to host your own.";
        }
        if (existing.isPresent() && existing.get().counting()) return "The run is already starting.";
        Optional<com.cobbletowers.trial.TrialSchedule.Instance> found = com.cobbletowers.trial.TrialService.current(kind);
        if (found.isEmpty()) return "There is no " + kind.name().toLowerCase(java.util.Locale.ROOT) + " trial on this server.";
        com.cobbletowers.trial.TrialSchedule.Instance trial = found.get();
        if (!TowerDefinitionRegistry.content().towers().containsKey(trial.entry().tower())) {
            return "Today's trial uses " + trial.entry().tower() + ", which is not loaded.";
        }
        PlaylistDefinition playlist = trial.entry().playlist().flatMap(PlaylistRegistry::get).orElse(null);
        TowerLobby lobby = existing.orElseGet(() -> {
            TowerLobby created = new TowerLobby(player.getUUID(), trial.entry().tower());
            BY_HOST.put(player.getUUID(), created);
            return created;
        });
        if (playlist != null && playlist.maxPlayers() > 0 && lobby.team().size() + lobby.pending().size() > playlist.maxPlayers()) {
            return playlist.displayName() + " allows " + playlist.maxPlayers() + " player(s); the team is larger.";
        }
        RentalDraftService.clearAll(lobby);
        lobby.selectTower(trial.entry().tower());
        lobby.setPlaylist(trial.entry().playlist());
        lobby.setTrial(Optional.of(trial));
        broadcast(server, lobby, "");
        boolean scored = com.cobbletowers.trial.TrialService.wouldBeScored(server, new ArrayList<>(lobby.team()), trial.id());
        return trial.title() + " selected: " + trial.floors() + " floors on " + towerName(trial.entry().tower())
                + (playlist == null ? "" : ", " + playlist.displayName())
                + (scored ? ". This will be your scored attempt: it cannot be retried. Confirm the mode, then start, on the play screen."
                        : ". Someone on the team has already used this trial's attempt, so this run is practice and will not post. Use /tower confirm, then /tower start.");
    }

    /** The code of the last run each player started, for {@code /tower play code}. In memory only. */
    private static final Map<UUID, String> LAST_CODES = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        ServerState.onStop(LobbyService::clear);
    }

    /** What {@code /tower play code} says with no argument: the code of the run you last started. */
    static void rememberCode(UUID player, String code) {
        LAST_CODES.put(player, code);
    }

    public static String lastCode(ServerPlayer player) {
        String code = LAST_CODES.get(player.getUUID());
        return code == null ? "You have not started a run since the server last restarted." : "Your last run code: " + code;
    }

    /** The host sets the lobby to a run code (P35). Refused if invalid, unloaded, or above the team's Ascension. */
    public static String useCode(MinecraftServer server, ServerPlayer player, String raw) {
        java.util.Optional<com.cobbletowers.runcode.RunCode.Decoded> decoded = com.cobbletowers.runcode.RunCode.decode(raw);
        if (decoded.isEmpty()) return "That is not a valid run code. Check it was copied whole (it looks like CT1-...).";
        com.cobbletowers.runcode.RunCode.Decoded code = decoded.get();
        if (!TowerDefinitionRegistry.content().towers().containsKey(code.tower())) {
            return "That code is for " + code.tower() + ", which this server does not have.";
        }
        if (code.playlist().isPresent() && PlaylistRegistry.get(code.playlist().get()).isEmpty()) {
            return "That code uses the mode " + code.playlist().get() + ", which this server does not have.";
        }
        String selected = select(server, player, code.tower());
        TowerLobby lobby = BY_HOST.get(player.getUUID());
        if (lobby == null || !lobby.tower().equals(code.tower())) return selected;
        if (lobby.counting()) return "The run is already starting.";
        lobby.setTrial(java.util.Optional.empty());
        String mode = setPlaylist(server, player, code.playlist().map(id -> id.toString()).orElse("standard"));
        if (code.playlist().isPresent() && lobby.playlist().isEmpty()) return mode;
        if (code.ascension() > 0) {
            String ascended = setAscension(server, player, code.ascension());
            if (lobby.ascension() != code.ascension()) return ascended;
        }
        lobby.setSeed(java.util.Optional.of(code.seed()));
        broadcast(server, lobby, "");
        return "Run code accepted: " + towerName(code.tower()) + ", " + playlistName(lobby.playlist())
                + (code.ascension() > 0 ? ", Ascension " + code.ascension() : "")
                + ". You will get the same opponents, bosses and draft cards as the player who shared it. Use /tower confirm, then /tower start.";
    }

    /** The host picks the playlist (P32): its house rules apply to the whole team. {@code "standard"} clears it. */
    public static String setPlaylist(MinecraftServer server, ServerPlayer player, String raw) {
        TowerLobby lobby = BY_HOST.get(player.getUUID());
        if (lobby == null) return lobbyOf(player.getUUID()).isPresent() ? "Only the host can choose the mode." : "Pick a tower first.";
        if (lobby.counting()) return "The run is already starting.";
        if (lobby.trial().isPresent()) return "A trial fixes the mode. Choose a tower to leave the trial.";
        if (raw.equalsIgnoreCase("standard") || raw.isBlank()) {
            RentalDraftService.clearAll(lobby);
            lobby.setPlaylist(java.util.Optional.empty());
            broadcast(server, lobby, "");
            return "Mode: Standard.";
        }
        ResourceLocation id = raw.contains(":") ? ResourceLocation.tryParse(raw)
                : CobbleTowers.id(raw.toLowerCase(java.util.Locale.ROOT));
        java.util.Optional<PlaylistDefinition> playlist = id == null ? java.util.Optional.empty() : PlaylistRegistry.get(id);
        if (playlist.isEmpty()) return "No mode called " + raw + ". Try: standard, "
                + PlaylistRegistry.all().stream().map(p -> p.id().getPath()).collect(java.util.stream.Collectors.joining(", ")) + ".";
        if (playlist.get().maxPlayers() > 0 && lobby.team().size() + lobby.pending().size() > playlist.get().maxPlayers()) {
            return playlist.get().displayName() + " allows " + playlist.get().maxPlayers() + " player(s); the team is larger.";
        }
        RentalDraftService.clearAll(lobby);
        lobby.setPlaylist(java.util.Optional.of(id));
        broadcast(server, lobby, "");
        return "Mode: " + playlist.get().displayName() + ". " + playlist.get().description()
                + (playlist.get().rental() ? " Confirm the mode on the play screen when you are happy with it, and everyone's packs open."
                        : " Confirm the mode on the play screen when you are happy with it.");
    }

    static String playlistName(java.util.Optional<ResourceLocation> playlist) {
        return playlist.flatMap(PlaylistRegistry::get).map(PlaylistDefinition::displayName).orElse("Standard");
    }

    /** The deepest Ascension the whole team may start at (P30): the lowest record on the team. */
    public static int maxAscension(MinecraftServer server, TowerLobby lobby) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(lobby.tower());
        if (tower == null || !tower.ascension()) return 0;
        com.cobbletowers.persistence.TowerAscensionStore records = com.cobbletowers.persistence.TowerAscensionStore.get(server);
        int deepest = Integer.MAX_VALUE;
        for (UUID id : lobby.team()) deepest = Math.min(deepest, records.recordOf(id, lobby.tower()));
        return deepest == Integer.MAX_VALUE ? 0 : deepest;
    }

    /** The host chooses the Ascension the team starts at, up to what every member has reached. */
    public static String setAscension(MinecraftServer server, ServerPlayer player, int level) {
        TowerLobby lobby = BY_HOST.get(player.getUUID());
        if (lobby == null) return lobbyOf(player.getUUID()).isPresent() ? "Only the host can choose the Ascension."
                : "Pick a tower first.";
        if (lobby.counting()) return "The run is already starting.";
        if (lobby.trial().isPresent()) return "A trial always starts from the first floor. Choose a tower to leave the trial.";
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(lobby.tower());
        if (tower == null || !tower.ascension()) return towerName(lobby.tower()) + " does not ascend.";
        int max = maxAscension(server, lobby);
        if (level > max) {
            return "Your team can start at Ascension " + max + " at most (a start needs everyone to have reached it).";
        }
        lobby.setAscension(level);
        broadcast(server, lobby, "");
        return level == 0 ? "Starting from the first floor." : "Starting at Ascension " + level + ".";
    }

    /**
     * The host picks the Threat band the team enters at (balance phase 2): 1-6, or 0 for none. Every band is open to every team; the lobby shows the
     * library's advice beside the choice. Needs AscensionLib's Threat system to be on.
     */
    public static String setThreat(MinecraftServer server, ServerPlayer player, int band) {
        TowerLobby lobby = BY_HOST.get(player.getUUID());
        if (lobby == null) return lobbyOf(player.getUUID()).isPresent() ? "Only the host can choose the Threat." : "Pick a tower first.";
        if (lobby.counting()) return "The run is already starting.";
        if (lobby.trial().isPresent()) return "A trial sets its own difficulty. Choose a tower to leave the trial.";
        if (!com.cobbletowers.economy.AscensionLibScouting.threatEnabled()) return "Threat bands are not switched on on this server.";
        if (band < 0 || band > com.cobbletowers.ascension.ThreatPolicy.MAX_ENTRY) return "Pick a Threat from 1 to 6 (0 for none).";
        lobby.setEntryThreat(band);
        broadcast(server, lobby, "");
        if (band == 0) return "No Threat band: the tower uses its floor tiers.";
        int advice = com.cobbletowers.economy.AscensionLibScouting.recommendedThreat(lobby.team());
        String verdict = band <= advice ? "your team is built for it" : band == advice + 1 ? "a stretch for your team" : "well above what your team is built for";
        return "Entering at Threat " + roman(band) + " (" + verdict + "; advised Threat " + roman(advice) + ").";
    }

    /** I to VII. */
    static String roman(int band) {
        return switch (band) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII";
            default -> String.valueOf(band);
        };
    }

    /** The host confirms; the countdown runs and {@link #tick} launches the run when it ends. */
    public static String start(MinecraftServer server, ServerPlayer host) {
        TowerLobby lobby = BY_HOST.get(host.getUUID());
        if (lobby == null) return inRun(host.getUUID()) ? inRunMessage() : "Pick a tower first.";
        if (lobby.counting()) return "The run is already starting.";
        if (!lobby.modeConfirmed()) {
            return "Confirm the mode first (on the play screen)" + (RentalDraftService.isRentalLobby(lobby)
                    ? ", then everyone drafts and readies up." : ".");
        }
        if (RentalDraftService.isRentalLobby(lobby)) {
            List<String> waiting = new ArrayList<>();
            for (UUID id : lobby.team()) {
                if (lobby.isReady(id)) continue;
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                waiting.add(member == null ? id.toString().substring(0, 8) : name(member));
            }
            if (!waiting.isEmpty()) {
                return "Everyone must ready up (after drafting) before the run can start. Waiting on: " + String.join(", ", waiting) + ".";
            }
        }
        lobby.beginCountdown(System.currentTimeMillis(), COUNTDOWN_MILLIS);
        broadcast(server, lobby, "Starting " + towerName(lobby.tower()) + " in "
                + COUNTDOWN_MILLIS / 1000 + " seconds. Anyone may /tower leave to drop out.");
        return "Starting.";
    }

    /** The host settles on the mode (P33). A rental mode opens everyone's draft now. */
    public static String confirmMode(MinecraftServer server, ServerPlayer player) {
        TowerLobby lobby = BY_HOST.get(player.getUUID());
        if (lobby == null) return lobbyOf(player.getUUID()).isPresent() ? "Only the host can confirm the mode." : noTeam(player);
        if (lobby.counting()) return "The run is already starting.";
        if (lobby.modeConfirmed()) return "The mode is already confirmed.";
        lobby.confirmMode();
        boolean rental = RentalDraftService.isRentalLobby(lobby);
        broadcast(server, lobby, name(player) + " confirmed the mode" + (rental ? ": draft your teams." : "."));
        RentalDraftService.promptAll(server, lobby);
        return "Mode confirmed.";
    }

    /**
     * Ready up or stand down (P33). Rental lobbies only, finished drafts only; a running countdown is not
     * interrupted.
     */
    public static String ready(MinecraftServer server, ServerPlayer player, boolean value) {
        Optional<TowerLobby> found = lobbyOf(player.getUUID());
        if (found.isEmpty()) return noTeam(player);
        TowerLobby lobby = found.get();
        if (!RentalDraftService.isRentalLobby(lobby)) return "There is nothing to ready up for in this mode.";
        if (lobby.counting()) return "The run is already starting.";
        if (!lobby.modeConfirmed()) return "Waiting for the host to confirm the mode.";
        if (value && !RentalDraftService.draftOf(player.getUUID()).filter(RentalDraft::complete).isPresent()) {
            RentalDraftService.prompt(server, lobby, player);
            return "Finish your draft first: you can only ready up once your team is drafted.";
        }
        lobby.setReady(player.getUUID(), value);
        broadcast(server, lobby, name(player) + (value ? " is ready." : " is no longer ready."));
        return value ? "You are ready." : "You are not ready.";
    }

    /** Re-sends the lobby to everyone in it (without opening anything), after something changed that it shows. */
    public static void refresh(MinecraftServer server, TowerLobby lobby) {
        broadcast(server, lobby, "");
    }

    // ---- the tick ----------------------------------------------------------------------------

    static void tick(MinecraftServer server, long now) {
        for (TowerLobby lobby : List.copyOf(BY_HOST.values())) {
            // One lobby's fault must not stall the others, nor repeat every tick: its countdown is cancelled.
            try {
                tickLobby(server, lobby, now);
            } catch (RuntimeException ex) {
                TowerLog.error("The lobby of " + lobby.host() + " failed to tick; cancelling its countdown", ex);
                lobby.cancelCountdown();
                broadcast(server, lobby, "The run could not start. Try again.");
            }
        }
    }

    private static void tickLobby(MinecraftServer server, TowerLobby lobby, long now) {
        for (UUID lapsed : lobby.expire(now)) {
            ServerPlayer player = server.getPlayerList().getPlayer(lapsed);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Your invite to the team lapsed."));
                sendState(server, player, null, "");
            }
            broadcast(server, lobby, "An invite lapsed.");
        }
        if (!lobby.counting()) return;
        if (lobby.countdownDue(now)) {
            if (!com.cobbletowers.instance.HeavyWork.tryAcquire(now)) {
                // The tower is busy building or clearing other arenas (one at a time keeps the server
                // responsive): wait a second.
                lobby.beginCountdown(now, 1_000L);
                for (UUID id : lobby.team()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) player.displayClientMessage(Component.literal("The tower is preparing other arenas; starting in a moment..."), true);
                }
                return;
            }
            LobbyLaunch.launch(server, lobby, now);
        } else {
            int left = lobby.secondsLeft(now);
            if (!lobby.announce(left)) return;
            for (UUID id : lobby.team()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) player.displayClientMessage(Component.literal("Starting in " + left + "..."), true);
            }
        }
    }

    // ---- choosing what to register (P18) -----------------------------------------------------

    /** Adds the Pokemon to the player's registration, or takes it off if it is already there. */
    public static String choose(MinecraftServer server, ServerPlayer player, UUID pokemon) {
        Optional<TowerLobby> found = lobbyOf(player.getUUID());
        if (found.isEmpty()) return inRun(player.getUUID()) ? inRunMessage() : "Join or start a team first.";
        TowerLobby lobby = found.get();
        if (lobby.counting()) return "The run is already starting.";
        if (!PartyStorage.snapshot(player).entries().containsKey(pokemon)) return "That Pokemon is not yours.";

        List<UUID> now = new ArrayList<>(lobby.chosenOf(player.getUUID()));
        String reply;
        if (now.remove(pokemon)) {
            reply = "Removed from your registration.";
        } else if (now.size() >= registerLimit(lobby)) {
            return "You can register at most " + registerLimit(lobby) + " for this tower.";
        } else {
            now.add(pokemon);
            reply = "Registered.";
        }
        lobby.setChosen(player.getUUID(), now);
        broadcast(server, lobby, "");
        sendRegistration(server, player, reply, false);
        return reply;
    }

    public static String clearChoice(MinecraftServer server, ServerPlayer player) {
        Optional<TowerLobby> found = lobbyOf(player.getUUID());
        if (found.isEmpty()) return noTeam(player);
        if (found.get().counting()) return "The run is already starting.";
        found.get().setChosen(player.getUUID(), List.of());
        broadcast(server, found.get(), "");
        sendRegistration(server, player, "Registration cleared: your current party will be used.", false);
        return "Registration cleared.";
    }

    /** How many Pokemon the lobby's tower lets a player register. */
    public static int registerLimit(TowerLobby lobby) {
        TowerContent content = TowerDefinitionRegistry.content();
        TowerDefinition tower = content.towers().get(lobby.tower());
        RulesetDefinition ruleset = RulesetResolver.forTower(content, tower, lobby.playlist());
        return ruleset == null ? PartyArrangement.PARTY_SIZE : ruleset.registeredPartySize();
    }

    /** The most Pokemon the web chooser is sent (about 100 characters each against a 32,000 character message). */
    private static final int MAX_CHOOSER_ENTRIES = 200;

    /** The chooser: everything the player owns, what they have chosen, and the limit. */
    public static void sendRegistration(MinecraftServer server, ServerPlayer player, String message, boolean open) {
        PartyStorage.Snapshot snapshot = PartyStorage.snapshot(player);
        List<RegistrationStatePayload.Entry> entries = new ArrayList<>();
        for (PartyStorage.Entry entry : snapshot.entries().values()) {
            String where = entry.slot().isParty() ? "Party " + (entry.slot().index() + 1) : "Box " + (entry.slot().index() + 1);
            entries.add(new RegistrationStatePayload.Entry(entry.id(), entry.name(), entry.level(), entry.fainted(), where));
        }
        Optional<TowerLobby> lobby = lobbyOf(player.getUUID());
        List<UUID> chosen = lobby.map(found -> found.chosenOf(player.getUUID())).orElse(List.of());
        if (entries.size() > MAX_CHOOSER_ENTRIES && com.cobbletowers.webgui.WebGuiBridge.takesOver(player, RegistrationStatePayload.TYPE)) {
            // A page message has a size limit: keep the chosen ones, then party and box order.
            List<RegistrationStatePayload.Entry> kept = new ArrayList<>(entries.stream().filter(e -> chosen.contains(e.id())).toList());
            for (RegistrationStatePayload.Entry entry : entries) {
                if (kept.size() >= MAX_CHOOSER_ENTRIES) break;
                if (!chosen.contains(entry.id())) kept.add(entry);
            }
            List<RegistrationStatePayload.Entry> all = entries;
            kept.sort(java.util.Comparator.comparingInt(all::indexOf));
            entries = kept;
            message = message.isEmpty() ? "Showing the first " + MAX_CHOOSER_ENTRIES + " Pokemon: move others into your party or first boxes." : message;
        }
        TowerNetworking.sendRegistration(player, new RegistrationStatePayload(entries, chosen,
                lobby.map(LobbyService::registerLimit).orElse(PartyArrangement.PARTY_SIZE), message, open));
    }

    /** One line per Pokemon the player owns, for an operator or a test: place, id, name and level. */
    public static List<String> describePokemon(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        java.util.Set<UUID> lent = new java.util.HashSet<>(com.cobbletowers.battle.cobblemon.RentalStorage.tagged(player));
        for (PartyStorage.Entry entry : PartyStorage.snapshot(player).entries().values()) {
            lines.add((entry.slot().isParty() ? "party " + entry.slot().index() : "box " + entry.slot().index() + "/" + entry.slot().sub())
                    + " " + entry.id() + " " + entry.name() + " Lv" + entry.level() + (entry.fainted() ? " fainted" : "")
                    + (lent.contains(entry.id()) ? " RENTAL" : ""));
        }
        return lines;
    }

    // ---- state shown to the screen -------------------------------------------------------------

    public static void openScreen(MinecraftServer server, ServerPlayer player) {
        sendState(server, player, lobbyOf(player.getUUID()).orElse(null), "", true);
    }

    /** Same as {@link #openScreen}, with a sentence for the screen's message line. */
    public static void openScreenWithMessage(MinecraftServer server, ServerPlayer player, String message) {
        sendState(server, player, lobbyOf(player.getUUID()).orElse(null), message, true);
    }

    static void dissolve(MinecraftServer server, TowerLobby lobby, String message) {
        BY_HOST.remove(lobby.host());
        RentalDraftService.clearAll(lobby);
        List<UUID> everyone = new ArrayList<>(lobby.team());
        everyone.addAll(lobby.pending());
        for (UUID id : everyone) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            if (!message.isEmpty()) player.sendSystemMessage(Component.literal(message));
            sendState(server, player, null, message);
        }
    }

    static void broadcast(MinecraftServer server, TowerLobby lobby, String message) {
        List<UUID> everyone = new ArrayList<>(lobby.team());
        everyone.addAll(lobby.pending());
        for (UUID id : everyone) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            if (!message.isEmpty()) player.sendSystemMessage(Component.literal(message));
            sendState(server, player, lobby, message);
        }
    }

    private static void sendState(MinecraftServer server, ServerPlayer player, TowerLobby lobby, String message) {
        sendState(server, player, lobby, message, false);
    }

    private static void sendState(MinecraftServer server, ServerPlayer player, TowerLobby lobby, String message,
                                  boolean open) {
        TowerNetworking.sendPlayState(player, playState(server, player, lobby, message, open));
    }

    public static PlayStatePayload playState(MinecraftServer server, ServerPlayer player, TowerLobby lobby, String message, boolean open) {
        TowerContent content = TowerDefinitionRegistry.content();
        List<PlayStatePayload.Tower> towers = content.openCatalogTowers(System.currentTimeMillis()).stream()
                .map(tower -> new PlayStatePayload.Tower(tower.id(), tower.displayName())).toList();
        List<Integer> levels = PartyReader.members(player).stream().map(PartyMember::level).toList();

        int role = 0;
        String selected = "";
        String hostName = "";
        List<PlayStatePayload.Member> members = new ArrayList<>();
        int countdown = -1;
        PlayStatePayload.Depth depth = PlayStatePayload.Depth.none();
        PlayStatePayload.Threat threat = PlayStatePayload.Threat.none();
        PlayStatePayload.Modes modes = PlayStatePayload.Modes.none();
        if (lobby != null) {
            long now = System.currentTimeMillis();
            selected = lobby.tower().toString();
            ServerPlayer host = server.getPlayerList().getPlayer(lobby.host());
            hostName = host == null ? lobby.host().toString().substring(0, 8) : name(host);
            UUID me = player.getUUID();
            role = me.equals(lobby.host()) ? 1
                    : lobby.responseOf(me).orElse(null) == TowerLobby.Response.ACCEPTED ? 3 : 2;
            for (UUID id : lobby.invitees()) {
                ServerPlayer other = server.getPlayerList().getPlayer(id);
                String label = other == null ? id.toString().substring(0, 8) : name(other);
                members.add(new PlayStatePayload.Member(label, lobby.responseOf(id).orElse(null) == TowerLobby.Response.ACCEPTED,
                        lobby.isReady(id)));
            }
            countdown = lobby.secondsLeft(now);
            TowerDefinition chosen = content.towers().get(lobby.tower());
            depth = new PlayStatePayload.Depth(lobby.ascension(), maxAscension(server, lobby), chosen != null && chosen.ascension());
            boolean threatOn = com.cobbletowers.economy.AscensionLibScouting.threatEnabled() && lobby.trial().isEmpty();
            threat = threatOn ? new PlayStatePayload.Threat(lobby.entryThreat(),
                    com.cobbletowers.economy.AscensionLibScouting.recommendedThreat(lobby.team()), true) : PlayStatePayload.Threat.none();
            List<String> modeIds = new ArrayList<>();
            List<String> modeNames = new ArrayList<>();
            for (PlaylistDefinition playlist : PlaylistRegistry.all()) {
                modeIds.add(playlist.id().getPath());
                modeNames.add(playlist.displayName());
            }
            modes = new PlayStatePayload.Modes(modeIds, modeNames, lobby.playlist().map(ResourceLocation::getPath).orElse(""),
                    RentalDraftService.isRentalLobby(lobby),
                    new PlayStatePayload.Readiness(lobby.modeConfirmed(), RentalDraftService.draftOf(me).filter(RentalDraft::complete).isPresent(),
                            lobby.isReady(me), lobby.isReady(lobby.host())));
        }
        List<PlayStatePayload.Roster> rosters = lobby != null && (role == 1 || role == 3) ? rosters(server, player, lobby) : List.of();
        return new PlayStatePayload(towers,
                new PlayStatePayload.Lobby(role, selected, hostName, members, countdown,
                        new PlayStatePayload.Options(depth, modes, rosters, threat)), levels, message, open);
    }

    /**
     * What each other player on the team is bringing (P51): their registered Pokemon, or the drafted team in a rental
     * lobby once the draft is finished. Only for someone already on the team; an invitee sees nobody's.
     */
    private static List<PlayStatePayload.Roster> rosters(MinecraftServer server, ServerPlayer viewer, TowerLobby lobby) {
        List<PlayStatePayload.Roster> out = new ArrayList<>();
        boolean rental = RentalDraftService.isRentalLobby(lobby);
        int limit = registerLimit(lobby);
        for (UUID id : lobby.team()) {
            if (id.equals(viewer.getUUID())) continue;
            ServerPlayer other = server.getPlayerList().getPlayer(id);
            if (other == null) continue;
            List<TowerHallStatePayload.Mon> cards;
            try {
                cards = rental ? RentalDraftService.draftOf(id).filter(RentalDraft::complete)
                                .map(d -> d.team().stream().map(LobbyService::rentalCard).toList()).orElse(List.of())
                        : PartyReader.registeredCards(other, lobby.chosenOf(id), limit);
            } catch (RuntimeException ex) {
                TowerLog.warn("Lobby roster for {} could not be read: {}", name(other), ex.toString());
                cards = List.of();
            }
            if (!cards.isEmpty()) out.add(new PlayStatePayload.Roster(name(other), cards));
        }
        return out;
    }

    private static TowerHallStatePayload.Mon rentalCard(com.cobbletowers.definition.RentalSetDefinition set) {
        return new TowerHallStatePayload.Mon(set.species(), com.cobbletowers.menu.HallExtras.spriteName(set.species()), set.level(), false, 100,
                set.types());
    }

    // ---- helpers -------------------------------------------------------------------------------

    static boolean inRun(UUID player) {
        return TowerRuns.forPlayer(player).filter(run -> !run.isRetired()).isPresent();
    }

    private static String towerName(ResourceLocation id) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(id);
        return tower == null ? id.toString() : tower.displayName();
    }

    static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }
}
