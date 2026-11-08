package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Route legs walked in a movement style in the single-owner runtime: the look viewers get, the speed and the end of the route. */
class StyledRouteRuntimeTest {
    private static final String NPC = "walker";
    private static final UUID ID = UUID.fromString("6c1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c14");
    private static final MotionValidator.Sampler FLAT = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
    @TempDir Path directory;

    private ViewerWatchRaceTest.Harness harness(String type, String movement, String route) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = FLAT;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        harness.transport.presentation = true;
        Files.writeString(npcs.resolve(NPC + ".json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "Walker", "type": "%s", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}, "movement": %s,
                 "modules": {"animus:navigation": {"enabled": true, "speed": 2, "waitTicks": 0, %s}}}
                """.formatted(ID, NPC, type, movement, route));
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }
    private static double x(ViewerWatchRaceTest.Harness harness) { return harness.service.state(ID).orElseThrow().position().point().x(); }
    private static void until(ViewerWatchRaceTest.Harness harness, int limit, Predicate<ViewerWatchRaceTest.Harness> condition, String what) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        for (int i = 0; i < limit; i++) {
            if (condition.test(harness)) return;
            if (harness.service.navigationStatus(NPC).contains("planning") && System.nanoTime() < deadline) {
                try { Thread.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                i--;
            }
            harness.tick(1);
        }
        fail(what + " within " + limit + " ticks: " + harness.service.navigationStatus(NPC));
    }
    private static boolean moving(ViewerWatchRaceTest.Harness harness) { return harness.service.navigationStatus(NPC).contains(": moving;"); }

    @Test void aSneakingNpcCrouchesWalksAtSneakSpeedAndStandsWhenTheRouteEnds() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("minecraft:player", "{\"style\": \"sneak\"}", "\"points\": [{\"x\": 12, \"y\": 64, \"z\": 2}]");
        until(harness, 400, StyledRouteRuntimeTest::moving, "moving");
        double start = x(harness);
        harness.tick(20);
        assertEquals(0.6, x(harness) - start, 0.11, "2 blocks/second at 0.3 of a walk");
        List<BodyLook> looks = harness.transport.looks;
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.SNEAKING), looks.toString());
        until(harness, 2000, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains(": arrived;"), "arrived");
        harness.tick(2);
        assertEquals(NpcPose.STANDING, looks.getLast().pose(), "the definition's pose returns once the route ends");
    }

    @Test void aRoutePointStyleOverridesTheNpcStyle() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("minecraft:player", "{\"style\": \"sneak\"}", "\"points\": [{\"x\": 12, \"y\": 64, \"z\": 2, \"style\": \"sprint\"}]");
        until(harness, 400, StyledRouteRuntimeTest::moving, "moving");
        double start = x(harness);
        harness.tick(20);
        assertEquals(2.6, x(harness) - start, 0.11, "2 blocks/second at 1.3 of a walk");
        assertTrue(harness.transport.looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.STANDING && look.motion() == BodyLook.SPRINTING),
                harness.transport.looks.toString());
        assertFalse(harness.transport.looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.SNEAKING));
    }

    @Test void anotherBodyShowsOnlyTheSneakingFlag() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("minecraft:villager", "{}", "\"style\": \"sneak\", \"points\": [{\"x\": 12, \"y\": 64, \"z\": 2}]");
        until(harness, 400, StyledRouteRuntimeTest::moving, "moving");
        harness.tick(5);
        List<BodyLook> looks = harness.transport.looks;
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.STANDING && look.motion() == BodyLook.SNEAKING), looks.toString());
        assertEquals(0x02, looks.getLast().sharedFlags(false));
    }

    @Test void automaticCrawlTakesAPlayerBodyThroughAGapOneBlockHighAndStandsUpAfter() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("minecraft:player", "{\"auto\": {\"crawl\": true}}", "\"points\": [{\"x\": 12, \"y\": 64, \"z\": 2}]");
        // A tunnel one block high from x=6 to x=8, walled on both sides, so the only way east is through it.
        harness.terrain = (int x, int y, int z) -> y < 64 || (x >= 6 && x <= 8 && (y >= 65 || z != 2)) ? Cell.SOLID : Cell.AIR;
        until(harness, 3000, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains(": arrived;"), "arrived through the gap");
        List<BodyLook> looks = harness.transport.looks;
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.SWIMMING), looks.toString());
        assertEquals(NpcPose.STANDING, looks.getLast().pose(), "standing again in the open");
    }

    @Test void aSwimmingNpcCrossesWaterInTheSwimmingPose() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("minecraft:player", "{\"style\": \"swim\"}", "\"points\": [{\"x\": 12, \"y\": 64, \"z\": 2}]");
        // A channel of water from x=5 to x=9 across the whole width, three deep.
        harness.terrain = (int x, int y, int z) -> x >= 5 && x <= 9 && y >= 61 && y < 64 ? Cell.WATER : y < 64 ? Cell.SOLID : Cell.AIR;
        until(harness, 3000, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains(": arrived;"), "arrived across the water");
        List<BodyLook> looks = harness.transport.looks;
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.SWIMMING && look.motion() == BodyLook.SPRINTING), looks.toString());
        assertEquals(NpcPose.STANDING, looks.getLast().pose());
    }
}
