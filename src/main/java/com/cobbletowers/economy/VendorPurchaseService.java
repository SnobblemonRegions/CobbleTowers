package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.VendorServiceDefinition;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.runtime.TowerRuns;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one place a vendor sale happens (TDS #16, #18, #19). Every condition is re-checked here, never trusted from the
 * caller.
 */
public final class VendorPurchaseService {

    /** Why a purchase did or did not go through -- named so a client can show the actual reason. */
    public enum Result {
        SUCCESS, RUN_NOT_FOUND, NOT_INTERMISSION, UNKNOWN_SERVICE, TARGET_NOT_IN_RUN, TARGET_OFFLINE,
        SOLD_OUT, INSUFFICIENT_FUNDS, VENDOR_CLOSED,
        /** The effect threw after the charge; the price was given back. */
        FAILED
    }

    private VendorPurchaseService() {}

    /** What a purchase attempt means to the player who made it, for the message line of the vendor screen. */
    public static String describe(Result result, String targetName, boolean self) {
        return switch (result) {
            case SUCCESS -> self ? "Bought for yourself." : "Bought for " + targetName + ".";
            case INSUFFICIENT_FUNDS -> "Not enough CobbleDollars.";
            case SOLD_OUT -> "That service is sold out for this run.";
            case TARGET_OFFLINE -> targetName + " is not online.";
            case TARGET_NOT_IN_RUN -> targetName + " is no longer in the run.";
            case NOT_INTERMISSION -> "The vendor is only open at an intermission.";
            case UNKNOWN_SERVICE -> "That service does not exist.";
            case RUN_NOT_FOUND -> "You are not in a run.";
            case VENDOR_CLOSED -> "The vendor is closed in this mode.";
            case FAILED -> "That did not work. You were not charged.";
        };
    }

    public static Result purchase(MinecraftServer server, UUID runId, UUID payingPlayerId, UUID targetPlayerId,
                                  ResourceLocation serviceId) {
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return Result.RUN_NOT_FOUND;
        PersistedRun run = found.get();
        // A playlist can close the vendor outright (Hardcore, P32).
        if (com.cobbletowers.definition.PlaylistRegistry.vendorClosed(run)) return Result.VENDOR_CLOSED;
        // TDS #16's "no automatic free BETWEEN-FLOOR healing": the vendor is not reachable mid-fight.
        if (run.state() != RunState.INTERMISSION) return Result.NOT_INTERMISSION;

        TowerContent content = TowerDefinitionRegistry.content();
        Optional<VendorServiceDefinition> service = content.vendorService(serviceId);
        if (service.isEmpty()) return Result.UNKNOWN_SERVICE;

        boolean targetInRun = run.participants().stream()
                .anyMatch(participant -> participant.playerId().equals(targetPlayerId) && participant.state().isInRun());
        if (!targetInRun) return Result.TARGET_NOT_IN_RUN;

        // Nothing to apply the effect to; refused before anything is charged.
        ServerPlayer target = server.getPlayerList().getPlayer(targetPlayerId);
        if (target == null) return Result.TARGET_OFFLINE;

        int cap = service.get().maxPurchasesPerRun();
        if (cap > 0 && run.purchasesOf(serviceId) >= cap) return Result.SOLD_OUT;

        // The payer's own price: their worn armor may discount it. An offline payer (possible when a teammate buys on
        // their behalf) simply pays the listed price.
        ServerPlayer payer = server.getPlayerList().getPlayer(payingPlayerId);
        int price = payer == null ? service.get().priceCobbleDollars()
                : com.cobbletowers.armor.ArmorBonusEffects.vendorPrice(payer, service.get().priceCobbleDollars());
        if (!TowerWalletStore.get(server).debit(payingPlayerId, price)) {
            return Result.INSUFFICIENT_FUNDS;
        }
        // Flushed immediately: a crash before the run write costs a purchase count, never a duplicated debit.
        TowerWalletStore.get(server).checkpoint(server);

        try {
            VendorServices.apply(service.get().effect(), target);
        } catch (RuntimeException ex) {
            // Charged and flushed above: a service that did nothing must not cost anything.
            TowerLog.error("Run " + runId + ": " + serviceId + " threw after " + payingPlayerId + " was charged; refunding", ex);
            TowerWalletStore.get(server).credit(payingPlayerId, price);
            TowerWalletStore.get(server).checkpoint(server);
            return Result.FAILED;
        }

        long now = System.currentTimeMillis();
        TowerRuns.save(server, run.withVendorPurchase(serviceId, now), false);
        TowerLog.info("Run {}: {} bought {} for {}", runId, payingPlayerId, serviceId, targetPlayerId);
        com.cobbletowers.events.TowerEvents.emit(new com.cobbletowers.events.TowerEvent.Purchased(runId,
                java.util.List.of(payingPlayerId), serviceId));
        return Result.SUCCESS;
    }
}
