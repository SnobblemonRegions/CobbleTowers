package com.cobbletowers.route;

import static com.cobbletowers.route.RouteScreenFont.BUTTON_X;
import static com.cobbletowers.route.RouteScreenFont.FLOORS_PER_PAGE;
import static com.cobbletowers.route.RouteScreenFont.FLOOR_X;
import static com.cobbletowers.route.RouteScreenFont.TITLE_X;

import com.cobbletowers.route.RouteBookIcons.Line;
import com.cobbletowers.route.RouteBookMapPages.StopState;
import com.cobbletowers.route.RouteMap.Edge;
import com.cobbletowers.route.RouteMap.Stop;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The route map as a screen (concept A, the crypt ledger): the picture title that paints the parchment, the stops, the connectors and the choices, and the
 * tooltips that the invisible slots under the stops show. Pure (the map and the trail in, a {@link View} out), so it is tested without a game; the chest it
 * is shown on is {@link RouteScreenMenu}.
 *
 * <p>A page shows {@value RouteScreenFont#FLOORS_PER_PAGE} floors, left to right, in three lanes (left on top). A veiled stop is a picture of fog and its
 * tooltip says only that it is not scouted: nothing about its kind is put in the title or in a slot, because both are sent to the player's game whether or not
 * they look.
 */
public final class RouteScreenLayout {

    /** What a slot says when the mouse is over it. */
    public record Tip(int slot, Component name, List<Component> lore) {}

    /** Everything one page of the screen is made of. */
    public record View(Component title, List<Tip> tips, int page, int pages) {}

    private static final int WHITE = 0xFFFFFF;
    private static final int WALKED = RouteBookPages.TRAIL;
    private static final int OPEN = RouteBookPages.OPEN;
    private static final int UNWALKED = 0x7A6A4A;
    private static final int INK = 0xE8DCC0;
    private static final int DIM = 0xA89B80;
    private static final int GOLD = 0xE8B830;
    private static final int FOG = 0x8A8F9C;

    private RouteScreenLayout() {}

    /** How many pages the map takes, five floors to a page. */
    public static int pages(RouteMap.Map map) {
        return Math.max(1, (map.rows() + FLOORS_PER_PAGE - 1) / FLOORS_PER_PAGE);
    }

    /** The page the party's next step is on, so the screen opens where the choice is. */
    public static int pageOf(RouteMap.Map map, List<Integer> trail) {
        int next = Math.max(1, Math.min(map.rows(), trail.size()));
        return clamp((next - 1) / FLOORS_PER_PAGE, 0, pages(map) - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The colour multiplied into a stop's picture: as painted where the party stands or can step, dimmer for the way it came and the rest. */
    static int iconTint(StopState state) {
        return switch (state) {
            case HERE, OPEN -> WHITE;
            case TRAIL -> 0xD2D2D2;
            case SEEN -> 0xA4A4A4;
            case HIDDEN -> FOG;
        };
    }

    private static Style color(int rgb) {
        return RouteBookIcons.style(rgb);
    }

    private static Style plain(int rgb, boolean bold) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold).withItalic(false);
    }

    /** The tooltip of a stop: its name, where it is and what it does, and whether the party can step to it. */
    static Tip stopTip(int slot, Stop stop, StopState state, int row) {
        if (state == StopState.HIDDEN) {
            return new Tip(slot, Component.literal("Not scouted yet").withStyle(plain(WHITE, true)),
                    List.of(Component.literal("Floor " + row + ". Scouting shows more of the map.").withStyle(plain(DIM, false))));
        }
        int ring = RouteBookMapPages.ringTint(state);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Floor " + row + ". " + RouteBookMapPages.blurb(stop)).withStyle(plain(DIM, false)));
        String line = RouteBookMapPages.stateLine(state);
        if (!line.isEmpty()) lore.add(Component.literal(line).withStyle(plain(INK, false)));
        return new Tip(slot, Component.literal(RouteBookPages.nameOf(stop)).withStyle(plain(ring > 0 ? ring : WHITE, true)), lore);
    }

    /**
     * @param page the page to draw (clamped to the map)
     * @param reveal the highest row whose stops the party can see (a boss always shows)
     */
    public static View build(RouteMap.Map map, List<Integer> trail, int reveal, int page) {
        int pages = pages(map);
        page = clamp(page, 0, pages - 1);
        int first = page * FLOORS_PER_PAGE + 1;
        int here = trail.size() - 1;
        List<Integer> open = here >= 0 ? map.next(here, trail.get(here)) : List.of();

        Line line = RouteScreenFont.line();
        List<Tip> tips = new ArrayList<>();
        Style white = color(WHITE);

        line.glyph(RouteScreenFont.PANEL_X - TITLE_X, RouteScreenFont.panel(0), RouteScreenFont.PIECE_W, white);
        line.glyph(RouteScreenFont.PANEL_X - TITLE_X + RouteScreenFont.PIECE_W, RouteScreenFont.panel(1), RouteScreenFont.PIECE_W, white);

        // the connectors first, so the stops are drawn over their ends
        for (int i = 0; i + 1 < FLOORS_PER_PAGE; i++) {
            int lower = first + i, upper = lower + 1;
            if (upper > map.rows()) break;
            for (Edge e : map.edges()) {
                if (e.row() != lower) continue;
                boolean walked = upper < trail.size() && trail.get(lower) == e.fromLane() && trail.get(upper) == e.toLane();
                boolean next = lower == here && trail.get(here) == e.fromLane() && open.contains(e.toLane());
                Style tint = color(walked ? WALKED : next ? OPEN : UNWALKED);
                int left = FLOOR_X[i] - TITLE_X;
                if (e.fromLane() == e.toLane()) {
                    line.glyph(left, RouteScreenFont.straight(e.fromLane()), RouteScreenFont.LINK, tint);
                } else if (e.toLane() > e.fromLane()) {
                    line.glyph(left, RouteScreenFont.down(e.fromLane()), RouteScreenFont.LINK, tint);
                } else {
                    line.glyph(left, RouteScreenFont.up(e.toLane()), RouteScreenFont.LINK, tint);
                }
            }
        }

        // the stops: a ring where the party stands or can step, the picture, and the slot that explains it
        for (int i = 0; i < FLOORS_PER_PAGE; i++) {
            int row = first + i;
            if (row > map.rows()) break;
            for (int lane = 0; lane < RouteMap.LANES; lane++) {
                Stop stop = map.stopAt(row, lane);
                if (stop == null) continue;
                StopState state = RouteBookMapPages.stateOf(row, lane, trail, open, reveal, stop);
                int ring = RouteBookMapPages.ringTint(state);
                if (ring > 0) line.glyph(FLOOR_X[i] - RouteScreenFont.RING / 2 - TITLE_X, RouteScreenFont.ring(lane), RouteScreenFont.RING, color(ring));
                char picture = state == StopState.HIDDEN ? RouteScreenFont.fog(lane) : RouteScreenFont.icon(lane, stop);
                line.glyph(FLOOR_X[i] - RouteScreenFont.ICON / 2 - TITLE_X, picture, RouteScreenFont.ICON, color(iconTint(state)));
                tips.add(stopTip(RouteScreenFont.stopSlot(i, lane), stop, state, row));
            }
        }

        // the floor numbers
        for (int i = 0; i < FLOORS_PER_PAGE; i++) {
            int row = first + i;
            if (row > map.rows()) break;
            String digits = Integer.toString(row);
            int width = (RouteScreenFont.DIGIT + 1) * digits.length() - 1;
            int left = FLOOR_X[i] - width / 2 - TITLE_X;
            for (int k = 0; k < digits.length(); k++) {
                line.glyph(left + k * (RouteScreenFont.DIGIT + 1), RouteScreenFont.digit(digits.charAt(k) - '0'), RouteScreenFont.DIGIT,
                        color(row == here ? GOLD : DIM));
            }
        }

        // the choices: one row a lane that is open now, the whole row a hover target
        for (int lane : open) {
            Stop stop = map.stopAt(here + 1, lane);
            if (stop == null || lane >= RouteScreenFont.CHOICE_Y.length) continue;
            line.glyph(RouteScreenFont.CHOICE_ICON_X - RouteScreenFont.ICON / 2 - TITLE_X, RouteScreenFont.icon(3 + lane, stop), RouteScreenFont.ICON, white);
            line.glyph(RouteScreenFont.CHOICE_NAME_X - TITLE_X, RouteScreenFont.name(lane, stop), RouteScreenFont.NAME, color(OPEN));
            Tip tip = stopTip(0, stop, StopState.OPEN, here + 1);
            for (int c = 0; c < RouteScreenFont.COLUMNS; c++) tips.add(new Tip(RouteScreenFont.choiceSlot(lane, c), tip.name(), tip.lore()));
        }

        // the page buttons
        if (page > 0) {
            line.glyph(BUTTON_X[0] - RouteScreenFont.ARROW / 2 - TITLE_X, RouteScreenFont.arrow(0), RouteScreenFont.ARROW, color(GOLD));
            tips.add(new Tip(RouteScreenFont.BACK_SLOT, Component.literal("Earlier floors").withStyle(plain(GOLD, true)),
                    List.of(Component.literal("Floors " + ((page - 1) * FLOORS_PER_PAGE + 1) + " to " + (page * FLOORS_PER_PAGE) + ".").withStyle(plain(DIM, false)))));
        }
        if (page < pages - 1) {
            line.glyph(BUTTON_X[1] - RouteScreenFont.ARROW / 2 - TITLE_X, RouteScreenFont.arrow(1), RouteScreenFont.ARROW, color(GOLD));
            int from = (page + 1) * FLOORS_PER_PAGE + 1, to = Math.min(map.rows(), (page + 2) * FLOORS_PER_PAGE);
            tips.add(new Tip(RouteScreenFont.NEXT_SLOT, Component.literal("Later floors").withStyle(plain(GOLD, true)),
                    List.of(Component.literal("Floors " + from + " to " + to + ".").withStyle(plain(DIM, false)))));
        }
        return new View(line.build(), List.copyOf(tips), page, pages);
    }
}
