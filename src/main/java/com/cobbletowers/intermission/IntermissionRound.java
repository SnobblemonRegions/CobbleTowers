package com.cobbletowers.intermission;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where a team stands at one intermission (P17): who is ready, cash-out votes and when the next floor opens. Pure and
 * in memory; the electorate and time are passed in.
 */
public final class IntermissionRound {

    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Boolean> cashOut = new HashMap<>();
    private long countdownEndsAt = -1;
    private long walkEndsAt = -1;

    public void setReady(UUID player, boolean value) {
        if (value) ready.add(player);
        else ready.remove(player);
        // A team that changed its mind is not the team that agreed to go.
        cancelCountdown();
    }

    public boolean isReady(UUID player) {
        return ready.contains(player);
    }

    /** {@code true} for cashing out, {@code false} for staying. Changing a vote cancels a countdown. */
    public void voteCashOut(UUID player, boolean value) {
        cashOut.put(player, value);
        cancelCountdown();
    }

    /** True for a player who voted to cash out, false for one who voted to stay or has not voted. */
    public boolean votedCashOut(UUID player) {
        return Boolean.TRUE.equals(cashOut.get(player));
    }

    /** Everyone entitled to vote is ready (and there is someone to ask). */
    public boolean allReady(Collection<UUID> electorate) {
        return !electorate.isEmpty() && ready.containsAll(electorate);
    }

    /** A strict majority of the electorate voted to cash out; a tie keeps playing. */
    public boolean cashOutPasses(Collection<UUID> electorate) {
        if (electorate.isEmpty()) return false;
        long yes = electorate.stream().filter(this::votedCashOut).count();
        return yes * 2 > electorate.size();
    }

    /** Exactly half of the electorate voted to cash out: not a majority, so the team keeps playing. */
    public boolean cashOutTied(Collection<UUID> electorate) {
        if (electorate.isEmpty()) return false;
        long yes = electorate.stream().filter(this::votedCashOut).count();
        return yes > 0 && yes * 2 == electorate.size();
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

    /** The team is climbing to the next floor: it opens when everybody is there or when {@code millis} have passed. */
    public void beginWalk(long now, long millis) {
        walkEndsAt = now + millis;
        lastAnnounced = -1;
    }

    private boolean autoWalkTried;

    /** Whether the gate has already been opened on its own this intermission (once, so a floor that cannot be walked is not retried every tick). */
    public boolean autoWalkTried() {
        return autoWalkTried;
    }

    public void markAutoWalkTried() {
        autoWalkTried = true;
    }

    public void cancelWalk() {
        walkEndsAt = -1;
        climbLane = -1;
        fadeEndsAt = -1;
        transferred = false;
    }

    // ---- the lane stair (P58): climb to the landing, fade, be taken to the arrival, then walk into the arena ----

    private int climbLane = -1;
    private long fadeEndsAt = -1;
    private boolean transferred;

    /** The team is to climb the stair of {@code lane}; until it has been taken up, the walk is the climb. */
    public void beginClimb(int lane) {
        climbLane = lane;
        fadeEndsAt = -1;
        transferred = false;
    }

    /** The lane being climbed, or -1 when this walk has no lane stair. */
    public int climbLane() {
        return climbLane;
    }

    /** The team is on the landing and the screen is going dark; the move happens {@code millis} from {@code now}. */
    public void beginFade(long now, long millis) {
        fadeEndsAt = now + millis;
    }

    public boolean fading() {
        return fadeEndsAt >= 0 && !transferred;
    }

    public boolean fadeDue(long now) {
        return fading() && now >= fadeEndsAt;
    }

    /** The team has been taken up: from here the walk is from the arrival hall to the arena door, with a new deadline. */
    public void markTransferred(long now, long millis) {
        transferred = true;
        fadeEndsAt = -1;
        beginWalk(now, millis);
    }

    public boolean transferred() {
        return transferred;
    }

    /** Whether this walk is still on its stair: a lane was chosen and nobody has been taken up. */
    public boolean climbing() {
        return climbLane >= 0 && !transferred;
    }

    public boolean walking() {
        return walkEndsAt >= 0;
    }

    public boolean walkDue(long now) {
        return walking() && now >= walkEndsAt;
    }

    public int walkSecondsLeft(long now) {
        return (int) Math.max(0, (walkEndsAt - now + 999) / 1000);
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
