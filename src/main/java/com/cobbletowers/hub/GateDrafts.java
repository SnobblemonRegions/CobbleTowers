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
    /**
     * When each draft was last looked after. A draft outlives its party leaving the mode by this long (audit, 25 Nov 2026: one stray click
     * on another mode tile threw away every member's finished team), so going back to Rental Draft finds it as it was.
     */
    private static final Map<UUID, Long> SEEN_AT = new HashMap<>();
    static final long KEEP_MILLIS = 30_000L;

    private GateDrafts() {}

    static void clear() {
        VIEWS.values().forEach(GateDraft::destroy);
        VIEWS.clear();
        DRAFTS.clear();
        SEEN.clear();
        SEEN_AT.clear();
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
        long now = System.currentTimeMillis();
        for (UUID id : party.members()) {
            SEEN.add(id);
            SEEN_AT.put(id, now);
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

    /** The key a shrine's drafts are kept under: its kind and the trial's seed, so a new day's trial deals new packs. */
    static ResourceLocation shrineKey(String kind, long seed) {
        return ResourceLocation.fromNamespaceAndPath("cobbletowers", "trial_" + kind + "_" + Long.toHexString(seed));
    }

    /**
     * A trial shrine whose trial is a Rental Draft (owner, 25 Nov 2026): each player ready at it drafts on a board hung over the shrine's
     * arch, from the trial's own packs (its shared seed, no God Pack, as the trial's draft screen dealt them).
     * @param drafters the players ready at this shrine
     * @param onRing whether a player stands on the shrine's ring now: stepping on it brings their board up
     * @param near whether a player is still at the shrine: their board stays up while they are, so they can walk up to it to click
     */
    static void shrine(MinecraftServer server, PartyService.Hub hub, HubLayout.Shrine shrine, ResourceLocation key, long seed,
                       List<UUID> drafters, java.util.function.Predicate<UUID> onRing, java.util.function.Predicate<UUID> near) {
        if (RentalSetRegistry.all().isEmpty()) return;
        // the board stands just out from the shrine's wall, facing into the pavilion: the plate's cell is the wall's front course, and
        // the set-back spot is further in along the shrine's facing
        HubLayout.Cell plate = shrine.plate();
        HubLayout.Spot back = shrine.portal().back();
        double fx = Math.signum(back.x() - plate.x()), fz = Math.signum(back.z() - plate.z());
        if (Math.abs(back.x() - plate.x()) < Math.abs(back.z() - plate.z())) fx = 0;
        else fz = 0;
        Vec3 face = new Vec3(hub.origin().getX() + plate.x() + 0.5 + fx * 0.7, hub.origin().getY() + back.y(),
                hub.origin().getZ() + plate.z() + 0.5 + fz * 0.7);
        Vec3 base = face.add(fx * GateDraft.CARD_X, 0, fz * GateDraft.CARD_X);
        float yaw = (float) Math.toDegrees(Math.atan2(fz, -fx));                // the board's frame turned so its +x runs back to the wall
        long now = System.currentTimeMillis();
        for (UUID id : drafters) {
            SEEN.add(id);
            SEEN_AT.put(id, now);
            Entry entry = DRAFTS.get(id);
            if (entry == null || !entry.tower().equals(key)) {
                entry = new Entry(key, new RentalDraft(RentalDraw.draw(RentalSetRegistry.all(), seed, false)));
                DRAFTS.put(id, entry);
                GateDraft old = VIEWS.remove(id);
                if (old != null) old.destroy();
            }
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            GateDraft view = VIEWS.get(id);
            boolean show = member != null && member.level() == hub.level() && (onRing.test(id) || (view != null && near.test(id)));
            if (show && (view == null || view.getAttachment() == null)) {
                if (view != null) view.destroy();
                VIEWS.put(id, new GateDraft(hub.level(), base, yaw, id, entry.draft(), p -> { }, GateDraft.Frame.SHRINE));
            } else if (!show && view != null) {
                view.destroy();
                VIEWS.remove(id);
            }
        }
    }

    /**
     * Ends the pass: a player whose party no longer holds a gate in Rental Draft loses their board at once and their draft after
     * {@link #KEEP_MILLIS}.
     */
    static void end() {
        long now = System.currentTimeMillis();
        DRAFTS.keySet().removeIf(id -> !SEEN.contains(id) && now - SEEN_AT.getOrDefault(id, 0L) > KEEP_MILLIS);
        SEEN_AT.keySet().retainAll(DRAFTS.keySet());
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
        PartyService.active(tower, player.getUUID());
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
