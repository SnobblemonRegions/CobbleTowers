package com.cobbletowers.persistence;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/**
 * Why a cell is out of circulation and since when. The reason is a message to an operator about what was left in it. {@code manual} marks a cell an
 * operator took out of service by hand: only an operator returns it. Every other quarantine comes from the release check and is retried on its own
 * (see {@code QuarantineRecovery}).
 */
public record CellQuarantine(int cell, String reason, long since, boolean manual) {

    public CellQuarantine {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("a quarantine needs a reason");
        if (cell < 0) throw new IllegalArgumentException("cell must be >= 0, got " + cell);
    }

    /** A quarantine the release check made. */
    public CellQuarantine(int cell, String reason, long since) {
        this(cell, reason, since, false);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cell", cell);
        tag.putString("reason", reason);
        tag.putLong("since", since);
        if (manual) tag.putBoolean("manual", true);
        return tag;
    }

    /** A file written before {@code manual} existed reads as automatic: every quarantine then came from the release check or the start-up index. */
    public static CellQuarantine fromTag(CompoundTag tag) {
        return new CellQuarantine(tag.getInt("cell"), tag.getString("reason"), tag.getLong("since"), tag.getBoolean("manual"));
    }
}
