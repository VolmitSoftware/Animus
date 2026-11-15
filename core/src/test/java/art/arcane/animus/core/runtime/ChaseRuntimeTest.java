package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell.Box;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Combat chases on the navigation planner: slabs and stairs, any distance, and a moving goal planned while walking. */
class ChaseRuntimeTest {
    private static final String NPC = "guard";
    private static final UUID UUID_OF = UUID.fromString("7d1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c14");
    private static final Cell SLAB = Cell.layer(0.5);
    private static final Cell STAIR = Cell.of(List.of(new Box(0, 0, 0, 1, 0.5, 1), new Box(0.5, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    @TempDir Path directory;

    /** Flat ground at 64; from x 10 a slab, then a stair, then a raised floor at 66 to x 20, all along z 0..4. */
    private static final MotionValidator.Sampler STEPS = (int x, int y, int z) -> {
        if (y < 64) return Cell.SOLID;
        if (z < 0 || z > 4) return Cell.AIR;
        if (x == 10 && y == 64) return SLAB;
        if (x == 11 && y == 64) return Cell.SOLID;
        if (x == 12 && y == 65) return STAIR;
        if (x == 12 && y == 64) return Cell.SOLID;
        if (x >= 13 && x <= 20 && y <= 65) return Cell.SOLID;
        return Cell.AIR;
    };

    private ViewerWatchRaceTest.Harness harness(MotionValidator.Sampler terrain) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = terrain;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve(NPC + ".json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test", "name": "Guard", "type": "minecraft:player",
                 "spawned": true, "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}}
                """.formatted(UUID_OF, NPC));
        runtime.npcs().reload();
        harness.service.refresh();
        for (int i = 0; i < 40 && harness.service.state(UUID_OF).map((art.arcane.animus.core.virtual.VirtualNpcState.Snapshot self) -> !self.active()).orElse(true); i++) harness.tick(1);
        return harness;
    }
    private static Vec3 at(ViewerWatchRaceTest.Harness harness) { return harness.service.state(UUID_OF).orElseThrow().position().point(); }
    private static WorldPosition goal(double x, double y, double z) { return new WorldPosition("world", new Vec3(x, y, z)); }
    /** Ticks until the condition holds; a tick that only waits for a plan on the planner threads does not count. */
    private static void until(ViewerWatchRaceTest.Harness harness, int limit, Predicate<ViewerWatchRaceTest.Harness> condition, String what) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        for (int i = 0; i < limit; i++) {
            if (condition.test(harness)) return;
            if (harness.service.navigationStatus(NPC).contains(": planning;") && System.nanoTime() < deadline) {
                try { Thread.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                i--;
            }
            harness.tick(1);
        }
        fail(what + " within " + limit + " ticks: " + harness.service.navigationStatus(NPC));
    }

    @Test void aChaseClimbsTheSlabAndStairsOntoTheRaisedFloor() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(STEPS);
        harness.service.chase(NPC, goal(17.5, 66, 2.5), 4);
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> at(h).x() > 16 && at(h).y() >= 66 - 1e-6, "the guard stands on the raised floor");
    }

    @Test void aGoalFarBeyondOnePlanIsChasedInSegments() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness((int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR);
        harness.service.chase(NPC, goal(62.5, 64, 2.5), 6);
        until(harness, 1200, (ViewerWatchRaceTest.Harness h) -> at(h).x() > 40, "the guard walks past the old thirty block limit");
    }

    @Test void aMovingGoalIsPlannedAgainWhileTheGuardKeepsWalking() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness((int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR);
        UUID first = harness.service.chase(NPC, goal(20.5, 64, 2.5), 4);
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> at(h).x() > 4, "the first walk starts");
        assertEquals(first, harness.service.chase(NPC, goal(20.9, 64, 2.6), 4), "A goal that barely moved keeps its plan");
        int stillTicks = 0;
        double goalX = 20.5, last = at(harness).x();
        for (int round = 0; round < 6; round++) {
            goalX += 2;
            harness.service.chase(NPC, goal(goalX, 64, 2.5), 4);
            // The planner threads get the wall time a server tick would give them, so a busy machine is not counted as standing.
            for (long waited = System.nanoTime(); harness.service.navigationStatus(NPC).contains(": planning;") && System.nanoTime() - waited < 200_000_000L;) {
                try { Thread.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            for (int tick = 0; tick < 10; tick++) {
                harness.tick(1);
                double now = at(harness).x();
                if (now - last < 1e-6) stillTicks++;
                last = now;
            }
        }
        assertTrue(stillTicks <= 6, "the guard walks its last path while each new goal is planned, standing at most a few ticks: " + stillTicks);
        assertTrue(at(harness).x() > 14, "Sixty ticks at four blocks a second cover about twelve: " + harness.service.navigationStatus(NPC));
    }
}
