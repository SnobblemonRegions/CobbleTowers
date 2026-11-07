package com.cobbletowers.route;

import com.cobbletowers.route.RouteBookIcons.Line;
import com.cobbletowers.route.RouteMap.Stop;
import net.minecraft.resources.ResourceLocation;

/**
 * The characters of the route screen's font ({@code assets/cobbletowers/font/route_screen.json}, made by {@code tools/vendor/make_route_screen_art.py}) and
 * where everything stands on the chest screen they are drawn over. All positions are in container pixels: the origin is the top left of the 176 x 222 chest,
 * and the title text starts at x = {@value #TITLE_X}, so a {@link Line} (whose origin is the title's) is given {@code x - TITLE_X}.
 *
 * <p>A chest screen draws its slots first and its title last, so a title made of pictures covers the slots. The slots under it are invisible hover targets.
 * Their places on the chest's 18 px grid decide where the map can be: a stop's slot must sit under its picture, which is why the floors are 36 px apart (every
 * second column) and the lanes 36 px apart (every second row). The values are checked against the font file by {@code RouteScreenFontTest}.
 */
public final class RouteScreenFont {

    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("cobbletowers", "route_screen");

    /** Where the title text starts, and so the origin of a {@link Line}. */
    public static final int TITLE_X = 8;

    /** The chest screen: 9 columns by 6 rows of slots, then the player's 3 inventory rows and the hotbar, 90 slots in all. */
    public static final int CHEST_SLOTS = 54, SLOTS = 90, COLUMNS = 9;
    public static final int INVENTORY_FIRST = 54, HOTBAR_FIRST = 81;

    /** The five floors of a page: the chest's columns 0, 2, 4, 6 and 8. */
    public static final int FLOORS_PER_PAGE = 5;
    public static final int[] FLOOR_X = {16, 52, 88, 124, 160};

    /** The three lanes: the chest's rows 1, 3 and 5. */
    public static final int[] LANE_Y = {44, 80, 116};

    /** The floor numbers, above the lanes. */
    public static final int HEADER_Y = 26;

    /** The three choice rows, on the player inventory rows. */
    public static final int[] CHOICE_Y = {147, 165, 183};
    public static final int CHOICE_ICON_X = 16, CHOICE_NAME_X = 72;

    /** The two page buttons, on the first and last hotbar slots. */
    public static final int BUTTON_Y = 205;
    public static final int[] BUTTON_X = {16, 160};
    public static final int BACK_SLOT = HOTBAR_FIRST, NEXT_SLOT = HOTBAR_FIRST + 8;

    /** The panel: 330 x 230, in two pieces, its top left at (-77, -4). */
    public static final int PANEL_X = -77, PIECE_W = 165;

    static final int PANEL_BASE = 0xE000;
    static final int ICON_BASE = 0xE100, ICON_STEP = 0x20;
    static final int RING_BASE = 0xE200;
    static final int STRAIGHT_BASE = 0xE300, DOWN_BASE = 0xE310, UP_BASE = 0xE320;
    static final int DIGIT_BASE = 0xE400;
    static final int NAME_BASE = 0xE500, NAME_STEP = 0x10;
    static final int ARROW_BASE = 0xE600;
    static final int SPACE_POS = 0xE700, SPACE_NEG = 0xE710;

    /** Sizes of the pictures, which decide how far the cursor moves past each (the picture's width + 1). */
    static final int ICON = 18, RING = 22, LINK = 36, DIGIT = 6, NAME = 84, ARROW = 16;

    private RouteScreenFont() {}

    /** A new line in this font, its origin the title's. */
    public static Line line() {
        return new Line(FONT, SPACE_POS, SPACE_NEG);
    }

    public static char panel(int piece) {
        return (char) (PANEL_BASE + piece);
    }

    /** The picture of a stop at one of the six vertical places an icon can stand (the three lanes, then the three choice rows). */
    public static char icon(int place, Stop stop) {
        return (char) (ICON_BASE + place * ICON_STEP + stop.ordinal());
    }

    /** The veiled stop at the same places. */
    public static char fog(int place) {
        return (char) (ICON_BASE + place * ICON_STEP + Stop.values().length);
    }

    public static char ring(int lane) {
        return (char) (RING_BASE + lane);
    }

    /** A flat connector at the height of a lane. */
    public static char straight(int lane) {
        return (char) (STRAIGHT_BASE + lane);
    }

    /** A connector falling from the upper lane to the one below it (a "\"); {@code upper} is the upper lane, 0 or 1. */
    public static char down(int upper) {
        return (char) (DOWN_BASE + upper);
    }

    /** A connector rising from the lower lane to the one above it (a "/"); {@code upper} is the upper lane, 0 or 1. */
    public static char up(int upper) {
        return (char) (UP_BASE + upper);
    }

    /** A digit 0 to 9, or 10 for the dash. */
    public static char digit(int d) {
        return (char) (DIGIT_BASE + d);
    }

    /** The name of a stop on one of the three choice rows. */
    public static char name(int row, Stop stop) {
        return (char) (NAME_BASE + row * NAME_STEP + stop.ordinal());
    }

    /** The page arrows: 0 back, 1 next. */
    public static char arrow(int which) {
        return (char) (ARROW_BASE + which);
    }

    /** The chest slot under the stop at a floor column (0 to 4) and lane (0 to 2). */
    public static int stopSlot(int column, int lane) {
        return (1 + 2 * lane) * COLUMNS + 2 * column;
    }

    /** The nine slots of a choice row (a row of the player inventory), so the whole row hovers. */
    public static int choiceSlot(int row, int column) {
        return INVENTORY_FIRST + row * COLUMNS + column;
    }
}
