package art.arcane.animus.core.building;

import static art.arcane.animus.core.building.BuildFixtures.WORLD;
import static art.arcane.animus.core.building.BuildFixtures.plan;
import static art.arcane.animus.core.building.BuildFixtures.run;
import static art.arcane.animus.core.building.BuildFixtures.schematic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.building.BuildSession.State;
import art.arcane.animus.core.navigation.BlockPos;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A build's turns on a regionized server: only NPCs the turn's region holds work, and each turn goes where work is. */
class BuildRegionTurnsTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final Map<Character, String> LEGEND = Map.of('s', "stone");
    private BuildFixtures.World world;
    private BuildFixtures.Crew crew;
    private BuildFixtures.Events events;

    @BeforeEach void setUp() {
        world = new BuildFixtures.World();
        crew = new BuildFixtures.Crew();
        events = new BuildFixtures.Events();
    }

    private BuildSession session(List<String> team) {
        BuildOptions options = BuildOptions.DEFAULT.with(Map.of("anchor", "corner", "pattern", "linear", "interval", "1"));
        BuildSession session = new BuildSession("leader", "leader", WORLD, ORIGIN, "wall.schem", "hash", options, Optional.empty(), "2026-11-05T00:00:00Z", 100, events);
        session.attach(plan(schematic(LEGEND, new String[]{"ssssssss"}), options, ORIGIN));
        for (String member : team) session.addMember(member, 4);
        return session;
    }
    private BuildSession.Context context() {
        return new BuildSession.Context(world, crew, new BuildProtection.Gate(request -> BuildProtection.Decision.ALLOW, message -> { }), new BuildSession.Budget(64, 512, 64));
    }

    @Test void aLeaderOnAnotherRegionNeitherWorksNorMakesTheBuildWait() {
        crew.add("leader", .5, 64, -2.5);
        crew.elsewhere.add("leader");
        BuildSession session = session(List.of());
        for (int turn = 0; turn < 20; turn++) session.tick(context());
        assertEquals(State.RUNNING, session.state(), session.reason());
        assertEquals(0, session.placed());
        assertTrue(crew.walkers.isEmpty(), "nothing moved an NPC another region holds");
        crew.elsewhere.clear();
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state(), session.reason());
        assertEquals(8, session.placed());
    }

    @Test void onlyTheMembersThisRegionHoldsWorkInATurn() {
        crew.add("leader", .5, 64, -2.5);
        crew.add("helper", 6.5, 64, -2.5);
        crew.elsewhere.add("helper");
        BuildSession session = session(List.of("helper"));
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state(), session.reason());
        assertFalse(crew.walkers.contains("helper"));
        assertFalse(crew.swings.containsKey("helper"));
        assertTrue(crew.swings.getOrDefault("leader", 0) > 0);
    }

    @Test void aBlockAnotherRegionHoldsIsWalkedToNotRefusedAndPlacedOnceThisRegionHoldsIt() {
        crew.add("leader", .5, 64, -40.5);
        crew.walkInstantly = false;
        world.foreign(0, 0);
        BuildSession session = session(List.of());
        for (int turn = 0; turn < 40; turn++) session.tick(context());
        assertEquals(State.RUNNING, session.state(), session.reason());
        assertEquals(0, session.placed());
        assertTrue(crew.walkers.contains("leader"), "the NPC walks towards the block another region holds");
        world.own(0, 0);
        crew.walkInstantly = true;
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state(), session.reason());
        assertEquals(8, session.placed());
        assertTrue(session.status().contains(" refused=0 "), session.status());
    }

    @Test void turnsRotateOverTheActiveMembersWherevertheyStand() {
        crew.add("leader", .5, 64, -2.5);
        crew.add("helper", 40.5, 64, 3.5);
        BuildSession session = session(List.of("helper"));
        Set<BlockPos> focused = new HashSet<>();
        for (int turn = 0; turn < 4; turn++) focused.add(session.focus(crew));
        assertEquals(Set.of(new BlockPos(0, 64, -3), new BlockPos(40, 64, 3)), focused);
    }

    @Test void withNoActiveMemberTheTurnGoesToTheOrigin() {
        BuildSession session = session(List.of());
        assertEquals(ORIGIN, session.focus(crew));
        assertTrue(session.wantsTurn());
    }

    @Test void undoGoesToTheNextChangeToPutBack() {
        crew.add("leader", .5, 64, -2.5);
        BuildSession session = session(List.of());
        run(session, context(), 400);
        assertEquals(State.FINISHED, session.state());
        assertFalse(session.wantsTurn(), "a finished build with no scaffolding has no turn to take");
        session.undo();
        assertEquals(new BlockPos(7, 64, 0), session.focus(crew));
        assertTrue(session.wantsTurn());
    }

    @Test void anUnreachablePlaceMakesAnOpenBuildWaitWithTheReason() {
        crew.add("leader", .5, 64, -2.5);
        BuildSession session = session(List.of());
        session.unreachable("chunk 0 -1 is not loaded");
        assertEquals(State.WAITING, session.state());
        assertEquals("chunk 0 -1 is not loaded", session.reason());
        assertEquals(List.of("chunk 0 -1 is not loaded"), events.paused);
    }
}
