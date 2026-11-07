package com.cobbletowers.route;

import com.cobbletowers.route.RouteBookIcons.Line;
import com.cobbletowers.route.RouteBookIcons.Link;
import com.cobbletowers.route.RouteMap.Edge;
import com.cobbletowers.route.RouteMap.Stop;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The route book drawn in pictures, for a player who has the server resource pack: the map as stop icons joined by lines, five floors to a page, then the
 * choices and the legend with small icons. Hover a stop to read what it is. Pure, like {@link RouteBookPages}, whose letter book is the fallback.
 *
 * <p>A page is {@value #PAGE_WIDTH} px wide and {@value RouteBookPages#LINES_PER_PAGE} lines of 9 px. A map row takes three lines: the top half of its
 * icons (an invisible span there carries the hover), the icons' own line, and the line of connectors down to the row below. The start (row 0) is not
 * drawn: floor 1 is a lone battle every party walks into, so it shows as open from the start.
 *
 * <p>A stop the party has not scouted is drawn as fog, and its hover says only that: nothing about its kind reaches the client.
 */
public final class RouteBookMapPages {

    public static final int PAGE_WIDTH = 114;
    public static final int ROWS_PER_PAGE = 5;
    /** The centre column of each lane's icon. */
    static final int[] LANE_X = {27, 57, 87};

    static final int DIM = 0x8A8F9C;
    static final int LINK_DIM = 0x5A5F6B;
    private static final int PLAIN = 0xC1C5CF;

    /** Where a stop stands for this party. */
    public enum StopState { HERE, TRAIL, OPEN, SEEN, HIDDEN }

    private RouteBookMapPages() {}

    /**
     * The colour multiplied into a stop's icon picture (0xFFFFFF draws it as painted; darker greys dim it). Where the party stands and where it can step
     * next are drawn in full; the way it came is a touch dimmer (the gold lines carry the road), and the rest of the scouted map dimmer still, so the open
     * lanes stand out while a Forge two floors up is still a Forge. Not darker than about 0xA0: on the parchment page a darker tint reads as mud, not grey.
     */
    static int iconTint(StopState state) {
        return switch (state) {
            case HERE, OPEN -> 0xFFFFFF;
            case TRAIL -> 0xD2D2D2;
            case SEEN -> 0xA4A4A4;
            case HIDDEN -> DIM;
        };
    }

    /** The colour of the ring drawn round a stop, or -1 for none. */
    static int ringTint(StopState state) {
        return switch (state) {
            case HERE -> RouteBookPages.TRAIL;
            case OPEN -> RouteBookPages.OPEN;
            default -> -1;
        };
    }

    public static StopState stateOf(int row, int lane, List<Integer> trail, List<Integer> open, int reveal, Stop stop) {
        int here = trail.size() - 1;
        if (row == here && trail.get(here) == lane) return StopState.HERE;
        if (row < here && trail.get(row) == lane) return StopState.TRAIL;
        if (row == here + 1 && open.contains(lane)) return StopState.OPEN;
        return row <= reveal || stop == Stop.BOSS ? StopState.SEEN : StopState.HIDDEN;
    }

    /** What a stop is for, in a line or two, for its hover. */
    static String blurb(Stop stop) {
        return switch (stop) {
            case START -> "Where the climb begins.";
            case BATTLE -> "A trainer battle.";
            case ELITE -> "A tougher battle, for a better reward.";
            case REST -> "Fully heal the party.";
            case SHRINE -> "Take a curse: a relic now, a challenge for good.";
            case FORGE -> "Pay CobbleDollars for a battle relic.";
            case LIBRARY -> "Study, and take a scouting relic.";
            case VAULT -> "Crack it: a purse for everyone, or an ambush.";
            case POOL -> "Dive, and swap a relic for a different one.";
            case MERCHANT -> "Buy from the tower's goblin.";
            case TRICK_OR_TREAT -> "Open the Jack-o'-Lantern Vault: a treat, or a mimic.";
            case BOSS -> "The floor's boss. Beat it to go on.";
        };
    }

    static String stateLine(StopState state) {
        return switch (state) {
            case HERE -> "You are here.";
            case TRAIL -> "Passed.";
            case OPEN -> "Open now: you can step here next.";
            default -> "";
        };
    }

    private static Component hover(Stop stop, StopState state, int row) {
        MutableComponent out = Component.empty();
        if (state == StopState.HIDDEN) {
            return out.append(Component.literal("Not scouted yet").withStyle(text(PLAIN, true)))
                    .append(Component.literal("\nFloor " + row + ". Scouting shows more of the map.").withStyle(text(DIM, false)));
        }
        out.append(Component.literal(RouteBookPages.nameOf(stop)).withStyle(text(ringTint(state) > 0 ? ringTint(state) : PLAIN, true)));
        out.append(Component.literal("\nFloor " + row + ". " + blurb(stop)).withStyle(text(DIM, false)));
        String line = stateLine(state);
        if (!line.isEmpty()) out.append(Component.literal("\n" + line).withStyle(text(PLAIN, false)));
        return out;
    }

    private static Style text(int rgb, boolean bold) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold).withItalic(false);
    }

    /** The whole picture book: the map pages (lowest floors first), then the choices and the legend. */
    public static List<Component> pages(RouteMap.Map map, List<Integer> trail, int reveal) {
        List<Component> pages = new ArrayList<>();
        // rows 1..rows() are the floors (RouteMap.generate puts the last floor on row rows())
        for (int lo = 1; lo <= map.rows(); lo += ROWS_PER_PAGE) {
            pages.add(page(mapPage(map, trail, reveal, lo, Math.min(map.rows(), lo + ROWS_PER_PAGE - 1))));
        }
        pages.add(page(choices(map, trail, reveal)));
        pages.add(page(legend()));
        return pages;
    }

    /** The lines of one map page, rows {@code hi} down to {@code lo}. */
    static List<Line> mapPage(RouteMap.Map map, List<Integer> trail, int reveal, int lo, int hi) {
        int here = trail.size() - 1;
        List<Integer> open = here >= 0 ? map.next(here, trail.get(here)) : List.of();
        List<Line> lines = new ArrayList<>();
        for (int row = hi; row >= lo; row--) {
            Line top = new Line(), icons = new Line(), links = new Line();
            String label = Integer.toString(row);
            icons.text(label, 6 * label.length(), text(DIM, false));
            for (int lane = 0; lane < RouteMap.LANES; lane++) {
                Stop stop = map.stopAt(row, lane);
                if (stop == null) continue;
                StopState state = stateOf(row, lane, trail, open, reveal, stop);
                Style hover = Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover(stop, state, row)));
                int cx = LANE_X[lane];
                top.span(cx - RouteBookIcons.ICON / 2, RouteBookIcons.advance(RouteBookIcons.ICON), hover);
                int ring = ringTint(state);
                if (ring > 0) icons.glyph(cx - RouteBookIcons.RING_SIZE / 2, RouteBookIcons.ring(), RouteBookIcons.RING_SIZE, RouteBookIcons.style(ring).applyTo(hover));
                char glyph = state == StopState.HIDDEN ? RouteBookIcons.fog() : RouteBookIcons.icon(stop);
                icons.glyph(cx - RouteBookIcons.ICON / 2, glyph, RouteBookIcons.ICON, RouteBookIcons.style(state == StopState.HIDDEN ? DIM : iconTint(state)).applyTo(hover));
            }
            if (row > lo) {
                for (Edge e : map.edges()) {
                    if (e.row() == row - 1) link(links, e, trail, open);
                }
            }
            lines.add(top);
            lines.add(icons);
            if (row > lo) lines.add(links);
        }
        return lines;
    }

    /** One connector from a stop of the lower row ({@code e.fromLane()}) up to one of the row above ({@code e.toLane()}). */
    private static void link(Line line, Edge e, List<Integer> trail, List<Integer> open) {
        int here = trail.size() - 1;
        int below = e.row(), above = e.row() + 1;
        boolean walked = above <= here && trail.get(below) == e.fromLane() && trail.get(above) == e.toLane();
        boolean next = below == here && trail.get(here) == e.fromLane() && open.contains(e.toLane());
        int tint = walked ? RouteBookPages.TRAIL : next ? RouteBookPages.OPEN : LINK_DIM;
        int from = LANE_X[e.fromLane()], to = LANE_X[e.toLane()];
        Link kind;
        int left;
        if (from == to) {
            kind = Link.STRAIGHT;
            left = to - RouteBookIcons.TILE_W / 2;
        } else if (Math.abs(e.toLane() - e.fromLane()) == 1) {
            // the line is drawn from its top end (the row above) to its bottom end
            kind = to < from ? Link.DOWN_RIGHT : Link.DOWN_LEFT;
            left = Math.min(from, to) - 1;
        } else {
            return;
        }
        line.glyph(left, RouteBookIcons.link(kind), RouteBookIcons.TILE_W, RouteBookIcons.style(tint));
    }

    private static List<Line> choices(RouteMap.Map map, List<Integer> trail, int reveal) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line().text("YOUR CHOICES", 0, text(PLAIN, true)));
        int here = trail.size() - 1;
        if (here < 0) return lines;
        Stop standing = map.stopAt(here, trail.get(here));
        lines.addAll(textLines(RouteBookPages.wrapped("You are on: " + (standing == null ? "?" : RouteBookPages.nameOf(standing)), "", "  ", text(DIM, false))));
        List<Integer> open = map.next(here, trail.get(here));
        if (open.isEmpty()) {
            lines.add(new Line());
            lines.addAll(textLines(RouteBookPages.wrapped("No choice to make right now.", "", "  ", text(DIM, false))));
            return lines;
        }
        lines.addAll(textLines(RouteBookPages.wrapped("Next is floor " + (here + 1) + ".", "", "  ", text(DIM, false))));
        String[] laneNames = {"Left", "Middle", "Right"};
        for (int lane : open) {
            Stop stop = map.stopAt(here + 1, lane);
            lines.add(new Line());
            Line head = new Line();
            if (stop != null) head.glyph(0, RouteBookIcons.mini(stop), RouteBookIcons.MINI, RouteBookIcons.style(0xFFFFFF)).to(RouteBookIcons.advance(RouteBookIcons.MINI) + 2);
            head.text(lane < laneNames.length ? laneNames[lane] : "Lane " + (lane + 1), 0, text(RouteBookPages.OPEN, true));
            lines.add(head);
            lines.addAll(textLines(RouteBookPages.wrapped(stop == null ? "?" : RouteBookPages.nameOf(stop), "  ", "  ", text(PLAIN, false))));
        }
        return lines;
    }

    private static List<Line> legend() {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line().text("LEGEND", 0, text(PLAIN, true)));
        for (Stop stop : Stop.values()) {
            if (stop == Stop.START) continue;
            lines.add(new Line().glyph(0, RouteBookIcons.mini(stop), RouteBookIcons.MINI, RouteBookIcons.style(0xFFFFFF))
                    .to(RouteBookIcons.advance(RouteBookIcons.MINI) + 3).text(RouteBookPages.nameOf(stop), 0, text(DIM, false)));
        }
        lines.addAll(textLines(RouteBookPages.wrapped("Hover a stop on the map to read what it is.", "", "", text(DIM, false))));
        return lines;
    }

    private static List<Line> textLines(List<Component> wrapped) {
        List<Line> out = new ArrayList<>();
        for (Component c : wrapped) {
            out.add(new Line().append(c));
        }
        return out;
    }

    private static Component page(List<Line> lines) {
        MutableComponent page = Component.empty();
        for (int i = 0; i < Math.min(lines.size(), RouteBookPages.LINES_PER_PAGE); i++) {
            if (i > 0) page.append(Component.literal("\n"));
            page.append(lines.get(i).build());
        }
        return page;
    }
}
