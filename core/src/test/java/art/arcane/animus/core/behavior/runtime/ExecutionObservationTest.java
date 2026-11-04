package art.arcane.animus.core.behavior.runtime;

import static art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime.Execution;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime.NpcTrace;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionObservationTest {
    @Test
    void freshObservationReportsCurrentWaitsAndUnknownExecutionsAreNotFound() throws Exception {
        Sources sources = new Sources(WAIT);
        ManualBehaviorRuntime runtime = sources.runtime(new Fake());
        try {
            UUID id = await(runtime.run("test", "example:manual")).value().orElseThrow();
            eventually(() -> runtime.status().engine().tick() > 3);
            Execution view = await(runtime.observe(id)).value().orElseThrow();
            assertEquals(BehaviorEngine.State.RUNNING, view.report().state());
            assertEquals(1, view.report().waits().size());
            assertTrue(view.report().waits().getFirst().startsWith("tick:"), view.report().waits().toString());
            assertFalse(view.report().trace().isEmpty());
            assertEquals(Outcome.Status.NOT_FOUND, await(runtime.observe(UUID.randomUUID())).status());
        } finally {
            stop(runtime);
        }
    }

    @Test
    void npcTraceShowsTheActiveRunAndABoundedRingOfRecentRuns() throws Exception {
        Sources sources = new Sources(EMPTY);
        ManualBehaviorRuntime runtime = sources.runtime(new Fake());
        try {
            UUID npc = sources.npcs.get().get("test").uuid();
            for (int index = 0; index < 7; index++) {
                UUID id = await(runtime.run("test", "example:manual")).value().orElseThrow();
                eventually(() -> runtime.inspect(id).orElseThrow().report().state() == BehaviorEngine.State.COMPLETED);
            }
            NpcTrace trace = await(runtime.observeNpc("test")).value().orElseThrow();
            assertEquals(npc, trace.npc());
            assertTrue(trace.active().isEmpty());
            assertEquals(ManualBehaviorRuntime.RECENT_PER_NPC, trace.recent().size());
            NpcTrace byUuid = await(runtime.observeNpc(npc.toString())).value().orElseThrow();
            assertEquals(trace.recent().size(), byUuid.recent().size());
            sources.replaceProgram(WAIT, 2);
            UUID active = await(runtime.run("test", "example:manual")).value().orElseThrow();
            assertEquals(active, await(runtime.observeNpc("test")).value().orElseThrow().active().orElseThrow().report().id());
            assertEquals(Outcome.Status.NOT_FOUND, await(runtime.observeNpc("nobody")).status());
        } finally {
            stop(runtime);
        }
    }

    @Test
    void activeViewsAreNotRebuiltOnTicksWhenNobodyIsLooking() throws Exception {
        Sources sources = new Sources(WAIT);
        ManualBehaviorRuntime runtime = sources.runtime(new Fake());
        try {
            UUID id = await(runtime.run("test", "example:manual")).value().orElseThrow();
            long ticks = runtime.status().engine().tick();
            eventually(() -> runtime.status().engine().tick() > ticks + 2);
            long publications = runtime.status().viewPublications();
            long later = runtime.status().engine().tick();
            eventually(() -> runtime.status().engine().tick() > later + 4);
            assertEquals(publications, runtime.status().viewPublications(), "Unobserved ticks publish status only");
            assertTrue(runtime.inspect(id).isPresent());
            long observed = runtime.status().engine().tick();
            eventually(() -> runtime.status().engine().tick() > observed + 2);
            assertTrue(runtime.status().viewPublications() > publications, "Looking resumes view publication");
        } finally {
            stop(runtime);
        }
    }

    @Test
    void ownerTurnsDriveStateMaintenance() throws Exception {
        Sources sources = new Sources(EMPTY);
        AtomicInteger maintained = new AtomicInteger();
        BehaviorState state = new BehaviorState() {
            @Override public Value read(Subject subject, String name, ScopedVariable declaration, Value initial) { return initial; }
            @Override public void write(Subject subject, Map<String, Value> writes, Map<String, ScopedVariable> declarations) {}
            @Override public void maintain() { maintained.incrementAndGet(); }
        };
        ManualBehaviorRuntime runtime = new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8, 8, 50, 20), BehaviorEngine.Limits.defaults(), sources.npcs::get, sources.catalog::get, new Fake(), state);
        try {
            eventually(() -> maintained.get() > 1);
        } finally {
            stop(runtime);
        }
    }
}
