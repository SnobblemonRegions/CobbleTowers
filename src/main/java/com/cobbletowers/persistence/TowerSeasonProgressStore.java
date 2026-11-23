package com.cobbletowers.persistence;

import com.cobbletowers.season.SeasonPoints.Progress;
import com.cobbletowers.season.SeasonPoints.Source;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

/**
 * Each player's season points, track progress and earned cosmetics (P36b). Points belong to one season (another
 * season's record reads as nothing); cosmetics are permanent.
 */
public final class TowerSeasonProgressStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_season_progress";

    private record Entry(int season, Progress progress) {}

    private final Map<UUID, Entry> progress = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> cosmetics = new LinkedHashMap<>();
    /** The title each player has chosen to wear (P36d); empty or absent means none. */
    private final Map<UUID, String> selectedTitles = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> claims = new LinkedHashMap<>();

    public static TowerSeasonProgressStore get(MinecraftServer server) {
        return open(server, TowerSeasonProgressStore::new, TowerSeasonProgressStore::load, FILE_ID);
    }

    /** The player's tally for {@code season}; empty if they have none, or only one from another season. */
    public Progress of(UUID player, int season) {
        Entry entry = progress.get(player);
        return entry != null && entry.season() == season ? entry.progress() : Progress.EMPTY;
    }

    /** The season's leaders (Records Hall statues, 20 Nov 2026): the {@code n} players with the most points, ties to the earlier entry. */
    public java.util.List<Map.Entry<UUID, Progress>> top(int season, int n) {
        return progress.entrySet().stream()
                .filter(e -> e.getValue().season() == season && e.getValue().progress().total() > 0)
                .sorted(java.util.Comparator.comparingInt((Map.Entry<UUID, Entry> e) -> e.getValue().progress().total()).reversed())
                .limit(n)
                .map(e -> Map.entry(e.getKey(), e.getValue().progress()))
                .toList();
    }

    /** Where a player stands in the season: 1 for first, 0 when they have no points in it. */
    public int rankOf(UUID player, int season) {
        int mine = of(player, season).total();
        if (mine <= 0) return 0;
        int above = 0;
        for (Entry e : progress.values()) if (e.season() == season && e.progress().total() > mine) above++;
        return above + 1;
    }

    /** How many players have points in the season. */
    public int playersIn(int season) {
        int n = 0;
        for (Entry e : progress.values()) if (e.season() == season && e.progress().total() > 0) n++;
        return n;
    }

    public void put(UUID player, int season, Progress next) {
        progress.put(player, new Entry(season, next));
        setDirty();
    }

    /**
     * Which track nodes the player has claimed (P37): keys such as {@code s3:5} (season 3, step 5) and {@code
     * m:cobbletowers:tideforge:10}.
     */
    public boolean claimed(UUID player, String key) {
        return claims.getOrDefault(player, Set.of()).contains(key);
    }

    /** Records a claim; false when it was already recorded. */
    public boolean markClaimed(UUID player, String key) {
        boolean added = claims.computeIfAbsent(player, id -> new TreeSet<>()).add(key);
        if (added) setDirty();
        return added;
    }

    /** Every cosmetic the player has earned, such as {@code s1:banner_1}. */
    public Set<String> cosmeticsOf(UUID player) {
        return Set.copyOf(cosmetics.getOrDefault(player, Set.of()));
    }

    /** Adds cosmetics and returns the ones that were new, so a caller can act on a first award only. */
    public Set<String> addCosmetics(UUID player, Set<String> earned) {
        if (earned.isEmpty()) return Set.of();
        Set<String> held = cosmetics.computeIfAbsent(player, id -> new TreeSet<>());
        Set<String> added = new TreeSet<>();
        for (String name : earned) if (held.add(name)) added.add(name);
        if (!added.isEmpty()) setDirty();
        return added;
    }

    /** The title the player wears, or empty for none. Whether they still own it is the caller's check. */
    public String selectedTitle(UUID player) {
        return selectedTitles.getOrDefault(player, "");
    }

    public void selectTitle(UUID player, String id) {
        if (id == null || id.isEmpty()) selectedTitles.remove(player);
        else selectedTitles.put(player, id);
        setDirty();
    }

    /** An operator's reset, and what a test uses to start clean. */
    public void clear() {
        progress.clear();
        cosmetics.clear();
        selectedTitles.clear();
        claims.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> item : progress.entrySet()) {
            Progress p = item.getValue().progress();
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", item.getKey());
            entry.putInt("season", item.getValue().season());
            entry.putInt("total", p.total());
            entry.putInt("steps", p.steps());
            entry.putString("day", p.day());
            entry.putInt("day_total", p.dayTotal());
            CompoundTag counts = new CompoundTag();
            for (Map.Entry<Source, Integer> count : p.dayCounts().entrySet()) counts.putInt(count.getKey().name(), count.getValue());
            entry.put("day_counts", counts);
            ListTag once = new ListTag();
            for (String key : new TreeSet<>(p.once())) once.add(StringTag.valueOf(key));
            entry.put("once", once);
            list.add(entry);
        }
        tag.put("progress", list);
        ListTag earned = new ListTag();
        for (Map.Entry<UUID, Set<String>> item : cosmetics.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", item.getKey());
            ListTag names = new ListTag();
            for (String name : item.getValue()) names.add(StringTag.valueOf(name));
            entry.put("names", names);
            earned.add(entry);
        }
        tag.put("cosmetics", earned);
        ListTag worn = new ListTag();
        for (Map.Entry<UUID, String> title : selectedTitles.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", title.getKey());
            entry.putString("title", title.getValue());
            worn.add(entry);
        }
        tag.put("selected_titles", worn);
        ListTag claimed = new ListTag();
        for (Map.Entry<UUID, Set<String>> item : claims.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", item.getKey());
            ListTag keys = new ListTag();
            for (String key : item.getValue()) keys.add(StringTag.valueOf(key));
            entry.put("keys", keys);
            claimed.add(entry);
        }
        tag.put("claims", claimed);
        return tag;
    }

    public static TowerSeasonProgressStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerSeasonProgressStore store = new TowerSeasonProgressStore();
        ListTag list = tag.getList("progress", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Map<Source, Integer> counts = new EnumMap<>(Source.class);
            CompoundTag stored = entry.getCompound("day_counts");
            for (Source source : Source.values()) if (stored.contains(source.name())) counts.put(source, stored.getInt(source.name()));
            Set<String> once = new HashSet<>();
            ListTag keys = entry.getList("once", Tag.TAG_STRING);
            for (int j = 0; j < keys.size(); j++) once.add(keys.getString(j));
            store.progress.put(entry.getUUID("player"), new Entry(entry.getInt("season"),
                    new Progress(entry.getInt("total"), entry.getInt("steps"), entry.getString("day"), entry.getInt("day_total"),
                            counts, once)));
        }
        ListTag earned = tag.getList("cosmetics", Tag.TAG_COMPOUND);
        for (int i = 0; i < earned.size(); i++) {
            CompoundTag entry = earned.getCompound(i);
            Set<String> names = new TreeSet<>();
            ListTag stored = entry.getList("names", Tag.TAG_STRING);
            for (int j = 0; j < stored.size(); j++) names.add(stored.getString(j));
            store.cosmetics.put(entry.getUUID("player"), names);
        }
        ListTag worn = tag.getList("selected_titles", Tag.TAG_COMPOUND);
        for (int i = 0; i < worn.size(); i++) {
            store.selectedTitles.put(worn.getCompound(i).getUUID("player"), worn.getCompound(i).getString("title"));
        }
        ListTag claimed = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimed.size(); i++) {
            Set<String> keys = new TreeSet<>();
            ListTag stored = claimed.getCompound(i).getList("keys", Tag.TAG_STRING);
            for (int j = 0; j < stored.size(); j++) keys.add(stored.getString(j));
            store.claims.put(claimed.getCompound(i).getUUID("player"), keys);
        }
        return store;
    }
}
