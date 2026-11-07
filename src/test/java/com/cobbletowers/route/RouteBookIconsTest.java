package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.route.RouteBookIcons.Line;
import com.cobbletowers.route.RouteBookMapPages.StopState;
import com.cobbletowers.route.RouteMap.Stop;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route book in pictures: its characters match the font file, every line fits the page, and fog hides what it hides. */
class RouteBookIconsTest {

    private static final Path FONT = Path.of("src/main/resources/assets/cobbletowers/font/route.json");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RouteMap.Map halloween(long seed) {
        IntPredicate milestone = row -> row == 5 || row == 10;
        return RouteMap.generate(seed, 10, true, milestone, RouteMap.PLAYABLE);
    }

    @Test
    @DisplayName("the characters and sizes here are the ones the font file maps")
    void matchesTheFont() throws Exception {
        JsonObject font = JsonParser.parseString(Files.readString(FONT)).getAsJsonObject();
        var providers = font.getAsJsonArray("providers");
        String icons = providers.get(0).getAsJsonObject().getAsJsonArray("chars").get(0).getAsString();
        for (Stop stop : Stop.values()) assertEquals(RouteBookIcons.icon(stop), icons.charAt(stop.ordinal()), stop.name());
        assertEquals(RouteBookIcons.fog(), icons.charAt(Stop.values().length));
        assertEquals(RouteBookIcons.ICON, providers.get(0).getAsJsonObject().get("height").getAsInt());
        String minis = providers.get(1).getAsJsonObject().getAsJsonArray("chars").get(0).getAsString();
        for (Stop stop : Stop.values()) assertEquals(RouteBookIcons.mini(stop), minis.charAt(stop.ordinal()), stop.name());
        assertEquals(String.valueOf(RouteBookIcons.ring()), providers.get(2).getAsJsonObject().getAsJsonArray("chars").get(0).getAsString());
        String links = providers.get(3).getAsJsonObject().getAsJsonArray("chars").get(0).getAsString();
        for (RouteBookIcons.Link link : RouteBookIcons.Link.values()) assertEquals(RouteBookIcons.link(link), links.charAt(link.ordinal()));
        JsonObject advances = providers.get(4).getAsJsonObject().getAsJsonObject("advances");
        for (int bit = 0; bit < 7; bit++) {
            assertEquals(1 << bit, advances.get(String.valueOf((char) (RouteBookIcons.SPACE_POS + bit))).getAsInt());
            assertEquals(-(1 << bit), advances.get(String.valueOf((char) (RouteBookIcons.SPACE_NEG + bit))).getAsInt());
        }
    }

    @Test
    @DisplayName("moving the cursor adds up to exactly the distance asked, either way")
    void movesExactly() throws Exception {
        JsonObject advances = JsonParser.parseString(Files.readString(FONT)).getAsJsonObject().getAsJsonArray("providers").get(4).getAsJsonObject()
                .getAsJsonObject("advances");
        for (int dx = -200; dx <= 200; dx++) {
            int sum = 0;
            for (char c : RouteBookIcons.move(dx).toCharArray()) sum += advances.get(String.valueOf(c)).getAsInt();
            assertEquals(dx, sum, "dx " + dx);
        }
    }

    @Test
    @DisplayName("every map line stays inside the page, and a page of five floors is exactly the 14 lines a book shows")
    void fitsThePage() {
        for (long seed = 0; seed < 200; seed++) {
            RouteMap.Map map = halloween(seed);
            for (int at = 0; at <= map.rows(); at++) {
                List<Integer> trail = walk(map, at);
                for (int lo = 1; lo <= map.rows(); lo += RouteBookMapPages.ROWS_PER_PAGE) {
                    int hi = Math.min(map.rows(), lo + RouteBookMapPages.ROWS_PER_PAGE - 1);
                    List<Line> lines = RouteBookMapPages.mapPage(map, trail, at + 2, lo, hi);
                    assertTrue(lines.size() <= RouteBookPages.LINES_PER_PAGE, "seed " + seed + " lines " + lines.size());
                    for (Line line : lines) assertTrue(line.widest() <= RouteBookMapPages.PAGE_WIDTH, "seed " + seed + " width " + line.widest());
                }
            }
        }
    }

    @Test
    @DisplayName("a stop not scouted yet is fog, and nothing in its hover names its kind")
    void fogHidesTheKind() {
        RouteMap.Map map = halloween(7);
        List<Integer> trail = walk(map, 0);
        List<Line> lines = RouteBookMapPages.mapPage(map, trail, 1, 6, 9);
        List<String> hovers = new ArrayList<>();
        for (Line line : lines) collectHovers(line.build(), hovers);
        assertFalse(hovers.isEmpty());
        for (String h : hovers) {
            assertTrue(h.startsWith("Not scouted yet"), h);
            for (Stop stop : Stop.values()) if (stop != Stop.BOSS) assertFalse(h.contains(RouteBookPages.nameOf(stop)), h);
        }
        for (Line line : lines) {
            String s = line.build().getString();
            for (Stop stop : Stop.values()) if (stop != Stop.BOSS) assertFalse(s.indexOf(RouteBookIcons.icon(stop)) >= 0, "the icon of " + stop + " shows");
        }
    }

    @Test
    @DisplayName("where the party stands, the way it came and the lanes open now are told apart")
    void states() {
        List<Integer> trail = List.of(1, 1, 0);
        List<Integer> open = List.of(0, 1);
        assertEquals(StopState.HERE, RouteBookMapPages.stateOf(2, 0, trail, open, 3, Stop.REST));
        assertEquals(StopState.TRAIL, RouteBookMapPages.stateOf(1, 1, trail, open, 3, Stop.BATTLE));
        assertEquals(StopState.OPEN, RouteBookMapPages.stateOf(3, 1, trail, open, 3, Stop.VAULT));
        assertEquals(StopState.SEEN, RouteBookMapPages.stateOf(3, 2, trail, open, 3, Stop.VAULT));
        assertEquals(StopState.HIDDEN, RouteBookMapPages.stateOf(4, 1, trail, open, 3, Stop.VAULT));
        assertEquals(StopState.SEEN, RouteBookMapPages.stateOf(5, 1, trail, open, 3, Stop.BOSS));
    }

    /** A trail from the start to row {@code at}, taking the leftmost lane each step. */
    private static List<Integer> walk(RouteMap.Map map, int at) {
        List<Integer> trail = new ArrayList<>(List.of(RouteMap.MIDDLE));
        for (int row = 0; row < at; row++) trail.add(map.next(row, trail.get(row)).get(0));
        return trail;
    }

    private static void collectHovers(Component c, List<String> out) {
        HoverEvent hover = c.getStyle().getHoverEvent();
        if (hover != null) {
            Component shown = hover.getValue(HoverEvent.Action.SHOW_TEXT);
            if (shown != null && !out.contains(shown.getString())) out.add(shown.getString());
        }
        for (Component sibling : c.getSiblings()) collectHovers(sibling, out);
    }
}
