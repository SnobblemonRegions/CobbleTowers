package com.cobbletowers.economy;

import com.cobbleraids.api.points.CobbleRaidsPoints;
import com.cobbletowers.TowerLog;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Credits Raid Points a tower run earned, against CobbleRaids' daily tower cap.
 *
 * <p>CobbleRaids owns the cap (150 a day by default, {@code raid_points.daily_cap_towers}) and the record of what a
 * player has earned today; this only says which content the points came from. {@code awardEarned} is newer than the
 * CobbleRaids build this compiles against, so it is found by reflection: on a server with an older CobbleRaids the run
 * pays through the plain {@code award}, uncapped, exactly as before.
 */
public final class RaidPointsCredit {

    /** The source id CobbleRaids caps as tower content. */
    static final String TOWER_SOURCE = "tower";

    private static volatile Method earned;
    private static volatile boolean resolved;

    private RaidPointsCredit() {}

    /** Credits {@code amount} earned in a tower run and returns what was actually credited (0 once today's cap is hit). */
    public static int awardFromRun(MinecraftServer server, UUID playerId, int amount) {
        if (amount <= 0) return 0;
        Method method = earnedMethod();
        if (method == null) {
            CobbleRaidsPoints.award(server, playerId, amount);
            return amount;
        }
        try {
            return (Integer) method.invoke(null, server, playerId, amount, TOWER_SOURCE);
        } catch (InvocationTargetException ex) {
            // The credit itself failed. Not retried uncapped: the caller holds the reward, so nothing is paid twice.
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("CobbleRaids failed to credit Raid Points", cause);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("CobbleRaidsPoints.awardEarned is not accessible", ex);
        }
    }

    private static Method earnedMethod() {
        if (!resolved) {
            synchronized (RaidPointsCredit.class) {
                if (!resolved) {
                    try {
                        earned = CobbleRaidsPoints.class.getMethod("awardEarned", MinecraftServer.class, UUID.class, int.class,
                                String.class);
                    } catch (NoSuchMethodException ex) {
                        TowerLog.info("The installed CobbleRaids has no daily Raid Points cap; tower Raid Points are uncapped.");
                        earned = null;
                    }
                    resolved = true;
                }
            }
        }
        return earned;
    }
}
