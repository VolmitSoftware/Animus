package art.arcane.animus.core.behavior.state;

import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.state.BehaviorStateStore.Limits;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StateLimitsHotloadTest {
    private static final ScopedVariable PERSISTENT = new ScopedVariable(Scope.NPC, Lifetime.PERSISTENT);
    private static final BehaviorState.Subject SUBJECT = new BehaviorState.Subject("example:quests/town", UUID.randomUUID(), Optional.empty());
    private static final Value ZERO = new Value(Type.INTEGER, 0L);
    @TempDir Path root;

    private static Value integer(long value) { return new Value(Type.INTEGER, value); }
    private static void set(BehaviorStateStore state, String name, long value) { state.write(SUBJECT, Map.of(name, integer(value)), Map.of(name, PERSISTENT)); }
    private static Value get(BehaviorStateStore state, String name) { return state.read(SUBJECT, name, PERSISTENT, ZERO); }

    @Test void loweredKeyCapKeepsStoredValuesAndRefusesOnlyNewKeys() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(8, 8, 4096, 65536, 1000));
            for (int i = 0; i < 4; i++) set(state, "k" + i, i);
            state.limits(new Limits(2, 8, 4096, 65536, 1000));
            for (int i = 0; i < 4; i++) assertEquals(integer(i), get(state, "k" + i), "nothing stored is deleted");
            set(state, "k1", 41);
            assertEquals(integer(41), get(state, "k1"), "existing keys still change");
            BehaviorState.Rejected refused = assertThrows(BehaviorState.Rejected.class, () -> set(state, "k9", 9));
            assertEquals("behavior-state-key-budget", refused.diagnostic().code());
            assertEquals(4, state.status().persistentKeys());
            state.purge(BehaviorStateStore.Purge.all());
            set(state, "k9", 9);
            assertEquals(integer(9), get(state, "k9"));
        }
    }

    @Test void shorterFlushIntervalPullsThePendingFlushIn() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<Runnable> lane = new ArrayList<>();
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(8, 8, 4096, 65536, 60_000), clock::get);
            state.attach(lane::add);
            set(state, "v", 1);
            clock.addAndGet(1_000_000_000L);
            state.maintain();
            assertTrue(lane.isEmpty(), "the original interval has a minute to go");
            state.limits(new Limits(8, 8, 4096, 65536, 500));
            clock.addAndGet(600_000_000L);
            state.maintain();
            assertEquals(1, lane.size());
            lane.getFirst().run();
            assertEquals(0, state.status().dirtyDocuments());
        }
    }
}
