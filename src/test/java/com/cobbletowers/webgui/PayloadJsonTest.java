package com.cobbletowers.webgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.network.IntermissionStatePayload;
import com.cobbletowers.network.RentalDraftPayload;
import com.cobbletowers.network.RewardRevealPayload;
import com.cobbletowers.network.SpectatorPanelPayload;
import com.cobbletowers.network.VendorCatalogPayload;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalDraw;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a WebGUI page is sent. The page is written against exactly this JSON, so the shapes asserted here are the contract,
 * and every run rewrites {@code build/webgui-fixtures/} from the real payload records for the page's author to read.
 */
class PayloadJsonTest {

    private static final Path FIXTURES = Paths.get("build", "webgui-fixtures");

    private enum Color { RED }

    private record Sample(Color color, UUID id, List<Integer> numbers, java.util.Optional<String> note, long big) {}

    private static void fixture(String name, Object payload) throws IOException {
        Files.createDirectories(FIXTURES);
        Files.writeString(FIXTURES.resolve(name + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(PayloadJson.envelope(payload)) + "\n");
    }

    @Test
    @DisplayName("a record becomes an object; an enum, a UUID and an Optional become plain JSON")
    void shapes() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        JsonObject out = PayloadJson.toJson(new Sample(Color.RED, id, List.of(1, 2), java.util.Optional.empty(), 5_000_000_000L)).getAsJsonObject();
        assertEquals("RED", out.get("color").getAsString());
        assertEquals(id.toString(), out.get("id").getAsString());
        assertEquals(2, out.getAsJsonArray("numbers").size());
        assertTrue(out.get("note").isJsonNull());
        assertEquals(5_000_000_000L, out.get("big").getAsLong());
    }

    @Test
    @DisplayName("the envelope names the screen by the record, without the Payload suffix")
    void envelope() {
        assertEquals("IntermissionState", PayloadJson.kindOf(IntermissionStatePayload.class));
        assertEquals("RentalDraft", PayloadJson.kindOf(RentalDraftPayload.class));
        assertEquals("TowerFeatureState", PayloadJson.kindOf(com.cobbletowers.network.TowerFeatureState.class));
    }

    @Test
    @DisplayName("the intermission screen's state reaches the page with every field the native screen reads")
    void intermission() throws IOException {
        var card = new IntermissionStatePayload.Card(ResourceLocation.fromNamespaceAndPath("cobbletowers", "downpour"), "Downpour", 2, 1,
                "weather:raindance", List.of("Risk: moderate / Stack limit: 1", "Battle weather: raindance"));
        var sent = new IntermissionStatePayload(4, new IntermissionStatePayload.Draft(1, List.of(card), -1, 0),
                List.of(new IntermissionStatePayload.Member("Alex", true, false)), 24, "Floor 4 cleared", true);
        fixture("IntermissionState", sent);

        JsonObject envelope = PayloadJson.envelope(sent);
        assertEquals("IntermissionState", envelope.get("kind").getAsString());
        JsonObject data = envelope.getAsJsonObject("data");
        assertEquals(4, data.get("floor").getAsInt());
        assertTrue(data.get("open").getAsBoolean());
        assertEquals(24, data.get("countdown").getAsInt());
        JsonObject first = data.getAsJsonObject("draft").getAsJsonArray("cards").get(0).getAsJsonObject();
        assertEquals("cobbletowers:downpour", first.get("id").getAsString());
        assertEquals("Downpour", first.get("displayName").getAsString());
        assertEquals(2, first.getAsJsonArray("lines").size());
        assertTrue(data.getAsJsonArray("members").get(0).getAsJsonObject().get("ready").getAsBoolean());
    }

    @Test
    @DisplayName("a real rental draft reaches the page as three packs of five cards with their moves")
    void rentalDraft() throws IOException {
        RentalDraft draft = new RentalDraft(RentalDraw.draw(pool(), 11, false));
        RentalDraftPayload sent = RentalDraftPayload.of(draft, "");
        fixture("RentalDraft", sent);

        JsonObject data = PayloadJson.envelope(sent).getAsJsonObject("data");
        assertEquals(3, data.getAsJsonArray("packs").size());
        JsonObject pack = data.getAsJsonArray("packs").get(0).getAsJsonObject();
        assertEquals(5, pack.getAsJsonArray("cards").size());
        JsonObject card = pack.getAsJsonArray("cards").get(0).getAsJsonObject();
        assertTrue(card.get("species").getAsString().length() > 0);
        assertTrue(card.get("rarity").getAsString().length() > 0);
        assertTrue(card.has("details"), "the card carries its set so the page can draw it");
    }

    @Test
    @DisplayName("reward, run-effects and vendor payloads convert without losing a list or a nested record")
    void others() throws IOException {
        var reward = new RewardRevealPayload(4, List.of(new RewardRevealPayload.Grant(
                ResourceLocation.fromNamespaceAndPath("cobblemon", "super_potion"), 2, "Super Potion")));
        fixture("RewardReveal", reward);
        var grant = PayloadJson.envelope(reward).getAsJsonObject("data").getAsJsonArray("grants").get(0).getAsJsonObject();
        assertEquals("cobblemon:super_potion", grant.get("item").getAsString());
        assertEquals(2, grant.get("amount").getAsInt());

        var panel = new SpectatorPanelPayload("Ash", 4, 6, 7, "In battle");
        fixture("SpectatorPanel", panel);
        assertEquals("Ash", PayloadJson.envelope(panel).getAsJsonObject("data").get("teammateName").getAsString());

        UUID me = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var vendor = new VendorCatalogPayload(180, List.of(new VendorCatalogPayload.Entry(
                ResourceLocation.fromNamespaceAndPath("cobbletowers", "full_heal"), "Full Heal", 25, -1)),
                List.of(new VendorCatalogPayload.Teammate(me, "Alex", true)), "");
        fixture("VendorCatalog", vendor);
        JsonObject data = PayloadJson.envelope(vendor).getAsJsonObject("data");
        assertEquals(180, data.get("cobbleDollars").getAsLong());
        assertEquals(me.toString(), data.getAsJsonArray("team").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    @DisplayName("the counter shop's extras reach the page: floor, place, line, goods sold elsewhere, each teammate's party health (P59)")
    void counterShop() {
        UUID me = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ResourceLocation bag = ResourceLocation.fromNamespaceAndPath("cobbletowers", "mystery_bag");
        var shop = new VendorCatalogPayload.Shop("Neutral Tower", 0, 10, "prep", "Supplies for the climb.",
                List.of(new VendorCatalogPayload.Good(bag, "Mystery Bag", 500, "A sealed bag of tower loot.", false, "Sold on the floors")),
                List.of(new VendorCatalogPayload.Party(me, List.of(new VendorCatalogPayload.Mon("Lucario", 0, true, ""),
                        new VendorCatalogPayload.Mon("Volcarona", 100, false, "psn")))));
        var vendor = new VendorCatalogPayload(413, List.of(), List.of(new VendorCatalogPayload.Teammate(me, "Alex", true)), "", shop);
        JsonObject data = PayloadJson.envelope(vendor).getAsJsonObject("data").getAsJsonObject("shop");
        assertEquals("prep", data.get("place").getAsString());
        assertEquals(10, data.get("floors").getAsInt());
        JsonObject good = data.getAsJsonArray("goods").get(0).getAsJsonObject();
        assertEquals(false, good.get("here").getAsBoolean());
        assertEquals("Sold on the floors", good.get("where").getAsString());
        JsonObject mon = data.getAsJsonArray("parties").get(0).getAsJsonObject().getAsJsonArray("mons").get(1).getAsJsonObject();
        assertEquals("psn", mon.get("status").getAsString());
        // the web and native screens keep building the catalog without it
        assertEquals(VendorCatalogPayload.Shop.NONE, new VendorCatalogPayload(1, List.of(), List.of(), "").shop());
    }

    @Test
    @DisplayName("the lobby carries what each teammate is bringing (P51)")
    void lobbyRosters() {
        var mon = new com.cobbletowers.network.TowerHallStatePayload.Mon("Arcanine", "arcanine", 50, false, 100, List.of("fire"));
        var options = new com.cobbletowers.network.PlayStatePayload.Options(com.cobbletowers.network.PlayStatePayload.Depth.none(),
                com.cobbletowers.network.PlayStatePayload.Modes.none(),
                List.of(new com.cobbletowers.network.PlayStatePayload.Roster("Sam", List.of(mon))));
        var sent = new com.cobbletowers.network.PlayStatePayload(List.of(),
                new com.cobbletowers.network.PlayStatePayload.Lobby(1, "", "Alex", List.of(), -1, options), List.of(), "", false);
        JsonObject roster = PayloadJson.envelope(sent).getAsJsonObject("data").getAsJsonObject("lobby").getAsJsonObject("options")
                .getAsJsonArray("rosters").get(0).getAsJsonObject();
        assertEquals("Sam", roster.get("name").getAsString());
        assertEquals("arcanine", roster.getAsJsonArray("team").get(0).getAsJsonObject().get("species").getAsString());
    }

    @Test
    @DisplayName("the Hall carries the team, the run's tower and the mastery standings the Frontier Desk page reads (P51)")
    void hallExtras() throws IOException {
        var tide = ResourceLocation.fromNamespaceAndPath("cobbletowers", "tideforge");
        var extras = new com.cobbletowers.network.TowerHallStatePayload.Extras("cobbletowers:tideforge",
                List.of(new com.cobbletowers.network.TowerHallStatePayload.Mon("Arcanine", "arcanine", 50, false, 100, List.of("fire")),
                        new com.cobbletowers.network.TowerHallStatePayload.Mon("Gliscor", "gliscor", 50, true, 0, List.of("ground", "flying"))),
                List.of(new com.cobbletowers.network.TowerHallStatePayload.Standing(tide, "Silver", 5, 2, 1)));
        var sent = new com.cobbletowers.network.TowerHallStatePayload(List.of(), List.of(), null, "Floor 6 / INTERMISSION", true, extras);
        JsonObject data = PayloadJson.envelope(sent).getAsJsonObject("data");
        JsonObject out = data.getAsJsonObject("extras");
        assertEquals("cobbletowers:tideforge", out.get("runTower").getAsString());
        JsonObject gliscor = out.getAsJsonArray("party").get(1).getAsJsonObject();
        assertEquals("gliscor", gliscor.get("species").getAsString());
        assertTrue(gliscor.get("fainted").getAsBoolean());
        assertEquals(0, gliscor.get("hp").getAsInt());
        assertEquals(2, gliscor.getAsJsonArray("types").size());
        JsonObject standing = out.getAsJsonArray("standings").get(0).getAsJsonObject();
        assertEquals("cobbletowers:tideforge", standing.get("tower").getAsString());
        assertEquals("Silver", standing.get("rank").getAsString());
        assertEquals(2, standing.get("toNext").getAsInt());
        assertEquals(1, standing.get("ascension").getAsInt());
        // the five-argument constructor the native screens use sends no extras, which the page treats as an empty team
        var plain = new com.cobbletowers.network.TowerHallStatePayload(List.of(), List.of(), null, "", false);
        assertEquals(0, PayloadJson.envelope(plain).getAsJsonObject("data").getAsJsonObject("extras").getAsJsonArray("party").size());
    }

    @Test
    @DisplayName("the intermission carries the team's health and the run's place in its tower for the Frontier Desk page (P51)")
    void intermissionExtras() {
        var route = new IntermissionStatePayload.Route(1, List.of(new IntermissionStatePayload.Route.Node(0, 0, "REST", ""), new IntermissionStatePayload.Route.Node(1, 1, "FORGE", "cobbletowers:duskvale")),
                List.of(new IntermissionStatePayload.Route.Step(5, false, List.of("BATTLE", "SHRINE", "REST")), new IntermissionStatePayload.Route.Step(6, true, List.of())));
        var extras = new IntermissionStatePayload.Extras("Tideforge Tower", 4, 10, List.of(5, 10),
                List.of(new com.cobbletowers.network.TowerHallStatePayload.Mon("Arcanine", "arcanine", 50, false, 71, List.of("fire"))), route);
        var sent = new IntermissionStatePayload(5, new IntermissionStatePayload.Draft(0, List.of(), -1, -1), List.of(), -1, "", true, extras);
        JsonObject out = PayloadJson.envelope(sent).getAsJsonObject("data").getAsJsonObject("extras");
        assertEquals("Tideforge Tower", out.get("tower").getAsString());
        assertEquals(4, out.get("cycleFloor").getAsInt());
        assertEquals(10, out.get("cycleLength").getAsInt());
        assertEquals(2, out.getAsJsonArray("milestones").size());
        assertEquals(71, out.getAsJsonArray("party").get(0).getAsJsonObject().get("hp").getAsInt());
        JsonObject r = out.getAsJsonObject("route");
        assertEquals(1, r.get("lane").getAsInt());
        assertEquals("FORGE", r.getAsJsonArray("now").get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals(1, r.getAsJsonArray("now").get(1).getAsJsonObject().get("card").getAsInt());
        assertEquals("cobbletowers:duskvale", r.getAsJsonArray("now").get(1).getAsJsonObject().get("arena").getAsString());
        assertEquals("", r.getAsJsonArray("now").get(0).getAsJsonObject().get("arena").getAsString());
        assertTrue(r.getAsJsonArray("ahead").get(1).getAsJsonObject().get("milestone").getAsBoolean());
        assertEquals(3, r.getAsJsonArray("ahead").get(0).getAsJsonObject().getAsJsonArray("kinds").size());
        // the six-argument constructor the native screens use sends no extras
        var plain = new IntermissionStatePayload(5, new IntermissionStatePayload.Draft(0, List.of(), -1, -1), List.of(), -1, "", true);
        assertEquals(0, PayloadJson.envelope(plain).getAsJsonObject("data").getAsJsonObject("extras").get("cycleLength").getAsInt());
        assertEquals(0, PayloadJson.envelope(plain).getAsJsonObject("data").getAsJsonObject("extras").getAsJsonObject("route").getAsJsonArray("now").size());
    }

    @Test
    @DisplayName("the route map reaches the page as stops, links, the party's trail and how far scouting sees (P56)")
    void routeGraph() {
        var graph = new IntermissionStatePayload.Route.Graph(10, 4, List.of(1, 1, 0),
                List.of(new IntermissionStatePayload.Route.Graph.Stop(0, 1, "START"), new IntermissionStatePayload.Route.Graph.Stop(1, 1, "BATTLE")),
                List.of(new IntermissionStatePayload.Route.Graph.Link(0, 1, 1)));
        var route = new IntermissionStatePayload.Route(0, List.of(), List.of(), graph);
        var extras = new IntermissionStatePayload.Extras("Hollow's Eve", 3, 10, List.of(5, 10), List.of(), route);
        var sent = new IntermissionStatePayload(3, new IntermissionStatePayload.Draft(0, List.of(), -1, -1), List.of(), -1, "", true, extras);
        JsonObject g = PayloadJson.envelope(sent).getAsJsonObject("data").getAsJsonObject("extras").getAsJsonObject("route").getAsJsonObject("graph");
        assertEquals(10, g.get("rows").getAsInt());
        assertEquals(4, g.get("reveal").getAsInt());
        assertEquals(3, g.getAsJsonArray("trail").size());
        assertEquals("BATTLE", g.getAsJsonArray("stops").get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals(1, g.getAsJsonArray("links").get(0).getAsJsonObject().get("to").getAsInt());
        // a route with no map sends rows 0, which the page reads as "no map"
        var plain = new IntermissionStatePayload.Route(-1, List.of(), List.of());
        var none = new IntermissionStatePayload(3, new IntermissionStatePayload.Draft(0, List.of(), -1, -1), List.of(), -1, "", true,
                new IntermissionStatePayload.Extras("", 0, 0, List.of(), List.of(), plain));
        assertEquals(0, PayloadJson.envelope(none).getAsJsonObject("data").getAsJsonObject("extras").getAsJsonObject("route").getAsJsonObject("graph").get("rows").getAsInt());
    }

    @Test
    @DisplayName("a feature menu keeps its entries, their lines and the actions on both levels")
    void featureMenu() throws IOException {
        var buy = new com.cobbletowers.network.TowerFeatureState.Action("title-wear", "Wear this title", "champion", "Wear Champion?", "");
        var sample = new com.cobbletowers.network.TowerFeatureState(7, "cosmetics", "Cosmetics & titles", List.of(
                new com.cobbletowers.network.TowerFeatureState.Entry("title:champion", "Champion", List.of("[+] Unlocked at Gold.", "Shows next to your name."), List.of(buy))),
                List.of(new com.cobbletowers.network.TowerFeatureState.Action("page", "This run", "run", "", "")), "Saved.");
        fixture("TowerFeatureState", sample);
        JsonObject data = PayloadJson.envelope(sample).getAsJsonObject("data");
        assertEquals(7, data.get("requestId").getAsInt());
        JsonObject entry = data.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(2, entry.getAsJsonArray("lines").size());
        assertEquals("title-wear", entry.getAsJsonArray("actions").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("run", data.getAsJsonArray("actions").get(0).getAsJsonObject().get("argument").getAsString());
    }

    @Test
    @DisplayName("the Ranks duel reaches the page with a lead per stat and a champions list per side")
    void ranksState() throws IOException {
        var payload = new com.cobbletowers.network.RanksStatePayload("Season 3", "tideforge", "tideforge", "rootvale",
                List.of(new com.cobbletowers.network.RanksStatePayload.Region("tideforge", "Tideforge", 41),
                        new com.cobbletowers.network.RanksStatePayload.Region("rootvale", "Rootvale", 38),
                        new com.cobbletowers.network.RanksStatePayload.Region("duskvale", "Duskvale", 29)),
                List.of(new com.cobbletowers.network.RanksStatePayload.Row("clears", "Total clears", "1311", "1204", 100, 92, -1),
                        new com.cobbletowers.network.RanksStatePayload.Row("away", "Away conquests", "167", "192", 87, 100, 1),
                        new com.cobbletowers.network.RanksStatePayload.Row("fastest", "Fastest cycle", "10:58", "11:42", 100, 94, -1)),
                List.of(new com.cobbletowers.network.RanksStatePayload.Champion("BrineKnight", 3120)),
                List.of(new com.cobbletowers.network.RanksStatePayload.Champion("Mosswalker", 3340)), "");
        fixture("RanksState", payload);
        JsonObject env = PayloadJson.envelope(payload);
        assertEquals("RanksState", env.get("kind").getAsString());
        JsonObject data = env.getAsJsonObject("data");
        assertEquals("rootvale", data.get("right").getAsString());
        assertEquals(1, data.getAsJsonArray("rows").get(1).getAsJsonObject().get("lead").getAsInt());
        assertEquals("BrineKnight", data.getAsJsonArray("leftChampions").get(0).getAsJsonObject().get("name").getAsString());
    }

    private static List<RentalSetDefinition> pool() throws IOException {
        List<RentalSetDefinition> all = new ArrayList<>();
        Path dir = Paths.get("src/main/resources/data/cobbletowers/cobbletowers/rental_sets");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", file.getFileName().toString().replace(".json", ""));
                try (Reader reader = Files.newBufferedReader(file)) {
                    all.add(RentalSetDefinition.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        }
        return all;
    }
}
