package com.cobbletowers.persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

/**
 * Each player's Tutorial Tower (owner, 25 Nov 2026): whether they have been sent into it, and whether they have cleared it. Cleared is
 * what the one-time starter kit is paid against, so it is only ever set once.
 */
public final class TowerTutorialStore extends TowerStore {

    public enum State { STARTED, CLEARED }

    private static final String FILE_ID = "cobbletowers_tutorial";
    private static final String ENTRIES = "entries";

    private final Map<UUID, State> states = new HashMap<>();

    public static TowerTutorialStore get(MinecraftServer server) {
        return open(server, TowerTutorialStore::new, TowerTutorialStore::load, FILE_ID);
    }

    public boolean started(UUID player) {
        return states.containsKey(player);
    }

    public boolean cleared(UUID player) {
        return states.get(player) == State.CLEARED;
    }

    /** Notes that the player has been sent into the tutorial (a clear is never undone). */
    public void markStarted(UUID player) {
        if (states.putIfAbsent(player, State.STARTED) == null) setDirty();
    }

    /** Marks the first clear. True only the one time it changes, so the caller pays the starter kit exactly once. */
    public boolean markCleared(UUID player) {
        if (states.get(player) == State.CLEARED) return false;
        states.put(player, State.CLEARED);
        setDirty();
        return true;
    }

    static TowerTutorialStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerTutorialStore store = new TowerTutorialStore();
        ListTag list = tag.getList(ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                store.states.put(entry.getUUID("p"), State.valueOf(entry.getString("s")));
            } catch (IllegalArgumentException ignored) {
                // an unknown state from a later version: left out rather than guessed
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("p", entry.getKey());
            item.putString("s", entry.getValue().name());
            list.add(item);
        }
        tag.put(ENTRIES, list);
        return tag;
    }
}
