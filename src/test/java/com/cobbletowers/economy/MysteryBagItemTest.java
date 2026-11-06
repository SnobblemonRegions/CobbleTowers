package com.cobbletowers.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    @DisplayName("a model value is put on the stack, so players with the resource pack see the bag's artwork; without one it stays the plain base item")
    void modelIsApplied() {
        ItemStack withModel = MysteryBagItem.withModel(MysteryBagItem.create(UUID.randomUUID()), 4242);
        assertEquals(4242, withModel.get(DataComponents.CUSTOM_MODEL_DATA).value());
        ItemStack plain = new ItemStack(MysteryBagItem.BASE);
        MysteryBagItem.withModel(plain, 0);
        assertEquals(null, plain.get(DataComponents.CUSTOM_MODEL_DATA), "no Polymer, no model");
    }

    @Test
    @DisplayName("loading the bag item without Polymer on the classpath (a unit test) is harmless")
    void worksWithoutPolymer() {
        MysteryBagItem.bootstrap();
        assertTrue(MysteryBagItem.idOf(MysteryBagItem.create(UUID.randomUUID())).isPresent());
    }

    @Test
    @DisplayName("the artwork ships in the jar: a 16 x 16 PNG, and a model that points at it")
    void theArtworkShips() throws Exception {
        try (var texture = MysteryBagItemTest.class.getResourceAsStream("/assets/cobbletowers/textures/item/mystery_bag.png")) {
            assertNotNull(texture, "textures/item/mystery_bag.png is missing");
            byte[] png = texture.readAllBytes();
            assertEquals(0x89, png[0] & 0xFF, "not a PNG");
            int width = java.nio.ByteBuffer.wrap(png, 16, 4).getInt();
            int height = java.nio.ByteBuffer.wrap(png, 20, 4).getInt();
            assertEquals(16, width);
            assertEquals(16, height);
        }
        try (var model = MysteryBagItemTest.class.getResourceAsStream("/assets/cobbletowers/models/item/mystery_bag.json")) {
            assertNotNull(model, "models/item/mystery_bag.json is missing");
            String json = new String(model.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("cobbletowers:item/mystery_bag"), json);
        }
    }
}
