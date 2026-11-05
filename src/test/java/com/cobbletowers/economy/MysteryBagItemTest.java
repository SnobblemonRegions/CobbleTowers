package com.cobbletowers.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The physical Mystery Bag: a plain item that carries its own id, never stacks, and says what it is. */
class MysteryBagItemTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("a bag reads back the id it was made with")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), MysteryBagItem.idOf(MysteryBagItem.create(id)));
    }

    @Test
    @DisplayName("anything that is not a bag has no id, including an empty hand and a plain item of the same kind")
    void notABag() {
        assertEquals(Optional.empty(), MysteryBagItem.idOf(ItemStack.EMPTY));
        assertEquals(Optional.empty(), MysteryBagItem.idOf(new ItemStack(MysteryBagItem.BASE)));
        assertEquals(Optional.empty(), MysteryBagItem.idOf(new ItemStack(Items.DIAMOND)));
    }

    @Test
    @DisplayName("every bag has its own id, so two bags are never the same bag")
    void bagsAreDistinct() {
        assertNotEquals(MysteryBagItem.idOf(MysteryBagItem.create(UUID.randomUUID())), MysteryBagItem.idOf(MysteryBagItem.create(UUID.randomUUID())));
    }

    @Test
    @DisplayName("a bag never stacks with another, and it is a single item")
    void neverStacks() {
        ItemStack bag = MysteryBagItem.create(UUID.randomUUID());
        assertEquals(1, bag.getCount());
        assertEquals(1, bag.getMaxStackSize());
    }

    @Test
    @DisplayName("a bag is named and tells the player how to open it")
    void saysWhatItIs() {
        ItemStack bag = MysteryBagItem.create(UUID.randomUUID());
        assertTrue(bag.get(DataComponents.CUSTOM_NAME).getString().contains("Mystery Bag"));
        String lore = bag.get(DataComponents.LORE).lines().toString();
        assertTrue(lore.contains("Right-click"), lore);
    }

    @Test
    @DisplayName("a bag is recognised from its stack alone, so a copy with the same data is the same bag")
    void aCopyIsTheSameBag() {
        ItemStack bag = MysteryBagItem.create(UUID.randomUUID());
        assertEquals(MysteryBagItem.idOf(bag), MysteryBagItem.idOf(bag.copy()));
    }
}
