package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A speech bubble changes the text of a row the body already has: the body is never despawned or spawned again for it. */
class SpeechBubbleTest {
    @TempDir Path directory;

    private ViewerWatchRaceTest.Harness harness(String type, String name) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        Files.writeString(npcs.resolve("talker.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0c41", "id": "talker", "group": "test",
                 "name": "%s", "type": "%s", "spawned": true, "behaviors": ["test:talk"],
                 "position": {"world": "world", "x": 0.5, "y": 64, "z": 2.5, "yaw": 180, "pitch": 0}}
                """.formatted(name, type));
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        runtime.npcs().reload();
        harness.service.refresh();
        assertTrue(harness.service.available(), harness.service.status());
        harness.tick(6);
        assertEquals(1, harness.transport.spawned.get(), "the NPC reached the viewer: " + harness.warnings + " " + harness.service.inspect("talker"));
        return harness;
    }

    private static void speakAndListen(ViewerWatchRaceTest.Harness harness) {
        harness.service.bubble("talker", "&eHello there", 40);
        harness.tick(3);
        assertFalse(harness.transport.relabels.isEmpty(), "the bubble was relabelled in place");
        assertTrue(String.join("\n", harness.transport.relabels.getLast()).contains("Hello there"), harness.transport.relabels.toString());

        harness.service.bubble("talker", "Goodbye", 40);
        harness.tick(3);
        assertTrue(String.join("\n", harness.transport.relabels.getLast()).contains("Goodbye"), harness.transport.relabels.toString());

        harness.tick(50);
        assertFalse(String.join("\n", harness.transport.relabels.getLast()).contains("Goodbye"), "the bubble left when its time ended: " + harness.transport.relabels);
        assertEquals(1, harness.transport.spawned.get(), "the body was never spawned again: " + harness.transport.sent);
        assertFalse(harness.transport.sent.stream().anyMatch((String packet) -> packet.startsWith("despawn")), "the body was never despawned: " + harness.transport.sent);
        assertEquals(List.of(1), harness.transport.relabels.stream().map(List::size).distinct().toList(), "one row throughout");
    }

    @Test
    void aBubbleOverANamedVillagerRelabelsItsRow() throws IOException { speakAndListen(harness("minecraft:villager", "Baker")); }

    @Test
    void aBubbleOverAPlayerWithANativeNameUsesItsReservedRow() throws IOException { speakAndListen(harness("minecraft:player", "Bob")); }
}
