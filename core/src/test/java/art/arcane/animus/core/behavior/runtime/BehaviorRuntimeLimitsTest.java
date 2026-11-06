package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.BehaviorState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static art.arcane.animus.core.behavior.runtime.BehaviorRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorRuntimeLimitsTest {
    private static BehaviorRuntime runtime(Sources sources, int maximumOperations) {
        return new BehaviorRuntime(new BehaviorRuntime.Limits(maximumOperations, Math.min(8, maximumOperations), 50, 20), BehaviorEngine.Limits.defaults(),
                sources.npcs::get, sources.catalog::get, new Fake(), null, BehaviorState.UNAVAILABLE);
    }
    /** An operation whose delivery blocks on {@code release} keeps its admission until released. */
    private static CompletionStage<Outcome<Boolean>> held(BehaviorRuntime runtime, CountDownLatch release, CountDownLatch delivering) {
        CompletionStage<Outcome<Boolean>> stage = runtime.cancel(UUID.randomUUID());
        stage.whenComplete((outcome, failure) -> { delivering.countDown(); latch(release); });
        return stage;
    }
    private static Outcome.Status probe(BehaviorRuntime runtime) throws Exception { return await(runtime.cancel(UUID.randomUUID())).status(); }

    @Test void loweredOperationCapRefusesNewWorkUntilAdmittedWorkDrains() throws Exception {
        Sources sources = new Sources(WAIT);
        BehaviorRuntime runtime = runtime(sources, 8);
        CountDownLatch release = new CountDownLatch(1), delivering = new CountDownLatch(1);
        try {
            UUID running = await(runtime.run("test", "example:manual")).value().orElseThrow();
            List<CompletionStage<Outcome<Boolean>>> holding = new ArrayList<>();
            holding.add(held(runtime, release, delivering));
            latch(delivering);
            runtime.limits(new BehaviorRuntime.Limits(1, 1, 50, 20), BehaviorEngine.Limits.defaults());
            eventually(() -> { try { return probe(runtime) == Outcome.Status.BUSY; } catch (Exception failure) { throw new AssertionError(failure); } });
            assertEquals(BehaviorEngine.State.RUNNING, runtime.inspect(running).orElseThrow().report().state(), "the running execution is untouched");
            release.countDown();
            for (CompletionStage<Outcome<Boolean>> stage : holding) assertEquals(Outcome.Status.OK, await(stage).status());
            eventually(() -> { try { return probe(runtime) == Outcome.Status.OK; } catch (Exception failure) { throw new AssertionError(failure); } });
        } finally { release.countDown(); stop(runtime); }
    }

    @Test void raisedOperationCapAdmitsMoreWithoutOverflowingDeliveries() throws Exception {
        Sources sources = new Sources(WAIT);
        BehaviorRuntime runtime = runtime(sources, 1);
        CountDownLatch release = new CountDownLatch(1), delivering = new CountDownLatch(1);
        try {
            held(runtime, release, delivering);
            latch(delivering);
            assertEquals(Outcome.Status.BUSY, probe(runtime));
            runtime.limits(new BehaviorRuntime.Limits(32, 8, 50, 20), BehaviorEngine.Limits.defaults());
            eventually(() -> { try { return probe(runtime) == Outcome.Status.OK; } catch (Exception failure) { throw new AssertionError(failure); } });
            List<CompletionStage<Outcome<Boolean>>> burst = new ArrayList<>();
            for (int i = 0; i < 24; i++) burst.add(runtime.cancel(UUID.randomUUID()));
            for (CompletionStage<Outcome<Boolean>> stage : burst) assertNotEquals(Outcome.Status.FAILED, await(stage).status());
            assertEquals("", runtime.status().failure());
        } finally { release.countDown(); stop(runtime); }
    }
}
