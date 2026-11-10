package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An imported patrol can stand above the ground it walks on (the terrain under a Citizens NPC changed, or it was placed
 * on a different world). A walking body falls to the ground before its first leg instead of failing every leg from the air.
 */
class RouteFootingTest {
    @TempDir Path directory;

    private ViewerWatchRaceTest.Harness patrol(double y, boolean playersAway) throws IOException {
        return patrol(y, playersAway, 64, "", (int x, int y2, int z) -> y2 < 64 ? Cell.SOLID : Cell.AIR);
    }
    private ViewerWatchRaceTest.Harness patrol(double y, boolean playersAway, int pointY, String movement,
                                               art.arcane.animus.core.navigation.MotionValidator.Sampler terrain) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = terrain;
        harness.playersAway = playersAway;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("floating-patrol.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "5b1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c12", "id": "floating-patrol", "group": "test",
                 "name": "Patrol", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": %s, "z": 2.5, "yaw": 0, "pitch": 0},%s
                 "modules": {"animus:navigation": {"enabled": true, "mode": "walk", "speed": 1.72, "order": "sequence",
                   "loop": true, "waitTicks": 0, "points": [{"x": 6.5, "y": %d, "z": 2.5}, {"x": 2.5, "y": %d, "z": 2.5}]}}}
                """.formatted(y, movement, pointY, pointY));
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }

    /**
     * Ticks until {@code done} holds for the navigation status. Path planning runs on its own thread, so a fixed tick
     * count can end before a plan lands on a loaded machine; the wall-clock deadline bounds the wait instead.
     */
    private static String tickUntil(ViewerWatchRaceTest.Harness harness, Predicate<String> done, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            harness.tick(1);
            String navigation = harness.service.navigationStatus("floating-patrol");
            if (done.test(navigation)) return navigation;
            if (System.nanoTime() > deadline) fail("expected " + expected + " within 30 s; navigation: " + navigation);
            try { TimeUnit.MILLISECONDS.sleep(1); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail("interrupted while waiting for " + expected); }
        }
    }

    @Test
    void aPatrolWithNoPlayerNearSleepsAndWakesWhenOneComes() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(64, true);
        tickUntil(harness, (String navigation) -> navigation.contains("sleeping"), "the patrol to sleep");
        assertTrue(harness.service.status().contains("routes-sleeping=1"), harness.service.status());
        harness.playersAway = false;
        tickUntil(harness, (String navigation) -> harness.service.status().contains("routes-sleeping=0"), "the patrol to wake");
        assertTrue(!harness.service.navigationStatus("floating-patrol").contains("sleeping"), harness.service.navigationStatus("floating-patrol"));
    }

    @Test
    void aPatrolStandingInTheAirFallsToTheGroundBeforeItsFirstLeg() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(67, false);
        String navigation = tickUntil(harness, (String status) -> status.contains("position=") && !status.contains("y=67.0, z=2.5]; tick"), "the body to leave the air");
        assertTrue(!navigation.contains("UNREACHABLE"), navigation);
        assertEquals(0, harness.warnings.size(), harness.warnings.toString());
    }

    /** Citizens stores the clicked block, the ground under the feet, so an imported point names y=63 on ground topped at 64. */
    @Test void aPatrolWhoseWaypointsNameTheGroundBlockWalksOnTopOfIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(64, false, 63, "", (int x, int y2, int z) -> y2 < 64 ? Cell.SOLID : Cell.AIR);
        String navigation = tickUntil(harness, (String status) -> status.contains("arrivals=") && !status.contains("arrivals=0"), "an arrival");
        assertEquals(0, harness.warnings.size(), harness.warnings.toString());
        assertTrue(!navigation.contains("arrivals=0"), navigation);
        assertTrue(navigation.matches("(?s).*position=Vec3\\[x=[^,]+, y=64\\.0.*"), "the body stays on the ground: " + navigation);
    }

    /** A stuck leg that teleports puts the body on top of the block its waypoint names, never inside it. */
    @Test void aTeleportToAWaypointNamingTheGroundStandsOnTopOfIt() throws IOException {
        // The first point is fenced in by a ring of tall walls, so its leg can only end by teleporting.
        ViewerWatchRaceTest.Harness harness = patrol(64, false, 63, """

                 "movement": {"stuck": {"afterTicks": 40, "teleport": true}},""",
                (int x, int y2, int z) -> y2 < 64 || (y2 < 68 && Math.max(Math.abs(x - 6), Math.abs(z - 2)) == 1) ? Cell.SOLID : Cell.AIR);
        String navigation = tickUntil(harness, (String status) -> status.contains("arrivals=") && !status.contains("arrivals=0"), "an arrival after the teleport");
        assertTrue(navigation.matches("(?s).*position=Vec3\\[x=[^,]+, y=64\\.0.*"), "the body never ends inside the ground: " + navigation);
        assertTrue(!navigation.contains("arrivals=0"), "the patrol keeps going: " + navigation);
    }
}
