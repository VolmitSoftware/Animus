package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.BodyVariant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VariantWireTest {
    private static BodyVariant of(String field, Map<String, String> choices, Set<String> flags) { return new BodyVariant(field, choices, flags); }

    @Test void skinLayersClearTheirBitsFromEveryLayerShown() {
        assertEquals((byte) 0x7f, VariantWire.skinParts(BodyVariant.NONE));
        assertEquals((byte) (0x7f & ~0x01 & ~0x40), VariantWire.skinParts(of("player", Map.of("cape", "hidden", "hat", "hidden", "jacket", "shown"), Set.of())));
    }

    @Test void woolPacksTheDyeAndShearing() {
        assertEquals(-1, VariantWire.wool(of("sheep", Map.of(), Set.of())));
        assertEquals(14 | 0x10, VariantWire.wool(of("sheep", Map.of("color", "red"), Set.of("sheared"))));
        assertEquals(0x10, VariantWire.wool(of("sheep", Map.of(), Set.of("sheared"))));
    }

    @Test void flagsCombineIntoOneByte() {
        assertEquals(0x01 | 0x04, VariantWire.tameFlags(of("wolf", Map.of("collar", "blue"), Set.of("sitting"))));
        assertEquals(-1, VariantWire.tameFlags(of("wolf", Map.of("variant", "black"), Set.of())));
        assertEquals(0x20 | 0x40, VariantWire.foxFlags(of("fox", Map.of(), Set.of("sleeping", "faceplanted"))));
        assertEquals(0x08 | 0x02, VariantWire.pandaFlags(of("panda", Map.of(), Set.of("sitting", "sneezing"))));
        assertEquals(0x08, VariantWire.beeFlags(of("bee", Map.of(), Set.of("nectar", "angry"))));
    }

    @Test void idsThatAreNotListPositions() {
        assertEquals(99, VariantWire.rabbit(of("rabbit", Map.of("type", "evil"), Set.of())));
        assertEquals(3, VariantWire.rabbit(of("rabbit", Map.of("type", "white_splotched"), Set.of())));
        assertEquals(1 | 3 << 8 | 14 << 16 | 4 << 24, VariantWire.tropicalFish(of("tropicalFish", Map.of("pattern", "blockfish", "baseColor", "red", "patternColor", "yellow"), Set.of())));
        assertEquals(2 << 8, VariantWire.tropicalFish(of("tropicalFish", Map.of("pattern", "snooper"), Set.of())));
        assertEquals(0, VariantWire.pumpkin(of("snowGolem", Map.of("pumpkin", "hidden"), Set.of())));
        assertEquals(5, VariantWire.villagerLevel(of("villager", Map.of("level", "master"), Set.of())));
        assertEquals(1, VariantWire.villagerLevel(of("villager", Map.of(), Set.of())));
    }

    @Test void hangingBodiesFaceSouthByDefaultAndTextPacksItsStyle() {
        assertEquals(3, VariantWire.facing("minecraft:painting", BodyVariant.NONE));
        assertEquals(5, VariantWire.facing("minecraft:item_frame", of("itemFrame", Map.of("facing", "east"), Set.of())));
        assertEquals(0, VariantWire.facing("minecraft:sheep", BodyVariant.NONE));
        assertEquals(0x01 | 0x10, VariantWire.textStyle(of("textDisplay", Map.of("alignment", "right"), Set.of("shadowed"))));
        assertEquals(-1, VariantWire.textStyle(of("textDisplay", Map.of("alignment", "center"), Set.of())));
        assertFalse(VariantWire.living("minecraft:falling_block"));
        assertTrue(VariantWire.living("minecraft:sheep"));
    }
}
