package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionSpecTest {
    private static final Set<String> TYPES = Set.of("minecraft:player", "minecraft:horse");
    private static final UUID WORLD = UUID.randomUUID();

    @Test void retainsSupportedUnicodeAndSpaceNamesWithoutSanitizingPersistedData() throws Exception {
        var definition = DefinitionQueueTest.definition("guard", "Town Guard Ω", 7);
        var spec = DefinitionSpec.parse(definition, TYPES, ignored -> WORLD);
        assertEquals("Town Guard Ω", spec.name()); assertEquals(WORLD.toString(), spec.position().world());
        assertEquals(definition.serialize(), spec.serialized()); assertSame(definition, spec.definition());
    }

    @Test void activationReportsAllMissingPlatformCapabilitiesAndLeavesDefinitionUnchanged() throws Exception {
        var definition = NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guard",
                 "group":"test","name":"NameLongerThanSixteen","type":"minecraft:unimplemented","spawned":true,
                 "skin":"%s","position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":91}}
                """.formatted("a".repeat(64)));
        List<String> errors = DefinitionSpec.validate(definition, TYPES, ignored -> null);
        assertEquals(4, errors.size());
        for (String field : List.of("$.type", "$.position.world", "$.position.pitch", "$.skin"))
            assertTrue(errors.stream().anyMatch(error -> error.startsWith(field)), field);
        assertEquals("NameLongerThanSixteen", definition.name());
        assertTrue(DefinitionSpec.spawned(definition)); assertFalse(DefinitionSpec.spawned(null));
    }

    @Test void namesLongerThanAProfileActivateAndKeepTheirAppearance() throws Exception {
        var definition = NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guard",
                 "group":"test","name":"Captain of the Northern Watch","type":"minecraft:player","spawned":true,
                 "hologram":{"lines":["Quest"]},"pose":"sneaking",
                 "position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """);
        assertEquals(List.of(), DefinitionSpec.validate(definition, TYPES, ignored -> WORLD));
        var spec = DefinitionSpec.parse(definition, TYPES, ignored -> WORLD);
        assertEquals(List.of("Quest"), spec.appearance().hologram());
        assertEquals(art.arcane.animus.core.npc.NpcPose.SNEAKING, spec.appearance().pose());
        assertTrue(spec.equipment().isEmpty());
    }

    @Test void aSittingBodyCannotAlsoRideAnotherNpc() throws Exception {
        var definition = NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guard",
                 "group":"test","name":"Guard","type":"minecraft:player","spawned":true,"pose":"sitting",
                 "modules":{"animus:mount":{"vehicle":"horse"}},
                 "position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """);
        assertEquals(List.of("$.pose: a sitting NPC cannot also ride another NPC; remove the pose or the mount"), DefinitionSpec.validate(definition, TYPES, ignored -> WORLD));
    }

    @Test void deliveredChunkKeysPreserveNegativeCoordinatesWithoutAliasing() {
        Set<Long> keys = new HashSet<>();
        for (int x : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
            for (int z : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
                long key = ChunkDelivery.key(x, z);
                assertTrue(keys.add(key)); assertEquals(x, (int) key); assertEquals(z, (int) (key >>> 32));
            }
    }

    @Test void settingsCannotExceedHardQueueAndVisibilityCeilings() {
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, Double.NaN, 64, 256, 16));
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, 7, 64, 256, 16));
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, 257, 64, 256, 16));
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, 48, 129, 256, 16));
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, 48, 64, 1025, 16));
        assertThrows(IllegalArgumentException.class, () -> new PersistedNpcService.Settings(true, 48, 64, 256, 65));
    }

    @Test void acceptedTomlBudgetEndpointsConstructRuntimeSettings(@TempDir Path directory) throws Exception {
        try (var store = new DocumentStore(directory)) {
            for (boolean enabled : new boolean[]{false, true})
                for (int distance : new int[]{8, 256})
                    for (int visible : new int[]{1, 128})
                        for (int viewers : new int[]{1, 1024})
                            for (int updates : new int[]{1, 64}) {
                                Files.writeString(directory.resolve("config.toml"), """
                                        statusMessage = "Runtime budget validation"
                                        [npcs]
                                        enabled = %s
                                        viewDistance = %d
                                        maximumVisible = %d
                                        maximumViewers = %d
                                        updatesPerTick = %d
                                        """.formatted(enabled, distance, visible, viewers, updates));
                                var config = PluginSettings.load(store).npcs();
                                var runtime = new PersistedNpcService.Settings(config.enabled(), config.viewDistance(),
                                        config.maximumVisible(), config.maximumViewers(), config.updatesPerTick());
                                assertEquals(enabled, runtime.enabled());
                                assertEquals(distance, runtime.viewDistance());
                                assertEquals(visible, runtime.maximumVisible());
                                assertEquals(viewers, runtime.maximumViewers());
                                assertEquals(updates, runtime.updatesPerTick());
                            }
        }
    }
}
