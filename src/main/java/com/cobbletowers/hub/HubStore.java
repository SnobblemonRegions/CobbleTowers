package com.cobbletowers.hub;

import com.cobbletowers.persistence.TowerStore;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Where the Tower Hub was built: its world and the corner it was pasted at. One hub per server; building again moves it. */
public final class HubStore extends TowerStore {

    private ResourceLocation dimension;
    private BlockPos origin;

    public static HubStore get(MinecraftServer server) {
        return open(server, HubStore::new, HubStore::load, "cobbletowers_hub");
    }

    private static HubStore load(CompoundTag tag, HolderLookup.Provider registries) {
        HubStore store = new HubStore();
        if (tag.contains("dimension")) {
            store.dimension = ResourceLocation.parse(tag.getString("dimension"));
            store.origin = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (dimension != null) {
            tag.putString("dimension", dimension.toString());
            tag.putInt("x", origin.getX());
            tag.putInt("y", origin.getY());
            tag.putInt("z", origin.getZ());
        }
        return tag;
    }

    public record Placed(ResourceLocation dimension, BlockPos origin) {}

    public Optional<Placed> placed() {
        return dimension == null ? Optional.empty() : Optional.of(new Placed(dimension, origin));
    }

    public void set(ResourceLocation dimension, BlockPos origin) {
        this.dimension = dimension;
        this.origin = origin.immutable();
        setDirty();
    }

    public void clear() {
        dimension = null;
        origin = null;
        setDirty();
    }
}
