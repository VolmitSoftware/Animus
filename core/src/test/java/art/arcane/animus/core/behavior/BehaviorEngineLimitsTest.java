package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorEngine.Limits;
import art.arcane.animus.core.behavior.BehaviorEngine.State;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.compile;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.document;
import static art.arcane.animus.core.behavior.BehaviorEngineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorEngineLimitsTest {
    private static final String WAIT = "{\"type\":\"wait\",\"ticks\":20}";

    private static BehaviorEngine engine(Limits limits, Fake invoker) {
        return new BehaviorEngine(limits, invoker, ignored -> { }, BehaviorState.UNAVAILABLE);
    }

    @Test void loweredExecutionCapLetsRunningExecutionsFinishAndRefusesNewOnesUntilDrained() {
        BehaviorEngine engine = engine(limits(4, 8, 4, 8, 100, 100, 4, 8, 8), new Fake());
        BehaviorProgram program = compile(document(WAIT));
        List<UUID> running = new ArrayList<>();
        for (int i = 0; i < 3; i++) running.add(start(engine, program));
        engine.limits(limits(1, 8, 4, 8, 100, 100, 4, 8, 8));
        Outcome<UUID> refused = engine.start(program, context());
        assertEquals(Outcome.Status.BUSY, refused.status());
        for (UUID id : running) assertEquals(State.COMPLETED, finish(engine, id).state(), "a lowered cap stops nothing");
        assertEquals(Outcome.Status.OK, engine.start(program, context()).status());
        assertEquals(Outcome.Status.BUSY, engine.start(program, context()).status());
    }

    @Test void runningExecutionKeepsItsStepLifetimeAndDepthBudgets() {
        BehaviorEngine engine = engine(limits(4, 8, 4, 8, 100, 100, 4, 8, 8), new Fake());
        UUID waiting = start(engine, compile(document(WAIT)));
        UUID stepping = start(engine, compile(document("{\"type\":\"repeat\",\"count\":10,\"body\":{\"type\":\"sequence\",\"steps\":[]}}")));
        engine.tick();
        engine.limits(limits(4, 8, 4, 8, 3, 3, 4, 8, 8));
        assertEquals(State.COMPLETED, finish(engine, waiting).state());
        assertEquals(State.COMPLETED, finish(engine, stepping).state());
        UUID fresh = start(engine, compile(document(WAIT)));
        assertEquals("execution-lifetime", finish(engine, fresh).reason(), "new executions take the new budget");
    }

    @Test void loweredInFlightCapMakesNewCallsWaitWithoutCancellingPendingOnes() {
        Fake invoker = new Fake();
        List<CompletableFuture<Outcome<String>>> answers = new ArrayList<>();
        invoker.action = invocation -> {
            CompletableFuture<Outcome<String>> answer = new CompletableFuture<>();
            answers.add(answer);
            return new BehaviorInvoker.Call<>(answer, invoker.cancellations::incrementAndGet);
        };
        BehaviorEngine engine = engine(limits(4, 8, 4, 8, 100, 1000, 4, 8, 8), invoker);
        BehaviorProgram program = compile(document(ACTION));
        UUID first = start(engine, program), second = start(engine, program);
        engine.tick();
        assertEquals(2, engine.status().callsInFlight());
        engine.limits(limits(4, 8, 1, 8, 100, 1000, 4, 8, 8));
        UUID third = start(engine, program);
        engine.tick();
        assertEquals(2, invoker.actions.size(), "the third call waits for capacity");
        assertEquals(0, invoker.cancellations.get());
        answers.get(0).complete(Outcome.ok("{}"));
        answers.get(1).complete(Outcome.ok("{}"));
        engine.tick();
        engine.tick();
        assertEquals(State.COMPLETED, engine.inspect(first).orElseThrow().state());
        assertEquals(State.COMPLETED, engine.inspect(second).orElseThrow().state());
        assertEquals(3, invoker.actions.size());
        answers.get(2).complete(Outcome.ok("{}"));
        assertEquals(State.COMPLETED, finish(engine, third).state());
    }
}
