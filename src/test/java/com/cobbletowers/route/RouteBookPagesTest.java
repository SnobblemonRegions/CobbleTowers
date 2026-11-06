package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.route.RouteMap.Edge;
import com.cobbletowers.route.RouteMap.Node;
import com.cobbletowers.route.RouteMap.Stop;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route map as a book (TODO: a route map the player can open in the tower): the whole branching map, and what each choice opens up next. */
class RouteBookPagesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * row 3:            BOSS
     * row 2:   FORGE          VAULT        (left lane 0, right lane 2)
     * row 1:   BATTLE  REST   SHRINE
     * row 0:           START
     */
    private static RouteMap.Map map() {
        List<Node> nodes = List.of(new Node(0, 1, Stop.START),
                new Node(1, 0, Stop.BATTLE), new Node(1, 1, Stop.REST), new Node(1, 2, Stop.SHRINE),
                new Node(2, 0, Stop.FORGE), new Node(2, 2, Stop.VAULT),
                new Node(3, 1, Stop.BOSS));
        List<Edge> edges = List.of(new Edge(0, 1, 0), new Edge(0, 1, 1), new Edge(0, 1, 2),
                new Edge(1, 0, 0), new Edge(1, 1, 0), new Edge(1, 1, 2), new Edge(1, 2, 2),
                new Edge(2, 0, 1), new Edge(2, 2, 1));
        return new RouteMap.Map(3, nodes, edges);
    }

    private static String text(List<Component> pages) {
        StringBuilder out = new StringBuilder();
        for (Component page : pages) out.append(page.getString()).append("\n--page--\n");
        return out.toString();
    }

    private static String firstPage(List<Component> pages) {
        return pages.get(0).getString();
    }

    @Test
    @DisplayName("the first page draws the whole map, top row first, with each stop's letter and the boss written out")
    void drawsTheMap() {
        String page = firstPage(RouteBookPages.pages(map(), List.of(1), 3, 0));
        assertTrue(page.contains("ROUTE MAP"), page);
        assertTrue(page.contains("[B]") && page.contains("[R]") && page.contains("[S]"), page);
        assertTrue(page.contains("[F]") && page.contains("[V]"), page);
        assertTrue(page.contains("BOSS"), page);
        assertTrue(page.indexOf("BOSS") < page.indexOf("[F]") && page.indexOf("[F]") < page.indexOf("[B]"), "the top of the tower is at the top of the page:\n" + page);
    }

    @Test
    @DisplayName("stops beyond what the party can see are drawn as ?, but the boss always shows")
    void hidesWhatIsNotRevealed() {
        String page = firstPage(RouteBookPages.pages(map(), List.of(1), 1, 0));
        assertTrue(page.contains("[B]") && page.contains("[R]") && page.contains("[S]"), "row 1 is within sight:\n" + page);
        assertFalse(page.contains("[F]") || page.contains("[V]"), "row 2 is beyond sight:\n" + page);
        assertTrue(page.contains("[?]"), page);
        assertTrue(page.contains("BOSS"), page);
    }

    @Test
    @DisplayName("the party's trail is gold and bold, and the lanes it can step to now are green and bold")
    void colouring() {
        Component page = RouteBookPages.pages(map(), List.of(1, 0), 3, 0).get(0);
        boolean trail = false;
        boolean open = false;
        for (Component part : page.toFlatList(page.getStyle())) {
            String s = part.getString();
            if (s.contains("[B]")) {   // row 1 lane 0 is on the trail
                trail = part.getStyle().isBold() && part.getStyle().getColor().getValue() == RouteBookPages.TRAIL;
            }
            if (s.contains("[F]")) {   // row 2 lane 0 is open from there
                open = part.getStyle().isBold() && part.getStyle().getColor().getValue() == RouteBookPages.OPEN;
            }
        }
        assertTrue(trail, "the stop the party passed must be gold");
        assertTrue(open, "a stop the party can step to must be green");
    }

    @Test
    @DisplayName("the choices page says, for each lane open now, what it is and what it leads to next")
    void whatEachChoiceDoes() {
        String all = text(RouteBookPages.pages(map(), List.of(1), 3, 0));
        assertTrue(all.contains("Left") && all.contains("Battle"), all);
        assertTrue(all.contains("Middle") && all.contains("Rest"), all);
        assertTrue(all.contains("Right") && all.contains("Shrine"), all);
        // the middle lane (Rest) leads on to both the Forge and the Vault; the left (Battle) only to the Forge; the right (Shrine) only to the Vault
        int left = all.indexOf("Left");
        int middle = all.indexOf("Middle");
        int right = all.indexOf("Right");
        String afterLeft = all.substring(left, middle);
        String afterMiddle = all.substring(middle, right);
        String afterRight = all.substring(right, all.indexOf("LEGEND"));   // the legend that follows names every stop, so it is not part of what the lane leads to
        assertTrue(afterLeft.contains("Forge") && !afterLeft.contains("Vault"), afterLeft);
        assertTrue(afterMiddle.contains("Forge") && afterMiddle.contains("Vault"), afterMiddle);
        assertTrue(afterRight.contains("Vault") && !afterRight.contains("Forge"), afterRight);
    }

    @Test
    @DisplayName("a choice that leads to the boss says so")
    void leadsToTheBoss() {
        String all = text(RouteBookPages.pages(map(), List.of(1, 0, 0), 3, 0));
        assertTrue(all.toLowerCase().contains("boss"), all);
        assertTrue(all.contains("Forge"), all);
    }

    @Test
    @DisplayName("with no choice to make (the party stands on the last row) the page says so instead of listing nothing")
    void noChoice() {
        String all = text(RouteBookPages.pages(map(), List.of(1, 0, 0, 1), 3, 0));
        assertTrue(all.toLowerCase().contains("no choice"), all);
    }

    @Test
    @DisplayName("a later cycle of an ascending tower is named on the title")
    void namesTheCycle() {
        assertTrue(firstPage(RouteBookPages.pages(map(), List.of(1), 3, 2)).contains("cycle 3"));
        assertFalse(firstPage(RouteBookPages.pages(map(), List.of(1), 3, 0)).toLowerCase().contains("cycle"));
    }

    @Test
    @DisplayName("a tall map is split across pages of no more than 14 lines, and nothing is lost")
    void tallMapsSplit() {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(new Node(0, 1, Stop.START));
        for (int row = 1; row < 29; row++) {
            nodes.add(new Node(row, 1, Stop.BATTLE));
            edges.add(new Edge(row - 1, 1, 1));
        }
        nodes.add(new Node(29, 1, Stop.BOSS));
        edges.add(new Edge(28, 1, 1));
        List<Component> pages = RouteBookPages.pages(new RouteMap.Map(30, nodes, edges), List.of(1), 29, 0);
        for (Component page : pages) {
            assertTrue(page.getString().split("\n", -1).length <= RouteBookPages.LINES_PER_PAGE, "page too long:\n" + page.getString());
        }
        String all = text(pages);
        for (int row = 0; row < 30; row++) assertTrue(all.contains(String.valueOf(row)), "row " + row + " is missing");
    }

    @Test
    @DisplayName("every map stop has a letter and a name for the legend")
    void everyStopIsNamed() {
        for (Stop stop : Stop.values()) {
            assertFalse(RouteBookPages.letterOf(stop).isEmpty(), stop.name());
            assertFalse(RouteBookPages.nameOf(stop).isEmpty(), stop.name());
        }
        assertEquals("Trick or Treat", RouteBookPages.nameOf(Stop.TRICK_OR_TREAT));
    }

    @Test
    @DisplayName("no line of any page is wider than a book page holds, so nothing wraps and pushes text off the page")
    void linesFitTheBook() {
        List<Component> pages = RouteBookPages.pages(map(), List.of(1, 0, 0), 3, 2);
        for (Component page : pages) {
            for (String line : page.getString().split("\n", -1)) {
                assertTrue(line.length() <= RouteBookPages.WIDTH, "too wide for a book line (" + line.length() + "): '" + line + "'");
            }
        }
    }

    @Test
    @DisplayName("a long stop name wraps over lines of its own and the page still holds at most 14 lines")
    void longNamesWrap() {
        List<Node> nodes = List.of(new Node(0, 1, Stop.START), new Node(1, 0, Stop.TRICK_OR_TREAT), new Node(1, 2, Stop.POOL), new Node(2, 1, Stop.BOSS));
        List<Edge> edges = List.of(new Edge(0, 1, 0), new Edge(0, 1, 2), new Edge(1, 0, 1), new Edge(1, 2, 1));
        List<Component> pages = RouteBookPages.pages(new RouteMap.Map(3, nodes, edges), List.of(1), 2, 0);
        String all = text(pages);
        assertTrue(all.contains("Trick or"), all);
        assertTrue(all.contains("Treat"), all);
        for (Component page : pages) assertTrue(page.getString().split("\n", -1).length <= RouteBookPages.LINES_PER_PAGE, page.getString());
    }

    @Test
    @DisplayName("the legend names every letter on a page of its own and does not spill onto the map page")
    void legendHasItsOwnPage() {
        List<Component> pages = RouteBookPages.pages(map(), List.of(1), 3, 0);
        assertFalse(pages.get(0).getString().contains("LEGEND"));
        String legend = pages.get(pages.size() - 1).getString();
        assertTrue(legend.contains("LEGEND") && legend.contains("Forge") && legend.contains("Trick or") && legend.contains("Treat") && legend.contains("Mystic Pool"), legend);
    }
}
