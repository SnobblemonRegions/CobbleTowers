package com.cobbletowers.contract;

import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.ContractTemplateDefinition;
import com.cobbletowers.definition.ContractTemplateDefinition.Period;
import com.cobbletowers.definition.ContractTemplateRegistry;
import com.cobbletowers.events.TowerEvent;
import com.cobbletowers.events.TowerEvents;
import com.cobbletowers.persistence.TowerContractStore;
import com.cobbletowers.persistence.TowerContractStore.Progress;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.trial.TrialService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Contracts at run time (P32c): hands out the day's and week's contracts, moves progress on tower events and pays the
 * reward once. The draw ({@link ContractSchedule}) and progress rules ({@link ContractRules}) are pure.
 */
public final class ContractService {

    private static volatile MinecraftServer SERVER;

    private ContractService() {}

    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVER = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVER = null);
        TowerEvents.subscribe(ContractService::onEvent);
    }

    /** A player's contract in one slot, ready to show. */
    public record Active(String slotKey, Period period, int slot, ContractTemplateDefinition template, int count, boolean done) {}

    /** The contracts a player holds right now: today's daily slots, then this week's. */
    public static List<Active> activeFor(MinecraftServer server, UUID player) {
        TowerContractStore store = TowerContractStore.get(server);
        LocalDate today = TrialService.today();
        List<Active> active = new ArrayList<>();
        for (Period period : Period.values()) {
            String periodKey = ContractSchedule.periodKey(period, today);
            int[] rerolls = new int[ContractSchedule.slotsOf(period)];
            int rerolled = store.rerolledSlot(player, periodKey);
            if (rerolled >= 0 && rerolled < rerolls.length) rerolls[rerolled] = 1;
            List<ContractTemplateDefinition> picked = ContractSchedule.pick(ContractTemplateRegistry.of(period), period,
                    ContractSchedule.periodNumber(period, today), rerolls);
            for (int slot = 0; slot < picked.size(); slot++) {
                String slotKey = periodKey + ":" + slot;
                ContractTemplateDefinition template = picked.get(slot);
                Optional<Progress> progress = store.progressOf(player, slotKey)
                        .filter(found -> found.templateId().equals(template.id().toString()));
                active.add(new Active(slotKey, period, slot, template, progress.map(Progress::count).orElse(0),
                        progress.map(Progress::done).orElse(false)));
            }
        }
        return active;
    }

    /** An event arrived: move every online player's contracts it counts for. */
    static void onEvent(TowerEvent event) {
        MinecraftServer server = SERVER;
        if (server == null) return;
        if (com.cobbletowers.tutorial.Tutorial.isTutorialRun(event.runId())) return;       // the replayable tutorial counts for no contract (25 Nov 2026)
        TowerContractStore store = TowerContractStore.get(server);
        boolean changed = false;
        for (UUID player : event.players()) {
            for (Active contract : activeFor(server, player)) {
                if (contract.done()) continue;
                int delta = ContractRules.delta(contract.template().condition(), event, player);
                if (delta == 0) continue;
                int count = Math.min(contract.template().condition().count(), contract.count() + delta);
                boolean done = count >= contract.template().condition().count();
                store.set(player, contract.slotKey(), new Progress(contract.template().id().toString(), count, done));
                changed = true;
                if (done) complete(server, player, contract.template());
            }
        }
        if (changed) pruneOld(store);
    }

    private static void complete(MinecraftServer server, UUID player, ContractTemplateDefinition template) {
        int reward = template.reward();
        if (reward > 0) {
            TowerWalletStore.get(server).credit(player, reward);
            TowerWalletStore.get(server).checkpoint(server);
        }
        TowerContractStore.get(server).checkpoint(server);
        TowerLog.info("{} completed the contract {} and is paid {} Tower Tokens", player, template.id(), reward);
        com.cobbletowers.mastery.TuningCounters.bump(server, "contract_completed." + template.id());
        com.cobbletowers.season.SeasonProgressService.award(server, player, template.period() == ContractTemplateDefinition.Period.DAILY
                ? com.cobbletowers.season.SeasonPoints.Source.DAILY_CONTRACT : com.cobbletowers.season.SeasonPoints.Source.WEEKLY_CONTRACT, 0, false);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(Component.literal("Contract complete: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(template.displayName()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(reward > 0 ? "  +" + reward + " Tower Tokens" : "").withStyle(ChatFormatting.GREEN)));
        }
    }

    /** Daily progress older than three days and weekly older than three weeks is no longer shown to anyone. */
    private static void pruneOld(TowerContractStore store) {
        LocalDate today = TrialService.today();
        store.prune(period -> isStale(period, today));
    }

    static boolean isStale(String periodKey, LocalDate today) {
        try {
            if (periodKey.startsWith("daily:")) return LocalDate.parse(periodKey.substring(6)).isBefore(today.minusDays(3));
            if (periodKey.startsWith("weekly:")) {
                return com.cobbletowers.trial.TrialClock.weekStartOf(periodKey.substring(7))
                        .map(start -> start.isBefore(today.minusWeeks(3))).orElse(false);
            }
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
        return false;
    }

    // ---- rerolling
    // ---------------------------------------------------------------------------------------------------

    /**
     * Rerolls one slot of one period for this player; one reroll per period, and a finished slot cannot be rerolled.
     */
    public static String reroll(MinecraftServer server, UUID player, Period period, int slot) {
        LocalDate today = TrialService.today();
        TowerContractStore store = TowerContractStore.get(server);
        String periodKey = ContractSchedule.periodKey(period, today);
        if (slot < 0 || slot >= ContractSchedule.slotsOf(period)) return "There is no contract " + (slot + 1) + ".";
        if (store.rerolledSlot(player, periodKey) >= 0) return "You have already used this " + period.name().toLowerCase(java.util.Locale.ROOT) + " reroll.";
        List<Active> active = activeFor(server, player);
        Optional<Active> target = active.stream().filter(a -> a.period() == period && a.slot() == slot).findFirst();
        if (target.isEmpty()) return "There is no contract " + (slot + 1) + ".";
        if (target.get().done()) return "That contract is already complete.";
        store.markReroll(player, periodKey, slot);
        store.checkpoint(server);
        Optional<Active> replacement = activeFor(server, player).stream()
                .filter(a -> a.period() == period && a.slot() == slot).findFirst();
        return "Rerolled: " + replacement.map(a -> a.template().displayName() + " - " + a.template().description())
                .orElse("a new contract") + ". Progress on the old one is gone.";
    }

    // ---- words
    // -------------------------------------------------------------------------------------------------------

    /** The lines of {@code /tower contracts}. */
    public static List<String> describe(MinecraftServer server, UUID player) {
        List<String> lines = new ArrayList<>();
        Period shown = null;
        for (Active contract : activeFor(server, player)) {
            if (contract.period() != shown) {
                shown = contract.period();
                lines.add(shown == Period.DAILY ? "Daily contracts" : "Weekly contracts");
            }
            lines.add("  " + (contract.slot() + 1) + ". " + contract.template().displayName() + " - "
                    + contract.template().description() + " [" + contract.count() + "/" + contract.template().condition().count() + "]"
                    + (contract.done() ? " DONE" : "") + "  (+" + contract.template().reward() + " Tower Tokens)");
        }
        if (lines.isEmpty()) lines.add("There are no contracts on this server.");
        return lines;
    }

    /** One line for the login summary: how many contracts are still open, or empty when there are none. */
    public static Optional<String> summary(MinecraftServer server, UUID player) {
        List<Active> active = activeFor(server, player);
        if (active.isEmpty()) return Optional.empty();
        long open = active.stream().filter(a -> !a.done()).count();
        return Optional.of(open == 0 ? "All contracts complete." : open + " contract(s) open: see the Hall");
    }
}
