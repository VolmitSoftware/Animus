package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class NearestSelectionTest {
    private static final WorldPosition ORIGIN = new WorldPosition("world", new Vec3(0, 64, 0));

    @Test void theNearestEnterFirstWhenMoreAreInRangeThanTheCap() {
        List<NearestSelection.Candidate> candidates = new ArrayList<>();
        for (int distance = 1; distance <= 10; distance++) candidates.add(candidate(distance, false, 1));
        Collections.shuffle(candidates, new Random(7));
        NearestSelection selection = new NearestSelection();
        List<UUID> leaving = selection.rank(0, ORIGIN, candidates, 4, 256, 0);
        assertEquals(List.of(), leaving);
        assertEquals(List.of(id(1), id(2), id(3), id(4)), drain(selection), "nearest first");
        assertFalse(selection.scanning(), "left out");
    }

    @Test void everythingInRangeEntersWhenItFits() {
        NearestSelection selection = new NearestSelection();
        selection.rank(0, ORIGIN, List.of(candidate(9, false, 1), candidate(3, false, 1), candidate(5, true, 1)), 4, 256, 0);
        assertEquals(List.of(id(3), id(9)), drain(selection));
        assertTrue(selection.scanning(), "everything fits");
    }

    @Test void aShownNpcKeepsItsPlaceUntilALeftOutOneIsAMarginNearer() {
        NearestSelection selection = new NearestSelection();
        double shown = 10;
        assertEquals(List.of(), selection.rank(0, ORIGIN, List.of(candidate(shown, true, 1), candidate(shown - NearestSelection.MARGIN + 0.5, false, 1)), 1, 256, 0),
                "a little nearer is not enough to swap");
        assertEquals(List.of(), drain(selection));
        assertEquals(List.of(id(shown)), selection.rank(1, ORIGIN, List.of(candidate(shown, true, 1), candidate(shown - NearestSelection.MARGIN - 0.5, false, 1)), 1, 256, 0));
        assertEquals(List.of(id(shown - NearestSelection.MARGIN - 0.5)), drain(selection));
    }

    @Test void noLeftOutNpcIsNearerThanAShownOneByMoreThanTheMargin() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            int maximum = 1 + random.nextInt(12);
            List<NearestSelection.Candidate> candidates = new ArrayList<>();
            int shown = 0;
            for (int i = 0, count = random.nextInt(40); i < count; i++) {
                boolean visible = shown < maximum && random.nextBoolean();
                if (visible) shown++;
                candidates.add(new NearestSelection.Candidate(UUID.randomUUID(), random.nextDouble() * 48, visible, () -> 1));
            }
            NearestSelection selection = new NearestSelection();
            Set<UUID> leaving = new HashSet<>(selection.rank(round, ORIGIN, candidates, maximum, 256, 0));
            Set<UUID> entering = new HashSet<>(drain(selection));
            List<NearestSelection.Candidate> kept = new ArrayList<>(), left = new ArrayList<>();
            for (NearestSelection.Candidate candidate : candidates) {
                boolean after = candidate.shown() ? !leaving.contains(candidate.id()) : entering.contains(candidate.id());
                (after ? kept : left).add(candidate);
            }
            assertEquals(Math.min(maximum, candidates.size()), kept.size(), "the cap is filled");
            for (NearestSelection.Candidate out : left)
                for (NearestSelection.Candidate in : kept)
                    assertTrue(out.distance() >= in.distance() - NearestSelection.MARGIN - 1e-9,
                            "left out at " + out.distance() + " while one at " + in.distance() + " is shown");
        }
    }

    @Test void theNativeIdBudgetStopsTheRanking() {
        NearestSelection selection = new NearestSelection();
        List<UUID> leaving = selection.rank(0, ORIGIN, List.of(candidate(1, false, 2), candidate(2, false, 2), candidate(30, true, 2)), 10, 5, 0);
        assertEquals(List.of(id(30)), leaving, "the farthest no longer fits");
        assertEquals(List.of(id(1), id(2)), drain(selection));
        assertFalse(selection.scanning(), "left out");
    }

    @Test void aLoweredCapChangesNothingAlreadyShown() {
        NearestSelection selection = new NearestSelection();
        List<NearestSelection.Candidate> candidates = List.of(candidate(30, true, 1), candidate(20, true, 1), candidate(10, true, 1), candidate(1, false, 1));
        assertEquals(List.of(), selection.rank(0, ORIGIN, candidates, 2, 256, 0));
        assertEquals(List.of(), drain(selection), "new NPCs wait until the count falls under the cap");
        assertFalse(selection.scanning(), "more in range than places");
    }

    @Test void theRankingIsRedoneOnFirstUseAfterAMoveOrNewCapAndOnACadenceWhileFull() {
        NearestSelection selection = new NearestSelection();
        assertTrue(selection.due(0, ORIGIN, 1, 4), "never ranked");
        selection.rank(0, ORIGIN, List.of(candidate(5, false, 1)), 4, 256, 0);
        assertFalse(selection.due(1, ORIGIN, 1, 4));
        assertFalse(selection.due(NearestSelection.RECHECK_TURNS, ORIGIN, 1, 4), "room for everything in range: no cadence");
        assertTrue(selection.due(NearestSelection.RECHECK_TURNS, ORIGIN, 4, 4), "at the cap: ranked again on the cadence");
        assertFalse(selection.due(NearestSelection.RECHECK_TURNS - 1, ORIGIN, 4, 4));
        assertTrue(selection.due(NearestSelection.MOVE_TURNS, new WorldPosition("world", new Vec3(NearestSelection.RECHECK_DISTANCE, 64, 0)), 1, 4), "the viewer moved");
        assertFalse(selection.due(NearestSelection.MOVE_TURNS, new WorldPosition("world", new Vec3(NearestSelection.RECHECK_DISTANCE - 0.5, 64, 0)), 1, 4));
        assertTrue(selection.due(1, new WorldPosition("nether", new Vec3(0, 64, 0)), 1, 4), "another world");
        assertTrue(selection.due(1, ORIGIN, 1, 6), "the cap changed");

        List<NearestSelection.Candidate> crowd = new ArrayList<>();
        for (int distance = 1; distance <= 6; distance++) crowd.add(candidate(distance, false, 1));
        selection.rank(20, ORIGIN, crowd, 4, 256, 0);
        assertTrue(selection.due(20 + NearestSelection.RECHECK_TURNS, ORIGIN, 1, 4), "NPCs left out: ranked again on the cadence");
        drain(selection);
        assertFalse(selection.due(21, ORIGIN, 4, 4));
        assertTrue(selection.due(21, ORIGIN, 3, 4), "a shown NPC left while others wait: its place is filled at once");
    }

    @Test void npcsWhoseChunksHaveNotReachedTheViewerKeepTheScanOffUntilARankingSeesThem() {
        NearestSelection selection = new NearestSelection();
        selection.rank(0, ORIGIN, List.of(candidate(2, false, 1), candidate(3, false, 1)), 4, 256, 5);
        assertEquals(List.of(id(2), id(3)), drain(selection), "the ones already there enter, nearest first");
        assertFalse(selection.scanning(), "seven in range for four places");
        assertFalse(selection.scans(2, 4), "the bounded scan would fill the cap in scan order");
        assertTrue(selection.due(NearestSelection.RECHECK_TURNS, ORIGIN, 2, 4), "the next ranking lets in the nearest of those that arrived");
    }

    @Test void theBoundedScanLeavesTheLastFreePlaceToARanking() {
        NearestSelection selection = new NearestSelection();
        selection.rank(0, ORIGIN, List.of(candidate(2, true, 1)), 4, 256, 0);
        assertTrue(selection.scans(2, 4));
        assertFalse(selection.due(1, ORIGIN, 3, 4));
        assertFalse(selection.scans(3, 4), "one place left");
        assertTrue(selection.due(1, ORIGIN, 3, 4), "the ranking that hands out the last place runs on the next update");
        selection.rank(1, ORIGIN, List.of(candidate(2, true, 1)), 4, 256, 0);
        assertFalse(selection.due(2, ORIGIN, 3, 4));
    }

    @Test void anNpcThatRespawnsKeepsTheMarginOfThePlaceItHad() {
        NearestSelection selection = new NearestSelection();
        double held = 10;
        selection.rank(0, ORIGIN, List.of(candidate(held, true, 1)), 1, 256, 0);
        NearestSelection.Candidate respawning = candidate(held, false, 1), nearer = candidate(held - NearestSelection.MARGIN + 0.5, false, 1);
        selection.rank(1, ORIGIN, List.of(respawning, nearer), 1, 256, 0);
        assertEquals(List.of(id(held)), drain(selection), "a little nearer does not take the place of one that is respawning");
    }

    @Test void aMovingViewerIsRankedEveryFewUpdatesAndTheScanWaitsForIt() {
        NearestSelection selection = new NearestSelection();
        selection.rank(0, ORIGIN, List.of(candidate(5, false, 1)), 4, 256, 0);
        drain(selection);
        WorldPosition moved = new WorldPosition("world", new Vec3(NearestSelection.RECHECK_DISTANCE + 1, 64, 0));
        assertFalse(selection.due(1, moved, 1, 4), "a flyer is not ranked on every update");
        assertFalse(selection.scans(1, 4), "nor filled in scan order while the ranking for its new spot waits");
        assertTrue(selection.due(NearestSelection.MOVE_TURNS, moved, 1, 4));
        assertTrue(selection.due(1, new WorldPosition("world", new Vec3(NearestSelection.FAR, 64, 0)), 1, 4), "a teleport is ranked at once");
    }

    /**
     * The ranking and filling both presenters run, Paper and Folia, over a real index: one viewer at the origin who is
     * shown what {@link NearestSelection#fill} spawns, and loses what {@link NearestSelection#rank(long, NearestSelection.Area, NearestSelection.View, int, int)} retires.
     */
    private static final class Presenter implements NearestSelection.View<VirtualNpcState.Snapshot> {
        final SpatialNpcIndex index = new SpatialNpcIndex(64);
        final Map<UUID, VirtualNpcState.Snapshot> npcs = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        final Set<UUID> shown = new LinkedHashSet<>();
        final List<String> retired = new ArrayList<>();
        final NearestSelection selection = new NearestSelection();
        /** The chunks that reached the viewer. */
        Predicate<ChunkPosition> sent = (ChunkPosition chunk) -> true;

        void npc(String name, double x) {
            Vec3 point = new Vec3(x, 64, 0.5);
            VirtualNpcState.Snapshot snapshot = new VirtualNpcState.Snapshot(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new WorldPosition("world", point), new Hitbox(point.add(new Vec3(-0.3, 0, -0.3)), point.add(new Vec3(0.3, 1.8, 0.3))), 20, true, 1);
            index.upsert(snapshot); npcs.put(snapshot.npcId(), snapshot); names.put(snapshot.npcId(), name);
        }
        void turns(int from, int to, int maximum) {
            WorldPosition at = new WorldPosition("world", new Vec3(0.5, 64, 0.5));
            NearestSelection.Area area = new NearestSelection.Area(index, at, 48, sent);
            for (int turn = from; turn < to; turn++) {
                if (selection.due(turn, at, shown.size(), maximum))
                    for (UUID leaving : selection.rank(turn, area, this, maximum, 256)) { shown.remove(leaving); retired.add(names.get(leaving)); }
                if (shown.size() < maximum) selection.fill(area, this, Math.min(8, maximum - shown.size()), maximum);
            }
        }
        Set<String> shownNames() { Set<String> out = new HashSet<>(); for (UUID id : shown) out.add(names.get(id)); return out; }

        @Override public VirtualNpcState.Snapshot npc(UUID id) { return npcs.get(id); }
        @Override public boolean eligible(VirtualNpcState.Snapshot npc, Predicate<ChunkPosition> chunks) { return chunks.test(npc.position().chunk()); }
        @Override public WorldPosition position(VirtualNpcState.Snapshot npc) { return npc.position(); }
        @Override public int ids(VirtualNpcState.Snapshot npc) { return 1; }
        @Override public boolean showing(UUID id) { return shown.contains(id); }
        @Override public int shownIds(UUID id) { return 1; }
        @Override public int shownCount() { return shown.size(); }
        @Override public boolean retiring(UUID id) { return false; }
        @Override public boolean fits(int ids) { return true; }
        @Override public boolean admit() { return true; }
        @Override public void spawn(VirtualNpcState.Snapshot npc) { shown.add(npc.npcId()); }
        @Override public boolean current() { return true; }
    }

    @Test void chunksThatReachAViewerNearestFirstFillTheCapNearestFirstWithNoSwaps() {
        Presenter presenter = new Presenter();
        // Scan order starts in the far west, so a fill in scan order would take these first.
        presenter.npc("far-west", -40.5); presenter.npc("west", -30.5); presenter.npc("mid-west", -20.5); presenter.npc("near-west", -12.5);
        presenter.npc("a", 2.5); presenter.npc("b", 6.5); presenter.npc("c", 10.5);
        presenter.sent = (ChunkPosition chunk) -> chunk.x() == 0;
        presenter.turns(0, 3, 5);
        assertEquals(Set.of("a", "b", "c"), presenter.shownNames(), "the chunk the viewer stands in arrives first");
        presenter.sent = (ChunkPosition chunk) -> true;
        presenter.turns(3, 3 * NearestSelection.RECHECK_TURNS, 5);
        assertEquals(Set.of("a", "b", "c", "near-west", "mid-west"), presenter.shownNames());
        assertEquals(List.of(), presenter.retired, "nothing was shown only to be swapped out");
    }

    @Test void theSharedFillScansForNpcsThatArriveWhileEverythingFits() {
        Presenter presenter = new Presenter();
        presenter.npc("a", 2.5);
        presenter.turns(0, 2, 4);
        assertEquals(Set.of("a"), presenter.shownNames());
        presenter.npc("b", -20.5);
        presenter.turns(2, 4, 4);
        assertEquals(Set.of("a", "b"), presenter.shownNames(), "found by the bounded scan, without waiting for a ranking");
    }

    private static NearestSelection.Candidate candidate(double distance, boolean shown, int ids) {
        return new NearestSelection.Candidate(id(distance), distance, shown, () -> ids);
    }
    private static UUID id(double distance) { return UUID.nameUUIDFromBytes(Double.toString(distance).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static List<UUID> drain(NearestSelection selection) {
        List<UUID> entering = new ArrayList<>();
        for (UUID next = selection.poll(); next != null; next = selection.poll()) entering.add(next);
        return entering;
    }
}
