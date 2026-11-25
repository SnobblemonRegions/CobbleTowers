package com.cobbletowers.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * The deepest Ascension each player has reached in each tower (P30): per player and tower, outliving any run, and
 * what gates a direct start at a higher Ascension. Only goes up: {@link #record} keeps the larger value.
 */
public final class TowerAscensionStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_ascension";
    private static final String RECORDS = "records";
    private static final String PLAYER_ID = "player";
    private static final String TOWER_ID = "tower";
    private static final String ASCENSION = "ascension";

    private final Map<UUID, Map<ResourceLocation, Integer>> records = new LinkedHashMap<>();

    /** The store for this server. Created empty on a world where nobody has ascended. */
    public static TowerAscensionStore get(MinecraftServer server) {
        return open(server, TowerAscensionStore::new, TowerAscensionStore::load, FILE_ID);
    }

    /** Whether the player has a record in any tower (the Tutorial Tower's "has played before", 25 Nov 2026). */
    public boolean knows(UUID playerId) {
        return records.containsKey(playerId);
    }

    /** The deepest Ascension this player has reached in this tower; 0 for nobody who has not ascended. */
    public int recordOf(UUID playerId, ResourceLocation towerId) {
        return records.getOrDefault(playerId, Map.of()).getOrDefault(towerId, 0);
    }

    /**
     * Raises the player's record to {@code ascension} if that is deeper than it. Returns whether it was a new record.
     */
    public boolean record(UUID playerId, ResourceLocation towerId, int ascension) {
        if (ascension <= recordOf(playerId, towerId)) return false;
        records.computeIfAbsent(playerId, id -> new LinkedHashMap<>()).put(towerId, ascension);
        setDirty();
        return true;
    }

    static TowerAscensionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerAscensionStore store = new TowerAscensionStore();
        ListTag list = tag.getList(RECORDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation tower = ResourceLocation.tryParse(entry.getString(TOWER_ID));
            if (tower == null) continue;
            store.records.computeIfAbsent(entry.getUUID(PLAYER_ID), id -> new LinkedHashMap<>())
                    .put(tower, entry.getInt(ASCENSION));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Map<ResourceLocation, Integer>> player : records.entrySet()) {
            for (Map.Entry<ResourceLocation, Integer> entry : player.getValue().entrySet()) {
                CompoundTag record = new CompoundTag();
                record.putUUID(PLAYER_ID, player.getKey());
                record.putString(TOWER_ID, entry.getKey().toString());
                record.putInt(ASCENSION, entry.getValue());
                list.add(record);
            }
        }
        tag.put(RECORDS, list);
        return tag;
    }
}
