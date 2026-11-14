package com.cobbletowers.mastery;

import com.cobbletowers.mastery.LeaderboardRules.Board;
import com.cobbletowers.mastery.LeaderboardRules.Entry;
import com.cobbletowers.mastery.LeaderboardRules.Member;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Words for mastery and leaderboards (P31), pure so chat and screens say the same thing. */
public final class MasteryView {

    private MasteryView() {}

    /** {@code 754000} as {@code 12:34}; hours only when there are some. */
    public static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    /** How a board's value reads: a time for Speed, a number for the rest. */
    public static String value(Board board, long value) {
        return switch (board) {
            case SPEED -> duration(value);
            case ASCENSION -> "Ascension " + value;
            case DIFFICULTY -> "score " + value;
            case CLEARS -> value + (value == 1 ? " cycle" : " cycles");
            case TRIAL -> "score " + value;
        };
    }

    public static String names(List<Member> players) {
        return players.stream().map(Member::name).collect(Collectors.joining(", "));
    }

    /** One ranked row, with the revisions the entry was earned against (TDS #90). */
    public static String row(Board board, int rank, Entry entry) {
        StringBuilder row = new StringBuilder();
        row.append('#').append(rank).append(' ').append(names(entry.players())).append("  ")
                .append(value(board, entry.value()));
        if (board != Board.ASCENSION && board != Board.CLEARS) row.append("  (Ascension ").append(entry.ascension()).append(')');
        if (board == Board.SPEED) row.append("  score ").append(entry.score());
        row.append("  [ruleset r").append(entry.rulesetRevision()).append(", tower r").append(entry.towerRevision()).append(']');
        return row.toString();
    }

    /** What the next rank needs, for a level. */
    public static String progressLine(int level) {
        return progressLine(null, level);
    }

    public static String progressLine(net.minecraft.resources.ResourceLocation tower, int level) {
        int next = MasteryPerks.nextRankAt(tower, level);
        String rank = MasteryPerks.rankOf(tower, level);
        return next < 0 ? "level " + level + " (" + rank + ", the top)"
                : "level " + level + " (" + rank + "), " + (next - level) + " to " + MasteryPerks.rankOf(tower, next);
    }

    /** The perks a level has earned, in words; "none yet" below the first. */
    public static String perksLine(MasteryPerks.Perks perks) {
        List<String> parts = new java.util.ArrayList<>();
        if (perks.vendorDiscountPercent() > 0) parts.add("vendor prices -" + perks.vendorDiscountPercent() + "%");
        if (perks.cobbleDollarBonusPercent() > 0) parts.add("+" + perks.cobbleDollarBonusPercent() + "% Tower Tokens");
        if (perks.raidPointsBonusPercent() > 0) parts.add("+" + perks.raidPointsBonusPercent() + "% Raid Points");
        return parts.isEmpty() ? "none yet" : String.join(", ", parts);
    }
}
