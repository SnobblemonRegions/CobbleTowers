package com.cobbletowers.menu;

import com.cobbletowers.definition.PlaylistDefinition;
import com.cobbletowers.network.TowerHallStatePayload.Extras;
import com.cobbletowers.runtime.PartyValidation.PartyMember;
import com.cobbletowers.runtime.PlaylistRules;
import com.cobbletowers.network.TowerHallStatePayload.Mon;
import com.cobbletowers.network.TowerHallStatePayload.Standing;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** What the Frontier Desk Hall (P51) shows beyond the native snapshot. Pure: the service reads the world, this shapes it. */
public final class HallExtras {

    private HallExtras() {}

    /**
     * One tower's standing. {@code nextRankAt} is the level where the next rank begins, or a negative number at the top;
     * the payload carries the gap, or -1 at the top.
     */
    public static Standing standing(ResourceLocation tower, int level, int ascension, String rank, int nextRankAt) {
        int shown = Math.max(0, level);
        return new Standing(tower, rank, shown, nextRankAt < 0 ? -1 : Math.max(0, nextRankAt - shown), Math.max(0, ascension));
    }

    /** The sprite file name for a species id path: lower case, letters and digits only ("nidoran_f" is nidoranf, "mr_mime" is mrmime). */
    public static String spriteName(String speciesIdPath) {
        return speciesIdPath.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Health as a whole percent, 0 to 100; anything above zero shows as at least 1 so a living Pokemon never reads as empty. */
    public static int hpPercent(int current, int max) {
        if (max <= 0 || current <= 0) return 0;
        return Math.min(100, Math.max(1, Math.round(current * 100f / max)));
    }

    /**
     * Why the party would not pass the lobby's mode: the playlist's clauses applied to the Pokemon the tower would register
     * (the first {@code registerLimit}, or the ones the player chose). Same rules as the start check; empty when it passes.
     */
    public static List<String> modeProblems(List<PartyMember> party, int registerLimit, PlaylistDefinition.Clauses clauses) {
        List<PartyMember> registered = party.subList(0, Math.min(party.size(), Math.max(0, registerLimit)));
        List<String> out = PlaylistRules.problems(registered, clauses);
        return out.size() > 12 ? List.copyOf(out.subList(0, 12)) : out;
    }

    /** The same extras with the lobby's mode and why the team does not fit it. */
    public static Extras withMode(Extras base, String modeName, List<String> problems) {
        return new Extras(base.runTower(), base.party(), base.standings(), modeName == null ? "" : modeName, List.copyOf(problems),
                base.focusTower());
    }

    /** The same extras, opening the Hall on {@code tower} (a hub host or portal sent the player here). */
    public static Extras withFocus(Extras base, ResourceLocation tower) {
        return new Extras(base.runTower(), base.party(), base.standings(), base.modeName(), base.modeProblems(),
                tower == null ? "" : tower.toString());
    }

    /** At most six Pokemon, in party order. */
    public static Extras of(String runTower, List<Mon> party, List<Standing> standings) {
        return new Extras(runTower == null ? "" : runTower, party.size() > 6 ? List.copyOf(party.subList(0, 6)) : List.copyOf(party),
                List.copyOf(standings));
    }
}
