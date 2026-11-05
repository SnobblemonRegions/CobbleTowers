package com.cobbletowers.menu;

import com.cobbletowers.definition.ModifierDefinition;
import java.util.Locale;

/**
 * Which scene a modifier card is painted with: a short stable key the client maps to a diorama. A reward modifier is
 * keyed by the direction of its direct reward factor, so art never implies an effect it lacks. Unrecognised is {@code
 * unknown}: a neutral scene.
 */
public final class ModifierArt {

    public static final String UNKNOWN = "unknown";
    public static final String EVENT = "event";

    private ModifierArt() {}

    public static String theme(ModifierDefinition modifier) {
        if (modifier == null) return UNKNOWN;
        var effect = modifier.effect();
        if (effect.weather().isPresent()) return "weather:" + effect.weather().get().toLowerCase(Locale.ROOT);
        if (effect.terrain().isPresent()) return "terrain:" + effect.terrain().get().toLowerCase(Locale.ROOT);
        if (effect.custom().isPresent()) return "custom:" + effect.custom().get().toLowerCase(Locale.ROOT);
        return switch (modifier.type()) {
            case ENEMY -> "enemy" + enemyDetail(effect);
            case PLAYER_CONSTRAINT -> "constraint" + constraintDetail(effect);
            case SCOUTING -> "scouting";
            case REWARD -> effect.rewardPercent() >= 150 ? "reward_up:hoard"
                    : effect.rewardPercent() > 100 ? "reward_up" : effect.rewardPercent() < 100 ? "reward_down" : UNKNOWN;
            case FIELD -> UNKNOWN;
            case CUSTOM -> UNKNOWN;
        };
    }

    /** What an enemy modifier does to the boss or the opposition, most dramatic effect first. */
    private static String enemyDetail(ModifierDefinition.Effect e) {
        if (e.bossHealthPercent() > 100) return ":tough";
        if (e.bossHealthPercent() < 100) return ":fragile";
        if (e.bossLevelOffset() > 0) return ":champion";
        return "";
    }

    private static String constraintDetail(ModifierDefinition.Effect e) {
        if (!e.bannedMoves().isEmpty()) return e.bannedMoves().contains("recover") ? ":no_heal" : ":no_setup";
        if (!e.allowSwitching()) return ":no_switch";
        if (!e.allowItems()) return ":no_items";
        return "";
    }
}
