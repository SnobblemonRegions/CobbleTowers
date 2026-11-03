package com.cobbletowers.lobby;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * A team forming before a run exists: a host, a tower and up to three invitees. Pure and in memory; the first thing
 * persisted is the run. Time is passed in so expiry and the countdown are testable.
 */
public final class TowerLobby {

    /** TDS: up to four players share a run. The host counts. */
    public static final int MAX_PLAYERS = 4;

    /** How long an invite stays open before it lapses. */
    public static final long INVITE_TTL_MILLIS = 3 * 60 * 1000L;

    public enum Response { INVITED, ACCEPTED }

    public enum Result {
        OK,
        /** Host plus every invite, pending or accepted, already makes four. */
        FULL,
        /** That player already has an open invite or is on the team. */
        ALREADY_ON_TEAM,
        /** No open invite to answer (never sent, or it lapsed). */
        NO_INVITE,
        NOT_HOST
    }

    private record Member(Response response, long invitedAt) {}

    private final UUID host;
    private ResourceLocation tower;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private long countdownEndsAt = -1;
    /** The Ascension the team will start at (P30); 0 is the ordinary start. */
    private int ascension;
    /** The Threat band the team picked at the door (balance phase 2): 1-6, or 0 for none. Advice is shown, nothing is locked. */
    private int entryThreat;
    /**
     * The trial (P32) the team will attempt, empty for an ordinary run. It fixes the tower, playlist, seed and floor
     * limit.
     */
    private java.util.Optional<com.cobbletowers.trial.TrialSchedule.Instance> trial = java.util.Optional.empty();
    /** The playlist (P32) the team will play, empty for Standard. */
    private java.util.Optional<ResourceLocation> playlist = java.util.Optional.empty();
    /** Pokemon each player chose to register (P18). Absent or empty means "my current party". */
    private final Map<UUID, List<UUID>> chosen = new LinkedHashMap<>();
    /** Who has readied up (P33): a rental team starts only when every member is, and only a finished draft can be. */
    private final java.util.Set<UUID> ready = new java.util.HashSet<>();

    /** Whether the host has confirmed the mode (P33); changing mode, trial or tower withdraws it. */
    private boolean modeConfirmed;

    public boolean modeConfirmed() {
        return modeConfirmed;
    }

    public void confirmMode() {
        modeConfirmed = true;
        cancelCountdown();
    }

    public boolean isReady(UUID player) {
        return ready.contains(player);
    }

    public void setReady(UUID player, boolean value) {
        if (value) ready.add(player);
        else ready.remove(player);
    }

    /** Everyone must ready up again: what they readied for has changed. */
    public void clearReady() {
        ready.clear();
    }

    /**
     * The seed from a run code (P35), empty for a fresh random one. A different tower is a different offer, so it
     * clears it.
     */
    private java.util.Optional<Long> seed = java.util.Optional.empty();

    public java.util.Optional<Long> seed() {
        return seed;
    }

    public void setSeed(java.util.Optional<Long> next) {
        this.seed = next;
    }

    public TowerLobby(UUID host, ResourceLocation tower) {
        this.host = host;
        this.tower = tower;
    }

    public UUID host() {
        return host;
    }

    public ResourceLocation tower() {
        return tower;
    }

    public int ascension() {
        return ascension;
    }

    public java.util.Optional<com.cobbletowers.trial.TrialSchedule.Instance> trial() {
        return trial;
    }

    public void setTrial(java.util.Optional<com.cobbletowers.trial.TrialSchedule.Instance> next) {
        this.trial = next;
        modeConfirmed = false;
        ready.clear();
        cancelCountdown();
    }

    public java.util.Optional<ResourceLocation> playlist() {
        return playlist;
    }

    public void setPlaylist(java.util.Optional<ResourceLocation> next) {
        this.playlist = next;
        modeConfirmed = false;
        ready.clear();
        cancelCountdown();
    }

    public int entryThreat() {
        return entryThreat;
    }

    public void setEntryThreat(int next) {
        this.entryThreat = com.cobbletowers.ascension.ThreatPolicy.clampEntry(next);
        cancelCountdown();
    }

    public void setAscension(int next) {
        this.ascension = Math.max(0, next);
        cancelCountdown();
    }

    public void selectTower(ResourceLocation next) {
        this.tower = next;
        // Records are per tower, so the Ascension chosen for the old one means nothing for the new one.
        this.ascension = 0;
        this.playlist = java.util.Optional.empty();
        this.trial = java.util.Optional.empty();
        this.seed = java.util.Optional.empty();
        modeConfirmed = false;
        ready.clear();
        // A different tower is a different offer: anyone who already accepted agreed to the old one.
        members.replaceAll((id, member) -> new Member(Response.INVITED, member.invitedAt()));
        cancelCountdown();
    }

    public Result invite(UUID player, long now) {
        if (player.equals(host) || members.containsKey(player)) return Result.ALREADY_ON_TEAM;
        if (1 + members.size() >= MAX_PLAYERS) return Result.FULL;
        members.put(player, new Member(Response.INVITED, now));
        return Result.OK;
    }

    /** An invitee says yes. Lapsed invites are removed first, so one cannot be accepted late. */
    public Result accept(UUID player, long now) {
        expire(now);
        Member member = members.get(player);
        if (member == null) return Result.NO_INVITE;
        members.put(player, new Member(Response.ACCEPTED, member.invitedAt()));
        return Result.OK;
    }

    public Result decline(UUID player) {
        chosen.remove(player);
        ready.remove(player);
        return members.remove(player) == null ? Result.NO_INVITE : Result.OK;
    }

    /** Takes someone off the team, accepted or not. */
    public boolean remove(UUID player) {
        chosen.remove(player);
        ready.remove(player);
        boolean removed = members.remove(player) != null;
        if (removed) cancelCountdown();
        return removed;
    }

    /** Drops invites older than {@link #INVITE_TTL_MILLIS} that were never answered; returns who. */
    public List<UUID> expire(long now) {
        List<UUID> lapsed = new ArrayList<>();
        members.entrySet().removeIf(entry -> {
            boolean old = entry.getValue().response() == Response.INVITED
                    && now - entry.getValue().invitedAt() >= INVITE_TTL_MILLIS;
            if (old) lapsed.add(entry.getKey());
            return old;
        });
        return lapsed;
    }

    /** What this player chose to register, in order; empty means they have not chosen and the party is used. */
    public List<UUID> chosenOf(UUID player) {
        return List.copyOf(chosen.getOrDefault(player, List.of()));
    }

    public void setChosen(UUID player, List<UUID> pokemon) {
        if (pokemon.isEmpty()) chosen.remove(player);
        else chosen.put(player, List.copyOf(pokemon));
        cancelCountdown();
    }

    public Optional<Response> responseOf(UUID player) {
        return Optional.ofNullable(members.get(player)).map(Member::response);
    }

    public boolean contains(UUID player) {
        return player.equals(host) || members.containsKey(player);
    }

    /** The host and everyone who said yes, in invite order -- the team that would start. */
    public List<UUID> team() {
        List<UUID> team = new ArrayList<>();
        team.add(host);
        members.forEach((id, member) -> {
            if (member.response() == Response.ACCEPTED) team.add(id);
        });
        return team;
    }

    /** Every invitee, accepted or still pending. */
    public List<UUID> invitees() {
        return List.copyOf(members.keySet());
    }

    /** Invitees who have not answered. Starting early drops these. */
    public List<UUID> pending() {
        List<UUID> pending = new ArrayList<>();
        members.forEach((id, member) -> {
            if (member.response() == Response.INVITED) pending.add(id);
        });
        return pending;
    }

    private int lastAnnounced = -1;

    /**
     * True the first time a given whole-second figure is seen, so a countdown shows once per second, not per tick.
     */
    public boolean announce(int secondsLeft) {
        if (secondsLeft == lastAnnounced) return false;
        lastAnnounced = secondsLeft;
        return true;
    }

    public void beginCountdown(long now, long millis) {
        countdownEndsAt = now + millis;
    }

    public void cancelCountdown() {
        countdownEndsAt = -1;
    }

    public boolean counting() {
        return countdownEndsAt >= 0;
    }

    public boolean countdownDue(long now) {
        return counting() && now >= countdownEndsAt;
    }

    /** Whole seconds left, rounded up, or -1 when not counting. */
    public int secondsLeft(long now) {
        return counting() ? (int) Math.max(0, (countdownEndsAt - now + 999) / 1000) : -1;
    }
}
