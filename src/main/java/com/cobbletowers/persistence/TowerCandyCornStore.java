package com.cobbletowers.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

/** How much Candy Corn the tower paid each player today, for the daily cap. Only the current day is kept. */
public final class TowerCandyCornStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_candy_corn";

    private record Day(String key, int paid) {}

    private final Map<UUID, Day> players = new LinkedHashMap<>();

    public static TowerCandyCornStore get(MinecraftServer server) {
        return open(server, TowerCandyCornStore::new, TowerCandyCornStore::load, FILE_ID);
    }

    /** How much this player was paid on the day with this key. */
    public int paidOn(UUID player, String dayKey) {
        Day day = players.get(player);
        return day != null && day.key().equals(dayKey) ? day.paid() : 0;
    }

    /** Records {@code amount} more paid on that day; returns the day's new total. A non-positive amount changes nothing. */
    public int record(UUID player, String dayKey, int amount) {
        if (amount <= 0) return paidOn(player, dayKey);
        int paid = (int) Math.min(Integer.MAX_VALUE, (long) paidOn(player, dayKey) + amount);
        players.put(player, new Day(dayKey, paid));
        setDirty();
        return paid;
    }

    /** An operator's tool, and what a test uses to start clean. */
    public void reset(UUID player) {
        if (players.remove(player) != null) setDirty();
    }

    static TowerCandyCornStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerCandyCornStore store = new TowerCandyCornStore();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            store.players.put(item.getUUID("player"), new Day(item.getString("day"), item.getInt("paid")));
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
            item.putInt("paid", entry.getValue().paid());
            list.add(item);
        }
        tag.put("players", list);
        return tag;
    }
}
