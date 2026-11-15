package com.cobbletowers.instance;

import com.cobbletowers.TowerLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A building cut into one small template per chunk column it covers once pasted, so a sliced build pastes a column by
 * pasting that column's own template.
 *
 * <p>Why: {@code StructureTemplate.placeInWorld} processes every block of the template on every call and only then
 * drops those outside the bounding box it was given. Pasting a building chunk by chunk with one big template therefore
 * costs the whole building per step: 20 to 60 ms for Neutral's acts, and 60 to 350 ms for the Tideforge cathedral
 * (650k to 1M blocks, rig 2, 14 Nov), each one a hitch every player on the server feels. A column's own template costs
 * only that column.
 *
 * <p>The cut goes through the template's own NBT (save, split the block list, load each part), so it uses only the
 * public API. It runs off the server thread and is cached per structure; until it is ready a build pastes from the
 * whole template as before. A template reloaded by {@code /reload} is a new object, so its old pieces are not reused.
 */
final class StructurePieces {

    private record Key(ResourceLocation id, int offsetX, int offsetZ) {}

    private record Entry(StructureTemplate source, CompletableFuture<Map<Long, StructureTemplate>> pieces) {}

    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    private static final ExecutorService SPLITTER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CobbleTowers structure splitter");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private StructurePieces() {}

    /**
     * The pieces of {@code template} as pasted at {@code origin}, keyed by {@link #index}; empty while they are still
     * being cut (the first call starts the cut) or if cutting failed.
     */
    static Optional<Map<Long, StructureTemplate>> of(ResourceLocation id, StructureTemplate template, BlockPos origin) {
        Key key = new Key(id, origin.getX() & 15, origin.getZ() & 15);
        Entry entry = CACHE.compute(key, (k, old) -> old != null && old.source() == template ? old
                : new Entry(template, CompletableFuture.supplyAsync(() -> cut(id, template, k.offsetX(), k.offsetZ()), SPLITTER)));
        CompletableFuture<Map<Long, StructureTemplate>> pieces = entry.pieces();
        if (!pieces.isDone()) return Optional.empty();
        if (pieces.isCompletedExceptionally()) {
            TowerLog.errorOnce("pieces:" + id, "Structure {} could not be cut into columns; it is pasted whole", id);
            return Optional.empty();
        }
        return Optional.of(pieces.join());
    }

    /** Starts cutting a structure ahead of its first build (at server start), for a cell's paste origin. */
    static void prepare(ResourceLocation id, StructureTemplate template) {
        of(id, template, CellTickets.pasteOrigin(0, template.getSize().getX(), template.getSize().getZ()));
    }

    /** The key of a piece: the chunk column, counted from the chunk the paste origin is in. */
    static long index(int column, int row) {
        return ((long) column << 32) | (row & 0xffffffffL);
    }

    /**
     * The chunk column (counted from the origin's chunk) a template-local coordinate lands in, when the origin sits
     * {@code offset} blocks into its chunk. Pure.
     */
    static int column(int local, int offset) {
        return Math.floorDiv(local + offset, 16);
    }

    private static Map<Long, StructureTemplate> cut(ResourceLocation id, StructureTemplate template, int offsetX, int offsetZ) {
        long started = System.nanoTime();
        CompoundTag whole = template.save(new CompoundTag());
        ListTag blocks = whole.getList("blocks", Tag.TAG_COMPOUND);
        Map<Long, ListTag> byColumn = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            long key = index(column(pos.getInt(0), offsetX), column(pos.getInt(2), offsetZ));
            byColumn.computeIfAbsent(key, k -> new ListTag()).add(block);
        }
        HolderGetter<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
        Map<Long, StructureTemplate> pieces = new HashMap<>();
        for (Map.Entry<Long, ListTag> column : byColumn.entrySet()) {
            // Everything but the blocks is shared with the whole (size, palette, data version); a piece carries no entities.
            CompoundTag part = new CompoundTag();
            for (String name : whole.getAllKeys()) {
                if (!name.equals("blocks") && !name.equals("entities")) part.put(name, whole.get(name));
            }
            part.put("blocks", column.getValue());
            part.put("entities", new ListTag());
            StructureTemplate piece = new StructureTemplate();
            piece.load(lookup, part);
            pieces.put(column.getKey(), piece);
        }
        TowerLog.info("Structure {} cut into {} column piece(s) ({} blocks) in {} ms, off the server thread", id, pieces.size(),
                blocks.size(), (System.nanoTime() - started) / 1_000_000);
        return Map.copyOf(pieces);
    }
}
