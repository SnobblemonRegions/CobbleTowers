package com.cobbletowers.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

/** How many Haunted rares (P58) each player caught today, for the daily limit. Only the current day is kept. */
public final class TowerRareCatchStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_rare_catches";

    private record Day(String key, int runs) {}

    private final Map<UUID, Day> players = new LinkedHashMap<>();

    public static TowerRareCatchStore get(MinecraftServer server) {
        return open(server, TowerRareCatchStore::new, TowerRareCatchStore::load, FILE_ID);
    }

    /** How many rares this player caught on the day with this key. */
    public int catchesOn(UUID player, String dayKey) {
        Day day = players.get(player);
        return day != null && day.key().equals(dayKey) ? day.runs() : 0;
    }

    /** Records one more catch on that day. */
    public int record(UUID player, String dayKey) {
        int runs = catchesOn(player, dayKey) + 1;
        players.put(player, new Day(dayKey, runs));
        setDirty();
        return runs;
    }

    /** Takes back one catch recorded on that day, for a grant that failed after it was counted. Never below nothing. */
    public int undo(UUID player, String dayKey) {
        int runs = Math.max(0, catchesOn(player, dayKey) - 1);
        if (runs == 0) players.remove(player); else players.put(player, new Day(dayKey, runs));
        setDirty();
        return runs;
    }

    /** An operator's tool, and what a test uses to start clean. */
    public void reset(UUID player) {
        if (players.remove(player) != null) setDirty();
    }

    static TowerRareCatchStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerRareCatchStore store = new TowerRareCatchStore();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            store.players.put(item.getUUID("player"), new Day(item.getString("day"), item.getInt("runs")));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Day> entry : players.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("player", entry.getKey());
            item.putString("day", entry.getValue().key());
            item.putInt("runs", entry.getValue().runs());
            list.add(item);
        }
        tag.put("players", list);
        return tag;
    }
}
