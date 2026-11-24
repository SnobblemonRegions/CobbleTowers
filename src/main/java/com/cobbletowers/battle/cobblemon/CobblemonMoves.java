package com.cobbletowers.battle.cobblemon;

import com.cobbletowers.network.RentalDraftPayload;
import com.cobblemon.mod.common.api.moves.Moves;
import java.util.Locale;

/**
 * A rental card move's type and damage category from Cobblemon's own data, so a card cannot disagree with the battle.
 * An unknown move has no type and draws a plain gem.
 */
public final class CobblemonMoves {

    private CobblemonMoves() {}

    /** A move's name as the game shows it (translated on the player's side), or the id when Cobblemon does not know it. */
    public static net.minecraft.network.chat.MutableComponent name(String id) {
        try {
            var template = Moves.INSTANCE.getByName(id);
            if (template != null) return template.getDisplayName().copy();
        } catch (RuntimeException ignored) {
            // falls through to the id
        }
        return net.minecraft.network.chat.Component.literal(id);
    }

    /** An ability's name as the game shows it (translated on the player's side), or null when Cobblemon does not know it. */
    public static net.minecraft.network.chat.MutableComponent abilityName(String id) {
        try {
            var template = com.cobblemon.mod.common.api.abilities.Abilities.INSTANCE.get(id);
            if (template != null) return net.minecraft.network.chat.Component.translatable(template.getDisplayName());
        } catch (RuntimeException ignored) {
            // falls through
        }
        return null;
    }

    public static RentalDraftPayload.Move resolve(String id) {
        try {
            var template = Moves.INSTANCE.getByName(id);
            if (template == null) return new RentalDraftPayload.Move(id, "", "");
            return new RentalDraftPayload.Move(id, template.getElementalType().getName().toLowerCase(Locale.ROOT),
                    template.getDamageCategory().getName().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return new RentalDraftPayload.Move(id, "", "");
        }
    }
}
