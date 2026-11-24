package com.cobbletowers.hub;

import com.cobbletowers.definition.RentalSetRegistry;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalDraw;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The Rental Drafts at the Tower Hub's gates (owner, 23 Nov 2026): each player in a party that has picked Rental Draft at a gate drafts their
 * team on the gate's rules board while they stand on the altar's ring ({@link GateDraft}, seen by them alone). The draft is kept while their
 * party holds the gate in that mode, whether or not they stand on the ring; leaving the ring only takes the board away. A finished draft is
 * the player's ready: the leader's walk-in hands the drafts to the lobby and starts the run. Server thread only.
 */
final class GateDrafts {

    /** Told when a player's draft moves on, so the altar and the party card can say how far everyone is. */
    interface Callbacks {
        void changed(ServerPlayer player);
    }

    private record Entry(ResourceLocation tower, RentalDraft draft) {}

    private static final Map<UUID, Entry> DRAFTS = new HashMap<>();
    private static final Map<UUID, GateDraft> VIEWS = new HashMap<>();
    /** Every player whose draft was looked after this tick, so the rest can be let go. */
    private static final Set<UUID> SEEN = new HashSet<>();

    private GateDrafts() {}

    static void clear() {
        VIEWS.values().forEach(GateDraft::destroy);
        VIEWS.clear();
        DRAFTS.clear();
        SEEN.clear();
    }

    /** Starts a tick's pass over the gates. */
    static void begin() {
        SEEN.clear();
    }

    /**
     * One gate, held by `party` in Rental Draft: each member keeps a draft; the ones on the ring see it on the board.
     * @param onRing whether a member stands on this gate's altar ring: stepping on it brings their board up
     * @param atGate whether a member is still at this gate: their board stays up while they are, so they can walk up to it to click (the
     *               board's click blocks stand behind its face, out of reach from the ring for the outer cards)
     */
    static void gate(MinecraftServer server, PartyService.Hub hub, HubLayout.Portal portal, HubLayout.Gate gate, Party party,
                     java.util.function.Predicate<UUID> onRing, java.util.function.Predicate<UUID> atGate, Callbacks on) {
        if (RentalSetRegistry.all().isEmpty()) return;
        HubLayout.Altar spot = gate.altar();
        BlockPos at = hub.origin().offset(spot.x(), spot.y(), spot.z());
        Vec3 base = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        for (UUID id : party.members()) {
            SEEN.add(id);
            Entry entry = DRAFTS.get(id);
            if (entry == null || !entry.tower().equals(portal.tower())) {
                long seed = hub.level().getRandom().nextLong();
                entry = new Entry(portal.tower(), new RentalDraft(RentalDraw.draw(RentalSetRegistry.all(), seed, true)));
                DRAFTS.put(id, entry);
                GateDraft old = VIEWS.remove(id);
                if (old != null) old.destroy();
            }
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            GateDraft view = VIEWS.get(id);
            boolean show = member != null && member.level() == hub.level() && (onRing.test(id) || (view != null && atGate.test(id)));
            if (show && (view == null || view.getAttachment() == null)) {
                if (view != null) view.destroy();
                VIEWS.put(id, new GateDraft(hub.level(), base, spot.yaw(), id, entry.draft(), on));
            } else if (!show && view != null) {
                view.destroy();
                VIEWS.remove(id);
            }
        }
    }

    /** Ends the pass: a player whose party no longer holds a gate in Rental Draft loses their draft. */
    static void end() {
        DRAFTS.keySet().removeIf(id -> !SEEN.contains(id));
        VIEWS.entrySet().removeIf(e -> {
            if (SEEN.contains(e.getKey())) return false;
            e.getValue().destroy();
            return true;
        });
    }

    /** A right-click on a gate's rules board: true if it was the player's draft board. */
    static boolean click(ServerPlayer player, ResourceLocation tower, Vec3 hit) {
        GateDraft view = VIEWS.get(player.getUUID());
        Entry entry = DRAFTS.get(player.getUUID());
        if (view == null || entry == null || !entry.tower().equals(tower)) return false;
        view.click(player, hit);
        return true;
    }

    /** Whether the player has a draft board in front of them now (its own card hints replace the board's). */
    static boolean drafting(UUID player) {
        return VIEWS.containsKey(player);
    }

    /** The player's finished draft for this tower, if they have one. */
    static Optional<RentalDraft> finished(UUID player, ResourceLocation tower) {
        Entry entry = DRAFTS.get(player);
        return entry == null || !entry.tower().equals(tower) || !entry.draft().complete() ? Optional.empty() : Optional.of(entry.draft());
    }

    /** How far a player's draft has gone, for the altar and the leader: 0 to 3 packs kept. */
    static int packsDone(UUID player, ResourceLocation tower) {
        Entry entry = DRAFTS.get(player);
        return entry == null || !entry.tower().equals(tower) ? 0 : entry.draft().currentPack();
    }

    /** Who of `team` has not finished drafting, as "Name (pack 2 of 3)". */
    static List<String> waiting(MinecraftServer server, List<UUID> team, ResourceLocation tower) {
        List<String> out = new ArrayList<>();
        for (UUID id : team) {
            if (finished(id, tower).isPresent()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            String name = p == null ? id.toString().substring(0, 8) : p.getGameProfile().getName();
            out.add(name + " (pack " + Math.min(RentalDraw.PACKS, packsDone(id, tower) + 1) + " of " + RentalDraw.PACKS + ")");
        }
        return out;
    }
}
