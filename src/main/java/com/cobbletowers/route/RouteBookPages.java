package com.cobbletowers.route;

import com.cobbletowers.route.RouteMap.Stop;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The tower's route map as the pages of a book, for a player in the tower: the whole branching map with the party's trail, then, for each lane open now,
 * what it is and what it leads to next, then a legend. Plain text, so it needs no client mod, and it is built from the run each time it is opened, so it is
 * always right for the floor. Pure (the map and the trail in, text out): {@link RouteMapView} supplies them.
 *
 * <p>A book page holds {@value #LINES_PER_PAGE} lines of about {@value #WIDTH} characters, and text that is wider wraps and pushes the rest off the page. Every
 * line here is therefore counted as a line of the page, long text is wrapped by hand, and each part starts on its own page so none is split.
 */
public final class RouteBookPages {

    /** The most lines a book page holds. */
    public static final int LINES_PER_PAGE = 14;

    /** The most characters of text that fit a line of a book page (bold text is wider, so this is on the safe side). */
    public static final int WIDTH = 18;

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
        List<Component> mapLines = new ArrayList<>();
        mapLines.add(Component.literal("ROUTE MAP").withStyle(style(PLAIN, true)));
        if (cycle > 0) mapLines.add(Component.literal("cycle " + (cycle + 1)).withStyle(style(DIM, false)));
        int here = trail.size() - 1;
        List<Integer> open = here >= 0 ? map.next(here, trail.get(here)) : List.of();
        // rows 0..rows(): the start, then a row per floor (RouteMap.generate puts the last floor on row rows())
        for (int row = map.rows(); row >= 0; row--) mapLines.add(rowLine(map, row, trail, open, reveal));

        List<Component> pages = new ArrayList<>(paginate(mapLines));
        pages.addAll(paginate(choices(map, trail, reveal)));
        pages.addAll(paginate(legend()));
        return pages;
    }

    private static MutableComponent rowLine(RouteMap.Map map, int row, List<Integer> trail, List<Integer> open, int reveal) {
        MutableComponent line = Component.literal(String.format("%2d ", row)).withStyle(style(DIM, false));
        boolean boss = false;
        for (int lane = 0; lane < RouteMap.LANES; lane++) {
            if (map.stopAt(row, lane) == Stop.BOSS) boss = true;
        }
        if (boss) {
            boolean passed = row < trail.size();
            return line.append(Component.literal("  BOSS").withStyle(passed ? style(TRAIL, true) : style(PLAIN, true)));
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
        lines.addAll(wrapped("You are on: " + (standing == null ? "?" : nameOf(standing)), "", "  ", style(DIM, false)));
        List<Integer> open = map.next(here, trail.get(here));
        if (open.isEmpty()) {
            lines.add(Component.empty());
            lines.addAll(wrapped("No choice to make right now.", "", "  ", style(DIM, false)));
            return lines;
        }
        lines.addAll(wrapped("Next is floor " + (here + 1) + ".", "", "  ", style(DIM, false)));
        for (int lane : open) {
            Stop stop = map.stopAt(here + 1, lane);
            lines.add(Component.empty());
            lines.addAll(wrapped(laneName(lane) + ": " + (stop == null ? "?" : nameOf(stop)), "", "  ", style(OPEN, true)));
            lines.addAll(wrapped(thenText(map, here + 1, lane, stop, reveal), "  ", "    ", style(DIM, false)));
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

    /** Every letter the map uses, named, on a page of its own. */
    private static List<Component> legend() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("LEGEND").withStyle(style(PLAIN, true)));
        for (Stop stop : Stop.values()) {
            if (stop == Stop.BOSS) continue;
            lines.addAll(wrapped(letterOf(stop) + "  " + nameOf(stop), "", "     ", style(DIM, false)));
        }
        lines.addAll(wrapped("[?]  not seen yet", "", "     ", style(DIM, false)));
        return lines;
    }

    /**
     * The text broken into lines of at most {@link #WIDTH} characters at spaces (a word longer than that is cut), the first line indented by
     * {@code firstIndent} and the lines that wrap by {@code restIndent}, each in {@code style}. A page counts these as the lines they really take.
     */
    static List<Component> wrapped(String text, String firstIndent, String restIndent, Style style) {
        List<String> lines = new ArrayList<>();
        String lead = firstIndent;
        StringBuilder line = new StringBuilder(lead);
        for (String word : text.split(" ")) {
            while (word.length() > WIDTH - lead.length()) {
                if (line.length() > lead.length()) {
                    lines.add(line.toString());
                    lead = restIndent;
                    line = new StringBuilder(lead);
                }
                int cut = WIDTH - lead.length();
                lines.add(lead + word.substring(0, cut));
                word = word.substring(cut);
                lead = restIndent;
                line = new StringBuilder(lead);
            }
            boolean empty = line.length() == lead.length();
            if (!empty && line.length() + 1 + word.length() > WIDTH) {
                lines.add(line.toString());
                lead = restIndent;
                line = new StringBuilder(lead + word);
            } else {
                line.append(empty ? "" : " ").append(word);
            }
        }
        if (line.length() > lead.length() || lines.isEmpty()) lines.add(line.toString());
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(Component.literal(l).withStyle(style));
        return out;
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
