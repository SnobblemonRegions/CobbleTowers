package com.cobbletowers.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrophiesTest {

    private static final Path ACHIEVEMENTS = Path.of("src/main/resources/data/cobbletowers/cobbletowers/achievements");

    @Test
    @DisplayName("Trophy case (23 Nov 2026): the six ladders and six medals cover every achievement exactly once")
    void coversEveryAchievement() throws Exception {
        Set<String> files;
        try (Stream<Path> s = Files.list(ACHIEVEMENTS)) {
            files = s.map(p -> p.getFileName().toString().replace(".json", "")).collect(Collectors.toSet());
        }
        List<String> shown = new java.util.ArrayList<>(Trophies.MEDALS);
        Trophies.LADDERS.values().forEach(shown::addAll);
        assertEquals(shown.size(), new HashSet<>(shown).size(), "an achievement shown twice");
        assertEquals(files, new HashSet<>(shown));
        assertEquals(Set.copyOf(Trophies.FAMILIES), Trophies.LADDERS.keySet());
    }

    @Test
    @DisplayName("Trophy case: stone with no rung, ascended with all, bronze / silver / gold spread over the rungs between")
    void tiers() {
        List<String> depth = Trophies.LADDERS.get("depth");
        String[] six = {"stone", "bronze", "bronze", "silver", "silver", "gold", "ascended"};
        for (int n = 0; n <= 6; n++) assertEquals(six[n], Trophies.tier("depth", Set.copyOf(depth.subList(0, n))), "depth with " + n);
        List<String> speed = Trophies.LADDERS.get("speed");
        String[] four = {"stone", "bronze", "silver", "gold", "ascended"};
        for (int n = 0; n <= 4; n++) assertEquals(four[n], Trophies.tier("speed", Set.copyOf(speed.subList(0, n))), "speed with " + n);
        assertEquals("bronze", Trophies.tier("score", Set.of("score_100")));        // a rung counts in any order
        assertEquals("ascended", Trophies.tier("score", Set.of("score_40", "score_100")));
        assertTrue(Trophies.tier("depth", Set.of("flawless_0", "eternal")).equals("stone"));
    }
}
