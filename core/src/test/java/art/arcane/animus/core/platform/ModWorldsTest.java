package art.arcane.animus.core.platform;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModWorldsTest {
    private static final Set<String> LOADED = Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "example:mining");

    @Test void dimensionKeysResolveToThemselvesWhenLoaded() {
        assertEquals(Optional.of("minecraft:overworld"), ModWorlds.resolve("minecraft:overworld", "world", LOADED));
        assertEquals(Optional.of("example:mining"), ModWorlds.resolve("example:mining", "world", LOADED));
        assertEquals(Optional.empty(), ModWorlds.resolve("example:missing", "world", LOADED));
    }

    @Test void bukkitWorldNamesOfTheLevelResolveToTheirDimension() {
        assertEquals(Optional.of("minecraft:overworld"), ModWorlds.resolve("world", "world", LOADED));
        assertEquals(Optional.of("minecraft:the_nether"), ModWorlds.resolve("world_nether", "world", LOADED));
        assertEquals(Optional.of("minecraft:the_end"), ModWorlds.resolve("world_the_end", "world", LOADED));
        assertEquals(Optional.of("minecraft:overworld"), ModWorlds.resolve("survival", "survival", LOADED));
        assertEquals(Optional.empty(), ModWorlds.resolve("world_nether", "survival", LOADED));
    }

    @Test void bareVanillaPathsNameTheirMinecraftDimension() {
        assertEquals(Optional.of("minecraft:overworld"), ModWorlds.resolve("overworld", "world", LOADED));
        assertEquals(Optional.of("minecraft:the_nether"), ModWorlds.resolve("the_nether", "world", LOADED));
        assertEquals(Optional.empty(), ModWorlds.resolve("mining", "world", LOADED));
    }

    @Test void aliasesAreEveryReferenceThatResolvesToTheKey() {
        assertEquals(Set.of("minecraft:overworld", "overworld", "world"), ModWorlds.aliases("minecraft:overworld", "world"));
        assertEquals(Set.of("minecraft:the_nether", "the_nether", "world_nether"), ModWorlds.aliases("minecraft:the_nether", "world"));
        assertEquals(Set.of("minecraft:the_end", "the_end", "survival_the_end"), ModWorlds.aliases("minecraft:the_end", "survival"));
        assertEquals(Set.of("example:mining"), ModWorlds.aliases("example:mining", "world"));
        for (String key : LOADED) for (String alias : ModWorlds.aliases(key, "world"))
            assertEquals(Optional.of(key), ModWorlds.resolve(alias, "world", LOADED), alias);
    }

    @Test void unusableReferencesResolveToNothing() {
        assertEquals(Optional.empty(), ModWorlds.resolve("1f3c1b0e-5d7a-4a2f-9a0c-111111111111", "world", LOADED));
        assertEquals(Optional.empty(), ModWorlds.resolve("", "world", LOADED));
        assertEquals(Optional.empty(), ModWorlds.resolve("Bad Name!", "world", LOADED));
    }
}
