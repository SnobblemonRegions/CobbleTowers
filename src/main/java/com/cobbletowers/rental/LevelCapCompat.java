package com.cobbletowers.rental;

import com.cobbletowers.TowerLog;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps rentals at or under the player's level cap when CobbleLvlCap is installed. CobbleLvlCap faints every party
 * Pokemon above the cap when a battle starts (and cancels the battle when all of them are), so a rental team scaled past a
 * player's cap could not fight at all. Optional: without CobbleLvlCap every cap is {@value RentalScaling#MAX_LEVEL}.
 * The cap is read from CobbleLvlCap itself, so it is always the same number its battle check uses.
 */
public final class LevelCapCompat {

    static final String MOD_ID = "cobblelvlcap";
    private static final String CLASS = "com.floye.LevelCapService";

    private static boolean resolved;
    private static Method getCapFor;
    private static boolean saidFailure;

    private LevelCapCompat() {}

    /** The highest level this player's Pokemon may be, or {@value RentalScaling#MAX_LEVEL} when nothing caps them. */
    public static int capFor(ServerPlayer player) {
        if (!resolve() || getCapFor == null) return RentalScaling.MAX_LEVEL;
        try {
            return ((Integer) getCapFor.invoke(null, player)).intValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (!saidFailure) {
                saidFailure = true;
                TowerLog.error("Reading the level cap of " + player.getUUID() + " from CobbleLvlCap failed; rentals keep their levels (said once)", ex);
            }
            return RentalScaling.MAX_LEVEL;
        }
    }

    /** A rental's level on this floor for a player with this cap: the floor's level, never above the cap, never below 1. */
    public static int capped(int level, int cap) {
        return Math.max(1, Math.min(level, cap));
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
            try {
                getCapFor = Class.forName(CLASS).getMethod("getCapFor", ServerPlayer.class);
            } catch (ReflectiveOperationException | LinkageError ex) {
                getCapFor = null;
                TowerLog.error("CobbleLvlCap is installed but " + CLASS + " does not match what CobbleTowers expects, "
                        + "so rentals are not held to the level cap", ex);
            }
        }
        return getCapFor != null;
    }
}
