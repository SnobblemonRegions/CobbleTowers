package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Tells AscensionLib about a floor's enemies so its players can spend a Scouter on them. Reached by reflection
 * ({@code com.ascensionlib.AscensionEncounters}). Fire and forget: a failure is logged and never touches a floor.
 */
public final class AscensionLibScouting {

    private static final String MOD_ID = "ascensionlib";
    private static final String CLASS = "com.ascensionlib.AscensionEncounters";

    private static boolean resolved;
    private static Method declare;
    private static Method end;
    private static Method arm;
    private static Method disarm;
    private static Method threatEnabled;
    private static Method recommendedThreat;
    private static Method threatBands;
    private static boolean saidUnknown;

    private AscensionLibScouting() {}

    /** Declares one enemy of an encounter. {@code boss} makes its reveal shared with every participant. */
    public static void declare(String encounterId, Collection<UUID> participants, int enemyIndex, String tier,
                               boolean boss, String speciesId, int level) {
        if (!resolve() || declare == null) return;
        try {
            Object status = declare.invoke(null, encounterId, participants, enemyIndex, tier, boss, null, speciesId, level);
            String text = String.valueOf(status);
            // DISABLED is the library saying no world is running; nothing to say about it every floor.
            if (!"DECLARED".equals(text) && !"DISABLED".equals(text) && !saidUnknown) {
                saidUnknown = true;
                TowerLog.warn("AscensionLib could not declare {} (level {}) for scouting: {} (said once)", speciesId,
                        level, text);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("Declaring enemy {} for scouting failed", speciesId, ex);
        }
    }

    /** The encounter is over: the library forgets its enemies and every reveal. Safe to repeat. */
    public static void end(String encounterId) {
        if (!resolve() || end == null) return;
        try {
            end.invoke(null, encounterId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("Ending scouting encounter {} failed", encounterId, ex);
        }
    }

    /**
     * Arms these players' next battle to fight an encounter's declared enemies (their ascension effects then act), or
     * no enemy effects when {@code encounterId} is null. Pair with {@link #disarm}; see {@link #armed}.
     */
    public static void arm(Collection<UUID> players, String encounterId) {
        if (!resolve() || arm == null) return;
        try {
            arm.invoke(null, players, encounterId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("Arming the battle of {} for ascension effects failed", players, ex);
        }
    }

    public static void disarm(Collection<UUID> players) {
        if (!resolve() || disarm == null) return;
        try {
            disarm.invoke(null, players);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("Disarming the battle of {} failed", players, ex);
        }
    }

    /** Whether AscensionLib's Threat system is on (balance phase 2); false when the library is missing or too old for it. */
    public static boolean threatEnabled() {
        if (!resolve() || threatEnabled == null) return false;
        try {
            return Boolean.TRUE.equals(threatEnabled.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** The Threat band (1-6) the library advises for these players' teams, or 1 when it cannot say. Advice only. */
    public static int recommendedThreat(Collection<UUID> players) {
        if (!resolve() || recommendedThreat == null) return 1;
        try {
            return recommendedThreat.invoke(null, players) instanceof Integer band ? band : 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return 1;
        }
    }

    /** What each Threat band is (AscensionLib's own enemy tiers and payouts), or none when the library is missing or too old to say. For the lobby's picker. */
    public static java.util.List<com.cobbletowers.ascension.ThreatBandInfo.Band> threatBands() {
        if (!resolve() || threatBands == null) return java.util.List.of();
        try {
            return threatBands.invoke(null) instanceof String json ? com.cobbletowers.ascension.ThreatBandInfo.cached(json) : java.util.List.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return java.util.List.of();
        }
    }

    /**
     * Runs {@code start} with these players armed for {@code encounterId} (null: explicitly native), always
     * disarming.
     */
    public static <T> T armed(Collection<UUID> players, String encounterId, java.util.function.Supplier<T> start) {
        arm(players, encounterId);
        try {
            return start.get();
        } finally {
            disarm(players);
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
            try {
                Class<?> encounters = Class.forName(CLASS);
                declare = encounters.getMethod("declareEnemy", String.class, Collection.class, int.class, String.class,
                        boolean.class, String.class, String.class, int.class);
                end = encounters.getMethod("end", String.class);
                arm = encounters.getMethod("armBattle", Collection.class, String.class);
                disarm = encounters.getMethod("disarmBattle", Collection.class);
                try {
                    // Newer than the rest (AscensionLib balance phase 1): without them every run keeps the floor tiers.
                    threatEnabled = encounters.getMethod("threatEnabled");
                    recommendedThreat = encounters.getMethod("recommendedThreat", Collection.class);
                } catch (NoSuchMethodException older) {
                    threatEnabled = null;
                    recommendedThreat = null;
                }
                try {
                    // Newer still: what each band is, for the lobby's picker. Without it the picker has no detail to show.
                    threatBands = encounters.getMethod("threatBands");
                } catch (NoSuchMethodException older) {
                    threatBands = null;
                }
            } catch (ReflectiveOperationException | LinkageError ex) {
                declare = null;
                end = null;
                arm = null;
                disarm = null;
                TowerLog.error("AscensionLib is installed but " + CLASS + " does not match what CobbleTowers expects, "
                        + "so Scouters cannot be used", ex);
            }
        }
        return declare != null;
    }
}
