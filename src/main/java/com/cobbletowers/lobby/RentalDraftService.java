package com.cobbletowers.lobby;

import com.cobbletowers.ServerState;
import com.cobbletowers.definition.PlaylistDefinition;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.definition.RentalSetRegistry;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalDraft.Result;
import com.cobbletowers.rental.RentalDraw;
import com.cobbletowers.rental.RentalDraw.Offer;
import com.cobbletowers.network.RentalDraftActionPayload;
import com.cobbletowers.network.RentalDraftPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Drafts in progress in lobbies (P33), one per player of a Rental lobby. In memory only. Each draft is drawn on first
 * open; a trial's seed is shared and rolls no God Pack, otherwise random.
 */
public final class RentalDraftService {

    private static final Map<UUID, RentalDraft> DRAFTS = new HashMap<>();

    static {
        ServerState.onStop(DRAFTS::clear);
    }

    private RentalDraftService() {}

    public static boolean isRentalLobby(TowerLobby lobby) {
        return lobby.playlist().flatMap(PlaylistRegistry::get).map(PlaylistDefinition::rental).orElse(false);
    }

    /** A rental lobby whose host has confirmed the mode: the only kind whose drafts may be opened. */
    public static boolean isOpen(TowerLobby lobby) {
        return isRentalLobby(lobby) && lobby.modeConfirmed();
    }

    private static final String UNCONFIRMED = "Waiting for the host to confirm the mode.";

    public static Optional<RentalDraft> draftOf(UUID player) {
        return Optional.ofNullable(DRAFTS.get(player));
    }

    /** Takes a draft made elsewhere (a Tower Hub gate's board, owner 23 Nov 2026) as the player's own for their lobby. */
    public static void adopt(UUID player, RentalDraft draft) {
        DRAFTS.put(player, draft);
    }

    public static void clear(UUID player) {
        DRAFTS.remove(player);
    }

    /** Forgets every draft of a lobby's players (the lobby ended, or changed what it is playing). */
    public static void clearAll(TowerLobby lobby) {
        for (UUID id : lobby.team()) DRAFTS.remove(id);
        for (UUID id : lobby.pending()) DRAFTS.remove(id);
    }

    /** Draws a player's draft if they have none yet; returns it. */
    public static RentalDraft open(MinecraftServer server, TowerLobby lobby, UUID player) {
        return DRAFTS.computeIfAbsent(player, id -> {
            boolean trial = lobby.trial().isPresent();
            long seed = trial ? lobby.trial().get().seed() : server.overworld().getRandom().nextLong();
            return new RentalDraft(RentalDraw.draw(RentalSetRegistry.all(), seed, !trial));
        });
    }

    /** Whether {@link #prompt} will open the pack screen for this player (rather than chat lines, or nothing). */
    public static boolean opensScreen(ServerPlayer player, TowerLobby lobby) {
        return isOpen(lobby) && !RentalSetRegistry.all().isEmpty()
                && com.cobbletowers.network.ClientUi.canShow(player, RentalDraftPayload.TYPE);
    }

    /**
     * Puts a member's draft in front of them unasked (pack screen, or chat for a client that cannot show it), on
     * joining a rental lobby or when it switches to a rental mode.
     */
    public static void prompt(MinecraftServer server, TowerLobby lobby, ServerPlayer player) {
        if (!isOpen(lobby) || RentalSetRegistry.all().isEmpty()) return;
        RentalDraft draft = open(server, lobby, player.getUUID());
        if (opensScreen(player, lobby)) {
            sendScreen(player, draft, "");
        } else {
            describe(draft).forEach(line -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line)));
        }
    }

    /** {@link #prompt} for everyone on the team who is online. */
    public static void promptAll(MinecraftServer server, TowerLobby lobby) {
        for (UUID id : lobby.team()) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null) prompt(server, lobby, member);
        }
    }

    // ---- the chat flow
    // -----------------------------------------------------------------------------------------------

    /** What {@code /tower draft} says: the current pack, or the finished team. */
    public static List<String> view(MinecraftServer server, ServerPlayer player) {
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isPresent() && isRentalLobby(lobby.get()) && !lobby.get().modeConfirmed()) return List.of(UNCONFIRMED);
        if (lobby.isEmpty() || !isRentalLobby(lobby.get())) {
            return List.of("There is nothing to draft: pick a tower and the Rental playlist first.");
        }
        if (RentalSetRegistry.all().isEmpty()) return List.of("No rental sets are loaded.");
        RentalDraft draft = open(server, lobby.get(), player.getUUID());
        if (com.cobbletowers.network.ClientUi.canShow(player, RentalDraftPayload.TYPE)) {
            sendScreen(player, draft, "");
            return List.of("Opening your packs.");
        }
        return describe(draft);
    }

    /** The same view as chat lines, whatever the client can show. */
    public static List<String> text(MinecraftServer server, ServerPlayer player) {
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isPresent() && isRentalLobby(lobby.get()) && !lobby.get().modeConfirmed()) return List.of(UNCONFIRMED);
        if (lobby.isEmpty() || !isRentalLobby(lobby.get())) return List.of("There is nothing to draft here.");
        return describe(open(server, lobby.get(), player.getUUID()));
    }

    // ---- the pack-opening screen
    // ---------------------------------------------------------------------------------------

    public static void sendScreen(ServerPlayer player, RentalDraft draft, String message) {
        if (com.cobbletowers.network.ClientUi.canShow(player, RentalDraftPayload.TYPE)) {
            com.cobbletowers.network.ClientUi.send(player, RentalDraftPayload.of(draft, message, com.cobbletowers.battle.cobblemon.CobblemonMoves::resolve));
        }
    }

    /** The screen's buttons. Every pick is checked by the draft; a modified client only gets a refusal. */
    public static void handle(MinecraftServer server, ServerPlayer player, RentalDraftActionPayload action) {
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isEmpty() || !isOpen(lobby.get()) || RentalSetRegistry.all().isEmpty()) return;
        if (action.action() == RentalDraftActionPayload.Action.READY) {
            // The draft is done: ready up and go back to the lobby, where the host starts (the pack screen is not
            // left open over it).
            LobbyService.openScreenWithMessage(server, player, LobbyService.ready(server, player, true));
            return;
        }
        if (action.action() == RentalDraftActionPayload.Action.LOBBY) {
            LobbyService.openScreen(server, player);
            return;
        }
        RentalDraft draft = open(server, lobby.get(), player.getUUID());
        String message = switch (action.action()) {
            case OPEN, READY, LOBBY -> "";
            case PICK -> say(draft.pick(draft.currentPack(), List.of(action.a(), action.b())));
            case RESTART -> {
                draft.restart();
                lobby.get().setReady(player.getUUID(), false);
                yield "";
            }
        };
        sendScreen(player, draft, message);
        LobbyService.refresh(server, lobby.get());
    }

    private static String say(Result result) {
        return switch (result) {
            case OK -> "";
            case ALREADY_COMPLETE -> "Your draft is finished.";
            case NOT_THIS_PACK -> "That is not the pack you are choosing from.";
            case WRONG_COUNT -> "Keep exactly two cards.";
            case DUPLICATE -> "Pick two different cards.";
            case OUT_OF_RANGE -> "Pick cards from the pack.";
            case TOO_MANY_LEGENDARY -> "A team may keep at most " + RentalDraft.MAX_TOP_RARITY + " legendary or mythic Pokemon.";
        };
    }

    public static List<String> describe(RentalDraft draft) {
        List<String> lines = new ArrayList<>();
        if (draft.complete()) {
            lines.add("Your team is drafted:");
            int number = 1;
            List<RentalSetDefinition> team = draft.team();
            for (int i = 0; i < team.size(); i++) lines.add("  " + number++ + ". " + line(team.get(i), draft.teamTiers().get(i)));
            lines.add("Use Ready on the play screen; the host can start once everyone is ready. Restart the draft there to draft again.");
            return lines;
        }
        int pack = draft.currentPack();
        RentalDraw.Pack current = draft.offer().packs().get(pack);
        lines.add("Pack " + (pack + 1) + " of " + RentalDraw.PACKS + (current.god() ? "  *** GOD PACK ***" : "")
                + ": keep two on the draft screen.");
        for (int i = 0; i < current.cards().size(); i++) lines.add("  " + (i + 1) + ". " + line(current.cards().get(i), current.tiers().get(i)));
        if (!draft.team().isEmpty()) {
            lines.add("Kept so far: " + String.join(", ", draft.team().stream().map(RentalSetDefinition::displayName).toList()));
        }
        return lines;
    }

    private static String line(RentalSetDefinition set, RentalSetDefinition.Rarity tier) {
        return "[" + tier.lower() + "] " + set.displayName() + " (" + set.ability() + ", "
                + set.item().map(item -> item.getPath()).orElse("no item") + "): " + String.join(", ", set.moves())
                + (set.role().isEmpty() ? "" : " - " + set.role());
    }

    /** Keeps two cards from the current pack. {@code a} and {@code b} are 1-based, as shown. */
    public static List<String> pick(MinecraftServer server, ServerPlayer player, int a, int b) {
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isPresent() && isRentalLobby(lobby.get()) && !lobby.get().modeConfirmed()) return List.of(UNCONFIRMED);
        if (lobby.isEmpty() || !isRentalLobby(lobby.get())) return List.of("There is nothing to draft here.");
        RentalDraft draft = open(server, lobby.get(), player.getUUID());
        Result result = draft.pick(draft.currentPack(), List.of(a - 1, b - 1));
        sendScreen(player, draft, say(result));
        LobbyService.refresh(server, lobby.get());
        return switch (result) {
            case OK -> describe(draft);
            case ALREADY_COMPLETE -> List.of("Your draft is finished. Restart it on the draft screen to draft again.");
            case NOT_THIS_PACK -> List.of("That is not the pack you are choosing from.");
            case WRONG_COUNT -> List.of("Keep exactly two cards.");
            case DUPLICATE -> List.of("Pick two different cards.");
            case OUT_OF_RANGE -> List.of("Pick cards numbered 1 to " + RentalDraw.CARDS + ".");
            case TOO_MANY_LEGENDARY -> List.of("A team may keep at most " + RentalDraft.MAX_TOP_RARITY
                    + " legendary or mythic Pokemon. Choose different cards.");
        };
    }

    public static List<String> restart(MinecraftServer server, ServerPlayer player) {
        Optional<TowerLobby> lobby = LobbyService.lobbyOf(player.getUUID());
        if (lobby.isPresent() && isRentalLobby(lobby.get()) && !lobby.get().modeConfirmed()) return List.of(UNCONFIRMED);
        if (lobby.isEmpty() || !isRentalLobby(lobby.get())) return List.of("There is nothing to draft here.");
        open(server, lobby.get(), player.getUUID()).restart();
        lobby.get().setReady(player.getUUID(), false);
        LobbyService.refresh(server, lobby.get());
        return view(server, player);
    }

    /** Whether every player of the lobby has a finished draft. */
    public static boolean allDone(TowerLobby lobby) {
        for (UUID id : lobby.team()) {
            if (draftOf(id).map(RentalDraft::complete).orElse(false)) continue;
            return false;
        }
        return true;
    }

    public static Offer offerOf(UUID player) {
        return DRAFTS.get(player).offer();
    }
}
