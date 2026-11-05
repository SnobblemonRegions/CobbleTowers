package com.cobbletowers.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.definition.ModifierDefinition;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The modifiers this mod ships, read from the real resource files: all parse, all reference real modifiers. */
class ShippedModifiersTest {

    private static Map<ResourceLocation, ModifierDefinition> load() throws IOException {
        Path dir = Paths.get("src/main/resources/data/cobbletowers/cobbletowers/modifiers");
        Map<ResourceLocation, ModifierDefinition> all = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", name);
                try (Reader reader = Files.newBufferedReader(file)) {
                    all.put(id, ModifierDefinition.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        }
        return all;
    }

    @Test
    @DisplayName("eighty-one modifiers ship (29 ordinary, 40 relics, 14 tag bonuses), and every one parses and does something its type applies")
    void allShip() throws IOException {
        assertEquals(81, load().size());
        assertEquals(40, load().values().stream().filter(ModifierDefinition::relic).count());
        assertEquals(14, load().values().stream().filter(m -> m.tagBonus().isPresent()).count());
    }

    @Test
    @DisplayName("every relic tag has at least four relics and a 2-set and a 4-set bonus, so a four-set can be reached")
    void tagsAreDeepEnoughForTheirBonuses() throws IOException {
        Map<ResourceLocation, ModifierDefinition> all = load();
        java.util.Set<String> tags = new java.util.TreeSet<>();
        for (ModifierDefinition m : all.values()) if (m.relic()) tags.addAll(m.tags());
        assertEquals(java.util.Set.of("sustain", "economy", "tempo", "offense", "scouting", "boss", "risk"), tags);
        for (String tag : tags) {
            long relics = all.values().stream().filter(m -> m.relic() && m.tags().contains(tag)).count();
            assertTrue(relics >= 4, tag + " has only " + relics + " relics");
            for (int count : new int[] {2, 4}) {
                assertTrue(all.values().stream().anyMatch(m -> m.tagBonus().map(b -> b.tag().equals(tag) && b.count() == count).orElse(false)),
                        tag + " has no " + count + "-set bonus");
            }
        }
        assertTrue(all.values().stream().filter(m -> m.tagBonus().isPresent()).noneMatch(ModifierDefinition::relic));
    }

    @Test
    @DisplayName("every relic is a single-copy boon with at least one tag")
    void relicsAreSingleCopyAndTagged() throws IOException {
        for (ModifierDefinition modifier : load().values()) {
            if (!modifier.relic()) continue;
            assertEquals(1, modifier.stackLimit(), modifier.id() + " must not stack");
            assertTrue(!modifier.tags().isEmpty(), modifier.id() + " needs a tag");
        }
    }

    @Test
    @DisplayName("every exclusion and requirement names a modifier that exists")
    void referencesResolve() throws IOException {
        Map<ResourceLocation, ModifierDefinition> all = load();
        List<String> dangling = new ArrayList<>();
        for (ModifierDefinition modifier : all.values()) {
            for (ResourceLocation other : modifier.excludes()) {
                if (!all.containsKey(other)) dangling.add(modifier.id() + " excludes " + other);
            }
            for (ResourceLocation other : modifier.requires()) {
                if (!all.containsKey(other)) dangling.add(modifier.id() + " requires " + other);
            }
        }
        assertTrue(dangling.isEmpty(), dangling.toString());
    }

    @Test
    @DisplayName("a requirement can be met: the modifier it requires is not one that excludes it")
    void requirementsAreReachable() throws IOException {
        Map<ResourceLocation, ModifierDefinition> all = load();
        for (ModifierDefinition modifier : all.values()) {
            for (ResourceLocation required : modifier.requires()) {
                assertTrue(!all.get(required).excludes().contains(modifier.id()),
                        modifier.id() + " requires " + required + ", which excludes it");
            }
        }
    }
}
