package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                {"schemaVersion": 1, "revision": 1, "uuid": "5b1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c12", "id": "floating-patrol", "group": "test",
                 "name": "Patrol", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": %s, "z": 2.5, "yaw": 0, "pitch": 0},%s
                 "modules": {"animus:navigation": {"enabled": true, "reviewAccepted": true, "mode": "walk", "speed": 1.72, "order": "sequence",
                   "loop": true, "waitTicks": 0, "points": [{"x": 6.5, "y": %d, "z": 2.5}, {"x": 2.5, "y": %d, "z": 2.5}]}}}
                """.formatted(y, movement, pointY, pointY));
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }

    @Test
    void aPatrolWithNoPlayerNearSleepsAndWakesWhenOneComes() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(64, true);
        harness.tick(80);
        assertTrue(harness.service.navigationStatus("floating-patrol").contains("sleeping"), harness.service.navigationStatus("floating-patrol"));
        assertTrue(harness.service.status().contains("routes-sleeping=1"), harness.service.status());
        harness.playersAway = false;
        harness.tick(80);
        assertTrue(harness.service.status().contains("routes-sleeping=0"), harness.service.status());
        assertTrue(!harness.service.navigationStatus("floating-patrol").contains("sleeping"), harness.service.navigationStatus("floating-patrol"));
    }

    @Test
    void aPatrolStandingInTheAirFallsToTheGroundBeforeItsFirstLeg() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(67, false);
        harness.tick(80);

        String navigation = harness.service.navigationStatus("floating-patrol");
        assertTrue(!navigation.contains("UNREACHABLE"), navigation);
        assertTrue(!navigation.contains("y=67.0, z=2.5]; tick"), "the body left the air: " + navigation);
        assertEquals(0, harness.warnings.size(), harness.warnings.toString());
    }

    /** Citizens stores the clicked block, the ground under the feet, so an imported point names y=63 on ground topped at 64. */
    @Test void aPatrolWhoseWaypointsNameTheGroundBlockWalksOnTopOfIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = patrol(64, false, 63, "", (int x, int y2, int z) -> y2 < 64 ? Cell.SOLID : Cell.AIR);
        harness.tick(200);

        String navigation = harness.service.navigationStatus("floating-patrol");
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
        harness.tick(400);

        String navigation = harness.service.navigationStatus("floating-patrol");
        assertTrue(navigation.matches("(?s).*position=Vec3\\[x=[^,]+, y=64\\.0.*"), "the body never ends inside the ground: " + navigation);
        assertTrue(!navigation.contains("arrivals=0"), "the patrol keeps going: " + navigation);
    }
}
