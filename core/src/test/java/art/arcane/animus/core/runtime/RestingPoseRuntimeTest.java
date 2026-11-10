package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.npc.NpcPose;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A schedule lays an NPC down where it arrives; only bodies that can show the pose take it. */
class RestingPoseRuntimeTest {
    @TempDir Path directory;

    @Test void playerBodiesRestAndOtherBodiesAreRefused() throws Exception {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        try {
            ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
            harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
            for (String[] npc : new String[][]{{"sleeper", "minecraft:player", "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c12"}, {"villager", "minecraft:villager", "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c13"}})
                Files.writeString(npcs.resolve(npc[0] + ".json"), """
                        {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                         "name": "Rester", "type": "%s", "spawned": true,
                         "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}}
                        """.formatted(npc[2], npc[0], npc[1]));
            runtime.npcs().reload();
            harness.service.refresh();
            harness.tick(3);
            harness.service.rest("sleeper", NpcPose.SLEEPING);
            harness.service.rest("sleeper", NpcPose.SITTING);
            harness.service.rest("sleeper", NpcPose.STANDING);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> harness.service.rest("villager", NpcPose.SLEEPING));
            assertEquals("Only player bodies rest sleeping", refused.getMessage());
            harness.service.rest("villager", NpcPose.STANDING);
        } finally { runtime.close(); }
    }
}
