package com.cobbletowers.hub;

import com.cobbletowers.definition.AchievementDefinition;
import com.cobbletowers.definition.AchievementRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * How a player's mastery achievements in one tower show on its trophy case (owner, 23 Nov 2026). The thirty feats fall into six ladders, each
 * one trophy that upgrades stone (none yet) -> bronze -> silver -> gold -> ascended (all of them) as its rungs are earned, and six one-off
 * feats shown as medals. A rung counts whenever it is held, in any order.
 */
final class Trophies {

    static final List<String> FAMILIES = List.of("depth", "flawless", "speed", "solo", "challenger", "score");
    static final List<String> TIERS = List.of("stone", "bronze", "silver", "gold", "ascended");
    /** The one-off feats, as medals on the case's back rail, left to right. */
    static final List<String> MEDALS = List.of("first_ascent", "regular", "veteran", "warden", "paragon", "eternal");

    /** Each ladder's rungs, easiest first. */
    static final Map<String, List<String>> LADDERS = Map.of(
            "depth", List.of("depth_1", "depth_3", "depth_5", "depth_10", "depth_15", "depth_20"),
            "flawless", List.of("flawless_0", "flawless_3", "flawless_6", "flawless_10"),
            "speed", List.of("speed_25", "speed_15", "speed_10", "speed_7"),
            "solo", List.of("solo_0", "solo_3", "solo_6", "solo_flawless"),
            "challenger", List.of("challenger_2", "challenger_4", "challenger_6", "challenger_a5"),
            "score", List.of("score_40", "score_100"));

    private static final Map<String, Integer> TIER_COLOUR = Map.of("stone", 0x8A8F9C, "bronze", 0xD08A4E, "silver", 0xDCE0EC,
            "gold", 0xF2C744, "ascended", 0xFFE27A);

    private Trophies() {}

    /** How many of a ladder's rungs are held. */
    static int held(String family, Set<String> held) {
        return (int) LADDERS.getOrDefault(family, List.of()).stream().filter(held::contains).count();
    }

    /**
     * The ladder trophy's tier: stone with no rung, ascended with every rung, and bronze, silver, gold spread evenly over the rungs between
     * (a six-rung ladder: 1-2 bronze, 3-4 silver, 5 gold; four rungs: 1 bronze, 2 silver, 3 gold; two rungs: 1 bronze).
     */
    static String tier(String family, Set<String> held) {
        int size = LADDERS.getOrDefault(family, List.of()).size();
        int n = held(family, held);
        if (n == 0 || size == 0) return "stone";
        if (n >= size) return "ascended";
        int step = size <= 2 ? 0 : Math.min(2, (n - 1) * 3 / (size - 1));
        return List.of("bronze", "silver", "gold").get(step);
    }

    /** What the action bar says while a player looks at a ladder trophy or a medal. */
    static Component hint(String feat, Set<String> held) {
        List<String> rungs = LADDERS.get(feat);
        if (rungs == null) {                                        // a medal
            Optional<AchievementDefinition> def = definition(feat);
            String name = def.map(AchievementDefinition::displayName).orElse(feat);
            MutableComponent out = Component.literal(name).withColor(0xF2C744);
            if (held.contains(feat)) return out.append(Component.literal("  ·  earned").withColor(0x96E6A0));
            out = Component.literal(name).withColor(0xA0A6B4).append(Component.literal("  ·  not yet").withColor(0x8A8F9C));
            String how = def.map(AchievementDefinition::description).orElse("");
            return how.isEmpty() ? out : out.append(Component.literal("  ·  " + how).withColor(0xDCE0EC));
        }
        String tier = tier(feat, held);
        int n = held(feat, held);
        MutableComponent out = Component.literal(capital(feat)).withColor(0xFFFFFF)
                .append(Component.literal("  ·  ").withColor(0x8A8F9C))
                .append(Component.literal(capital(tier)).withColor(TIER_COLOUR.get(tier)))
                .append(Component.literal("  ·  " + n + " of " + rungs.size()).withColor(0xA0A6B4));
        if (n >= rungs.size()) return out.append(Component.literal("  ·  every rung earned").withColor(0x96E6A0));
        String next = rungs.stream().filter(r -> !held.contains(r)).findFirst().orElse("");
        String how = definition(next).map(AchievementDefinition::description).orElse("");
        return how.isEmpty() ? out : out.append(Component.literal("  ·  next: " + how).withColor(0xDCE0EC));
    }

    private static Optional<AchievementDefinition> definition(String path) {
        return AchievementRegistry.get(ResourceLocation.fromNamespaceAndPath("cobbletowers", path));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
