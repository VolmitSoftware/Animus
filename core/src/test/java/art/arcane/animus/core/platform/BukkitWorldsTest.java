package art.arcane.animus.core.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitWorldsTest {
    private static final UUID MINING = UUID.fromString("1f3c1b0e-5d7a-4a2f-9a0c-111111111111");
    private static final List<BukkitWorlds.Loaded> LOADED = List.of(
            new BukkitWorlds.Loaded("world", UUID.randomUUID(), "minecraft:overworld"),
            new BukkitWorlds.Loaded("world_nether", UUID.randomUUID(), "minecraft:the_nether"),
            new BukkitWorlds.Loaded("world_the_end", UUID.randomUUID(), "minecraft:the_end"),
            new BukkitWorlds.Loaded("Mining", MINING, "minecraft:mining"));

    @Test void worldNamesResolveToTheLoadedNameIgnoringCase() {
        assertEquals(Optional.of("world"), BukkitWorlds.resolve("world", LOADED));
        assertEquals(Optional.of("world_nether"), BukkitWorlds.resolve("world_nether", LOADED));
        assertEquals(Optional.of("Mining"), BukkitWorlds.resolve("mining", LOADED));
        assertEquals(Optional.empty(), BukkitWorlds.resolve("missing", LOADED));
    }

    @Test void dimensionKeysAndUuidsResolveToTheWorldName() {
        assertEquals(Optional.of("world"), BukkitWorlds.resolve("minecraft:overworld", LOADED));
        assertEquals(Optional.of("world_nether"), BukkitWorlds.resolve("minecraft:the_nether", LOADED));
        assertEquals(Optional.of("world_the_end"), BukkitWorlds.resolve("minecraft:the_end", LOADED));
        assertEquals(Optional.of("Mining"), BukkitWorlds.resolve("minecraft:mining", LOADED));
        assertEquals(Optional.of("Mining"), BukkitWorlds.resolve(MINING.toString(), LOADED));
        assertEquals(Optional.empty(), BukkitWorlds.resolve("example:missing", LOADED));
    }

    @Test void vanillaKeysFindTheMainWorldAndItsFoldersWhenKeysDiffer() {
        List<BukkitWorlds.Loaded> survival = List.of(new BukkitWorlds.Loaded("survival", UUID.randomUUID(), "minecraft:survival"),
                new BukkitWorlds.Loaded("survival_nether", UUID.randomUUID(), "minecraft:survival_nether"));
        assertEquals(Optional.of("survival"), BukkitWorlds.resolve("minecraft:overworld", survival));
        assertEquals(Optional.of("survival_nether"), BukkitWorlds.resolve("minecraft:the_nether", survival));
        assertEquals(Optional.empty(), BukkitWorlds.resolve("minecraft:the_end", survival));
    }

    @Test void unusableReferencesResolveToNothing() {
        assertEquals(Optional.empty(), BukkitWorlds.resolve("", LOADED));
        assertEquals(Optional.empty(), BukkitWorlds.resolve(null, LOADED));
        assertEquals(Optional.empty(), BukkitWorlds.resolve("world", List.of()));
    }
}
