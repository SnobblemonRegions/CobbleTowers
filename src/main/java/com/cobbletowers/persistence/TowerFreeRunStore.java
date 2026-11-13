package com.cobbletowers.persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

/** The trial day on which each player last used their free daily run (the tower key is not needed for that one). */
public final class TowerFreeRunStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_free_runs";
    private static final String ENTRIES = "entries";

    private final Map<UUID, String> lastFreeDay = new HashMap<>();

    public static TowerFreeRunStore get(MinecraftServer server) {
        return open(server, TowerFreeRunStore::new, TowerFreeRunStore::load, FILE_ID);
    }

    /** The day key (such as {@code 2026-10-23}) of the player's last free run, if they have had one. */
    public Optional<String> lastFreeDay(UUID player) {
        return Optional.ofNullable(lastFreeDay.get(player));
    }

    public void markUsed(UUID player, String dayKey) {
        lastFreeDay.put(player, dayKey);
        setDirty();
    }

    /** Gives back a free run taken on {@code dayKey} (its start then failed). */
    public void unmark(UUID player, String dayKey) {
        if (lastFreeDay.remove(player, dayKey)) setDirty();
    }

    static TowerFreeRunStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerFreeRunStore store = new TowerFreeRunStore();
        ListTag list = tag.getList(ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            store.lastFreeDay.put(entry.getUUID("p"), entry.getString("d"));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> entry : lastFreeDay.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("p", entry.getKey());
            item.putString("d", entry.getValue());
            list.add(item);
        }
        tag.put(ENTRIES, list);
        return tag;
    }
}
