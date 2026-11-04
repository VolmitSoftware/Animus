package art.arcane.animus.bukkit.runtime;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FairViewerWorkTest {
    @Test void continuousEarlyViewerChurnCannotStarveLaterDespawns() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2, 3, 4, 5, 6, 7));
        List<Integer> visited = new ArrayList<>();
        for (int tick = 0; tick < 4; tick++) {
            assertEquals(32, FairViewerWork.run(viewers, 16, 32, ignored -> true, (viewer, remaining) -> {
                visited.add(viewer); return 8;
            }));
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7), visited);
    }
    @Test void oneLargeRetirementBatchCannotKeepFirstPlaceAndOverrunBudget() {
        Deque<String> viewers = new ArrayDeque<>(List.of("large-retirement", "later-retirement"));
        List<String> visited = new ArrayList<>();
        FairViewerWork.run(viewers, 16, 32, ignored -> true, (viewer, remaining) -> { visited.add(viewer); return remaining; });
        FairViewerWork.run(viewers, 16, 32, ignored -> true, (viewer, remaining) -> { visited.add(viewer); return remaining; });
        assertEquals(List.of("large-retirement", "later-retirement"), visited);
        assertThrows(IllegalStateException.class, () -> FairViewerWork.run(viewers, 1, 32, ignored -> true, (viewer, remaining) -> 33));
    }
    @Test void idleWorkIsBoundedAndRetiredViewersAreRemoved() {
        Deque<Integer> viewers = new ArrayDeque<>(List.of(0, 1, 2, 3));
        List<Integer> visited = new ArrayList<>();
        assertEquals(0, FairViewerWork.run(viewers, 2, 32, viewer -> viewer != 0, (viewer, remaining) -> { visited.add(viewer); return 0; }));
        assertEquals(List.of(1), visited); assertEquals(List.of(2, 3, 1), List.copyOf(viewers));
        assertEquals(0, FairViewerWork.run(viewers, 16, 0, ignored -> true, (viewer, remaining) -> fail("Zero budget must not rotate viewers")));
        assertEquals(List.of(2, 3, 1), List.copyOf(viewers));
    }
}
