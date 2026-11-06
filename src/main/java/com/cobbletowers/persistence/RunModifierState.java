package com.cobbletowers.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything a run has drafted: carried, locked in and the open draft. One record because they only make sense
 * together. Ids, re-resolved on read (TDS section 10).
 * @param accumulated in draft order, one per copy held
 * @param lockedIn the subset made permanent by a Lock-In Draft (TDS #57); also in {@code accumulated}
 * @param draft the draft on the table, open or just resolved
 * @param relics relics found so far (P34); they sum into effects but are not challenges, so never count toward a
 *     Lock-In
 * @param accumulatedFloors the floor each {@code accumulated} entry first applied to, same order; shorter than the
 *     list (or 0) for an entry taken before this was recorded
 * @param relicFloors the same for {@code relics}
 * @param routeSteps the route taken (P50): pairs of {@code floor, lane}, one per route step settled, in order
 * @param arenas the detours taken (P50 stage 2): {@code "floor=tower id"}, the building a floor is fought in when it is not the run's own
 * @param routeKinds what each route step was, one stop-kind name per {@code routeSteps} pair (shorter, or empty, for steps taken before this was kept)
 */
public record RunModifierState(
        List<ResourceLocation> accumulated,
        List<ResourceLocation> lockedIn,
        Optional<PersistedDraft> draft,
        List<ResourceLocation> relics,
        List<Integer> accumulatedFloors,
        List<Integer> relicFloors,
        List<Integer> routeSteps,
        List<String> arenas,
        List<String> routeKinds) {

    /** Relics a run can hold at once; a milestone offers none past this. */
    public static final int MAX_RELICS = 6;

    /** A run that has drafted nothing. What every run created before P8 is read back as. */
    public static final RunModifierState EMPTY = new RunModifierState(List.of(), List.of(), Optional.empty(), List.of());

    public RunModifierState(List<ResourceLocation> accumulated, List<ResourceLocation> lockedIn,
                            Optional<PersistedDraft> draft) {
        this(accumulated, lockedIn, draft, List.of());
    }

    /** A state that does not know which floors things were taken on. */
    public RunModifierState(List<ResourceLocation> accumulated, List<ResourceLocation> lockedIn,
                            Optional<PersistedDraft> draft, List<ResourceLocation> relics) {
        this(accumulated, lockedIn, draft, relics, List.of(), List.of());
    }

    /** A state with no route taken: every run before P50, and every state a route does not touch. */
    public RunModifierState(List<ResourceLocation> accumulated, List<ResourceLocation> lockedIn, Optional<PersistedDraft> draft,
                            List<ResourceLocation> relics, List<Integer> accumulatedFloors, List<Integer> relicFloors) {
        this(accumulated, lockedIn, draft, relics, accumulatedFloors, relicFloors, List.of());
    }

    /** A state with no stop kinds kept. */
    public RunModifierState(List<ResourceLocation> accumulated, List<ResourceLocation> lockedIn, Optional<PersistedDraft> draft,
                            List<ResourceLocation> relics, List<Integer> accumulatedFloors, List<Integer> relicFloors, List<Integer> routeSteps,
                            List<String> arenas) {
        this(accumulated, lockedIn, draft, relics, accumulatedFloors, relicFloors, routeSteps, arenas, List.of());
    }

    /** A state with no detour taken. */
    public RunModifierState(List<ResourceLocation> accumulated, List<ResourceLocation> lockedIn, Optional<PersistedDraft> draft,
                            List<ResourceLocation> relics, List<Integer> accumulatedFloors, List<Integer> relicFloors, List<Integer> routeSteps) {
        this(accumulated, lockedIn, draft, relics, accumulatedFloors, relicFloors, routeSteps, List.of());
    }

    public RunModifierState {
        arenas = List.copyOf(arenas);
        routeKinds = List.copyOf(routeKinds);
        routeSteps = List.copyOf(routeSteps);
        accumulated = List.copyOf(accumulated);
        lockedIn = List.copyOf(lockedIn);
        relics = List.copyOf(relics);
        accumulatedFloors = List.copyOf(accumulatedFloors);
        relicFloors = List.copyOf(relicFloors);
        Objects.requireNonNull(draft, "draft");
        for (ResourceLocation locked : lockedIn) {
            if (!accumulated.contains(locked)) {
                throw new IllegalArgumentException("locked-in modifier " + locked + " is not accumulated");
            }
        }
    }

    /** How many challenges the run has taken: the number TDS #57 counts to five on. */
    public int challengeCount() {
        return accumulated.size();
    }

    /** Whether a draft is on the table and still unanswered. */
    public boolean hasOpenDraft() {
        return draft.isPresent() && !draft.get().resolved();
    }

    public RunModifierState withDraft(PersistedDraft opened) {
        return new RunModifierState(accumulated, lockedIn, Optional.of(opened), relics, accumulatedFloors, relicFloors, routeSteps, arenas, routeKinds);
    }

    /** The same state with the draft cleared away, e.g. when a run moves on to the next floor. */
    public RunModifierState withoutDraft() {
        return new RunModifierState(accumulated, lockedIn, Optional.empty(), relics, accumulatedFloors, relicFloors, routeSteps, arenas, routeKinds);
    }

    /** The same state having taken {@code modifier} (floor not recorded). */
    public RunModifierState accumulating(ResourceLocation modifier) {
        return accumulating(modifier, 0);
    }

    /** The same state having taken {@code modifier}, which first applies to {@code floor}. */
    public RunModifierState accumulating(ResourceLocation modifier, int floor) {
        List<ResourceLocation> next = new ArrayList<>(accumulated);
        List<Integer> floors = padded(accumulatedFloors, accumulated.size());
        next.add(modifier);
        floors.add(floor);
        return new RunModifierState(next, lockedIn, draft, relics, floors, relicFloors, routeSteps, arenas, routeKinds);
    }

    /** The floor the {@code index}th held modifier first applied to, 0 when it was not recorded. */
    public int modifierFloor(int index) {
        return index >= 0 && index < accumulatedFloors.size() ? accumulatedFloors.get(index) : 0;
    }

    /** The floor the {@code index}th relic first applied to, 0 when it was not recorded. */
    public int relicFloor(int index) {
        return index >= 0 && index < relicFloors.size() ? relicFloors.get(index) : 0;
    }

    private static List<Integer> padded(List<Integer> floors, int size) {
        List<Integer> out = new ArrayList<>(floors);
        while (out.size() < size) out.add(0);
        return out;
    }

    /** The same state with {@code modifier} made permanent; locking in an already locked-in modifier is a no-op. */
    public RunModifierState lockingIn(ResourceLocation modifier) {
        if (lockedIn.contains(modifier)) return this;
        List<ResourceLocation> next = new ArrayList<>(lockedIn);
        next.add(modifier);
        return new RunModifierState(accumulated, next, draft, relics, accumulatedFloors, relicFloors, routeSteps, arenas, routeKinds);
    }

    /** The same state having found {@code relic} (floor not recorded). */
    public RunModifierState withRelic(ResourceLocation relic) {
        return withRelic(relic, 0);
    }

    /** The same state having found {@code relic}, which first applies to {@code floor}. */
    public RunModifierState withRelic(ResourceLocation relic, int floor) {
        List<ResourceLocation> next = new ArrayList<>(relics);
        List<Integer> floors = padded(relicFloors, relics.size());
        next.add(relic);
        floors.add(floor);
        return new RunModifierState(accumulated, lockedIn, draft, next, accumulatedFloors, floors, routeSteps, arenas, routeKinds);
    }

    /** The same state having lost {@code relic} (a lost gamble, P34b). */
    public RunModifierState withoutRelic(ResourceLocation relic) {
        List<ResourceLocation> next = new ArrayList<>(relics);
        List<Integer> floors = padded(relicFloors, relics.size());
        int at = next.indexOf(relic);
        if (at >= 0) {
            next.remove(at);
            floors.remove(at);
        }
        return new RunModifierState(accumulated, lockedIn, draft, next, accumulatedFloors, floors, routeSteps, arenas, routeKinds);
    }

    /** The lane of the last route step the run took, or -1 when it has not taken one (the next step can start anywhere). */
    public int lastLane() {
        return routeSteps.size() >= 2 ? routeSteps.get(routeSteps.size() - 1) : -1;
    }

    /** The lane taken at the step after {@code floor}, or -1 when none was recorded. */
    public int laneAt(int floor) {
        for (int i = routeSteps.size() - 2; i >= 0; i -= 2) if (routeSteps.get(i) == floor) return routeSteps.get(i + 1);
        return -1;
    }

    /** The same state having taken lane {@code lane} at the step after {@code floor}. */
    public RunModifierState withRouteStep(int floor, int lane) {
        return withRouteStep(floor, lane, "");
    }

    /** As above, keeping what the stop was ({@code kind}: a {@code RouteGraph.Kind} name) so the run can be told afterwards. */
    public RunModifierState withRouteStep(int floor, int lane, String kind) {
        List<Integer> next = new ArrayList<>(routeSteps);
        next.add(floor);
        next.add(lane);
        // keep the kinds in step with the pairs: a state from before kinds were kept is padded with blanks
        List<String> nextKinds = new ArrayList<>(routeKinds);
        while (nextKinds.size() < routeSteps.size() / 2) nextKinds.add("");
        nextKinds.add(kind);
        return new RunModifierState(accumulated, lockedIn, draft, relics, accumulatedFloors, relicFloors, next, arenas, nextKinds);
    }

    /** What route step number {@code index} (0 first) was, or empty when it is not known. */
    /** The kind of the stop taken after {@code floor} (a {@code RouteGraph.Kind} name), or blank when no step was recorded for it or it was taken before kinds were kept. */
    public String routeKindAtFloor(int floor) {
        for (int i = routeSteps.size() - 2; i >= 0; i -= 2) {
            if (routeSteps.get(i) == floor) return routeKindAt(i / 2);
        }
        return "";
    }

    public String routeKindAt(int index) {
        return index >= 0 && index < routeKinds.size() ? routeKinds.get(index) : "";
    }

    /** The building floor {@code floor} is fought in when a detour sent the party elsewhere; empty for the run's own tower. */
    public Optional<ResourceLocation> arenaAt(int floor) {
        String prefix = floor + "=";
        for (int i = arenas.size() - 1; i >= 0; i--) {
            if (arenas.get(i).startsWith(prefix)) return Optional.ofNullable(ResourceLocation.tryParse(arenas.get(i).substring(prefix.length())));
        }
        return Optional.empty();
    }

    /** The same state with floor {@code floor} to be fought in the building of {@code tower}. */
    public RunModifierState withArena(int floor, ResourceLocation tower) {
        List<String> next = new ArrayList<>(arenas);
        next.add(floor + "=" + tower);
        return new RunModifierState(accumulated, lockedIn, draft, relics, accumulatedFloors, relicFloors, routeSteps, next, routeKinds);
    }

    /** Whether there is room for another relic. */
    public boolean hasRelicRoom() {
        return relics.size() < MAX_RELICS;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("accumulated", idList(accumulated));
        tag.put("locked_in", idList(lockedIn));
        draft.ifPresent(open -> tag.put("draft", open.toTag()));
        tag.put("relics", idList(relics));
        tag.put("accumulated_floors", new net.minecraft.nbt.IntArrayTag(accumulatedFloors.stream().mapToInt(Integer::intValue).toArray()));
        tag.put("relic_floors", new net.minecraft.nbt.IntArrayTag(relicFloors.stream().mapToInt(Integer::intValue).toArray()));
        if (!routeSteps.isEmpty()) tag.put("route", new net.minecraft.nbt.IntArrayTag(routeSteps.stream().mapToInt(Integer::intValue).toArray()));
        if (!routeKinds.isEmpty()) tag.put("route_kinds", stringList(routeKinds));
        if (!arenas.isEmpty()) {
            ListTag places = new ListTag();
            for (String arena : arenas) places.add(StringTag.valueOf(arena));
            tag.put("arenas", places);
        }
        return tag;
    }

    public static RunModifierState fromTag(CompoundTag tag) {
        return new RunModifierState(
                readIds(tag, "accumulated"),
                readIds(tag, "locked_in"),
                tag.contains("draft", Tag.TAG_COMPOUND)
                        ? Optional.of(PersistedDraft.fromTag(tag.getCompound("draft")))
                        : Optional.empty(),
                readIds(tag, "relics"),
                readFloors(tag, "accumulated_floors"),
                readFloors(tag, "relic_floors"),
                readFloors(tag, "route"),
                readArenas(tag),
                readStrings(tag, "route_kinds"));
    }

    private static List<String> readArenas(CompoundTag tag) {
        return readStrings(tag, "arenas");
    }

    private static List<String> readStrings(CompoundTag tag, String key) {
        List<String> out = new ArrayList<>();
        ListTag stored = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < stored.size(); i++) out.add(stored.getString(i));
        return out;
    }

    private static ListTag stringList(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) list.add(StringTag.valueOf(value));
        return list;
    }

    /** Absent in a run saved before floors were recorded: nothing is known, which reads as 0. */
    private static List<Integer> readFloors(CompoundTag tag, String key) {
        List<Integer> floors = new ArrayList<>();
        for (int floor : tag.getIntArray(key)) floors.add(floor);
        return floors;
    }

    private static ListTag idList(List<ResourceLocation> ids) {
        ListTag list = new ListTag();
        for (ResourceLocation id : ids) list.add(StringTag.valueOf(id.toString()));
        return list;
    }

    private static List<ResourceLocation> readIds(CompoundTag tag, String key) {
        List<ResourceLocation> ids = new ArrayList<>();
        ListTag stored = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < stored.size(); i++) {
            String raw = stored.getString(i);
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) throw new IllegalArgumentException("run holds an invalid modifier id: '" + raw + "'");
            ids.add(id);
        }
        return ids;
    }
}
