package com.cobbletowers.persistence;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * How a run is being played beyond its tower (P32), fixed when it is created. An absent block means an ordinary run.
 * @param playlist the playlist (Monotype, Hardcore...), empty for Standard
 * @param trial the trial instance, such as {@code daily:2026-10-05}, empty for ordinary
 * @param floorLimit the floor a trial ends on (0 for none); clearing it completes the run
 * @param scored whether this is the player's one scored trial attempt
 * @param enemyLevelLock every enemy is exactly this level (0 for none)
 * @param entryThreat the Threat band picked at the door, 1-6 (balance phase 2); 0 for a run with no band, which keeps the old floor tiers
 */
public record RunOptions(Optional<ResourceLocation> playlist, Optional<String> trial, int floorLimit, boolean scored,
                         int enemyLevelLock, int entryThreat) {

    public static final RunOptions NONE = new RunOptions(Optional.empty(), Optional.empty(), 0, false, 0, 0);

    /** Without a Threat band: every run made before balance phase 2, and every trial. */
    public RunOptions(Optional<ResourceLocation> playlist, Optional<String> trial, int floorLimit, boolean scored, int enemyLevelLock) {
        this(playlist, trial, floorLimit, scored, enemyLevelLock, 0);
    }

    /** Without a level lock, which is every run that is not a trial. */
    public RunOptions(Optional<ResourceLocation> playlist, Optional<String> trial, int floorLimit, boolean scored) {
        this(playlist, trial, floorLimit, scored, 0, 0);
    }

    public RunOptions {
        Objects.requireNonNull(playlist, "playlist");
        Objects.requireNonNull(trial, "trial");
        if (floorLimit < 0) throw new IllegalArgumentException("floorLimit must be >= 0, got " + floorLimit);
        if (enemyLevelLock < 0 || enemyLevelLock > 100) throw new IllegalArgumentException("enemyLevelLock must be 0..100, got " + enemyLevelLock);
        if (entryThreat < 0 || entryThreat > com.cobbletowers.ascension.ThreatPolicy.MAX_ENTRY)
            throw new IllegalArgumentException("entryThreat must be 0..6, got " + entryThreat);
    }

    /** This run at a Threat band picked at the door (0 for none). */
    public RunOptions withEntryThreat(int band) {
        return new RunOptions(playlist, trial, floorLimit, scored, enemyLevelLock, com.cobbletowers.ascension.ThreatPolicy.clampEntry(band));
    }

    /** An ordinary run under a playlist. */
    public static RunOptions of(Optional<ResourceLocation> playlist) {
        return new RunOptions(playlist, Optional.empty(), 0, false, 0, 0);
    }

    public boolean isTrial() {
        return trial.isPresent();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        playlist.ifPresent(id -> tag.putString("playlist", id.toString()));
        trial.ifPresent(key -> tag.putString("trial", key));
        tag.putInt("floor_limit", floorLimit);
        tag.putBoolean("scored", scored);
        tag.putInt("enemy_level_lock", enemyLevelLock);
        if (entryThreat > 0) tag.putInt("entry_threat", entryThreat);
        return tag;
    }

    public static RunOptions fromTag(CompoundTag tag) {
        Optional<ResourceLocation> playlist = tag.contains("playlist")
                ? Optional.ofNullable(ResourceLocation.tryParse(tag.getString("playlist"))) : Optional.empty();
        Optional<String> trial = tag.contains("trial") ? Optional.of(tag.getString("trial")) : Optional.empty();
        return new RunOptions(playlist, trial, Math.max(0, tag.getInt("floor_limit")), tag.getBoolean("scored"),
                Math.max(0, Math.min(100, tag.getInt("enemy_level_lock"))),
                com.cobbletowers.ascension.ThreatPolicy.clampEntry(tag.getInt("entry_threat")));
    }
}
