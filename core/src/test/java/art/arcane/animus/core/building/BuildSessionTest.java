package art.arcane.animus.core.building;

import static art.arcane.animus.core.building.BuildFixtures.WORLD;
import static art.arcane.animus.core.building.BuildFixtures.plan;
import static art.arcane.animus.core.building.BuildFixtures.run;
import static art.arcane.animus.core.building.BuildFixtures.schematic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.building.BuildSession.State;
import art.arcane.animus.core.navigation.BlockPos;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildSessionTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final Map<Character, String> LEGEND = Map.of('s', "stone", 'p', "oak_planks", 't', "torch", 'a', "air", 'g', "glass");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private BuildFixtures.World world;
    private BuildFixtures.Crew crew;
    private BuildFixtures.Events events;
    private BuildProtection.Gate protection;
    private final List<BuildProtection.Request> requests = new ArrayList<>();
    private BuildProtection.Decision decision = BuildProtection.Decision.ALLOW;

    @BeforeEach void setUp() {
        world = new BuildFixtures.World();
        crew = new BuildFixtures.Crew();
        events = new BuildFixtures.Events();
        protection = new BuildProtection.Gate(request -> { requests.add(request); return decision; }, message -> { });
        crew.add("builder", .5, 64, -2.5);
    }

    private BuildSession session(Schematic schematic, BuildOptions options, int undo) {
        BuildSession session = new BuildSession("builder", "builder", WORLD, ORIGIN, "test.schem", "hash", options, Optional.of(PLAYER), "2026-11-04T00:00:00Z", undo, events);
        session.attach(plan(schematic, options, ORIGIN));
        return session;
    }
    private BuildSession.Context context() { return new BuildSession.Context(world, crew, protection, new BuildSession.Budget(64, 512, 64)); }
    private static BuildOptions corner() { return BuildOptions.DEFAULT.with(Map.of("anchor", "corner", "pattern", "linear", "interval", "1")); }

    @Test void creativeBuildPlacesEveryBlockInPlanOrderAndFinishes() {
        Schematic tower = schematic(LEGEND, new String[]{"sp"}, new String[]{"tg"});
        BuildSession session = session(tower, corner(), 100);
        run(session, context(), 200);
        assertEquals(State.FINISHED, session.state());
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, 64, 0)));
        assertEquals("minecraft:oak_planks", world.blocks.get(new BlockPos(1, 64, 0)));
        assertEquals("minecraft:torch", world.blocks.get(new BlockPos(0, 65, 0)));
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(1, 65, 0), new BlockPos(0, 65, 0)), world.effects);
        assertEquals(4, session.placed());
        assertEquals(4, session.journal().size());
        assertEquals(1, events.finished);
        assertEquals(4, crew.swings.get("builder"));
        assertTrue(events.messages.getLast().startsWith("Build builder finished"));
        assertEquals(Optional.empty(), crew.held.get("builder"));
    }

    @Test void blocksAlreadyInPlaceAreSkippedWithoutJournaling() {
        world.blocks.put(new BlockPos(0, 64, 0), "minecraft:stone");
        BuildSession session = session(schematic(LEGEND, new String[]{"sp"}), corner(), 100);
        run(session, context(), 100);
        assertEquals(1, session.skipped());
        assertEquals(1, session.placed());
        assertEquals(1, session.journal().size());
    }

    @Test void protectionIsAskedForEveryChangeAndADenialSkipsTheBlock() {
        decision = BuildProtection.Decision.DENY;
        BuildSession session = session(schematic(LEGEND, new String[]{"sa"}), corner(), 100);
        world.blocks.put(new BlockPos(1, 64, 0), "minecraft:dirt");
        run(session, context(), 100);
        assertEquals(State.FINISHED, session.state());
        assertEquals(2, session.denied());
        assertEquals(0, session.placed());
        assertEquals("minecraft:dirt", world.blocks.get(new BlockPos(1, 64, 0)));
        assertEquals(List.of(BuildProtection.Action.BREAK, BuildProtection.Action.PLACE), List.of(requests.get(0).action(), requests.get(1).action()));
        assertEquals(Optional.of(PLAYER), requests.getFirst().startedBy());
        assertEquals(2, protection.denials());
    }

    @Test void aThrowingProtectionAdapterDenies() {
        BuildProtection.Gate failing = new BuildProtection.Gate(request -> { throw new IllegalStateException("down"); }, message -> { });
        BuildSession session = session(schematic(LEGEND, new String[]{"s"}), corner(), 100);
        run(session, new BuildSession.Context(world, crew, failing, new BuildSession.Budget(8, 8, 8)), 50);
        assertEquals(1, session.denied());
        assertEquals(1, failing.failures());
        assertFalse(world.blocks.containsKey(ORIGIN));
    }

    @Test void unloadedTerrainMakesTheBuildWaitUntilItLoads() {
        world.unload(0, 0);
        BuildSession session = session(schematic(LEGEND, new String[]{"s"}), corner(), 100);
        session.tick(context());
        assertEquals(State.WAITING, session.state());
        assertTrue(session.reason().contains("unloaded chunk"));
        for (int tick = 0; tick < 100; tick++) session.tick(context());
        assertEquals(1, events.paused.size());
        world.load(0, 0);
        for (int tick = 0; tick < 100 && session.state() != State.FINISHED; tick++) session.tick(context());
        assertEquals(State.FINISHED, session.state());
        assertEquals("minecraft:stone", world.blocks.get(ORIGIN));
    }

    @Test void inactiveCrewMakesTheBuildWait() {
        crew.members.clear();
        BuildSession session = session(schematic(LEGEND, new String[]{"s"}), corner(), 100);
        session.tick(context());
        assertEquals(State.WAITING, session.state());
        assertEquals(List.of("no builder NPC is active in the build's world"), events.paused);
    }

    @Test void donatedBuildWaitsForMaterialsAndUsesExactlyWhatItPlaces() {
        BuildOptions options = corner().with(Map.of("materials", "donate"));
        BuildSession session = session(schematic(LEGEND, new String[]{"ppst"}), options, 100);
        session.bind(world);
        assertEquals(Map.of("minecraft:oak_planks", 2L, "minecraft:stone", 1L, "minecraft:torch", 1L), session.remaining());
        session.tick(context());
        assertEquals(State.WAITING, session.state());
        assertTrue(session.reason().startsWith("needs 1 minecraft:oak_planks (have 0"), session.reason());
        assertEquals(0, session.donate("minecraft:dirt", 10));
        assertEquals(2, session.donate("minecraft:oak_planks", 64));
        assertEquals(1, session.donate("minecraft:stone", 1));
        for (int tick = 0; tick < 200 && session.state() != State.WAITING || tick < 45; tick++) session.tick(context());
        assertEquals(State.WAITING, session.state());
        assertTrue(session.reason().contains("minecraft:torch"));
        assertEquals(3, session.placed());
        assertTrue(session.ledger().isEmpty());
        assertTrue(session.survey(world).stream().anyMatch((String line) -> line.equals("minecraft:torch: need 1, have 0, short 1")));
        session.donate("minecraft:torch", 1);
        for (int tick = 0; tick < 200 && session.state() != State.FINISHED; tick++) session.tick(context());
        assertEquals(State.FINISHED, session.state());
        assertEquals(4, session.placed());
        assertTrue(events.paused.size() >= 2);
    }

    @Test void donationsAreRefusedByOtherMaterialModes() {
        BuildSession session = session(schematic(LEGEND, new String[]{"s"}), corner(), 100);
        assertThrows(IllegalStateException.class, () -> session.donate("minecraft:stone", 1));
    }

    @Test void containerBuildTakesItemsFromTheContainer() {
        BuildOptions options = corner().with(Map.of("materials", "container", "container", "5,64,5"));
        world.container.put("minecraft:stone", 3L);
        world.items.put("minecraft:end_portal", "");
        Schematic mixed = schematic(Map.of('s', "stone", 'e', "end_portal"), new String[]{"sse"});
        BuildSession session = session(mixed, options, 100);
        run(session, context(), 200);
        assertEquals(State.FINISHED, session.state());
        assertEquals(1L, world.container.get("minecraft:stone"));
        assertEquals(1, session.unobtainable());
        world.containerPresent = false;
        world.blocks.clear();
        BuildSession missing = session(mixed, options, 100);
        missing.tick(context());
        assertEquals(State.WAITING, missing.state());
        assertTrue(missing.reason().contains("not loaded or not a container"));
    }

    @Test void savedBuildResumesFromItsStepAndSkipsWhatIsAlreadyBuilt() {
        Schematic wall = schematic(LEGEND, new String[]{"sssp"}, new String[]{"ppps"});
        BuildOptions options = corner();
        BuildSession first = session(wall, options, 100);
        for (int tick = 0; tick < 200 && first.placed() < 3; tick++) first.tick(context());
        assertEquals(3, first.placed());
        JsonObject saved = first.toJson();
        List<JsonObject> chunks = List.of(first.journal().chunk(0));
        BuildSession resumed = BuildSession.restore(saved, UndoJournal.restore(100, false, chunks), events);
        assertEquals(State.RUNNING, resumed.state());
        assertEquals(first.cursor(), resumed.cursor());
        resumed.attach(plan(wall, options, ORIGIN));
        run(resumed, context(), 300);
        assertEquals(State.FINISHED, resumed.state());
        assertEquals(8, resumed.placed());
        assertEquals(8, resumed.journal().size());
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(3, 65, 0)));
    }

    @Test void restoringAgainstAChangedSchematicIsRefused() {
        BuildSession first = session(schematic(LEGEND, new String[]{"ss"}), corner(), 100);
        run(first, context(), 100);
        BuildSession resumed = BuildSession.restore(first.toJson(), new UndoJournal(100), events);
        assertThrows(IllegalArgumentException.class, () -> resumed.attach(plan(schematic(LEGEND, new String[]{"sss"}), corner(), ORIGIN)));
        JsonObject broken = first.toJson();
        broken.addProperty("cursor", 99);
        assertThrows(IllegalArgumentException.class, () -> BuildSession.restore(broken, new UndoJournal(100), events));
    }

    @Test void undoRestoresWhatWasThereAndKeepsBlocksChangedSince() {
        world.blocks.put(new BlockPos(0, 64, 0), "minecraft:dirt");
        BuildSession session = session(schematic(LEGEND, new String[]{"sp"}, new String[]{"gg"}), corner(), 100);
        run(session, context(), 200);
        world.blocks.put(new BlockPos(0, 65, 0), "minecraft:gold_block");
        session.undo();
        run(session, context(), 200);
        assertEquals(State.UNDONE, session.state());
        assertEquals("minecraft:dirt", world.blocks.get(new BlockPos(0, 64, 0)));
        assertFalse(world.blocks.containsKey(new BlockPos(1, 64, 0)));
        assertFalse(world.blocks.containsKey(new BlockPos(1, 65, 0)));
        assertEquals("minecraft:gold_block", world.blocks.get(new BlockPos(0, 65, 0)));
        assertEquals(3, session.reverted());
        assertEquals(1, session.undoSkipped());
        assertThrows(IllegalStateException.class, session::undo);
    }

    @Test void excavationRemovesBlocksAndUndoPutsThemBack() {
        world.blocks.put(new BlockPos(1, 64, 0), "minecraft:dirt");
        world.blocks.put(new BlockPos(0, 65, 0), "minecraft:oak_log[axis=y]");
        world.blocks.put(new BlockPos(0, 64, 0), "minecraft:stone");
        BuildSession session = session(schematic(LEGEND, new String[]{"sa"}, new String[]{"aa"}), corner().with(Map.of("excavate", "true")), 100);
        run(session, context(), 200);
        assertEquals(State.FINISHED, session.state());
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(1, world.blocks.size());
        assertEquals(2, session.journal().size());
        session.undo();
        run(session, context(), 200);
        assertEquals("minecraft:dirt", world.blocks.get(new BlockPos(1, 64, 0)));
        assertEquals("minecraft:oak_log[axis=y]", world.blocks.get(new BlockPos(0, 65, 0)));
    }

    @Test void undoJournalLimitIsReportedAndUndoRevertsOnlyWhatItHolds() {
        BuildSession session = session(schematic(LEGEND, new String[]{"sss"}), corner(), 2);
        run(session, context(), 200);
        assertTrue(session.journal().truncated());
        assertTrue(session.undo().contains("journal was full"));
        run(session, context(), 200);
        assertEquals(2, session.reverted());
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(2, 64, 0)));
    }

    @Test void undoWaitsForUnloadedTerrain() {
        BuildSession session = session(schematic(LEGEND, new String[]{"s"}), corner(), 100);
        run(session, context(), 100);
        session.undo();
        world.unload(0, 0);
        session.tick(context());
        assertEquals(State.UNDOING, session.state());
        assertTrue(session.reason().contains("unloaded chunk"));
        world.load(0, 0);
        run(session, context(), 100);
        assertEquals(State.UNDONE, session.state());
        assertFalse(world.blocks.containsKey(ORIGIN));
    }

    @Test void farBlocksAreWalkedToAndATimeoutMovesTheNpc() {
        Schematic far = schematic(LEGEND, new String[]{"s"});
        BuildSession session = session(far, corner(), 100);
        crew.add("builder", 40.5, 64, 40.5);
        session.tick(context());
        assertEquals(1, crew.walks.size());
        assertTrue(crew.walks.getFirst().point().x() >= 10.5 && crew.walks.getFirst().point().x() <= 10.6, crew.walks.toString());
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state());
        crew.walkInstantly = false;
        crew.add("builder", 40.5, 64, 40.5);
        BuildSession stuck = session(schematic(LEGEND, new String[]{".s"}), corner().with(Map.of("timeout", "1")), 100);
        run(stuck, context(), 100);
        assertEquals(State.FINISHED, stuck.state());
        assertEquals(1, crew.relocations);
    }

    @Test void teamMembersShareTheBuild() {
        crew.add("helper", 2.5, 64, -2.5);
        Schematic row = schematic(LEGEND, new String[]{"ssssssss"});
        BuildOptions options = corner().with(Map.of("interval", "4"));
        BuildSession session = session(row, options, 100);
        session.addMember("helper", 4);
        assertThrows(IllegalArgumentException.class, () -> session.addMember("helper", 4));
        assertThrows(IllegalStateException.class, () -> session.addMember("third", 2));
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state());
        assertTrue(crew.swings.getOrDefault("helper", 0) > 0);
        assertTrue(crew.swings.getOrDefault("builder", 0) > 0);
        assertEquals(8, session.placed());
        assertThrows(IllegalArgumentException.class, () -> session.removeMember("builder", crew));
        session.removeMember("helper", crew);
        assertEquals(List.of("builder"), session.team());
    }

    @Test void pauseResumeAndCancel() {
        BuildSession session = session(schematic(LEGEND, new String[]{"ssss"}), corner(), 100);
        session.tick(context());
        session.pause();
        long placed = session.placed();
        for (int tick = 0; tick < 50; tick++) session.tick(context());
        assertEquals(placed, session.placed());
        assertEquals(List.of("paused by command"), events.paused);
        session.resume();
        session.tick(context());
        assertTrue(session.placed() > placed);
        session.cancel();
        assertEquals(State.CANCELLED, session.state());
        assertThrows(IllegalStateException.class, session::resume);
        assertThrows(IllegalStateException.class, session::pause);
    }

    @Test void statusShowsProgressAndCounters() {
        BuildSession session = session(schematic(LEGEND, new String[]{"ss"}), corner(), 100);
        run(session, context(), 100);
        String status = session.status();
        assertTrue(status.startsWith("Build builder: finished 2/2 (100%)"), status);
        assertTrue(status.contains("placed=2"), status);
    }

    @Test void heldItemFollowsTheBlockBeingPlaced() {
        BuildSession session = session(schematic(LEGEND, new String[]{"tt"}), corner(), 100);
        session.tick(context());
        assertEquals(Optional.of("minecraft:torch"), crew.held.get("builder"));
    }
}
