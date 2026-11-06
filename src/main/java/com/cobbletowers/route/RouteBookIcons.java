package com.cobbletowers.route;

import com.cobbletowers.route.RouteMap.Stop;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * The picture characters of the route book's font ({@code assets/cobbletowers/font/route.json}, made by {@code tools/vendor/make_route_book_font.py}), and a
 * line builder that lays them at exact pixel positions. A written book can only show text, but text in this font is pictures, so a player with the server
 * resource pack sees the map drawn; one without it gets the letter book instead (see {@link RouteBookService}). The values here mirror the generator and are
 * checked against the font file by {@code RouteBookIconsTest}.
 *
 * <p>Every glyph's advance is its width + 1 (the generator pins its last column), and the space characters move the cursor by 1, 2, 4 ... 64 pixels either
 * way, so a {@link Line} always knows exactly where its cursor is.
 */
public final class RouteBookIcons {

    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("cobbletowers", "route");

    static final int ICON_BASE = 0xE000, MINI_BASE = 0xE040, FOG = 0xE020, RING = 0xE060, LINES = 0xE080;
    static final int SPACE_POS = 0xE100, SPACE_NEG = 0xE110;
    static final int ICON = 18, MINI = 9, RING_SIZE = 22, TILE_W = 32;

    /** Connector kinds, in the order of the lines sheet. */
    public enum Link { STRAIGHT, DOWN_RIGHT, DOWN_LEFT }

    private RouteBookIcons() {}

    public static char icon(Stop stop) {
        return (char) (ICON_BASE + stop.ordinal());
    }

    public static char mini(Stop stop) {
        return (char) (MINI_BASE + stop.ordinal());
    }

    public static char fog() {
        return (char) FOG;
    }

    public static char ring() {
        return (char) RING;
    }

    public static char link(Link link) {
        return (char) (LINES + link.ordinal());
    }

    /** The cursor advance of a glyph of this width: Minecraft measures to the last non-transparent column, and adds one. */
    static int advance(int width) {
        return width + 1;
    }

    /** The space characters that move the cursor by exactly {@code dx} pixels (either way). */
    static String move(int dx) {
        return move(dx, SPACE_POS, SPACE_NEG);
    }

    /** The same, for a font whose space characters start at {@code spacePos} (right, 1 px) and {@code spaceNeg} (left, 1 px), each followed by 2, 4 ... 64 px. */
    static String move(int dx, int spacePos, int spaceNeg) {
        StringBuilder out = new StringBuilder();
        int base = dx < 0 ? spaceNeg : spacePos;
        int left = Math.abs(dx);
        while (left >= 64) {
            out.append((char) (base + 6));
            left -= 64;
        }
        for (int bit = 5; bit >= 0; bit--) {
            if ((left & (1 << bit)) != 0) out.append((char) (base + bit));
        }
        return out.toString();
    }

    /** The font style, tinted: the pictures are drawn in their own colours under white, and a colour multiplies them. */
    static Style style(int rgb) {
        return Style.EMPTY.withFont(FONT).withColor(TextColor.fromRgb(rgb)).withBold(false).withItalic(false);
    }

    /**
     * One line of a book page, built left to right with the cursor position tracked, so a picture can be put at any pixel. {@link #widest()} is how far right
     * the line ever reached: a page wraps a line wider than {@link RouteBookMapPages#PAGE_WIDTH}, which would push the rest of the map down.
     */
    static final class Line {
        private final MutableComponent text = Component.empty();
        private final ResourceLocation font;
        private final int spacePos;
        private final int spaceNeg;
        private int x;
        private int widest;

        Line() {
            this(FONT, SPACE_POS, SPACE_NEG);
        }

        /** A line in another font, whose space characters start at {@code spacePos} and {@code spaceNeg}. */
        Line(ResourceLocation font, int spacePos, int spaceNeg) {
            this.font = font;
            this.spacePos = spacePos;
            this.spaceNeg = spaceNeg;
        }

        /** Plain text in the book's own font, of a known width (the default font's digits are 6 px each). */
        Line text(String s, int width, Style style) {
            text.append(Component.literal(s).withStyle(style));
            return at(x + width);
        }

        private Line at(int nx) {
            x = nx;
            widest = Math.max(widest, x);
            return this;
        }

        /** Moves the cursor to {@code nx}. */
        Line to(int nx) {
            if (nx != x) text.append(Component.literal(move(nx - x, spacePos, spaceNeg)).withStyle(style(0xFFFFFF).withFont(font)));
            return at(nx);
        }

        /** A glyph of {@code width} pixels with its left edge at {@code left}. */
        Line glyph(int left, char glyph, int width, Style style) {
            to(left);
            text.append(Component.literal(String.valueOf(glyph)).withStyle(style.withFont(font)));
            return at(left + advance(width));
        }

        /** An invisible span from {@code left}, {@code width} wide, carrying {@code style} (for a hover over the top half of a picture). */
        Line span(int left, int width, Style style) {
            to(left);
            text.append(Component.literal(move(width, spacePos, spaceNeg)).withStyle(style.withFont(font)));
            return at(left + width);
        }

        /** Text already styled, of a width this line does not track (only for lines that hold nothing after it). */
        Line append(Component c) {
            text.append(c);
            return this;
        }

        int widest() {
            return widest;
        }

        Component build() {
            return text;
        }
    }
}
