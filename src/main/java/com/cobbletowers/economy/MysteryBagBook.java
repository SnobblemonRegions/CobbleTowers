package com.cobbletowers.economy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The ledger of physical Mystery Bags: which bag ids have been opened (so a duplicated stack opens only the first time) and which have a prize rolled
 * but not yet paid (so a retry pays the same prize, never a fresh roll). Pure: {@link MysteryBagStore} keeps one in the world's saved data.
 */
public final class MysteryBagBook {

    private static final String OPENED = "opened";
    private static final String ROLLED = "rolled";
    private static final String BAG = "bag";
    private static final String MATERIAL = "material";
    private static final String AMOUNT = "amount";

    private final Set<UUID> opened = new LinkedHashSet<>();
    private final Map<UUID, MysteryBag.Prize> rolled = new LinkedHashMap<>();

    public boolean isOpened(UUID bag) {
        return opened.contains(bag);
    }

    /** The prize rolled for this bag and not yet paid, if any. */
    public Optional<MysteryBag.Prize> rolledFor(UUID bag) {
        return Optional.ofNullable(rolled.get(bag));
    }

    /** Settles this bag's prize. A bag that is already open, or already holds a different prize, cannot be rolled again. */
    public void recordRoll(UUID bag, MysteryBag.Prize prize) {
        if (opened.contains(bag)) throw new IllegalStateException("bag " + bag + " is already open");
        MysteryBag.Prize before = rolled.putIfAbsent(bag, prize);
        if (before != null && !before.equals(prize)) throw new IllegalStateException("bag " + bag + " already holds " + before);
    }

    /** The prize was paid: the bag is open for good and its pending prize is forgotten. */
    public void recordOpened(UUID bag) {
        rolled.remove(bag);
        opened.add(bag);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag openedList = new ListTag();
        for (UUID bag : opened) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(BAG, bag);
            openedList.add(entry);
        }
        tag.put(OPENED, openedList);
        ListTag rolledList = new ListTag();
        for (Map.Entry<UUID, MysteryBag.Prize> pending : rolled.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(BAG, pending.getKey());
            entry.putString(MATERIAL, pending.getValue().material());
            entry.putInt(AMOUNT, pending.getValue().amount());
            rolledList.add(entry);
        }
        tag.put(ROLLED, rolledList);
        return tag;
    }

    public static MysteryBagBook fromTag(CompoundTag tag) {
        MysteryBagBook book = new MysteryBagBook();
        ListTag openedList = tag.getList(OPENED, Tag.TAG_COMPOUND);
        for (int i = 0; i < openedList.size(); i++) book.opened.add(openedList.getCompound(i).getUUID(BAG));
        ListTag rolledList = tag.getList(ROLLED, Tag.TAG_COMPOUND);
        for (int i = 0; i < rolledList.size(); i++) {
            CompoundTag entry = rolledList.getCompound(i);
            book.rolled.put(entry.getUUID(BAG), new MysteryBag.Prize(entry.getString(MATERIAL), entry.getInt(AMOUNT)));
        }
        return book;
    }
}
