package com.cobbletowers.reward;

import java.util.Random;

/**
 * Diminishing returns on the tower's own loot for repeat completions in one trial day: the first {@value #FULL_COMPLETIONS} pay in full, the next
 * {@value #FULL_COMPLETIONS} at {@value #REDUCED_PERCENT} percent, and every one after at {@value #LOW_PERCENT} percent. A reduced completion keeps each
 * rolled item with that probability (amounts are never rounded down to nothing). It applies to the tower's table only, never to AscensionLib's pile.
 * Pure.
 */
public final class DailyCompletionBudget {

    public static final int FULL_COMPLETIONS = 3;
    public static final int REDUCED_PERCENT = 50;
    public static final int LOW_PERCENT = 25;

    private DailyCompletionBudget() {}

    /** The share of the loot paid when the player has already completed {@code completionsToday} towers today. */
    public static int percentFor(int completionsToday) {
        if (completionsToday < FULL_COMPLETIONS) return 100;
        return completionsToday < 2 * FULL_COMPLETIONS ? REDUCED_PERCENT : LOW_PERCENT;
    }

    /**
     * Whether this payout counts as one of the day's completions, the count {@link #percentFor} steps down on.
     *
     * <p>Until 13 Nov only a payout that priced the top floor counted, so a team that cashed out at the last
     * intermission (one floor short) took the tower's loot for every floor it cleared, plus the risk bonus, and never
     * reached the reduced share however many runs it did that day.
     *
     * @param state the run's state at this payout: {@code CASHED_OUT}, {@code COMPLETED}, {@code INTERMISSION} (a
     *              milestone bank mid-run) or {@code RECOVERY_REQUIRED} (a parked run being closed)
     * @param highestPricedFloor the highest content floor this payout prices, 0 when it prices none
     * @param floorCount the tower's number of content floors
     */
    public static boolean countsAsCompletion(com.cobbletowers.api.tower.RunState state, int highestPricedFloor, int floorCount) {
        // Reaching the top always counts. A cash-out counts once it took at least half the tower: that is where the loot
        // and the risk bonus are worth most of a completion, while bailing early on a bad run stays cheap. A milestone
        // payout mid-run never counts on its own (the run's final payout does), and neither does a parked run closed by
        // the server, which is not the team's choice.
        if (highestPricedFloor >= floorCount) return true;
        return state == com.cobbletowers.api.tower.RunState.CASHED_OUT && floorCount > 0 && highestPricedFloor * 2 >= floorCount;
    }

    /** Whether one rolled item is kept at {@code percent}. Seeded, so a retry keeps the same items. */
    public static boolean keeps(long seed, int percent) {
        if (percent >= 100) return true;
        return new Random(seed).nextInt(100) < percent;
    }
}
