package com.cobbletowers.modifier;

import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.persistence.RunModifierState;
import net.minecraft.resources.ResourceLocation;

/**
 * How hard a run is right now, as one number and a word (the Current Run tab). Each held challenge scores 1, 2 or 3 by its
 * risk tier (a locked-in copy scores again), and every Ascension scores 3. Relics are boons and score nothing. Pure.
 */
public record RunDifficulty(int points, String label, int challenges, int ascension) {

    static final int ASCENSION_POINTS = 3;

    public static RunDifficulty of(TowerContent content, RunModifierState state, int ascension) {
        int points = ascension * ASCENSION_POINTS;
        for (ResourceLocation id : state.accumulated()) points += pointsOf(content, id);
        for (ResourceLocation id : state.lockedIn()) points += pointsOf(content, id);
        return new RunDifficulty(points, labelOf(points), state.accumulated().size(), ascension);
    }

    /**
     * The challenge part of a completion chest's score, before AscensionLib adds 2 per Ascension cycle and 2 for a flawless cycle (its tiers start at
     * 0, 2, 5 and 8). Drafts cannot be skipped, so a 10-floor run always holds about eight challenges: counting cards, or raw risk points (8 at the
     * least), put every completion in the Mythic tier (live data, 12 Nov 2026: 13 of 15). This counts only the risk a party chose above the minimum.
     * Parties draft mostly moderate and severe cards (live picks 49% moderate, 39% severe), so floor-10 runs score about 7 to 15 here: the server's
     * {@code towerCompletion.tiers.*.minScore} in AscensionLib's config must rise with this (proposed Silver 8, Gold 11, Mythic 15).
     */
    public static int completionScore(TowerContent content, RunModifierState state) {
        // Minor 0, moderate 1, severe 2: the risk points above the 1 every card scores. A locked-in copy counts again, like the
        // Current Run tab; a card no longer in content scores nothing.
        int score = 0;
        for (ResourceLocation id : state.accumulated()) score += excessOf(content, id);
        for (ResourceLocation id : state.lockedIn()) score += excessOf(content, id);
        return score;
    }

    private static int excessOf(TowerContent content, ResourceLocation id) {
        return Math.max(0, pointsOf(content, id) - 1);
    }

    private static int pointsOf(TowerContent content, ResourceLocation id) {
        return content.modifier(id).map(ModifierDefinition::risk).map(risk -> risk.ordinal() + 1).orElse(0);
    }

    /** The word for a score: Standard, Challenging, Hard, Brutal, Nightmare. */
    public static String labelOf(int points) {
        if (points <= 0) return "Standard";
        if (points <= 3) return "Challenging";
        if (points <= 7) return "Hard";
        if (points <= 12) return "Brutal";
        return "Nightmare";
    }

    /** One line for the menu: "Hard (6): 3 challenges, Ascension 1". */
    public String line() {
        return label + " (" + points + "): " + challenges + (challenges == 1 ? " challenge" : " challenges")
                + (ascension > 0 ? ", Ascension " + ascension : "");
    }
}
