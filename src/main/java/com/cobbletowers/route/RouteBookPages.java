package com.cobbletowers.route;

import com.cobbletowers.route.RouteMap.Stop;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The tower's route map as the pages of a book, for a player in the tower: the whole branching map with the party's trail, and, for each lane open now, what
 * it is and what it leads to next. Plain text, so it needs no client mod, and it is built from the run each time it is opened, so it is always right for the
 * floor. Pure (the map and the trail in, text out): {@link RouteMapView} supplies them.
 */
public final class RouteBookPages {

    /** The most lines a book page holds. */
    public static final int LINES_PER_PAGE = 14;

    /** Gold: the stops the party has passed. */
    public static final int TRAIL = 0xFFD437;
    /** Green: the stops the party can step to now. */
    public static final int OPEN = 0x55FF99;
    private static final int DIM = 0x8A8F9C;
    private static final int PLAIN = 0xC1C5CF;

    private static final String[] LANE_NAMES = {"Left", "Middle", "Right"};

    private RouteBookPages() {}

    private static Style style(int rgb, boolean bold) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold).withItalic(false);
    }

    public static String letterOf(Stop stop) {
        return switch (stop) {
            case START -> "o";
            case BATTLE -> "B";
            case ELITE -> "E";
            case REST -> "R";
            case SHRINE -> "S";
            case FORGE -> "F";
            case LIBRARY -> "L";
            case VAULT -> "V";
            case POOL -> "P";
            case MERCHANT -> "M";
            case TRICK_OR_TREAT -> "T";
            case BOSS -> "!";
        };
    }

    public static String nameOf(Stop stop) {
        return switch (stop) {
            case START -> "Start";
            case BATTLE -> "Battle";
            case ELITE -> "Elite battle";
            case REST -> "Rest";
            case SHRINE -> "Shrine";
            case FORGE -> "Forge";
            case LIBRARY -> "Library";
            case VAULT -> "Vault";
            case POOL -> "Mystic Pool";
            case MERCHANT -> "Merchant";
            case TRICK_OR_TREAT -> "Trick or Treat";
            case BOSS -> "Boss";
        };
    }

    private static String laneName(int lane) {
        return lane >= 0 && lane < LANE_NAMES.length ? LANE_NAMES[lane] : "Lane " + (lane + 1);
    }

    /**
     * @param map the branching map of the cycle the party is in
     * @param trail the lane the party took at each row from the start (row 0) to where it stands; the last entry is its stop
     * @param reveal the highest row whose stops the party can see (a boss always shows)
     * @param cycle the cycle of an ascending tower, 0 for the first
     */
    public static List<Component> pages(RouteMap.Map map, List<Integer> trail, int reveal, int cycle) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("ROUTE MAP" + (cycle > 0 ? "  cycle " + (cycle + 1) : "")).withStyle(style(PLAIN, true)));
        int here = trail.size() - 1;
        List<Integer> open = here >= 0 ? map.next(here, trail.get(here)) : List.of();
        for (int row = map.rows() - 1; row >= 0; row--) lines.add(rowLine(map, row, trail, open, reveal));
        lines.add(Component.literal("o start  B battle  R rest").withStyle(style(DIM, false)));
        lines.add(Component.literal("S shrine  F forge  L library").withStyle(style(DIM, false)));
        lines.add(Component.literal("V vault  P pool  T trick-or-treat").withStyle(style(DIM, false)));

        List<Component> pages = paginate(lines);
        pages.addAll(paginate(choices(map, trail, reveal)));
        return pages;
    }

    private static MutableComponent rowLine(RouteMap.Map map, int row, List<Integer> trail, List<Integer> open, int reveal) {
        MutableComponent line = Component.literal(String.format("%2d ", row)).withStyle(style(DIM, false));
        Stop lone = null;
        for (int lane = 0; lane < RouteMap.LANES; lane++) {
            Stop stop = map.stopAt(row, lane);
            if (stop == Stop.BOSS) lone = stop;
        }
        if (lone != null) {
            boolean on = row < trail.size();
            return line.append(Component.literal("  BOSS").withStyle(on ? style(TRAIL, true) : style(PLAIN, true)));
        }
        for (int lane = 0; lane < RouteMap.LANES; lane++) {
            Stop stop = map.stopAt(row, lane);
            if (stop == null) {
                line.append(Component.literal("   "));
                continue;
            }
            boolean onTrail = row < trail.size() && trail.get(row) == lane;
            boolean openNow = row == trail.size() && open.contains(lane);
            boolean seen = onTrail || row <= reveal;
            Style style = onTrail ? style(TRAIL, true) : openNow ? style(OPEN, true) : style(DIM, false);
            line.append(Component.literal("[" + (seen ? letterOf(stop) : "?") + "]").withStyle(style));
        }
        return line;
    }

    /** What each lane open now is, and what it leads to next (the stops reachable from it, left to right). */
    private static List<Component> choices(RouteMap.Map map, List<Integer> trail, int reveal) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("YOUR CHOICES").withStyle(style(PLAIN, true)));
        int here = trail.size() - 1;
        if (here < 0) return lines;
        Stop standing = map.stopAt(here, trail.get(here));
        lines.add(Component.literal("You stand on: " + (standing == null ? "?" : nameOf(standing))).withStyle(style(DIM, false)));
        lines.add(Component.empty());
        List<Integer> open = map.next(here, trail.get(here));
        if (open.isEmpty()) {
            lines.add(Component.literal("No choice to make right now.").withStyle(style(DIM, false)));
            return lines;
        }
        for (int lane : open) {
            Stop stop = map.stopAt(here + 1, lane);
            String name = stop == null ? "?" : nameOf(stop);
            lines.add(Component.literal(laneName(lane) + ": ").withStyle(style(OPEN, true)).append(Component.literal(name).withStyle(style(OPEN, true))));
            lines.add(Component.literal("  " + thenText(map, here + 1, lane, stop, reveal)).withStyle(style(DIM, false)));
        }
        return lines;
    }

    /** What the lane leads on to: the stops of the next row reachable from it, or the boss, or nothing known yet. */
    private static String thenText(RouteMap.Map map, int row, int lane, Stop stop, int reveal) {
        if (stop == Stop.BOSS) return "leads to the boss";
        List<Integer> next = map.next(row, lane);
        if (next.isEmpty()) return "then: the end of the path";
        List<String> names = new ArrayList<>();
        for (int n : next) {
            Stop later = map.stopAt(row + 1, n);
            boolean seen = later == Stop.BOSS || row + 1 <= reveal;
            names.add(later == null || !seen ? "?" : nameOf(later));
        }
        return "then: " + String.join(" or ", names);
    }

    private static List<Component> paginate(List<Component> lines) {
        List<Component> pages = new ArrayList<>();
        for (int from = 0; from < lines.size(); from += LINES_PER_PAGE) {
            MutableComponent page = Component.empty();
            for (int i = from; i < Math.min(lines.size(), from + LINES_PER_PAGE); i++) {
                if (i > from) page.append(Component.literal("\n"));
                page.append(lines.get(i));
            }
            pages.add(page);
        }
        return pages;
    }
}
