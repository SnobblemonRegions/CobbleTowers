package com.cobbletowers.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.TestRuns;
import com.cobbletowers.definition.PlaylistDefinition;
import com.cobbletowers.definition.PlaylistDefinition.Clauses;
import com.cobbletowers.definition.RulesetDefinition;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunOptions;
import com.cobbletowers.migration.RunMigrations;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Playlists (P32): the party clauses, the narrowed ruleset, the shipped files, and the run options that carry the
 * choice.
 */
class PlaylistRulesTest {

    private static PartyMember mon(String species, int level, boolean fullyEvolved, String... types) {
        return mon(species, level, fullyEvolved, Set.of(), types);
    }

    private static PartyMember mon(String species, int level, boolean fullyEvolved, Set<String> labels, String... types) {
        return new PartyMember(UUID.randomUUID(), level, false, species, List.of(types), fullyEvolved, labels);
    }

    private static List<PlaylistDefinition> shipped() throws IOException {
        Path dir = Paths.get("src/main/resources/data/cobbletowers/cobbletowers/playlists");
        List<PlaylistDefinition> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers",
                        file.getFileName().toString().replace(".json", ""));
                try (Reader reader = Files.newBufferedReader(file)) {
                    all.add(PlaylistDefinition.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        }
        return all;
    }

    private static PlaylistDefinition shipped(String name) throws IOException {
        return shipped().stream().filter(p -> p.id().getPath().equals(name)).findFirst().orElseThrow();
    }

    // ---- the clauses ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Monotype: a party that shares a type passes, and the one that does not names who is out")
    void monotype() throws IOException {
        PlaylistDefinition monotype = shipped("monotype");
        assertTrue(PlaylistRules.problems(List.of(mon("Vaporeon", 50, true, "water"), mon("Kyogre", 70, true, "water"),
                mon("Starmie", 50, true, "water", "psychic")), monotype).isEmpty());
        List<String> problems = PlaylistRules.problems(List.of(mon("Vaporeon", 50, true, "water"),
                mon("Charizard", 50, true, "fire", "flying"), mon("Blastoise", 50, true, "water")), monotype);
        assertEquals(List.of("Charizard is not a Water type"), problems);
    }

    @Test
    @DisplayName("Monotype judges a mixed bag by the most common type, and ignores a party it knows nothing about")
    void monotypeEdges() throws IOException {
        PlaylistDefinition monotype = shipped("monotype");
        List<String> mixed = PlaylistRules.problems(List.of(mon("A", 5, false, "fire"), mon("B", 5, false, "water"),
                mon("C", 5, false, "grass")), monotype);
        assertEquals(2, mixed.size(), mixed.toString());
        assertTrue(PlaylistRules.problems(List.of(new PartyMember(UUID.randomUUID(), 50, false)), monotype).isEmpty(),
                "a member with no species data is not judged");
        assertTrue(PlaylistRules.problems(List.of(), monotype).isEmpty());
    }

    @Test
    @DisplayName("Level Cap 50 names every Pokemon over the cap")
    void levelCap() throws IOException {
        List<String> problems = PlaylistRules.problems(List.of(mon("Garchomp", 80, true, "dragon"),
                mon("Pikachu", 50, false, "electric"), mon("Machamp", 51, true, "fighting")), shipped("level_cap_50"));
        assertEquals(List.of("Garchomp is level 80 (the limit is 50)", "Machamp is level 51 (the limit is 50)"), problems);
    }

    @Test
    @DisplayName("Underdog refuses fully evolved Pokemon and legendary, mythical, ultra beast and paradox labels")
    void underdog() throws IOException {
        PlaylistDefinition underdog = shipped("underdog");
        List<String> problems = PlaylistRules.problems(List.of(mon("Pikachu", 30, false, "electric"),
                mon("Raichu", 30, true, "electric"),
                mon("Mewtwo", 70, true, Set.of("legendary"), "psychic"),
                mon("Nihilego", 70, true, Set.of("ultra_beast"), "rock", "poison")), underdog);
        assertTrue(problems.contains("Raichu is fully evolved"), problems.toString());
        assertTrue(problems.contains("Mewtwo is a legendary Pokemon"), problems.toString());
        assertTrue(problems.contains("Nihilego is a ultra beast Pokemon"), problems.toString());
        assertFalse(problems.stream().anyMatch(p -> p.startsWith("Pikachu")));
    }

    @Test
    @DisplayName("a playlist with no party clauses never complains, and Hardcore and Solo Gauntlet have none to break")
    void noClauses() throws IOException {
        assertTrue(PlaylistRules.problems(List.of(mon("Mewtwo", 100, true, Set.of("legendary"), "psychic")), Clauses.NONE).isEmpty());
        assertFalse(shipped("hardcore").party().any());
        assertFalse(shipped("solo_gauntlet").party().any(), "its rule is a party size and a player count, not a clause");
    }

    // ---- the narrowed ruleset
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Level Cap 50 narrows the ruleset's enemy ceiling, Solo Gauntlet its party size, and neither raises anything")
    void narrowing() throws IOException {
        RulesetDefinition base = TestRuns.content().rulesets().values().iterator().next();
        RulesetDefinition capped = shipped("level_cap_50").narrow(base);
        assertEquals(50, capped.maxEnemyLevel());
        assertEquals(base.registeredPartySize(), capped.registeredPartySize());
        RulesetDefinition solo = shipped("solo_gauntlet").narrow(base);
        assertEquals(3, solo.registeredPartySize());
        assertEquals(base.maxEnemyLevel(), solo.maxEnemyLevel());
        assertSame(base, shipped("hardcore").narrow(base), "nothing to narrow returns the same ruleset");
        RulesetDefinition lowCeiling = new RulesetDefinition(base.id(), 1, 1, 5, 30, 2, true, true, true);
        assertEquals(30, shipped("level_cap_50").narrow(lowCeiling).maxEnemyLevel(), "a cap above the ruleset's own changes nothing");
        assertEquals(2, shipped("solo_gauntlet").narrow(lowCeiling).registeredPartySize());
    }

    @Test
    @DisplayName("the seven shipped playlists (the six modes and the Tutorial Tower's own) parse, carry a name and description, and Hardcore closes the vendor and forces a modifier")
    void shippedFiles() throws IOException {
        List<PlaylistDefinition> all = shipped();
        assertEquals(7, all.size());
        assertTrue(all.stream().allMatch(p -> !p.displayName().isBlank() && !p.description().isBlank()));
        // every mode is a little harder for its bonus; the Tutorial Tower's own playlist is no mode and has none
        assertTrue(all.stream().filter(p -> !p.id().getPath().equals("tutorial")).allMatch(p -> p.difficultyBonus() > 0));
        assertEquals(0, shipped("tutorial").difficultyBonus());
        PlaylistDefinition hardcore = shipped("hardcore");
        assertTrue(hardcore.vendorClosed());
        assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("cobbletowers", "empty_pockets")), hardcore.forcedModifiers());
        assertEquals(1, shipped("solo_gauntlet").maxPlayers());
        assertTrue(shipped("rental").rental(), "the Rental Draft playlist lends a drafted team (P33)");
        assertTrue(shipped("tutorial").rental(), "the Tutorial Tower lends its fixed team the same way (25 Nov 2026)");
        assertTrue(all.stream().filter(PlaylistDefinition::rental).count() == 2, "and only those two do");
    }

    @Test
    @DisplayName("a bad playlist file is refused")
    void refusesBad() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbletowers", "x");
        assertThrows(IllegalArgumentException.class, () -> PlaylistDefinition.fromJson(id,
                JsonParser.parseString("{\"schema_version\":2,\"display_name\":\"X\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> PlaylistDefinition.fromJson(id,
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\"X\",\"max_players\":9}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> PlaylistDefinition.fromJson(id,
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\"X\",\"enemy_level_max\":500}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> PlaylistDefinition.fromJson(id,
                JsonParser.parseString("{\"schema_version\":1,\"display_name\":\" \"}").getAsJsonObject()));
    }

    // ---- the run options
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("run options survive the tag, and an absent block reads as an ordinary run")
    void optionsRoundTrip() {
        RunOptions options = new RunOptions(Optional.of(ResourceLocation.fromNamespaceAndPath("cobbletowers", "monotype")),
                Optional.of("daily:2026-10-05"), 5, true);
        assertEquals(options, RunOptions.fromTag(options.toTag()));
        assertEquals(RunOptions.NONE, RunOptions.fromTag(new CompoundTag()));
        assertEquals(RunOptions.NONE, RunOptions.fromTag(RunOptions.NONE.toTag()));
    }

    @Test
    @DisplayName("a run carries its options through the tag and through every with-method")
    void runCarriesOptions() {
        RunOptions options = RunOptions.of(Optional.of(ResourceLocation.fromNamespaceAndPath("cobbletowers", "hardcore")));
        PersistedRun run = TestRuns.fresh(UUID.randomUUID()).withOptions(options, 5L);
        assertEquals(options, PersistedRun.fromTag(RunMigrations.toCurrent(run.toTag())).options());
        assertEquals(options, run.touched(9L).options());
        assertEquals(options, run.withCell(java.util.OptionalInt.of(3), 9L).options());
        assertEquals(options, run.withVendorPurchase(ResourceLocation.fromNamespaceAndPath("cobbletowers", "full_heal"), 9L).options());
        assertEquals(options, run.withModifiers(run.modifiers(), 9L).options());
        assertEquals(RunOptions.NONE, TestRuns.fresh(UUID.randomUUID()).options());
    }

    @Test
    @DisplayName("a version 6 run migrates to 7 as an ordinary run")
    void migrates() {
        PersistedRun run = TestRuns.fresh(UUID.randomUUID());
        CompoundTag v6 = run.toTag();
        v6.remove("options");
        v6.putInt("schema_version", 6);
        CompoundTag migrated = RunMigrations.toCurrent(v6);
        assertEquals(7, migrated.getInt("schema_version"));
        assertEquals(RunOptions.NONE, PersistedRun.fromTag(migrated).options());
        assertTrue(migrated.contains("options"));
    }
}
