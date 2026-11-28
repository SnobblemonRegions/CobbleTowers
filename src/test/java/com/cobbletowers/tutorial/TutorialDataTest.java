package com.cobbletowers.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TutorialDataTest {

    private static final Path DATA = Path.of("src/main/resources/data/cobbletowers/cobbletowers");

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static Path file(String kind, String id) {
        return DATA.resolve(kind).resolve(id.substring(id.indexOf(':') + 1) + ".json");
    }

    @Test
    @DisplayName("Tutorial Tower (25 Nov 2026): its config names a tower, playlist and lent team that all exist, and a starter kit")
    void configHolds() throws Exception {
        JsonObject config = read(DATA.resolve("hub/tutorial.json"));
        assertTrue(Files.exists(file("towers", config.get("tower").getAsString())), "the tower");
        JsonObject playlist = read(file("playlists", config.get("playlist").getAsString()));
        assertTrue(playlist.get("rental").getAsBoolean(), "the playlist lends its team");
        assertEquals(1, playlist.get("max_players").getAsInt(), "one player at a time");
        assertFalse(playlist.has("card_rewards"), "and pays no cards");
        assertFalse(config.getAsJsonArray("team").isEmpty());
        for (JsonElement set : config.getAsJsonArray("team")) assertTrue(Files.exists(file("rental_sets", set.getAsString())), set.getAsString());
        assertFalse(config.getAsJsonArray("kit").isEmpty(), "a first clear pays a starter kit");
    }

    @Test
    @DisplayName("the tutorial tower does not ascend, and every floor it names exists with a boss pool that exists")
    void floorsHold() throws Exception {
        JsonObject tower = read(DATA.resolve("towers/tutorial.json"));
        assertFalse(tower.get("ascension").getAsBoolean());
        assertTrue(tower.getAsJsonArray("milestones").isEmpty(), "no milestone boss: those pay from AscensionLib's bands");
        for (JsonElement id : tower.getAsJsonArray("floors")) {
            JsonObject floor = read(file("floors", id.getAsString()));
            assertTrue(Files.exists(file("boss_pools", floor.get("boss_pool").getAsString())), id.getAsString());
        }
    }
}
