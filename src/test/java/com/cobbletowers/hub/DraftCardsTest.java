package com.cobbletowers.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DraftCardsTest {

    private static final Path SETS = Path.of("src/main/resources/data/cobbletowers/cobbletowers/rental_sets");
    private static final Path DRAFT = Path.of("src/main/resources/data/cobbletowers/cobbletowers/hub/draft.json");
    private static final Path FONT = Path.of("src/main/resources/assets/cobbletowers/font/draft_cards.json");

    @Test
    @DisplayName("Rental Draft at the gate (23 Nov 2026): every rental set's card can be drawn: its sprite is a glyph of the card font, taken from "
            + "Cobblemon Cards, and its types have faces and gems (a new set needs tools/tower_gen/draft_cards_export.py again)")
    void everySetHasItsCard() throws Exception {
        JsonObject glyphs = JsonParser.parseString(Files.readString(DRAFT)).getAsJsonObject().getAsJsonObject("glyphs");
        Map<String, String> fileOf = new HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(FONT)).getAsJsonObject().getAsJsonArray("providers")) {
            JsonObject p = e.getAsJsonObject();
            if (!p.has("file")) continue;
            for (JsonElement row : p.getAsJsonArray("chars")) {                        // every glyph, one per character (a card is four tiles)
                row.getAsString().codePoints().filter(cp -> cp != 0)
                        .forEach(cp -> fileOf.put(new String(Character.toChars(cp)), p.get("file").getAsString()));
            }
        }
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(SETS)) {
            for (Path f : files.toList()) {
                JsonObject set = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                String species = set.get("species").getAsString();
                JsonElement sprite = glyphs.get("sprite_" + species);
                if (sprite == null || !fileOf.getOrDefault(sprite.getAsString(), "").startsWith("cobblemon-cards:item/cards/pokemon/entity_icon/")) {
                    missing.add(species + " sprite");
                }
                for (JsonElement t : set.getAsJsonArray("types")) {
                    for (String part : new String[] {"gem3_", "gem4_"}) {
                        if (!glyphs.has(part + t.getAsString())) missing.add(species + " " + part + t.getAsString());
                    }
                    for (String tier : new String[] {"common", "uncommon", "rare", "epic", "legendary", "mythic"}) {
                        if (!glyphs.has("card_" + t.getAsString() + "_" + tier)) missing.add(species + " card " + t.getAsString() + " " + tier);
                    }
                }
            }
        }
        for (String tier : new String[] {"common", "uncommon", "rare", "epic", "legendary", "mythic"}) {   // Inspect's stats back
            String first = glyphs.has("stats_" + tier) ? glyphs.get("stats_" + tier).getAsString().substring(0, 1) : "";
            if (!fileOf.getOrDefault(first, "").endsWith("draft/stats/" + tier + ".png")) {
                missing.add("stats back " + tier);
            }
        }
        assertTrue(missing.isEmpty(), "missing: " + missing);
    }
}
