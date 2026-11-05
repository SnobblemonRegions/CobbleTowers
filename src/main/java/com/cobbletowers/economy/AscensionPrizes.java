package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Pays a Mystery Bag prize through AscensionLib ({@code com.ascensionlib.AscensionGrants.grantAsVouchers}), reached by reflection because the library is
 * optional. The prize arrives as voucher items the player right-clicks to deposit; the bag's id is the key, so asking again never pays twice. An absent
 * library, or one older than this call, answers {@link MysteryBagOpening.Pay#UNAVAILABLE} and the bag stays closed.
 */
public final class AscensionPrizes {

    private static final String MOD_ID = "ascensionlib";
    private static final String CLASS = "com.ascensionlib.AscensionGrants";

    private static boolean resolved;
    private static Method grant;

    private AscensionPrizes() {}

    /** Whether the library is installed and has the call, so a bag could be paid. */
    public static boolean available() {
        return resolve();
    }

    /** A payer for one player's bags. */
    public static MysteryBagOpening.Payer payerFor(UUID player) {
        return (bag, prize) -> pay(player, bag, prize);
    }

    static MysteryBagOpening.Pay pay(UUID player, UUID bag, MysteryBag.Prize prize) {
        if (!resolve()) return MysteryBagOpening.Pay.UNAVAILABLE;
        try {
            String result = String.valueOf(grant.invoke(null, bag, player, Map.of(prize.material(), (long) prize.amount())));
            // DISABLED is the library saying no world is running progression: not a failure of ours, and the bag stays closed.
            if ("GRANTED".equals(result)) return MysteryBagOpening.Pay.PAID;
            if ("DISABLED".equals(result)) return MysteryBagOpening.Pay.UNAVAILABLE;
            TowerLog.warn("AscensionLib did not pay Mystery Bag {} ({} x{}): {}", bag, prize.material(), prize.amount(), result);
            return MysteryBagOpening.Pay.FAILED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            TowerLog.error("Paying Mystery Bag {} through AscensionLib failed", bag, ex);
            return MysteryBagOpening.Pay.FAILED;
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
            try {
                grant = Class.forName(CLASS).getMethod("grantAsVouchers", UUID.class, UUID.class, Map.class);
            } catch (ReflectiveOperationException | LinkageError ex) {
                grant = null;
                TowerLog.warn("AscensionLib is installed but has no {}.grantAsVouchers (update AscensionLib); Mystery Bags cannot be opened", CLASS);
            }
        }
        return grant != null;
    }
}
