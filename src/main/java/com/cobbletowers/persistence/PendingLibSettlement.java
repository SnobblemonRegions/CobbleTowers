package com.cobbletowers.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A boss victory's AscensionLib payout not yet confirmed, kept on disk until it is. The library pays once per
 * (encounter, player, reward kind), so repeating is safe; this record is the memory of a settlement it never
 * received. It holds the call's arguments, never an amount.
 * @param players who is still owed
 * @param createdAt epoch millis, for expiry
 * @param attempts tries so far
 */
public record PendingLibSettlement(UUID encounterId, Kind kind, String outcome, int fromFloor, int bossFloor,
                                   boolean keenEyeFloor, List<UUID> players, long createdAt, int attempts,
                                   int cycle, String towerId, List<UUID> extrasPaid, int threat) {

    /** Without a Threat band (balance phase 2): every settlement written before it, and every run with no band. */
    public PendingLibSettlement(UUID encounterId, Kind kind, String outcome, int fromFloor, int bossFloor,
                                boolean keenEyeFloor, List<UUID> players, long createdAt, int attempts,
                                int cycle, String towerId, List<UUID> extrasPaid) {
        this(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor, players, createdAt, attempts, cycle, towerId, extrasPaid, 0);
    }

    /** This settlement at a Threat band (1-7), paid through AscensionLib's Threat calls; 0 keeps the older calls. */
    public PendingLibSettlement withThreat(int band) {
        return new PendingLibSettlement(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor, players, createdAt, attempts, cycle, towerId,
                extrasPaid, Math.max(0, Math.min(7, band)));
    }

    /**
     * TRIAL carries its rank (1-3) in {@code bossFloor}. COMPLETION is a tower cycle's champion: it carries the run's
     * challenge-card count in {@code fromFloor}, whether the cycle was flawless in {@code keenEyeFloor}, the Ascension
     * cycle in {@code cycle} and the tower (whose reward table supplies the armor and tickets) in {@code towerId}.
     */
    public enum Kind { MILESTONE, SCOUTER_DROPS, TRIAL, COMPLETION }

    /** Every kind but a completion, which is the only one that needs a cycle and a tower. */
    public PendingLibSettlement(UUID encounterId, Kind kind, String outcome, int fromFloor, int bossFloor,
                                boolean keenEyeFloor, List<UUID> players, long createdAt, int attempts) {
        this(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor, players, createdAt, attempts, 0, "", List.of());
    }

    /** A completion as it is first submitted: nobody's armor and packs have been queued yet. */
    public PendingLibSettlement(UUID encounterId, Kind kind, String outcome, int fromFloor, int bossFloor,
                                boolean keenEyeFloor, List<UUID> players, long createdAt, int attempts, int cycle, String towerId) {
        this(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor, players, createdAt, attempts, cycle, towerId, List.of());
    }

    /** How long an unpaid settlement is kept trying before it is given up on and logged. */
    public static final long EXPIRY_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public PendingLibSettlement {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        players = List.copyOf(players);
        towerId = towerId == null ? "" : towerId;
        extrasPaid = extrasPaid == null ? List.of() : List.copyOf(extrasPaid);
    }

    /**
     * This settlement with more players marked as having had their completion armor and packs queued. Written to disk before the queue is delivered, so a crash and a
     * retry after the library already paid can never queue the same pieces twice ({@code extrasPaid} outlives the player leaving {@code players}).
     */
    public PendingLibSettlement withExtrasPaid(java.util.Collection<UUID> more) {
        var all = new ArrayList<>(extrasPaid);
        for (UUID player : more) if (!all.contains(player)) all.add(player);
        return new PendingLibSettlement(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor, players, createdAt, attempts, cycle, towerId, all, threat);
    }

    /** The statuses that mean "not paid yet, try again": the library was unavailable, or the wallet refused. */
    private static boolean retryable(String status) {
        return "DISABLED".equals(status) || "REFUSED".equals(status);
    }

    /**
     * What is left to settle after one call.
     * @param statuses the library's answer per player, or null when the call failed. A player absent from the answer
     *     is finished. {@code GRANTED}, {@code ALREADY_GRANTED}, {@code NOT_PAID} and {@code CONFLICT} are final;
     *     {@code DISABLED} and {@code REFUSED} are retried.
     * @return the settlement with only the still-owed players, or empty
     */
    public Optional<PendingLibSettlement> afterAttempt(Map<?, ?> statuses) {
        List<UUID> remaining = new ArrayList<>();
        for (UUID player : players) {
            if (statuses == null || retryable(String.valueOf(statuses.get(player)))) remaining.add(player);
        }
        if (remaining.isEmpty()) return Optional.empty();
        return Optional.of(new PendingLibSettlement(encounterId, kind, outcome, fromFloor, bossFloor, keenEyeFloor,
                remaining, createdAt, attempts + 1, cycle, towerId, extrasPaid, threat));
    }

    public boolean expired(long now) {
        return now - createdAt > EXPIRY_MILLIS;
    }

    /** Identifies the settlement: one encounter settles each kind once. */
    public String key() {
        return encounterId + "|" + kind;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("encounter", encounterId);
        tag.putString("kind", kind.name());
        tag.putString("outcome", outcome);
        tag.putInt("from", fromFloor);
        tag.putInt("boss", bossFloor);
        tag.putBoolean("keen_eye", keenEyeFloor);
        ListTag list = new ListTag();
        for (UUID player : players) list.add(NbtUtils.createUUID(player));
        tag.put("players", list);
        tag.putLong("created_at", createdAt);
        tag.putInt("attempts", attempts);
        tag.putInt("cycle", cycle);
        if (!towerId.isEmpty()) tag.putString("tower", towerId);
        if (!extrasPaid.isEmpty()) {
            ListTag paid = new ListTag();
            for (UUID player : extrasPaid) paid.add(NbtUtils.createUUID(player));
            tag.put("extras_paid", paid);
        }
        if (threat > 0) tag.putInt("threat", threat);
        return tag;
    }

    public static PendingLibSettlement fromTag(CompoundTag tag) {
        List<UUID> players = new ArrayList<>();
        for (Tag entry : tag.getList("players", Tag.TAG_INT_ARRAY)) players.add(NbtUtils.loadUUID(entry));
        List<UUID> paid = new ArrayList<>();
        for (Tag entry : tag.getList("extras_paid", Tag.TAG_INT_ARRAY)) paid.add(NbtUtils.loadUUID(entry));
        return new PendingLibSettlement(tag.getUUID("encounter"), Kind.valueOf(tag.getString("kind")),
                tag.getString("outcome"), tag.getInt("from"), tag.getInt("boss"), tag.getBoolean("keen_eye"), players,
                tag.getLong("created_at"), tag.getInt("attempts"), tag.getInt("cycle"), tag.getString("tower"), paid,
                Math.max(0, Math.min(7, tag.getInt("threat"))));
    }
}
