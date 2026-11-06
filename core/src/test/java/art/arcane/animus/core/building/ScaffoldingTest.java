package art.arcane.animus.core.building;

import static art.arcane.animus.core.building.BuildFixtures.WORLD;
import static art.arcane.animus.core.building.BuildFixtures.plan;
import static art.arcane.animus.core.building.BuildFixtures.run;
import static art.arcane.animus.core.building.BuildFixtures.schematic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaffoldingTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final Map<Character, String> LEGEND = Map.of('s', "stone");
    private BuildFixtures.World world;
    private BuildFixtures.Crew crew;
    private BuildFixtures.Events events;
    private BuildProtection.Gate protection;

    @BeforeEach void setUp() {
        world = new BuildFixtures.World();
        crew = new BuildFixtures.Crew();
        events = new BuildFixtures.Events();
        protection = new BuildProtection.Gate(request -> BuildProtection.Decision.ALLOW, message -> { });
        crew.add("builder", .5, 64, -2.5);
        // An NPC walks on the ground, and climbs only where scaffolding holds it.
        crew.walkable = (WorldPosition goal) -> goal.point().y() <= 64 || scaffold(goal, 0) || scaffold(goal, -1);
    }

    private boolean scaffold(WorldPosition goal, int below) {
        BlockPos cell = new BlockPos((int) Math.floor(goal.point().x()), (int) Math.floor(goal.point().y()) + below, (int) Math.floor(goal.point().z()));
        return world.blocks.getOrDefault(cell, "").startsWith(ScaffoldPlanner.BLOCK);
    }
    private static BuildOptions options(String... words) {
        Map<String, String> map = new HashMap<>(Map.of("anchor", "corner", "pattern", "linear", "interval", "1"));
        for (int index = 0; index + 1 < words.length; index += 2) map.put(words[index], words[index + 1]);
        return BuildOptions.DEFAULT.with(map);
    }
    /** A single stone {@code height - 1} blocks above the origin, with nothing under it. */
    private static Schematic floating(int height) {
        String[][] layers = new String[height][];
        Arrays.fill(layers, new String[]{"."});
        layers[height - 1] = new String[]{"s"};
        return schematic(LEGEND, layers);
    }
    private BuildSession session(Schematic schematic, BuildOptions options) {
        BuildSession session = new BuildSession("builder", "builder", WORLD, ORIGIN, "test.schem", "hash", options, Optional.of(UUID.randomUUID()),
                "2026-11-05T00:00:00Z", 10_000, events);
        session.attach(plan(schematic, options, ORIGIN));
        session.bind(world);
        return session;
    }
    private BuildSession.Context context() { return new BuildSession.Context(world, crew, protection, new BuildSession.Budget(64, 4096, 64)); }
    private long scaffoldBlocks() { return world.blocks.values().stream().filter((String state) -> state.startsWith(ScaffoldPlanner.BLOCK)).count(); }
    private int scaffoldEntries(BuildSession session) {
        int count = 0;
        for (int index = 0; index < session.journal().size(); index++) if (session.journal().get(index).scaffold()) count++;
        return count;
    }
    private int tickUntil(BuildSession session, java.util.function.BooleanSupplier done, int limit) {
        int ticks = 0;
        while (!done.getAsBoolean() && ticks++ < limit) session.tick(context());
        return ticks;
    }

    @Test void aBlockOutOfReachIsBuiltFromAScaffoldThatComesDownAfterwards() {
        BuildSession session = session(floating(10), options());
        int[] most = {0};
        boolean[] heldScaffolding = {false};
        int ticks = tickUntil(session, () -> {
            most[0] = Math.max(most[0], session.scaffolding());
            heldScaffolding[0] |= crew.held.getOrDefault("builder", Optional.empty()).equals(Optional.of(ScaffoldPlanner.BLOCK));
            return session.state() != BuildSession.State.RUNNING;
        }, 2000);
        assertTrue(ticks < 2000);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, 73, 0)));
        assertEquals(0, scaffoldBlocks(), "every scaffolding block came down");
        assertEquals(8, most[0], "a column from the ground to one block under the stand");
        assertEquals(16, scaffoldEntries(session));
        assertEquals(0, crew.relocations, "the NPC climbed instead of being moved");
        assertTrue(crew.path.stream().anyMatch((WorldPosition at) -> at.point().y() >= 72));
        assertEquals(64, crew.members.get("builder").position().point().y(), 1e-9, "the NPC climbed back down");
        assertTrue(crew.swings.get("builder") >= 17);
        assertTrue(heldScaffolding[0], "the NPC holds scaffolding while it puts it up");
    }

    @Test void aWallTooHighToReachIsClimbedOnceWalkingStalls() {
        String[][] layers = new String[10][];
        Arrays.fill(layers, new String[]{"ss"});
        BuildSession session = session(schematic(LEGEND, layers), options());
        tickUntil(session, () -> session.state() != BuildSession.State.RUNNING, 6000);
        assertEquals(BuildSession.State.FINISHED, session.state());
        for (int y = 64; y < 74; y++) {
            assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, y, 0)));
            assertEquals("minecraft:stone", world.blocks.get(new BlockPos(1, y, 0)));
        }
        assertEquals(0, scaffoldBlocks());
        assertTrue(scaffoldEntries(session) > 0);
        assertEquals(0, crew.relocations);
    }

    @Test void donatedScaffoldingIsUsedAndHandedBackWhenItComesDown() {
        world.items.put("minecraft:scaffolding", "minecraft:scaffolding");
        BuildSession session = session(floating(10), options("materials", "donate"));
        assertEquals(8L, session.remaining().get(ScaffoldPlanner.BLOCK));
        session.donate("minecraft:stone", 1);
        tickUntil(session, () -> session.state() == BuildSession.State.WAITING, 200);
        assertTrue(session.reason().contains(ScaffoldPlanner.BLOCK), session.reason());
        assertEquals(8, session.donate(ScaffoldPlanner.BLOCK, 64));
        tickUntil(session, () -> session.state() == BuildSession.State.FINISHED, 3000);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals(8, session.ledger().available(ScaffoldPlanner.BLOCK));
        assertEquals(0, session.ledger().available("minecraft:stone"));
        assertEquals(0, scaffoldBlocks());
    }

    @Test void withScaffoldingOffTheOldTimeoutMoveStillReachesTheBlock() {
        crew.walkable = (WorldPosition goal) -> true;
        BuildSession session = session(floating(10), options("scaffolding", "false", "timeout", "1"));
        run(session, context(), 400);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals(0, scaffoldEntries(session));
    }

    @Test void refusedScaffoldingFallsBackToPlacingFromWhereTheNpcStands() {
        protection = new BuildProtection.Gate(request -> request.at().equals(new BlockPos(0, 73, 0)) ? BuildProtection.Decision.ALLOW : BuildProtection.Decision.DENY, message -> { });
        BuildSession session = session(floating(10), options("timeout", "1"));
        tickUntil(session, () -> session.state() != BuildSession.State.RUNNING, 2000);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals(0, scaffoldEntries(session));
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, 73, 0)));
    }

    @Test void pausingTakesTheScaffoldDownAndResumingFinishes() {
        BuildSession session = session(floating(12), options());
        tickUntil(session, () -> session.scaffolding() >= 4, 400);
        session.pause();
        session.stopCrew(crew);
        tickUntil(session, () -> session.scaffolding() == 0, 400);
        assertEquals(BuildSession.State.PAUSED, session.state());
        assertEquals(0, scaffoldBlocks());
        session.resume();
        tickUntil(session, () -> session.state() != BuildSession.State.RUNNING, 3000);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals(0, scaffoldBlocks());
    }

    @Test void scaffoldingSavedWithTheBuildIsTakenDownAfterARestart() {
        BuildSession session = session(floating(12), options());
        tickUntil(session, () -> session.scaffolding() >= 5, 400);
        long standing = scaffoldBlocks();
        assertTrue(standing >= 5);
        BuildSession restored = BuildSession.restore(session.toJson(), session.journal(), events);
        restored.attach(plan(floating(12), options(), ORIGIN));
        assertEquals(standing, restored.scaffolding());
        crew.members.clear();
        tickUntil(restored, () -> restored.scaffolding() == 0, 100);
        assertEquals(0, scaffoldBlocks());
    }

    @Test void shutdownTakesEveryScaffoldDownAtOnce() {
        BuildSession session = session(floating(12), options());
        tickUntil(session, () -> session.scaffolding() >= 5, 400);
        session.dismantle(new BuildSession.Context(world, crew, protection, new BuildSession.Budget(0, 0, 0)));
        assertEquals(0, scaffoldBlocks());
        assertEquals(0, session.scaffolding());
        assertTrue(!session.toJson().has("scaffolding"));
    }

    @Test void undoLeavesNoScaffoldingAndPutsTheWorldBack() {
        BuildSession session = session(floating(10), options());
        tickUntil(session, () -> session.scaffolding() >= 3, 400);
        session.undo();
        run(session, context(), 400);
        assertEquals(BuildSession.State.UNDONE, session.state());
        assertTrue(world.blocks.isEmpty(), world.blocks.toString());
        BuildSession finished = session(floating(10), options());
        tickUntil(finished, () -> finished.state() != BuildSession.State.RUNNING, 2000);
        finished.undo();
        run(finished, context(), 400);
        assertTrue(world.blocks.isEmpty(), world.blocks.toString());
        assertEquals(1, finished.reverted());
        assertEquals(0, finished.undoSkipped());
    }
}
