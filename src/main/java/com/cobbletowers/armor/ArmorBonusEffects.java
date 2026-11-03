package com.cobbletowers.armor;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokemonCatchRateEvent;
import com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent;
import com.cobblemon.mod.common.api.events.pokemon.ShinyChanceCalculationEvent;
import com.cobbletowers.TowerLog;
import com.google.gson.JsonArray;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a worn set's non-attribute bonuses touch the game (P24): three Cobblemon numbers plus small pure helpers.
 * Each Cobblemon handler is guarded so a failure leaves Cobblemon's own number unchanged.
 */
public final class ArmorBonusEffects {

    private static boolean installed;

    private ArmorBonusEffects() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(ArmorBonusEffects::onExperience);
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(ArmorBonusEffects::onCatchRate);
        CobblemonEvents.SHINY_CHANCE_CALCULATION.subscribe(ArmorBonusEffects::onShiny);
        TowerLog.info("Armor set Cobblemon bonuses installed");
    }

    // ---- Cobblemon ----------------------------------------------------------------------------------------------

    private static void onExperience(ExperienceGainedEvent.Pre event) {
        try {
            ServerPlayer owner = event.getPokemon().getOwnerPlayer();
            if (owner == null) return;
            int percent = WornSets.current(owner).xpPercent();
            if (percent > 0) event.setExperience(scale(event.getExperience(), percent));
        } catch (RuntimeException ex) {
            TowerLog.error("The armor experience bonus failed; the experience is unchanged", ex);
        }
    }

    private static void onCatchRate(PokemonCatchRateEvent event) {
        try {
            if (!(event.getThrower() instanceof ServerPlayer thrower)) return;
            int percent = WornSets.current(thrower).catchRatePercent();
            if (percent > 0) event.setCatchRate(event.getCatchRate() * (1f + percent / 100f));
        } catch (RuntimeException ex) {
            TowerLog.error("The armor catch-rate bonus failed; the rate is unchanged", ex);
        }
    }

    private static void onShiny(ShinyChanceCalculationEvent event) {
        try {
            event.addModificationFunction((chance, player, pokemon) -> {
                try {
                    if (player == null) return chance;
                    int percent = WornSets.current(player).shinyPercent();
                    return percent > 0 ? chance * (1f + percent / 100f) : chance;
                } catch (RuntimeException ex) {
                    TowerLog.errorOnce("shiny", "The armor shiny bonus failed for a roll; the odds are unchanged", ex);
                    return chance;
                }
            });
        } catch (RuntimeException ex) {
            TowerLog.error("The armor shiny bonus failed; the odds are unchanged", ex);
        }
    }

    // ---- tower mechanics ------------------------------------------------------------------------------------------

    /** {@code amount} raised by {@code percent}, rounded to nearest, never below the amount. */
    public static int scale(int amount, int percent) {
        if (amount <= 0 || percent <= 0) return amount;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(amount * (1.0 + percent / 100.0)));
    }

    /**
     * The price {@code player} pays for something that costs {@code base}: discounted, but never free, never
     * negative.
     */
    public static int discountedPrice(int base, int discountPercent) {
        if (base <= 0 || discountPercent <= 0) return Math.max(0, base);
        return Math.max(1, (int) Math.round(base * (100 - Math.min(discountPercent, 100)) / 100.0));
    }

    /** The player's vendor price for a service listed at {@code base}. */
    public static int vendorPrice(ServerPlayer player, int base) {
        int price = discountedPrice(base, WornSets.current(player).vendorDiscountPercent());
        // A run's CUSTOM modifiers (P29) apply on top of any armor discount.
        int percent = com.cobbletowers.runtime.TowerRuns.forPlayer(player.getUUID())
                .map(run -> com.cobbletowers.modifier.DraftService.customs(run).vendorPricePercent()).orElse(100);
        // And the player's own mastery of the tower they are in (P31).
        int mastery = com.cobbletowers.mastery.MasteryService.perksInRun(player.server, player.getUUID()).vendorDiscountPercent();
        percent = percent * (100 - mastery) / 100;
        return percent == 100 ? price : Math.max(price > 0 ? 1 : 0, (int) Math.round(price * percent / 100.0));
    }

    /** Raid Points to award {@code player} for a base grant of {@code base}. */
    public static int raidPoints(ServerPlayer player, int base) {
        return scale(base, WornSets.current(player).raidPointsPercent());
    }

    /** As above, for a grant earned in {@code runId}: also the player's mastery of that run's tower (P31). */
    public static int raidPoints(ServerPlayer player, int base, java.util.UUID runId) {
        int plain = raidPoints(player, base);
        int bonus = com.cobbletowers.mastery.MasteryService.perksForRun(player.server, player.getUUID(), runId)
                .raidPointsBonusPercent();
        return bonus == 0 ? plain : plain + plain * bonus / 100;
    }

    /** The wearer's battle operations, ready to merge into {@code TowerBattleFx.armFloorBattle}. */
    public static JsonArray battleEffects(ServerPlayer player) {
        return WornSets.current(player).battleEffects();
    }

    /** The armor's operations plus the run's CUSTOM modifiers' (P29), for a battle in {@code runId}. */
    public static JsonArray battleEffects(ServerPlayer player, java.util.UUID runId) {
        return battleEffects(player, runId, true);
    }

    /**
     * As above, plus the run's Ascension (P30): the player's boon always, the enemy's growth only when {@code
     * enemyScaling} is set (a boss battle merges all players' operations, so one player asks).
     */
    public static JsonArray battleEffects(ServerPlayer player, java.util.UUID runId, boolean enemyScaling) {
        JsonArray ops = battleEffects(player);
        com.cobbletowers.runtime.TowerRuns.get(runId).ifPresent(run -> {
            // An enrage is the run's, not the player's: like the enemy's growth, only the one player who asks sends it, or
            // a boss battle merging everyone's operations would enrage once per player.
            for (var op : com.cobbletowers.modifier.DraftService.customs(run).battleOps()) {
                boolean enrage = op.isJsonObject() && op.getAsJsonObject().has("op")
                        && "enrage".equals(op.getAsJsonObject().get("op").getAsString());
                if (enemyScaling || !enrage) ops.add(op);
            }
            // The boon and the enemy's growth follow the same Ascension, which for a Threat run counts only the cycles past VII.
            int ascension = com.cobbletowers.modifier.DraftService.growthAscensionOf(run);
            com.cobbletowers.ascension.AscensionFx.boon(ascension).forEach(ops::add);
            if (enemyScaling) {
                var tower = com.cobbletowers.definition.TowerDefinitionRegistry.content().towers().get(run.towerId());
                com.cobbletowers.ascension.AscensionFx.enemy(ascension, tower != null && tower.bossRush()).forEach(ops::add);
            }
            com.cobbletowers.encounter.RegionFx.ops(run, player.getUUID(), enemyScaling).forEach(ops::add);
        });
        return ops;
    }
}
