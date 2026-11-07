package art.arcane.animus.core.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftIdsTest {
    @Test void bareIdsGainTheMinecraftNamespace() {
        assertEquals("minecraft:netherite_axe", MinecraftIds.normalize("netherite_axe"));
        assertEquals("minecraft:block.note_block.pling", MinecraftIds.normalize("block.note_block.pling"));
    }

    @Test void namespacedIdsAreKept() {
        assertEquals("minecraft:netherite_axe", MinecraftIds.normalize("minecraft:netherite_axe"));
        assertEquals("example:ruby", MinecraftIds.normalize("example:ruby"));
    }

    @Test void idsAreTrimmedAndLowercased() {
        assertEquals("minecraft:netherite_axe", MinecraftIds.normalize("  Netherite_Axe "));
        assertEquals("example:ruby", MinecraftIds.normalize("Example:Ruby"));
        assertEquals("", MinecraftIds.normalize("  "));
        assertEquals("", MinecraftIds.normalize(null));
    }

    @Test void validAcceptsBareAndNamespacedForms() {
        assertTrue(MinecraftIds.valid("netherite_axe"));
        assertTrue(MinecraftIds.valid("minecraft:netherite_axe"));
        assertTrue(MinecraftIds.valid("example:items/ruby"));
        assertFalse(MinecraftIds.valid(null));
        assertFalse(MinecraftIds.valid(""));
        assertFalse(MinecraftIds.valid("   "));
        assertFalse(MinecraftIds.valid(":stone"));
        assertFalse(MinecraftIds.valid("minecraft:"));
        assertFalse(MinecraftIds.valid("a:b:c"));
        assertFalse(MinecraftIds.valid("stone block"));
        assertFalse(MinecraftIds.valid("Netherite_Axe"), "ids are written in lowercase");
        assertFalse(MinecraftIds.valid(" stone"));
        assertFalse(MinecraftIds.valid("x".repeat(129)));
    }

    @Test void schemaPatternMatchesTheSameForms() {
        assertTrue("netherite_axe".matches(MinecraftIds.PATTERN));
        assertTrue("minecraft:netherite_axe".matches(MinecraftIds.PATTERN));
        assertFalse(":stone".matches(MinecraftIds.PATTERN));
        assertFalse("a:b:c".matches(MinecraftIds.PATTERN));
    }
}
