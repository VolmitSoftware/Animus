package art.arcane.animus.core.runtime;

import art.arcane.animus.core.presentation.BodyTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFailuresTest {
    @Test
    void logsEachCauseOnceWithContextAndKeepsTheLastForStatus() {
        List<String> warnings = new ArrayList<>();
        RuntimeFailures failures = new RuntimeFailures(warnings::add);
        failures.record("spawn", "viewer=Alex, npc=guard-1", new IllegalStateException("Viewer packet queue is full"));
        failures.record("spawn", "viewer=Sam, npc=guard-2", new IllegalStateException("Viewer packet queue is full"));
        failures.record("move", "viewer=Sam, npc=guard-2", new IllegalStateException("Viewer packet queue is full"));

        assertEquals(List.of("NPC runtime spawn failed (viewer=Alex, npc=guard-1): IllegalStateException: Viewer packet queue is full",
                "NPC runtime move failed (viewer=Sam, npc=guard-2): IllegalStateException: Viewer packet queue is full"), warnings);
        assertEquals("IllegalStateException: Viewer packet queue is full", failures.last());
    }

    @Test
    void stopsLoggingNewCausesPastTheBoundButStillRecordsThem() {
        List<String> warnings = new ArrayList<>();
        RuntimeFailures failures = new RuntimeFailures(warnings::add);
        for (int i = 0; i < RuntimeFailures.MAX_CAUSES + 10; i++) failures.record("spawn", "", "cause " + i);
        assertEquals(RuntimeFailures.MAX_CAUSES + 1, warnings.size());
        assertEquals("cause " + (RuntimeFailures.MAX_CAUSES + 9), failures.last());
    }

    @Test
    void anEndedWatchIsRecognisedThroughWrappersAndOtherCancellationsAreNot() {
        assertTrue(RuntimeFailures.viewerGone(new CompletionException(new BodyTransport.ViewerUnwatched("Viewer session retired"))));
        assertFalse(RuntimeFailures.viewerGone(new CancellationException("Body spawn was cancelled")));
        assertFalse(RuntimeFailures.viewerGone(new IllegalStateException("Viewer presentation ID limit reached")));
    }
}
