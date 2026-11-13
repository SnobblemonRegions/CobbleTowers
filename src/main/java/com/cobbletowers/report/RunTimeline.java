package com.cobbletowers.report;

import com.cobbletowers.api.tower.RunEvent;
import com.cobbletowers.api.tower.RunState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a run's transitions into the gameplay telemetry events that say where players struggle, wait and quit: {@code tower_floor}
 * (each floor fought, how long, cleared or not), {@code tower_arena_wait} (time on "Your arena is being prepared", and whether
 * the party was still there when it was ready), {@code tower_run_parked} (a technical park) and {@code tower_run_end} (the floor a
 * run ended on, why, at which Threat). Minecraft-free and in memory only, so it is tested directly; {@link TowerTelemetry} feeds it
 * and sends what it returns. A run that spans a restart has lost its clocks and its events leave the times out.
 */
public final class RunTimeline {

    /** One event to write: its type and fields, as AscensionLib's {@code AscensionTelemetry.event} takes them. */
    public record Event(String type, Map<String, Object> fields) {}

    /** What a run is, as the events describe it; read from the persisted run at each transition. */
    public record RunFacts(UUID runId, String tower, int floor, int lastBankedFloor, int threat, String kind, List<UUID> players) {}

    private static final class Clock {
        Long startedAt;
        Long floorStartedAt;
        Long waitStartedAt;
        boolean firstWait;
        int floorsCleared;
        long floorMillis;
        int busyRefusals;
        String cause;
        boolean restartPark;
    }

    private final Map<UUID, Clock> clocks = new ConcurrentHashMap<>();

    /** Why the run is about to end, when the transition alone cannot tell (a disconnect and a leave are both ABANDON_REQUESTED). */
    public void noteEndCause(UUID runId, String cause) {
        clock(runId).cause = cause;
    }

    /** The next park of this run comes from a restart ({@code RunRecovery} at boot), not a fault during play. */
    public void notePark(UUID runId, boolean byRestart) {
        clock(runId).restartPark = byRestart;
    }

    /** Whether this run was parked by a restart during this server's life; a run parked before it is not known to have been. */
    public boolean parkedByRestart(UUID runId) {
        Clock clock = clocks.get(runId);
        return clock != null && clock.restartPark;
    }

    /** A run asked for its next arena and has to wait for it to be built (the intermission's "next arena is being prepared"). */
    public void arenaWaitStarted(UUID runId, long now) {
        Clock clock = clock(runId);
        if (clock.waitStartedAt == null) {
            clock.waitStartedAt = now;
            clock.firstWait = false;
        }
    }

    /** An operator's allocation was refused because ended runs still hold cells ({@code Reason.BUSY}); counted into the run's end. */
    public void busyRefusal(UUID runId) {
        clock(runId).busyRefusals++;
    }

    public List<Event> onTransition(RunFacts run, RunState from, RunState to, RunEvent event, long now) {
        List<Event> out = new ArrayList<>(2);
        Clock clock = clock(run.runId());
        if (from == RunState.VALIDATING_PARTY && to == RunState.ALLOCATING_INSTANCE) {
            clock.startedAt = now;
            clock.waitStartedAt = now;
            clock.firstWait = true;
        }
        if (to == RunState.FLOOR_READY && clock.waitStartedAt != null) {
            out.add(arenaWait(run, clock, now, "ready"));
        }
        if (from == RunState.FLOOR_READY && to == RunState.ENCOUNTER_ACTIVE) clock.floorStartedAt = now;
        if (from == RunState.ENCOUNTER_ACTIVE) {
            String result = switch (event) {
                case ENCOUNTER_RESOLVED_CLEARED -> "cleared";
                case ENCOUNTER_RESOLVED_WIPED -> "wiped";
                default -> to.isTerminal() ? "left" : "interrupted";
            };
            out.add(floor(run, clock, now, result));
        }
        if (to == RunState.RECOVERY_REQUIRED) {
            Map<String, Object> f = base(run);
            f.put("from", id(from));
            f.put("event", id(event));
            f.put("cause", clock.restartPark ? "restart" : "technical");
            out.add(new Event("tower_run_parked", f));
        }
        if (to.isTerminal()) {
            if (clock.waitStartedAt != null) out.add(arenaWait(run, clock, now, "ended"));
            out.add(end(run, clock, from, to, event, now));
            clocks.remove(run.runId());
        }
        return out;
    }

    /** Forgets every run (server stopping); nothing is written for them. */
    public void clear() { clocks.clear(); }

    int tracked() { return clocks.size(); }

    private Event arenaWait(RunFacts run, Clock clock, long now, String outcome) {
        Map<String, Object> f = base(run);
        f.put("first", clock.firstWait);
        f.put("ms", Math.max(0L, now - clock.waitStartedAt));
        f.put("outcome", outcome);
        if (clock.busyRefusals > 0) f.put("busyRefusals", clock.busyRefusals);
        clock.waitStartedAt = null;
        return new Event("tower_arena_wait", f);
    }

    private Event floor(RunFacts run, Clock clock, long now, String result) {
        Map<String, Object> f = base(run);
        f.put("result", result);
        if (clock.floorStartedAt != null) {
            long ms = Math.max(0L, now - clock.floorStartedAt);
            f.put("seconds", ms / 1000L);
            if (result.equals("cleared")) clock.floorMillis += ms;
        }
        if (result.equals("cleared")) clock.floorsCleared++;
        f.put("partySize", run.players().size());
        clock.floorStartedAt = null;
        return new Event("tower_floor", f);
    }

    private Event end(RunFacts run, Clock clock, RunState from, RunState to, RunEvent event, long now) {
        Map<String, Object> f = base(run);
        f.put("state", id(to));
        f.put("cause", clock.cause != null ? clock.cause : causeOf(event));
        f.put("from", id(from));
        f.put("lastBankedFloor", run.lastBankedFloor());
        f.put("kind", run.kind());
        f.put("partySize", run.players().size());
        f.put("players", List.copyOf(run.players()));
        if (clock.startedAt != null) f.put("seconds", Math.max(0L, now - clock.startedAt) / 1000L);
        f.put("floorsCleared", clock.floorsCleared);
        if (clock.floorsCleared > 0) f.put("avgFloorSeconds", clock.floorMillis / clock.floorsCleared / 1000L);
        return new Event("tower_run_end", f);
    }

    /** Why a run ended, from the event that ended it, when no caller said more. */
    static String causeOf(RunEvent event) {
        return switch (event) {
            case FINAL_FLOOR_CLEARED -> "completed";
            case ENCOUNTER_RESOLVED_WIPED -> "wiped";
            case CASH_OUT_CHOSEN -> "cashed_out";
            case PARTY_REJECTED -> "party_rejected";
            case RECOVERY_ABANDONED -> "parked_abandoned";
            case TECHNICAL_FAILURE -> "technical";
            case ABANDON_REQUESTED -> "abandoned";
            default -> id(event);
        };
    }

    private static Map<String, Object> base(RunFacts run) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("run", run.runId().toString());
        f.put("tower", run.tower());
        f.put("floor", run.floor());
        f.put("threat", run.threat());
        return f;
    }

    private Clock clock(UUID runId) { return clocks.computeIfAbsent(runId, id -> new Clock()); }

    private static String id(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
