package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structure names its blocks by id, and the game pastes an id it does not know as air, silently. Neutral's gallery rail was
 * {@code minecraft:polished_andesite_wall}, which 1.21.1 does not have: 1,274 blocks never appeared, among them every gallery rail in the
 * Neutral halls and the hub's lamp posts (found 28 Nov 2026). Every vanilla id in every structure must be a real block.
 */
class StructureBlocksTest {

    private static final Path STRUCTURES = Path.of("src/main/resources/data/cobbletowers/structure");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("every minecraft: block a structure names exists in this version (an unknown one pastes as air)")
    void everyBlockExists() throws IOException {
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> files = Files.list(STRUCTURES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                TreeSet<String> bad = new TreeSet<>();
                List<ListTag> palettes = new ArrayList<>();
                if (tag.contains("palette", Tag.TAG_LIST)) {
                    palettes.add(tag.getList("palette", Tag.TAG_COMPOUND));
                }
                ListTag many = tag.getList("palettes", Tag.TAG_LIST);
                for (int i = 0; i < many.size(); i++) {
                    palettes.add(many.getList(i));
                }
                for (ListTag palette : palettes) {
                    for (int i = 0; i < palette.size(); i++) {
                        String name = palette.getCompound(i).getString("Name");
                        ResourceLocation id = ResourceLocation.tryParse(name);
                        if (id != null && id.getNamespace().equals("minecraft") && !BuiltInRegistries.BLOCK.containsKey(id)) {
                            bad.add(name);
                        }
                    }
                }
                if (!bad.isEmpty()) {
                    unknown.add(file.getFileName() + " " + bad);
                }
            }
        }
        assertTrue(unknown.isEmpty(), "blocks this version does not have (they paste as air): " + unknown);
    }
}
