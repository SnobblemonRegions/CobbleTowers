package com.cobbletowers.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.persistence.LedgerEntry;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What clearing a milestone floor pays (P21): guaranteed items for everybody, and a bigger roll. */
class RewardMilestoneTest {

    private static final long SEED = 4242L;
    private static final ResourceLocation BOSS_05 = ResourceLocation.fromNamespaceAndPath("cobbletowers", "boss_05");
    private static final ResourceLocation CHAMPION_10 = ResourceLocation.fromNamespaceAndPath("cobbletowers", "champion_10");
    private static final Map<ResourceLocation, MilestoneKind> KINDS = Map.of(BOSS_05, MilestoneKind.BOSS, CHAMPION_10, MilestoneKind.CHAMPION);

    private static RewardTableDefinition table(String json) {
        return RewardTableDefinition.fromJson(ResourceLocation.fromNamespaceAndPath("cobbletowers", "rewards"),
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String TABLE = """
            {"schema_version": 1, "display_name": "x", "growth_percent_per_floor": 10,
             "tiers": {"floor_cleared": [{"item": "minecraft:stone"}]},
             "milestones": {
               "boss": {
                 "guaranteed": [{"item": "cobblemon:exp_candy_xl", "amount": 1}, {"item": "cobbleraids:raid_points", "amount": 25}],
                 "bonus_rolls": 2,
                 "bonus_pool": [{"item": "minecraft:diamond", "min_amount": 2, "max_amount": 4, "weight": 100}]
               },
               "champion": {"guaranteed": [{"item": "cobblemon-cards:god_pack_ticket"}]}
             }}""";

    private static List<RewardValuation.Grant> value(RewardTableDefinition table, LedgerEntry... entries) {
        return RewardValuation.value(SEED, List.of(entries), table, ModifierEffects.NONE, id -> Optional.ofNullable(KINDS.get(id)));
    }

    @Test
    @DisplayName("a boss milestone pays each guaranteed item, flagged to go to every participant in full")
    void guaranteedIsPerPlayer() {
        List<RewardValuation.Grant> grants = value(table(TABLE), LedgerEntry.milestoneCleared(5, BOSS_05, 1L));

        List<RewardValuation.Grant> fixed = grants.stream().filter(RewardValuation.Grant::perPlayer).toList();
        assertEquals(2, fixed.size());
        assertEquals("cobblemon:exp_candy_xl", fixed.get(0).item().toString());
        assertEquals(1, fixed.get(0).amount());
        assertEquals(25, fixed.get(1).amount());
    }

    @Test
    @DisplayName("a guaranteed amount is exact: not grown by depth and not scaled by reward modifiers")
    void guaranteedIsNotScaled() {
        RewardTableDefinition table = table(TABLE);
        LedgerEntry floorTen = LedgerEntry.milestoneCleared(10, BOSS_05, 1L);

        List<RewardValuation.Grant> grants = RewardValuation.value(SEED, List.of(floorTen), table,
                new ModifierEffects(0, 100, 300, List.of(), true, true, Optional.empty(), Optional.empty(), 0),
                id -> Optional.of(MilestoneKind.BOSS));

        RewardValuation.Grant candy = grants.stream().filter(g -> g.perPlayer() && g.item().getPath().equals("exp_candy_xl")).findFirst().orElseThrow();
        assertEquals(1, candy.amount(), "a promise of one XL candy is one XL candy, at any depth, under any modifier");
    }

    @Test
    @DisplayName("bonus rolls add that many ordinary grants from the bonus pool, split rather than per player")
    void bonusRolls() {
        List<RewardValuation.Grant> grants = value(table(TABLE), LedgerEntry.milestoneCleared(5, BOSS_05, 1L));

        List<RewardValuation.Grant> bonus = grants.stream().filter(g -> !g.perPlayer()).toList();
        assertEquals(2, bonus.size());
        for (RewardValuation.Grant grant : bonus) {
            assertEquals("minecraft:diamond", grant.item().toString());
            assertTrue(grant.amount() >= 2, "rolled within 2..4 before growth: " + grant.amount());
        }
    }

    @Test
    @DisplayName("a milestone of a kind the table has no section for pays nothing extra")
    void unlistedKindPaysNothing() {
        RewardTableDefinition bossOnly = table("""
                {"schema_version": 1, "display_name": "x", "tiers": {},
                 "milestones": {"boss": {"guaranteed": [{"item": "minecraft:diamond"}]}}}""");

        assertEquals(List.of(), value(bossOnly, LedgerEntry.milestoneCleared(10, CHAMPION_10, 1L)));
    }

    @Test
    @DisplayName("a table with no milestone section values a milestone entry as nothing, as every old table does")
    void noSectionNoChange() {
        RewardTableDefinition old = table("""
                {"schema_version": 1, "display_name": "x", "tiers": {"floor_cleared": [{"item": "minecraft:stone"}]}}""");

        assertEquals(List.of(), value(old, LedgerEntry.milestoneCleared(5, BOSS_05, 1L)));
    }

    @Test
    @DisplayName("a milestone entry whose id no tower names is skipped, never guessed at")
    void unknownMilestoneId() {
        RewardTableDefinition table = table(TABLE);
        LedgerEntry stray = LedgerEntry.milestoneCleared(5, ResourceLocation.fromNamespaceAndPath("x", "gone"), 1L);

        assertEquals(List.of(), value(table, stray));
    }

    @Test
    @DisplayName("pricing is deterministic: the same ledger and seed always give the same grants")
    void deterministic() {
        RewardTableDefinition table = table(TABLE);
        LedgerEntry entry = LedgerEntry.milestoneCleared(5, BOSS_05, 1L);

        assertEquals(value(table, entry), value(table, entry));
    }

    @Test
    @DisplayName("a milestone entry survives a save and load like every other ledger entry")
    void ledgerRoundTrip() {
        LedgerEntry entry = LedgerEntry.milestoneCleared(5, BOSS_05, 99L);

        assertEquals(entry, LedgerEntry.fromTag(entry.toTag()));
    }

    // ---- parsing -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the milestone section parses into guaranteed items and a bonus pool")
    void parses() {
        RewardTableDefinition table = table(TABLE);

        RewardTableDefinition.MilestoneReward boss = table.milestoneReward(MilestoneKind.BOSS).orElseThrow();
        assertEquals(2, boss.guaranteed().size());
        assertEquals(2, boss.bonusRolls());
        assertEquals(1, boss.bonusPool().size());
        assertEquals(1, table.milestoneReward(MilestoneKind.CHAMPION).orElseThrow().guaranteed().get(0).amount(),
                "an amount left out defaults to one");
    }

    @Test
    @DisplayName("bonus rolls with nothing to roll from are refused")
    void bonusRollsNeedAPool() {
        assertThrows(IllegalArgumentException.class, () -> table("""
                {"schema_version": 1, "display_name": "x", "tiers": {}, "milestones": {"boss": {"bonus_rolls": 2}}}"""));
    }

    @Test
    @DisplayName("a milestone that pays nothing at all is refused")
    void emptyMilestoneRefused() {
        assertThrows(IllegalArgumentException.class, () -> table("""
                {"schema_version": 1, "display_name": "x", "tiers": {}, "milestones": {"boss": {}}}"""));
    }

    @Test
    @DisplayName("a guaranteed amount below one, and an unknown milestone kind, are refused")
    void badValuesRefused() {
        assertThrows(IllegalArgumentException.class, () -> table("""
                {"schema_version": 1, "display_name": "x", "tiers": {},
                 "milestones": {"boss": {"guaranteed": [{"item": "minecraft:stone", "amount": 0}]}}}"""));
        assertThrows(IllegalArgumentException.class, () -> table("""
                {"schema_version": 1, "display_name": "x", "tiers": {},
                 "milestones": {"final": {"guaranteed": [{"item": "minecraft:stone"}]}}}"""));
    }

    // ---- who gets the remainder --------------------------------------------------------------------------------

    private static final List<UUID> TEAM = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4));

    @Test
    @DisplayName("a one-item grant goes to a different teammate as the rotation advances")
    void remainderRotates() {
        UUID first = RewardBankService.evenSplit(1, TEAM, 0).keySet().iterator().next();
        UUID second = RewardBankService.evenSplit(1, TEAM, 1).keySet().iterator().next();
        UUID fifth = RewardBankService.evenSplit(1, TEAM, 4).keySet().iterator().next();

        assertEquals(TEAM.get(0), first);
        assertEquals(TEAM.get(1), second);
        assertEquals(first, fifth, "it wraps around the team");
    }

    @Test
    @DisplayName("rotation never changes how much is handed out, only who gets the odd one")
    void rotationConservesTheTotal() {
        for (int rotation = 0; rotation < 9; rotation++) {
            for (int amount = 0; amount <= 11; amount++) {
                int total = RewardBankService.evenSplit(amount, TEAM, rotation).values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(amount, total, "amount " + amount + " rotation " + rotation);
            }
        }
    }

    @Test
    @DisplayName("an even split is unaffected by rotation, and the old two-argument form still starts at the first player")
    void evenSplitUnaffected() {
        assertEquals(RewardBankService.evenSplit(8, TEAM, 0), RewardBankService.evenSplit(8, TEAM, 3));
        assertEquals(RewardBankService.evenSplit(5, TEAM), RewardBankService.evenSplit(5, TEAM, 0));
        assertFalse(RewardBankService.evenSplit(1, TEAM).isEmpty());
    }
}
