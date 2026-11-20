package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tower dimension keeps the overworld sky, whose clouds are drawn at y 192.33 to 196.33 through blocks. Every cell is built above
 * them (owner, 15 Nov 2026: a cloud hung under the Tideforge dome), and every structure still fits under the dimension's top.
 */
class CellHeightTest {

    private static final double CLOUD_TOP = 192.33 + 4.0;
    private static final Path STRUCTURES = Path.of("src/main/resources/data/cobbletowers/structure");

    @Test
    @DisplayName("the lowest thing a cell builds, its preparation room, stands above the clouds")
    void aboveTheClouds() {
        assertTrue(CellGrid.FLOOR_Y - PrepPlacement.DROP > CLOUD_TOP,
                "prep room at " + (CellGrid.FLOOR_Y - PrepPlacement.DROP) + " is not above the clouds at " + CLOUD_TOP);
    }

    @Test
    @DisplayName("every structure a cell pastes ends under the dimension's top")
    void underTheTop() throws IOException {
        List<String> tooTall = new ArrayList<>();
        try (Stream<Path> files = Files.list(STRUCTURES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                int height = tag.getList("size", 3).getInt(1);
                if (CellGrid.FLOOR_Y + height > CellGrid.MAX_Y) {
                    tooTall.add(file.getFileName() + " (" + height + " high)");
                }
            }
        }
        assertTrue(tooTall.isEmpty(), "too tall for a cell at y " + CellGrid.FLOOR_Y + ": " + tooTall);
    }
}
