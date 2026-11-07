package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.route.RouteBookMapPages.StopState;
import com.cobbletowers.route.RouteMap.Stop;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route screen: its characters match the font file, the stops sit over their slots, a veiled stop gives nothing away, and the Route Map item is itself. */
class RouteScreenTest {

    private static final Path FONT = Path.of("src/main/resources/assets/cobbletowers/font/route_screen.json");
    /** A glyph's top is the baseline (the title's y + 7) minus its ascent; the chest's title is drawn at y = 6. */
    private static final int BASELINE = 6 + 7;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RouteMap.Map halloween(long seed) {
        IntPredicate milestone = row -> row == 5 || row == 10;
        return RouteMap.generate(seed, 10, true, milestone, RouteMap.PLAYABLE);
    }

    private static List<Integer> walk(RouteMap.Map map, int at) {
        List<Integer> trail = new ArrayList<>(List.of(RouteMap.MIDDLE));
        for (int row = 0; row < at; row++) trail.add(map.next(row, trail.get(row)).get(0));
        return trail;
    }

    private static JsonArray providers() throws Exception {
        return JsonParser.parseString(Files.readString(FONT)).getAsJsonObject().getAsJsonArray("providers");
    }

    /** The bitmap provider that holds {@code ch}. */
    private static JsonObject providerOf(char ch) throws Exception {
        for (JsonElement e : providers()) {
            JsonObject p = e.getAsJsonObject();
            if (!p.get("type").getAsString().equals("bitmap")) continue;
            for (JsonElement row : p.getAsJsonArray("chars")) if (row.getAsString().indexOf(ch) >= 0) return p;
        }
        return null;
    }

    @Test
    @DisplayName("every character the layout can use is in the font file, with the height and ascent that put it where the layout says")
    void fontMatchesLayout() throws Exception {
        for (int i = 0; i < 2; i++) {
            JsonObject p = providerOf(RouteScreenFont.panel(i));
            assertNotNull(p, "panel piece " + i);
            assertEquals(BASELINE - (-4), p.get("ascent").getAsInt(), "the panel starts 4 px above the chest");
            assertEquals(230, p.get("height").getAsInt());
        }
        for (int place = 0; place < 6; place++) {
            int y = place < 3 ? RouteScreenFont.LANE_Y[place] : RouteScreenFont.CHOICE_Y[place - 3];
            for (Stop stop : Stop.values()) {
                JsonObject p = providerOf(RouteScreenFont.icon(place, stop));
                assertNotNull(p, "icon " + stop + " at place " + place);
                assertEquals(BASELINE - (y - 9), p.get("ascent").getAsInt(), stop + " at place " + place);
                assertEquals(18, p.get("height").getAsInt());
            }
            assertNotNull(providerOf(RouteScreenFont.fog(place)), "fog at place " + place);
        }
        for (int lane = 0; lane < 3; lane++) {
            assertEquals(BASELINE - (RouteScreenFont.LANE_Y[lane] - 11), providerOf(RouteScreenFont.ring(lane)).get("ascent").getAsInt());
            assertEquals(BASELINE - (RouteScreenFont.LANE_Y[lane] - 2), providerOf(RouteScreenFont.straight(lane)).get("ascent").getAsInt());
        }
        for (int upper = 0; upper < 2; upper++) {
            assertEquals(BASELINE - RouteScreenFont.LANE_Y[upper], providerOf(RouteScreenFont.down(upper)).get("ascent").getAsInt());
            assertEquals(BASELINE - RouteScreenFont.LANE_Y[upper], providerOf(RouteScreenFont.up(upper)).get("ascent").getAsInt());
        }
        for (int d = 0; d <= 10; d++) {
            assertEquals(BASELINE - (RouteScreenFont.HEADER_Y - 4), providerOf(RouteScreenFont.digit(d)).get("ascent").getAsInt());
        }
        for (int row = 0; row < 3; row++) {
            for (Stop stop : Stop.values()) {
                assertEquals(BASELINE - (RouteScreenFont.CHOICE_Y[row] - 4), providerOf(RouteScreenFont.name(row, stop)).get("ascent").getAsInt());
            }
        }
        for (int a = 0; a < 2; a++) {
            assertEquals(BASELINE - (RouteScreenFont.BUTTON_Y - 6), providerOf(RouteScreenFont.arrow(a)).get("ascent").getAsInt());
        }
    }

    @Test
    @DisplayName("the space characters of the font move by exactly 1 to 64 px either way")
    void spaces() throws Exception {
        JsonObject advances = null;
        for (JsonElement e : providers()) if (e.getAsJsonObject().get("type").getAsString().equals("space")) advances = e.getAsJsonObject().getAsJsonObject("advances");
        assertNotNull(advances);
        for (int dx = -250; dx <= 250; dx++) {
            int sum = 0;
            for (char c : RouteBookIcons.move(dx, RouteScreenFont.SPACE_POS, RouteScreenFont.SPACE_NEG).toCharArray()) sum += advances.get(String.valueOf(c)).getAsInt();
            assertEquals(dx, sum, "dx " + dx);
        }
    }

    @Test
    @DisplayName("each stop's slot sits under its picture, on the chest's own grid")
    void slotsAreUnderPictures() {
        // chest slot (row r, column c) is 16 px square at (8 + 18c, 18 + 18r), so its centre is (16 + 18c, 26 + 18r); the player inventory rows start at y = 139
        // and the hotbar at y = 197 (vanilla ChestMenu)
        for (int i = 0; i < RouteScreenFont.FLOORS_PER_PAGE; i++) assertEquals(16 + 18 * (2 * i), RouteScreenFont.FLOOR_X[i]);
        for (int lane = 0; lane < 3; lane++) assertEquals(26 + 18 * (1 + 2 * lane), RouteScreenFont.LANE_Y[lane]);
        for (int row = 0; row < 3; row++) assertEquals(139 + 18 * row + 8, RouteScreenFont.CHOICE_Y[row]);
        assertEquals(197 + 8, RouteScreenFont.BUTTON_Y);
        assertEquals(16, RouteScreenFont.BUTTON_X[0]);
        assertEquals(16 + 18 * 8, RouteScreenFont.BUTTON_X[1]);
        assertEquals(54, RouteScreenFont.INVENTORY_FIRST);
        assertEquals(81, RouteScreenFont.BACK_SLOT);
        assertEquals(89, RouteScreenFont.NEXT_SLOT);
        for (int i = 0; i < 5; i++) {
            for (int lane = 0; lane < 3; lane++) {
                int slot = RouteScreenFont.stopSlot(i, lane);
                assertEquals(RouteScreenFont.FLOOR_X[i], 16 + 18 * (slot % 9));
                assertEquals(RouteScreenFont.LANE_Y[lane], 26 + 18 * (slot / 9));
            }
        }
    }

    @Test
    @DisplayName("across many maps and every place the party can stand, a page uses only font characters, one tip per slot, and a veiled stop's tip says only that")
    void layouts() throws Exception {
        Set<Character> known = new HashSet<>();
        for (JsonElement e : providers()) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("type").getAsString().equals("bitmap")) for (JsonElement row : p.getAsJsonArray("chars")) for (char c : row.getAsString().toCharArray()) known.add(c);
            else for (String key : p.getAsJsonObject("advances").keySet()) known.add(key.charAt(0));
        }
        for (long seed = 0; seed < 150; seed++) {
            RouteMap.Map map = halloween(seed);
            for (int at = 0; at <= map.rows(); at++) {
                List<Integer> trail = walk(map, at);
                for (int page = 0; page < RouteScreenLayout.pages(map); page++) {
                    RouteScreenLayout.View view = RouteScreenLayout.build(map, trail, at + 2, page);
                    assertEquals(page, view.page());
                    for (char c : view.title().getString().toCharArray()) assertTrue(known.contains(c), "seed " + seed + " uses U+" + Integer.toHexString(c));
                    Set<Integer> slots = new HashSet<>();
                    for (RouteScreenLayout.Tip tip : view.tips()) {
                        assertTrue(slots.add(tip.slot()), "two tips on slot " + tip.slot());
                        assertTrue(tip.slot() >= 0 && tip.slot() < RouteScreenFont.SLOTS);
                    }
                    String all = view.title().getString();
                    for (RouteScreenLayout.Tip tip : view.tips()) {
                        if (!tip.name().getString().equals("Not scouted yet")) continue;
                        for (Component line : tip.lore()) for (Stop stop : Stop.values()) {
                            if (stop != Stop.BOSS) assertFalse(line.getString().contains(RouteBookPages.nameOf(stop)), line.getString());
                        }
                    }
                    // a veiled stop is fog in the title, never its picture: the picture of a kind would tell a modded client what it is
                    int here = trail.size() - 1;
                    int first = page * RouteScreenFont.FLOORS_PER_PAGE + 1;
                    List<Integer> open = map.next(here, trail.get(here));
                    // count each kind's picture in each lane: it must equal the number of stops of that kind that are NOT veiled, so a veiled one
                    // contributes only fog
                    for (int lane = 0; lane < 3; lane++) {
                        for (Stop kind : Stop.values()) {
                            int expected = 0;
                            for (int i = 0; i < RouteScreenFont.FLOORS_PER_PAGE && first + i <= map.rows(); i++) {
                                Stop stop = map.stopAt(first + i, lane);
                                if (stop != kind) continue;
                                if (RouteBookMapPages.stateOf(first + i, lane, trail, open, at + 2, stop) != StopState.HIDDEN) expected++;
                            }
                            char picture = RouteScreenFont.icon(lane, kind);
                            int actual = (int) all.chars().filter(ch -> ch == picture).count();
                            assertEquals(expected, actual, "kind " + kind + " in lane " + lane + ", seed " + seed + ", at " + at);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the page opens where the next choice is, and the back and next buttons exist only where there is somewhere to go")
    void pages() {
        RouteMap.Map map = halloween(3);
        assertEquals(2, RouteScreenLayout.pages(map));
        assertEquals(0, RouteScreenLayout.pageOf(map, walk(map, 0)));
        assertEquals(0, RouteScreenLayout.pageOf(map, walk(map, 4)));
        assertEquals(1, RouteScreenLayout.pageOf(map, walk(map, 5)));
        assertEquals(1, RouteScreenLayout.pageOf(map, walk(map, 10)));
        RouteScreenLayout.View first = RouteScreenLayout.build(map, walk(map, 0), 2, 0);
        RouteScreenLayout.View second = RouteScreenLayout.build(map, walk(map, 5), 7, 1);
        assertTrue(first.tips().stream().anyMatch(t -> t.slot() == RouteScreenFont.NEXT_SLOT));
        assertFalse(first.tips().stream().anyMatch(t -> t.slot() == RouteScreenFont.BACK_SLOT));
        assertTrue(second.tips().stream().anyMatch(t -> t.slot() == RouteScreenFont.BACK_SLOT));
        assertFalse(second.tips().stream().anyMatch(t -> t.slot() == RouteScreenFont.NEXT_SLOT));
        assertEquals(1, RouteScreenLayout.build(map, walk(map, 0), 2, 99).page() == 1 ? 1 : 0, "a page past the end is clamped to the last");
    }

    @Test
    @DisplayName("the Route Map is a paper marked in its data, one at a time, and a plain paper is not one")
    void routeMapItem() {
        ItemStack map = RouteMapItem.create();
        assertTrue(RouteMapItem.isRouteMap(map));
        assertEquals(1, map.getMaxStackSize());
        assertFalse(RouteMapItem.isRouteMap(new ItemStack(Items.PAPER)));
        assertFalse(RouteMapItem.isRouteMap(ItemStack.EMPTY));
        assertFalse(RouteMapItem.isRouteMap(RouteScreenItems.tip(Component.literal("Forge"), List.of())), "a hover target is not a map");
        assertFalse(RouteMapItem.isRouteMap(new ItemStack(Items.COMPASS)));
    }
}
