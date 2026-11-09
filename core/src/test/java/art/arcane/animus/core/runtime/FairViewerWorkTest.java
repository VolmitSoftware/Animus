package art.arcane.animus.core.runtime;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FairViewerWorkTest {
    @Test void theFirstViewerCannotSpendTheWholeBudget() {
        Deque<String> viewers = new ArrayDeque<>(List.of("a", "b", "c", "d"));
        Map<String, Integer> spent = new HashMap<>();
        assertEquals(32, FairViewerWork.run(viewers, 16, 32, ignored -> true, (String viewer, int remaining) -> { spent.merge(viewer, remaining, Integer::sum); return remaining; }));
        assertEquals(Map.of("a", 8, "b", 8, "c", 8, "d", 8), spent);
    }
    @Test void anUnusedShareGoesToTheViewersAfterIt() {
        Deque<String> viewers = new ArrayDeque<>(List.of("idle", "busy", "busier"));
        Map<String, Integer> spent = new HashMap<>();
        assertEquals(32, FairViewerWork.run(viewers, 16, 32, ignored -> true, (String viewer, int remaining) -> {
            int used = viewer.equals("idle") ? 0 : remaining; spent.put(viewer, used); return used;
        }));
        assertEquals(Map.of("idle", 0, "busy", 16, "busier", 16), spent);
    }
    @Test void theStartingViewerRotatesEveryTickWhenAllFit() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2));
        List<Integer> first = new ArrayList<>();
        for (int tick = 0; tick < 3; tick++) {
            List<Integer> visited = new ArrayList<>();
            FairViewerWork.run(viewers, 16, 32, ignored -> true, (Integer viewer, int remaining) -> { visited.add(viewer); return remaining; });
            assertEquals(3, visited.size(), "every viewer gets a turn: " + visited);
            first.add(visited.getFirst());
        }
        assertEquals(List.of(0, 1, 2), first);
    }
    @Test void viewersBeyondTheUpdateLimitGoNextTick() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2, 3, 4, 5, 6, 7));
        List<Integer> visited = new ArrayList<>();
        for (int tick = 0; tick < 2; tick++)
            assertEquals(32, FairViewerWork.run(viewers, 4, 32, ignored -> true, (Integer viewer, int remaining) -> { visited.add(viewer); return Math.min(8, remaining); }));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), visited);
    }
    @Test void aViewerLeftWithoutBudgetKeepsItsPlace() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2, 3));
        List<Integer> visited = new ArrayList<>();
        assertEquals(2, FairViewerWork.run(viewers, 16, 2, ignored -> true, (Integer viewer, int remaining) -> { visited.add(viewer); return remaining; }));
        assertEquals(List.of(0, 1), visited);
        assertEquals(List.of(2, 3, 0, 1), List.copyOf(viewers));
    }
    @Test void aWorkerCannotOverrunItsShare() {
        Deque<String> viewers = new ArrayDeque<>(List.of("a", "b"));
        assertThrows(IllegalStateException.class, () -> FairViewerWork.run(viewers, 16, 32, ignored -> true, (String viewer, int remaining) -> 17));
    }
    @Test void idleWorkIsBoundedAndRetiredViewersAreRemoved() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2, 3));
        List<Integer> visited = new ArrayList<>();
        assertEquals(0, FairViewerWork.run(viewers, 2, 32, viewer -> viewer != 0, (Integer viewer, int remaining) -> { visited.add(viewer); return 0; }));
        assertEquals(List.of(1), visited); assertEquals(List.of(2, 3, 1), List.copyOf(viewers));
        assertEquals(0, FairViewerWork.run(viewers, 16, 0, ignored -> true, (Integer viewer, int remaining) -> fail("Zero budget must not rotate viewers")));
        assertEquals(List.of(2, 3, 1), List.copyOf(viewers));
    }
}
