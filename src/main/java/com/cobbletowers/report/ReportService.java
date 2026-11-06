package com.cobbletowers.report;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.TowerLog;
import com.cobbletowers.persistence.PersistedParticipant;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.TowerRuns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tower report} (P48): writes what a player says, with the state of their run, to {@code
 * logs/cobbletowers-reports.log}. One report a player every {@value #COOLDOWN_MILLIS} ms so the file cannot be flooded.
 */
public final class ReportService {

    public static final long COOLDOWN_MILLIS = 30_000;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    private ReportService() {}

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("cobbletowers-reports.log");
    }

    /** Files a report and returns what to tell the player. */
    public static String file(ServerPlayer player, String rawText) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return "Please wait " + ((COOLDOWN_MILLIS - (now - last) + 999) / 1000) + " seconds before sending another report.";
        }
        String text = ReportFormat.clean(rawText);
        if (text.isEmpty()) return "Say what happened: /tower report <what went wrong>.";
        LAST.put(player.getUUID(), now);
        // The cooldown map would otherwise hold one entry for every player who ever filed a report: forget the ones whose cooldown is long over.
        if (LAST.size() > 256) LAST.values().removeIf(at -> now - at > COOLDOWN_MILLIS);
        String id = ReportFormat.idOf(now);
        String entry = ReportFormat.format(now, id, player.getGameProfile().getName(), player.getUUID().toString(),
                version(), text, runOf(player.getUUID()));
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            TowerLog.error("Could not write the player report {}", id, ex);
            return "Sorry, the report could not be saved. Please tell a moderator directly.";
        }
        TowerLog.info("Player report {} from {}: {}", id, player.getGameProfile().getName(), text);
        return "Thanks, your report was logged as " + id + ".";
    }

    /** The last {@code count} reports, newest last, as lines (an empty list when there are none). */
    public static List<String> recent(int count) {
        try {
            Path path = file();
            if (!Files.exists(path)) return List.of();
            String[] blocks = Files.readString(path, StandardCharsets.UTF_8).split("\n\n");
            List<String> lines = new ArrayList<>();
            int from = Math.max(0, blocks.length - Math.max(1, count));
            for (int i = from; i < blocks.length; i++) {
                if (blocks[i].isBlank()) continue;
                for (String line : blocks[i].split("\n")) lines.add(line);
                lines.add("");
            }
            return lines;
        } catch (IOException ex) {
            TowerLog.error("Could not read the player reports", ex);
            return List.of();
        }
    }

    private static Optional<ReportFormat.RunFacts> runOf(UUID player) {
        return TowerRuns.forPlayer(player).map(run -> facts(run));
    }

    private static ReportFormat.RunFacts facts(PersistedRun run) {
        List<String> players = new ArrayList<>();
        for (PersistedParticipant participant : run.participants()) players.add(participant.playerId().toString().substring(0, 8));
        return new ReportFormat.RunFacts(run.runId().toString(), run.towerId().toString(), run.floorIndex(), run.state().name(),
                run.options().trial().orElse(""), players, run.seed(), RunHistory.recent(run.runId()));
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer(CobbleTowers.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
}
