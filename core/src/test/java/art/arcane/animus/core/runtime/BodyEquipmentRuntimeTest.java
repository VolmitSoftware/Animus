package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.npc.EquipmentSlot;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A runtime equipment change, such as the set-equipment action, refuses a slot the body cannot show. */
class BodyEquipmentRuntimeTest {
    @TempDir Path directory;

    @Test void aSlotTheBodyCannotShowIsRefusedWithTheBodyAndSlot() throws Exception {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        try {
            ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
            harness.transport.equipment = true;
            harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
            Files.writeString(npcs.resolve("dressed.json"), """
                    {"schemaVersion": 1, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c12", "id": "dressed", "group": "test",
                     "name": "Dressed", "type": "minecraft:player", "spawned": true,
                     "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}}
                    """);
            runtime.npcs().reload();
            harness.service.refresh();
            harness.tick(3);
            harness.service.setEquipment("dressed", EquipmentSlot.HEAD, new Object());
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> harness.service.setEquipment("dressed", EquipmentSlot.SADDLE, new Object()));
            assertEquals("minecraft:player bodies cannot show saddle; they show main_hand, off_hand, head, chest, legs and feet", refused.getMessage());
        } finally { runtime.close(); }
    }
}
