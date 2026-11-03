package com.cobbletowers;

import com.cobbletowers.command.DefinitionsCommand;
import com.cobbletowers.command.CellsCommand;
import com.cobbletowers.command.DiagnosticsCommand;
import com.cobbletowers.command.PlayCommand;
import com.cobbletowers.command.RunsCommand;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.encounter.TowerEncounters;
import com.cobbletowers.instance.CellTickets;
import com.cobbletowers.instance.CellWarmPool;
import com.cobbletowers.instance.InstanceAllocator;
import com.cobbletowers.network.TowerNetworking;
import com.cobbletowers.reward.RewardBankService;
import com.cobbletowers.reward.RewardDelivery;
import com.cobbletowers.runtime.RecoverySweep;
import com.cobbletowers.runtime.RunRecovery;
import com.cobbletowers.runtime.TowerPresence;
import com.cobbletowers.runtime.TowerRuns;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;

/** Entrypoint: tower definitions, stored runs and their recovery, and the debug commands. */
public final class CobbleTowers implements ModInitializer {

    public static final String MOD_ID = "cobbletowers";

    /** A {@code cobbletowers:} resource location. */
    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.addModAssets(MOD_ID);

        // The Showdown extension is registered in TowerPreLaunch, not here: by the time this runs Cobblemon's
        // Showdown
        // thread has already unbundled the simulator and CobbleRaids has already written its extension list.

        // Guarded: a failure to register a command must not take the rest of the server's command
        // tree down with it.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            try {
                // First, so the merged /cobbletowers root is operator-only: players never see it, only /tower.
                dispatcher.register(net.minecraft.commands.Commands.literal("cobbletowers")
                        .requires(source -> source.hasPermission(2)));
                DefinitionsCommand.register(dispatcher);
                RunsCommand.register(dispatcher);
                com.cobbletowers.command.ArmorCommand.register(dispatcher);
                com.cobbletowers.command.VaultCommand.register(dispatcher);
                com.cobbletowers.command.TrickDoorCommand.register(dispatcher);
                com.cobbletowers.command.RareCommand.register(dispatcher);
                PlayCommand.register(dispatcher);
                com.cobbletowers.command.MasteryCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.TrialCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.EchoCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.ClubCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.SeasonCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.TrackCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.CosmeticsCommand.registerAdmin(dispatcher);
                com.cobbletowers.command.ReportCommand.registerAdmin(dispatcher);
                CellsCommand.register(dispatcher);
                DiagnosticsCommand.register(dispatcher);
                Dev.registerCommands(dispatcher);
            } catch (RuntimeException ex) {
                TowerLog.error("Could not register the CobbleTowers commands", ex);
            }
        });
        // Loading and recovery are separately guarded so one failure cannot cost the index of the runs that loaded.
        // Also says once which reward items cannot be given (P21).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> com.cobbletowers.reward.RewardCatalogCheck.report());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            long now = System.currentTimeMillis();
            try {
                int loaded = TowerRuns.load(server, now);
                // a rotating trial's tower is built from its id, so a run saved on it finds it again
                for (var run : TowerRuns.all()) com.cobbletowers.definition.TowerDefinitionRegistry.ensureRotating(run.towerId());
                // The cell index is rebuilt from the runs themselves, so there is no second file
                // that could disagree with them about who holds what.
                int leased = InstanceAllocator.rebuild(server, TowerRuns.all());
                if (loaded > 0) {
                    TowerLog.info("Loaded {} tower run(s) from disk, {} holding an instance cell.", loaded, leased);
                }
            } catch (RuntimeException ex) {
                TowerLog.error("Could not load stored tower runs", ex);
                return;
            }
            try {
                RunRecovery.recoverInterruptedRuns(server, now);
            } catch (RuntimeException ex) {
                TowerLog.error("Could not recover interrupted tower runs", ex);
            }
            // A later step: COMPLETED and CASHED_OUT are terminal so RunRecovery never touches them; this recovers a
            // run that crashed between its final checkpoint and its grant (docs/design/P9-economy.md section 4a).
            try {
                RewardBankService.sweepUnbanked(server, now);
            } catch (RuntimeException ex) {
                TowerLog.error("Could not sweep unbanked tower rewards", ex);
            }
            try {
                com.cobbletowers.reward.CardRewardService.sweep(server, now);
            } catch (RuntimeException ex) {
                TowerLog.error("Could not sweep unfinished card rewards", ex);
            }
        });
        ServerState.install();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new TowerDefinitionRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.AchievementRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.PlaylistRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.TrialPoolRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.TrialRecipeRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.SeasonRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.SeasonTrackRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.MasteryTrackRegistry());
        com.cobbletowers.track.TrackConfig.install();
        com.cobbletowers.report.BetaConfig.install();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.ContractTemplateRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.definition.RentalSetRegistry());
        // Armor sets (P24): the items must exist before any datapack loads; what they do is data.
        com.cobbletowers.armor.ArmorSetItems.register();
        com.cobbletowers.armor.worn.WornModelService.install();
        com.cobbletowers.halloween.vault.VaultService.install();
        com.cobbletowers.halloween.door.TrickDoors.bootstrap();
        com.cobbletowers.trick.TrickOrTreatService.install();
        com.cobbletowers.season.SeasonTrimItems.register();
        com.cobbletowers.economy.TowerKeys.install();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new com.cobbletowers.armor.ArmorSetRegistry());
        com.cobbletowers.armor.WornSets.install();
        com.cobbletowers.armor.flare.ArmorFlareService.install();
        com.cobbletowers.armor.ArmorBonusEffects.install();
        com.cobbletowers.armor.ArmorSetSync.install();
        // Subscribed once, for the life of the JVM: Cobblemon's battle events are global, and the
        // adapter filters them by battle id rather than re-subscribing per floor.
        TowerEncounters.install();
        // Retries AscensionLib payouts the wallet has not confirmed (at server start, then every 30 seconds).
        com.cobbletowers.economy.AscensionLibRewards.install();
        // Disconnects, rejoining, and the watchdog that ends a floor nobody is playing any more.
        TowerPresence.install();
        com.cobbletowers.webgui.WebGuiBridge.install();
        com.cobbletowers.lobby.LobbyService.install();
        com.cobbletowers.intermission.IntermissionService.install();
        com.cobbletowers.storage.PartyJournalService.install();
        com.cobbletowers.storage.RentalPartyService.install();
        com.cobbletowers.runtime.RunExitService.install();
        com.cobbletowers.instance.CellWarmPool.install();
        com.cobbletowers.instance.TowerDropGuard.install();
        com.cobbletowers.instance.FaintDropGuard.install();
        com.cobbletowers.vendor.VendorNpc.install();
        com.cobbletowers.intermission.PedestalService.install();
        com.cobbletowers.mastery.MasteryService.install();
        com.cobbletowers.trial.TrialService.install();
        com.cobbletowers.season.Seasons.install();
        com.cobbletowers.season.SeasonService.install();
        com.cobbletowers.season.ChatIntegration.install();
        com.cobbletowers.contract.ContractService.install();
        com.cobbletowers.trial.LoginSummary.install();
        com.cobbletowers.runtime.TowerCommandGuard.install();
        RecoverySweep.install();
        // Hands a player whatever the tower owes them the moment they are somewhere to receive it.
        RewardDelivery.install();
        // Registered on every side that runs "main": a payload must be registered wherever it is encoded or decoded,
        // including an integrated client's server.
        TowerNetworking.registerPayloadTypes();
        TowerNetworking.installServerReceivers();

        TowerLog.info("CobbleTowers {} loaded", version);
    }
}
