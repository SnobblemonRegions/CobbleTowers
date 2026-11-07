package com.cobbletowers.economy;

import com.cobbletowers.persistence.TowerStore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/** The Mystery Bag ledger in the world's saved data: opened bags and pending prizes outlive a restart, so a duplicated bag cannot be opened twice. */
public final class MysteryBagStore extends TowerStore {

    private static final String FILE_ID = "cobbletowers_mystery_bags";

    private final MysteryBagBook book;

    private MysteryBagStore(MysteryBagBook book) {
        this.book = book;
    }

    public static MysteryBagStore get(MinecraftServer server) {
        return open(server, () -> new MysteryBagStore(new MysteryBagBook()),
                (tag, registries) -> new MysteryBagStore(MysteryBagBook.fromTag(tag)), FILE_ID);
    }

    public MysteryBagBook book() {
        return book;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag saved = book.toTag();
        for (String key : saved.getAllKeys()) tag.put(key, saved.get(key));
        return tag;
    }
}
