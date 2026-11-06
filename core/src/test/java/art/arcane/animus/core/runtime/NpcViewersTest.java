package art.arcane.animus.core.runtime;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcViewersTest {
    private static final UUID GUARD = new UUID(0, 1), MERCHANT = new UUID(0, 2);

    @Test
    void anNpcListsOnlyTheViewersThatShowIt() {
        NpcViewers<String> viewers = new NpcViewers<>();
        viewers.shown(GUARD, "alex");
        viewers.shown(GUARD, "sam");
        viewers.shown(MERCHANT, "sam");
        assertEquals(List.of("alex", "sam"), viewers.of(GUARD));
        assertEquals(List.of("sam"), viewers.of(MERCHANT));
        assertEquals(0, viewers.count(new UUID(0, 3)));

        viewers.hidden(GUARD, "alex");
        assertEquals(List.of("sam"), viewers.of(GUARD));
        viewers.forget("sam", List.of(GUARD, MERCHANT));
        assertEquals(List.of(), viewers.of(GUARD));
        assertEquals(0, viewers.count(MERCHANT));
    }

    @Test
    void theListIsACopySoRetiringWhileWalkingItIsSafe() {
        NpcViewers<String> viewers = new NpcViewers<>();
        viewers.shown(GUARD, "alex");
        viewers.shown(GUARD, "sam");
        for (String viewer : viewers.of(GUARD)) viewers.hidden(GUARD, viewer);
        assertEquals(0, viewers.count(GUARD));
    }
}
