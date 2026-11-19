package art.arcane.animus.core.recording;

import art.arcane.animus.core.platform.BukkitWorlds;
import art.arcane.animus.core.platform.MinecraftIds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingWorldsTest {
    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final List<BukkitWorlds.Loaded> LOADED = List.of(new BukkitWorlds.Loaded("world", MAIN, MinecraftIds.OVERWORLD),
            new BukkitWorlds.Loaded("world_nether", NETHER, MinecraftIds.NETHER));
    /** As {@code BukkitRuntimeHost.resolveWorld}: any accepted reference to the loaded world's UUID. */
    private static final Function<String, Optional<String>> BUKKIT = (String reference) -> BukkitWorlds.resolve(reference, LOADED)
            .flatMap((String name) -> LOADED.stream().filter((BukkitWorlds.Loaded world) -> world.name().equals(name)).findFirst())
            .map((BukkitWorlds.Loaded world) -> world.uid().toString());
    /** As {@code VanillaRuntimeHost.resolveWorld} on level {@code world} with two dimensions: a folder name or a key to the dimension key. */
    private static final Map<String, String> MOD_WORLDS = Map.of("world", MinecraftIds.OVERWORLD, MinecraftIds.OVERWORLD, MinecraftIds.OVERWORLD,
            "world_nether", MinecraftIds.NETHER, MinecraftIds.NETHER, MinecraftIds.NETHER);
    private static final Function<String, Optional<String>> MOD = (String reference) -> Optional.ofNullable(MOD_WORLDS.get(reference));

    private static RecordingDocument madeIn(String world) {
        List<RecordingFrame> frames = List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, RecordingFrame.ON_GROUND), new RecordingFrame(40, 1, 0, 0, 0, 0, RecordingFrame.ON_GROUND));
        return new RecordingDocument(1, "walk", MAIN, world, new RecordingDocument.Origin(0.5, 64, 0.5, 0, 0), 2, 40, frames, List.of());
    }

    @Test void theNameTheDimensionKeyAndTheUuidAllPlayInTheirWorldOnBukkit() {
        for (String reference : List.of("world", MinecraftIds.OVERWORLD, MAIN.toString()))
            assertEquals(Optional.empty(), RecordingWorlds.refusal(madeIn(reference), MAIN.toString(), BUKKIT), reference);
        assertEquals(Optional.empty(), RecordingWorlds.refusal(madeIn(MinecraftIds.NETHER), NETHER.toString(), BUKKIT));
    }

    @Test void theNameAndTheDimensionKeyPlayInTheirWorldOnMods() {
        for (String reference : List.of("world", MinecraftIds.OVERWORLD))
            assertEquals(Optional.empty(), RecordingWorlds.refusal(madeIn(reference), MinecraftIds.OVERWORLD, MOD), reference);
        assertEquals(Optional.empty(), RecordingWorlds.refusal(madeIn("world_nether"), MinecraftIds.NETHER, MOD));
    }

    @Test void anotherWorldIsRefused() {
        assertEquals(Optional.of("recording walk was made in world world_nether, not the NPC's world"),
                RecordingWorlds.refusal(madeIn("world_nether"), MAIN.toString(), BUKKIT));
        assertTrue(RecordingWorlds.refusal(madeIn(MinecraftIds.NETHER), MinecraftIds.OVERWORLD, MOD).isPresent());
    }

    @Test void aWorldTheServerDoesNotHaveIsRefused() {
        assertEquals(Optional.of("recording walk names world skylands, which is not loaded"), RecordingWorlds.refusal(madeIn("skylands"), MAIN.toString(), BUKKIT));
        assertTrue(RecordingWorlds.refusal(madeIn(UUID.randomUUID().toString()), MAIN.toString(), BUKKIT).isPresent());
        assertTrue(RecordingWorlds.refusal(madeIn(MinecraftIds.END), MinecraftIds.OVERWORLD, MOD).isPresent());
    }

    @Test void sameMatchesWhatTheServerResolvesAndOtherwiseTheTextIgnoringCase() {
        assertTrue(RecordingWorlds.same("world", MAIN.toString(), BUKKIT));
        assertTrue(RecordingWorlds.same(MinecraftIds.NETHER, "world_nether", BUKKIT));
        assertFalse(RecordingWorlds.same("world", "world_nether", BUKKIT));
        assertTrue(RecordingWorlds.same("Skylands", "skylands", BUKKIT));
        assertFalse(RecordingWorlds.same(null, "world", BUKKIT));
    }
}
