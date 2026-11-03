package com.cobbletowers.trick;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.VendorEffect;
import com.cobbletowers.echo.EchoDuels;
import com.cobbletowers.encounter.TowerEncounters;
import com.cobbletowers.halloween.vault.VaultEvent;
import com.cobbletowers.halloween.vault.VaultFacing;
import com.cobbletowers.halloween.vault.VaultMachine;
import com.cobbletowers.halloween.vault.VaultService;
import com.cobbletowers.intermission.IntermissionEvents;
import com.cobbletowers.battle.cobblemon.CobblemonBattleAdapter;
import com.cobbletowers.persistence.LedgerEntry;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.persistence.TowerWalletStore;
import com.cobbletowers.route.TowerDoors;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Trick or Treat vault of an event tower (P54, P55). The party votes to open the vault in its room; the coin ({@link TrickRules#roll}) is read from the
 * run seed, the Jack-o'-Lantern Vault is raised on its dais and opens as that outcome, and the vault's own events do the rest:
 * <ul>
 *   <li>a <b>treat</b> heals the party and pays each player a purse, now and then with a relic;</li>
 *   <li>a <b>trick</b> is a mimic: each fighter faces one opponent from the floor's own pool as an exhibition. The cost of losing is taken <i>first</i>
 *       (a relic if the run holds one, and a ledger mark that cuts 5% of the loot banked from then on) and given back if every fighter wins, so a
 *       restart, a disconnect or an abandoned fight can only ever cost the party, never pay it.</li>
 * </ul>
 * Everything but the vault's pictures is on the server thread. The state kept here is in memory only and is forgotten with the intermission.
 */
public final class TrickOrTreatService {

    static final ResourceLocation VAULT_ID = CobbleTowers.id("halloween/jack_o_lantern_vault");

    /** One opened vault: which run and floor it belongs to, what it holds, and the server that raised it. */
    private record Active(MinecraftServer server, UUID runId, int floor, UUID vault, TrickRules.Result result) {}

    /** A mimic fight in progress: who has not finished, whether anyone lost, and what the loss already took. */
    private static final class Fight {
        final int floor;
        final Set<UUID> waiting = new HashSet<>();
        final Optional<ResourceLocation> relicTaken;
        boolean anyLost;

        Fight(int floor, Optional<ResourceLocation> relicTaken) {
            this.floor = floor;
            this.relicTaken = relicTaken;
        }
    }

    private static final Map<UUID, Active> BY_VAULT = new HashMap<>();
    private static final Map<UUID, Active> BY_RUN = new HashMap<>();
    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
    /** The closed vault standing in a room while its party votes, by run; clicking it casts the vote to open it. */
    private static final Map<UUID, UUID> SHOWN = new HashMap<>();
    /** Runs whose room has no vault spot (not built), so the closed vault is not retried every second. */
    private static final Set<UUID> NO_SPOT = new HashSet<>();
    private static boolean installed;

    private TrickOrTreatService() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        VaultService.addListener(TrickOrTreatService::onVaultEvent);
        ServerState.onStop(TrickOrTreatService::clear);
    }

    // ---- showing it shut, and the click ----------------------------------------------------------

    /**
     * Called each second while a run is at an intermission: when the open vote is on, raises the closed vault in its room so the party can click it.
     * Idempotent, and never throws (a vault that cannot be raised leaves the pedestals to vote with).
     */
    public static void tend(MinecraftServer server, PersistedRun run) {
        try {
            UUID runId = run.runId();
            if (openCard(run) >= 0) TowerDoors.standTrickDoor(server, run, run.modifiers().lastLane());   // the Hallowbound door on this room too (P57)
            if (SHOWN.containsKey(runId) || BY_RUN.containsKey(runId) || NO_SPOT.contains(runId)) return;
            if (openCard(run) < 0) return;
            Optional<TowerDoors.RoomSpot> spot = TowerDoors.roomSpot(server, run, run.modifiers().lastLane());
            if (spot.isEmpty()) {
                NO_SPOT.add(runId);
                return;
            }
            UUID vault = VaultService.spawn(spot.get().level(), spot.get().vault(), VaultFacing.WEST, player -> click(server, runId, player));
            SHOWN.put(runId, vault);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: showing the Trick or Treat vault failed", run.runId(), ex);
        }
    }

    /** Whether the run's Trick or Treat room must keep its dais: the vault is shown, open or fighting, or its open vote is on. */
    public static boolean holdsRoom(PersistedRun run) {
        UUID id = run.runId();
        return SHOWN.containsKey(id) || BY_RUN.containsKey(id) || FIGHTS.containsKey(id) || openCard(run) >= 0;
    }

    /** The index of the "open it" card in the run's open vote, or -1 when that vote is not on. */
    static int openCard(PersistedRun run) {
        return run.modifiers().draft().filter(draft -> !draft.resolved()).map(draft -> draft.cards().indexOf(IntermissionEvents.Option.TRICK_OPEN.cardId())).orElse(-1);
    }

    /** A click on the shut vault is that player's vote to open it: the majority still decides, so one player cannot commit the party to a mimic. */
    private static void click(MinecraftServer server, UUID runId, ServerPlayer player) {
        Optional<PersistedRun> mine = TowerRuns.forPlayer(player.getUUID());
        String message;
        if (mine.isEmpty() || !mine.get().runId().equals(runId)) {
            message = "This vault belongs to another team.";
        } else if (HauntedRare.active(runId)) {
            message = HauntedRare.throwBy(server, player);   // the rare is out: a click on the vault is a throw
        } else if (BY_RUN.containsKey(runId)) {
            message = "The vault is already open.";
        } else if (openCard(mine.get()) < 0) {
            message = "The vault stays shut.";
        } else {
            message = com.cobbletowers.intermission.IntermissionService.pick(server, player, openCard(mine.get()));
            if ("Vote recorded.".equals(message)) message = "You voted to open the vault.";
        }
        if (!message.isEmpty()) player.displayClientMessage(Component.literal(message), true);
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** The party voted to open the vault of this floor. Idempotent per run. Never throws: a vault that cannot be raised must not stop the vote. */
    public static void open(MinecraftServer server, PersistedRun run, int floorIndex) {
        try {
            openUnguarded(server, run, floorIndex);
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: opening the Trick or Treat vault failed", run.runId(), ex);
        }
    }

    private static void openUnguarded(MinecraftServer server, PersistedRun run, int floorIndex) {
        if (BY_RUN.containsKey(run.runId())) return;
        TrickRules.Result result = TrickRules.roll(run.seed(), floorIndex);
        Optional<TowerDoors.RoomSpot> spot = TowerDoors.roomSpot(server, run, run.modifiers().lastLane());
        if (spot.isEmpty()) {
            // No built room to show it in: still settle the coin, so the vote is never a silent nothing.
            TowerLog.warn("Run {}: no room for the Trick or Treat vault of floor {}; settling {} without it", run.runId(), floorIndex, result);
            Active bare = new Active(server, run.runId(), floorIndex, new UUID(0, 0), result);
            if (result == TrickRules.Result.TREAT) treat(bare); else mimic(bare, null);
            return;
        }
        UUID shown = SHOWN.remove(run.runId());
        UUID vault = shown != null && VaultService.state(shown) != null ? shown : VaultService.spawn(spot.get().level(), spot.get().vault(), VaultFacing.WEST);
        Active active = new Active(server, run.runId(), floorIndex, vault, result);
        BY_VAULT.put(vault, active);
        BY_RUN.put(run.runId(), active);
        VaultService.start(vault, result == TrickRules.Result.TREAT ? VaultMachine.Outcome.TREAT : VaultMachine.Outcome.TRICK);
        TowerLog.info("Run {} opened the Jack-o'-Lantern Vault of floor {}: {}", run.runId(), floorIndex, result);
    }

    private static void onVaultEvent(UUID vault, VaultEvent event) {
        Active active = BY_VAULT.get(vault);
        if (active == null) return;
        try {
            switch (event.type()) {
                case "reward_trigger" -> treat(active);
                case "encounter_trigger" -> mimic(active, vault);
                default -> { }
            }
        } catch (RuntimeException ex) {
            TowerLog.error("Run {}: the Trick or Treat vault event {} failed", active.runId(), event.type(), ex);
        }
    }

    // ---- a treat ---------------------------------------------------------------------------------

    private static void treat(Active active) {
        MinecraftServer server = active.server();
        Optional<PersistedRun> found = TowerRuns.get(active.runId());
        if (found.isEmpty()) return;
        PersistedRun run = found.get();
        int purse = IntermissionEvents.vaultPurse(active.floor());
        String relicLine = "";
        // the run is saved first: a crash between the two leaves a free relic, never a paid-for nothing
        if (TrickRules.treatHasRelic(run.seed(), active.floor())) {
            var relic = IntermissionEvents.treatRelic(TowerDefinitionRegistry.content(), run, active.floor());
            if (relic.isPresent()) {
                RunModifierState next = run.modifiers().withRelic(relic.get().id(), active.floor() + 1);
                TowerRuns.save(server, run.withModifiers(next, System.currentTimeMillis()), true);
                relicLine = " and " + relic.get().displayName();
            }
        }
        creditEach(server, run, purse);
        int candy = queueCandyCorn(server, run, active.floor());
        if (candy > 0) relicLine += " and " + candy + " Candy Corn";
        TowerLog.info("Run {} treat on floor {}: {} CobbleDollars each{}", run.runId(), active.floor(), purse, relicLine);
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            com.cobbletowers.economy.VendorServices.apply(VendorEffect.FULL_HEAL, player);
            player.sendSystemMessage(Component.literal("A treat! The vault pays " + purse + " CobbleDollars" + relicLine + " and the party is healed."));
        }
        HauntedRare.offer(server, run, active.floor());
    }

    // ---- a trick ---------------------------------------------------------------------------------

    private static void mimic(Active active, UUID vault) {
        MinecraftServer server = active.server();
        Optional<PersistedRun> found = TowerRuns.get(active.runId());
        if (found.isEmpty() || FIGHTS.containsKey(active.runId())) return;
        PersistedRun run = found.get();
        int floor = active.floor();
        // The price is taken before the fight and given back on a win.
        Optional<ResourceLocation> relic = TrickRules.relicLost(run.modifiers().relics(), run.seed(), floor);
        RunModifierState held = run.modifiers();
        RunModifierState state = relic.isPresent() ? held.withoutRelic(relic.get()) : held;
        long now = System.currentTimeMillis();
        run = run.withModifiers(state, now).withEarned(LedgerEntry.trickLost(floor, VAULT_ID, now), now);
        TowerRuns.save(server, run, true);

        Fight fight = new Fight(floor, relic);
        FIGHTS.put(run.runId(), fight);
        Optional<TowerDoors.RoomSpot> spot = TowerDoors.roomSpot(server, run, run.modifiers().lastLane());
        int ordinal = 0;
        for (PersistedParticipant participant : run.participants()) {
            if (!participant.state().canFight()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            var where = spot.map(TowerDoors.RoomSpot::front).orElse(player.blockPosition());
            if (TowerEncounters.startMimicDuel(server, run, player, ordinal++, where)) {
                EchoDuels.begin(run.runId(), player.getUUID(), EchoDuels.MIMIC);
                fight.waiting.add(player.getUUID());
                player.sendSystemMessage(Component.literal("A MIMIC! The vault was a trap, and it has already taken "
                        + (relic.isPresent() ? "a relic and " : "") + TrickRules.LOSS_CUT_PERCENT + "% of the final loot. Beat it to win them back."));
            }
        }
        TowerLog.info("Run {} mimic on floor {}: {} fighter(s), relic taken {}, loot cut now {}%", run.runId(), floor, fight.waiting.size(),
                relic.map(ResourceLocation::toString).orElse("none"), TrickRules.cutPercent(run.ledger()));
        if (fight.waiting.isEmpty()) {
            // Nobody could be sent into a fight (no opponent to draw, everyone offline): the vault shuts and the price is given back.
            TowerLog.warn("Run {}: the mimic of floor {} could not be summoned; the price is returned", run.runId(), floor);
            settle(server, run.runId(), fight, true);
        }
    }

    /** One fighter's mimic battle ended (called by {@link EchoDuels} for the mimic marker). */
    public static void onMimicResolved(MinecraftServer server, CobblemonBattleAdapter.Binding binding, boolean playerWon) {
        resolve(server, binding.runId(), binding.playerId(), playerWon);
    }

    private static void resolve(MinecraftServer server, UUID runId, UUID playerId, boolean playerWon) {
        Fight fight = FIGHTS.get(runId);
        if (fight == null) return;
        fight.waiting.remove(playerId);
        if (!playerWon) fight.anyLost = true;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal(playerWon ? "You beat the mimic." : "The mimic wins this round."));
        }
        if (fight.waiting.isEmpty()) settle(server, runId, fight, !fight.anyLost);
    }

    /** Whether a mimic fight is under way for this run. */
    public static boolean fighting(UUID runId) {
        return FIGHTS.containsKey(runId);
    }

    /** Operator tool ({@code runs losemimic}): ends each fighter's battle and counts it as lost, so the price stays taken. @return a line, or empty with no fight */
    public static Optional<String> operatorLose(MinecraftServer server, UUID runId) {
        Fight fight = FIGHTS.get(runId);
        if (fight == null) return Optional.empty();
        for (UUID playerId : new ArrayList<>(fight.waiting)) {
            CobblemonBattleAdapter.endPlayer(server, runId, playerId);
            resolve(server, runId, playerId, false);
        }
        return Optional.of("The mimic was lost by the operator.");
    }

    /** Operator tool ({@code runs winfloor} while a mimic is being fought): ends each fighter's battle and counts it as won. @return a line, or empty with no fight */
    public static Optional<String> operatorWin(MinecraftServer server, UUID runId) {
        Fight fight = FIGHTS.get(runId);
        if (fight == null) return Optional.empty();
        for (UUID playerId : new ArrayList<>(fight.waiting)) {
            CobblemonBattleAdapter.endPlayer(server, runId, playerId);   // the battle and its opponent, and the adapter's record of it
            resolve(server, runId, playerId, true);
        }
        return Optional.of("The mimic was beaten by the operator.");
    }

    /** Every fighter has finished: a win everywhere gives the price back and pays half a treat; any loss leaves it taken. */
    private static void settle(MinecraftServer server, UUID runId, Fight fight, boolean won) {
        FIGHTS.remove(runId);
        Optional<PersistedRun> found = TowerRuns.get(runId);
        if (found.isEmpty()) return;
        PersistedRun run = found.get();
        if (!won) {
            TowerLog.info("Run {} lost the mimic of floor {}: relic {} taken, loot cut stays", runId, fight.floor, fight.relicTaken.orElse(null));
            notify(server, run, "The mimic keeps its winnings" + (fight.relicTaken.isPresent() ? ": a relic" : "") + " and 5% of the final loot.");
            return;
        }
        long now = System.currentTimeMillis();
        RunModifierState state = run.modifiers();
        if (fight.relicTaken.isPresent() && state.hasRelicRoom() && !state.relics().contains(fight.relicTaken.get())) {
            state = state.withRelic(fight.relicTaken.get(), fight.floor + 1);
        }
        run = run.withModifiers(state, now).withEarned(LedgerEntry.trickWonBack(fight.floor, VAULT_ID, now), now);
        TowerRuns.save(server, run, true);
        int half = IntermissionEvents.vaultPurse(fight.floor) / 2;
        creditEach(server, run, half);
        TowerLog.info("Run {} beat the mimic of floor {}: the price is returned and each player is paid {}", runId, fight.floor, half);
        notify(server, run, "The mimic is beaten! You keep what it took, and the vault pays " + half + " CobbleDollars each.");
    }

    // ---- housekeeping ----------------------------------------------------------------------------

    /**
     * Queues Candy Corn for every player; the usual loot delivery hands it over (and holds it for /tower claim if the inventory is full).
     * Returns the amount each gets, 0 when SpookySurge is not installed.
     */
    private static int queueCandyCorn(MinecraftServer server, PersistedRun run, int floor) {
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(TrickRules.CANDY_CORN)) return 0;
        int amount = TrickRules.candyCorn(floor);
        var queue = com.cobbletowers.persistence.TowerPendingRewardStore.get(server);
        long now = System.currentTimeMillis();
        for (PersistedParticipant participant : run.participants()) {
            queue.add(participant.playerId(), new com.cobbletowers.persistence.PendingTowerReward(run.runId(), Math.max(1, floor), TrickRules.CANDY_CORN, amount, now));
        }
        queue.checkpoint(server);
        return amount;
    }

    private static void creditEach(MinecraftServer server, PersistedRun run, int amount) {
        if (amount <= 0) return;
        TowerWalletStore wallets = TowerWalletStore.get(server);
        for (PersistedParticipant participant : run.participants()) wallets.credit(participant.playerId(), amount);
        wallets.checkpoint(server);
    }

    private static void notify(MinecraftServer server, PersistedRun run, String line) {
        for (PersistedParticipant participant : run.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) player.sendSystemMessage(Component.literal(line));
        }
    }

    /** The intermission is over: the vault is taken down and nothing about it is kept. A fight still running is left to the battle's own cancel. */
    public static void forget(UUID runId) {
        Active active = BY_RUN.remove(runId);
        if (active != null) {
            BY_VAULT.remove(active.vault());
            VaultService.remove(active.vault());
        }
        UUID shown = SHOWN.remove(runId);
        if (shown != null) VaultService.remove(shown);
        NO_SPOT.remove(runId);
        FIGHTS.remove(runId);
        HauntedRare.forget(runId);
    }

    public static void clear() {
        SHOWN.clear();
        NO_SPOT.clear();
        BY_VAULT.clear();
        BY_RUN.clear();
        FIGHTS.clear();
        HauntedRare.clear();
    }

    /** For tests and the operator: the runs with an open vault. */
    public static List<UUID> openRuns() {
        return new ArrayList<>(BY_RUN.keySet());
    }
}
