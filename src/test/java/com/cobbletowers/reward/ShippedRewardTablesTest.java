package com.cobbletowers.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.api.reward.RewardKind;
import com.cobbletowers.api.tower.MilestoneKind;
import com.cobbletowers.definition.RewardTableDefinition;
import com.cobbletowers.modifier.ModifierEffects;
import com.cobbletowers.persistence.LedgerEntry;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped reward tables (P21), read from the real resources: each parses, has its regional identity and a clean
 * run pays about what the design said ("modest").
 */
class ShippedRewardTablesTest {

    private static final List<String> TOWERS = List.of("neutral", "tideforge", "rootvale", "duskvale");
    private static final ResourceLocation BOSS_05 = ResourceLocation.fromNamespaceAndPath("cobbletowers", "milestone_boss");
    private static final ResourceLocation CHAMPION_10 = ResourceLocation.fromNamespaceAndPath("cobbletowers", "milestone_champion");

    private static RewardTableDefinition load(String name) {
        String path = "/data/cobbletowers/cobbletowers/reward_tables/" + name + ".json";
        try (Reader reader = new InputStreamReader(ShippedRewardTablesTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            return RewardTableDefinition.fromJson(ResourceLocation.fromNamespaceAndPath("cobbletowers", name),
                    JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception ex) {
            throw new AssertionError("could not load " + path + ": " + ex, ex);
        }
    }

    @Test
    @DisplayName("every shipped table parses and rolls something for all three ordinary tiers")
    void everyTableIsComplete() {
        for (String name : TOWERS) {
            RewardTableDefinition table = load(name);
            for (RewardKind kind : RewardKind.values()) {
                assertFalse(table.entriesFor(kind).isEmpty(), name + " pays nothing for " + kind);
                assertTrue(table.totalWeight(kind) > 0, name + " has no weight in " + kind);
            }
        }
    }

    @Test
    @DisplayName("every table promises both milestones an XL candy, and the chase items are the agreed placeholders")
    void milestonesPromiseWhatWasAgreed() {
        for (String name : TOWERS) {
            RewardTableDefinition table = load(name);
            for (MilestoneKind kind : MilestoneKind.values()) {
                RewardTableDefinition.MilestoneReward reward = table.milestoneReward(kind).orElseThrow(
                        () -> new AssertionError(name + " has no " + kind + " milestone reward"));
                assertTrue(reward.guaranteed().stream().anyMatch(g -> g.item().toString().equals("cobblemon:exp_candy_xl")),
                        name + " " + kind + " does not guarantee an XL candy");
                assertTrue(reward.bonusRolls() >= 1, name + " " + kind + " has no bigger roll");
            }
            assertTrue(table.milestoneReward(MilestoneKind.CHAMPION).orElseThrow().guaranteed().stream()
                            .anyMatch(g -> g.item().toString().equals("cobblemon-cards:god_pack_ticket")),
                    name + " champion does not guarantee the God Pack Ticket");
            assertTrue(table.milestoneReward(MilestoneKind.BOSS).orElseThrow().guaranteed().stream()
                            .anyMatch(g -> g.item().getNamespace().equals("cobblemon-cards")),
                    name + " boss does not guarantee a booster pack");
        }
    }

    @Test
    @DisplayName("each region pays its own signature items, and they are not the neutral table's")
    void regionsHaveIdentity() {
        Map<String, String> signature = Map.of("tideforge", "cobblemon:water_stone", "rootvale", "cobblemon:leaf_stone",
                "duskvale", "cobblemon:dusk_stone");
        RewardTableDefinition neutral = load("neutral");
        for (Map.Entry<String, String> region : signature.entrySet()) {
            RewardTableDefinition table = load(region.getKey());
            assertTrue(mentions(table, region.getValue()), region.getKey() + " does not pay " + region.getValue());
            assertFalse(mentions(neutral, region.getValue()), "neutral should not pay " + region.getValue());
        }
    }

    private static boolean mentions(RewardTableDefinition table, String item) {
        for (RewardKind kind : RewardKind.values()) {
            if (table.entriesFor(kind).stream().anyMatch(e -> e.item().toString().equals(item))) return true;
        }
        for (MilestoneKind kind : MilestoneKind.values()) {
            RewardTableDefinition.MilestoneReward reward = table.milestoneReward(kind).orElse(null);
            if (reward != null && reward.bonusPool().stream().anyMatch(e -> e.item().toString().equals(item))) return true;
        }
        return false;
    }

    @Test
    @DisplayName("every tower pays CobbleDollars, and every table that can pay Raid Points names the reserved id")
    void currenciesArePresent() {
        for (String name : TOWERS) {
            RewardTableDefinition table = load(name);
            assertTrue(mentions(table, "cobbletowers:cobble_dollar"), name + " pays no CobbleDollars");
            assertTrue(mentions(table, "cobbleraids:raid_points"), name + " pays no Raid Points");
        }
    }

    /** A clean solo ten-floor run: one opponent, a boss and a cleared floor each time, and both milestones. */
    private static List<RewardValuation.Grant> cleanRun(RewardTableDefinition table, long seed) {
        List<LedgerEntry> ledger = new ArrayList<>();
        ResourceLocation species = ResourceLocation.fromNamespaceAndPath("cobblemon", "machoke");
        ResourceLocation boss = ResourceLocation.fromNamespaceAndPath("cobbleraids", "lucario");
        ResourceLocation floor = ResourceLocation.fromNamespaceAndPath("cobbletowers", "floor");
        for (int floorIndex = 1; floorIndex <= 10; floorIndex++) {
            ledger.add(LedgerEntry.opponentDefeated(floorIndex, species, UUID.randomUUID(), 1L));
            ledger.add(LedgerEntry.bossDefeated(floorIndex, boss, 1L));
            ledger.add(LedgerEntry.floorCleared(floorIndex, floor, 1L));
            if (floorIndex == 5) ledger.add(LedgerEntry.milestoneCleared(5, BOSS_05, 1L));
            if (floorIndex == 10) ledger.add(LedgerEntry.milestoneCleared(10, CHAMPION_10, 1L));
        }
        return RewardValuation.value(seed, ledger, table, ModifierEffects.NONE,
                id -> id.equals(BOSS_05) ? Optional.of(MilestoneKind.BOSS) : id.equals(CHAMPION_10)
                        ? Optional.of(MilestoneKind.CHAMPION) : Optional.empty());
    }

    @Test
    @DisplayName("a clean ten-floor solo run pays 'modest': a few dozen small items, a handful of growth items, "
            + "a few packs, and a few hundred Raid Points at most")
    void cleanRunIsModest() {
        for (String name : TOWERS) {
            RewardTableDefinition table = load(name);
            for (long seed : new long[] {1L, 77L, 4242L, 99_999L}) {
                List<RewardValuation.Grant> grants = cleanRun(table, seed);

                int items = grants.stream().filter(g -> !g.item().getPath().equals("raid_points")
                        && !g.item().getPath().equals("cobble_dollar")).mapToInt(RewardValuation.Grant::amount).sum();
                int raidPoints = grants.stream().filter(g -> g.item().getPath().equals("raid_points")).mapToInt(RewardValuation.Grant::amount).sum();
                long candies = grants.stream().filter(g -> g.item().getPath().startsWith("exp_candy")).count();
                long packs = grants.stream().filter(g -> g.item().getNamespace().equals("cobblemon-cards")).count();
                String where = name + " seed " + seed + ": " + items + " items, " + raidPoints + " RP, " + candies
                        + " candy grants, " + packs + " card grants";

                assertTrue(items >= 20 && items <= 90, where + " -- item count out of the modest range (measured 47-78 before the floor and boss rolls were cut to one; about 30-55 after)");
                // Balance phase 0: the F5 boss pays 10 RP (was 25) and the champion 25 (was 75), so raids, not towers, are the main source of RP.
                // 35 guaranteed (10 + 25) plus ten boss rolls of 2-5: about 42 expected; was about 51 with 5-10 rolls.
                assertTrue(raidPoints >= 35 && raidPoints <= 70, where + " -- Raid Points out of range (about 42 expected since the 2-5 boss roll; was 100-145 before phase 0)");
                assertTrue(candies >= 2, where + " -- the two guaranteed XL candies should always be there");
                assertTrue(packs >= 2 && packs <= 12, where + " -- card pack grants out of range (mean about 3.4, 2-9 over 400 seeds when re-measured in P24; armor is archived, so a few more)");
                // Exactly two guaranteed XL candies per participant, no more: one at each milestone.
                long guaranteedCandies = grants.stream().filter(g -> g.perPlayer() && g.item().getPath().equals("exp_candy_xl")).count();
                assertEquals(2, guaranteedCandies, where);
            }
        }
    }
}
