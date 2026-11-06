package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.BehaviorInvoker;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.behavior.runtime.BehaviorRuntime;
import art.arcane.animus.core.npc.NpcDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner thread and interpreter running a server behavior; only the invoker is replaced. */
class ServerTriggerRuntimeTest {
    private static final String BEHAVIOR = "denizen:world/welcome";
    private final UUID player = UUID.randomUUID();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>();
    private final AtomicReference<ServerBehaviorList> enabled = new AtomicReference<>(new ServerBehaviorList(1, List.of(BEHAVIOR), List.of()));
    private final List<BehaviorInvoker.Invocation> invocations = new CopyOnWriteArrayList<>();
    private ServerTriggerDispatcher dispatcher;
    private BehaviorRuntime runtime;

    private void start(String routine) {
        BehaviorProgram program = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"server\":true,\"entry\":\"main\",\"routines\":{\"main\":" + routine
                + "},\"triggers\":[{\"id\":\"hello\",\"event\":\"player_join\"}]}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.of(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(accepted), List.of())), List.of()));
        dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, catalog::get, enabled::get);
        dispatcher.connect();
        BehaviorInvoker invoker = new BehaviorInvoker() {
            public boolean available(Capabilities.Key key, Capabilities.Kind kind) { return true; }
            public Call<String> action(Invocation invocation) { invocations.add(invocation); return new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")), () -> {}); }
            public Call<Boolean> condition(Invocation invocation) { return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)), () -> {}); }
        };
        runtime = new BehaviorRuntime(new BehaviorRuntime.Limits(8, 8, 50, 20), BehaviorEngine.Limits.defaults(), () -> Map.<String, NpcDefinition>of(), catalog::get, invoker, null, dispatcher, BehaviorState.UNAVAILABLE);
    }
    private static void eventually(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(check.getAsBoolean());
    }
    @AfterEach void stop() throws Exception { if (runtime != null) { runtime.close(); assertTrue(runtime.awaitStopped(Duration.ofSeconds(4))); } }

    @Test void serverEventsRunWithTheServerHandleAndThePlayer() throws Exception {
        start("{\"type\":\"action\",\"capability\":\"sample:act\"}");
        eventually(() -> dispatcher.listens(TriggerKind.PLAYER_JOIN));
        assertEquals(Outcome.Status.OK, dispatcher.publish(ServerEvent.join(player, true)).status());
        eventually(() -> !invocations.isEmpty());
        assertEquals(ServerNpc.HANDLE, invocations.getFirst().npc());
        assertEquals(Optional.of(player), invocations.getFirst().player());
        assertEquals(BEHAVIOR, invocations.getFirst().behavior());
        eventually(() -> dispatcher.snapshot().active() == 0);
        eventually(() -> !runtime.snapshot().executions().isEmpty());
        BehaviorRuntime.Execution execution = runtime.snapshot().executions().values().iterator().next();
        assertEquals(BehaviorRuntime.SERVER_NPC_ID, execution.npcId());
        assertEquals(Optional.of(BEHAVIOR + "#hello"), execution.trigger());
    }

    @Test void disablingTheBehaviorCancelsItsRuns() throws Exception {
        start("{\"type\":\"wait\",\"ticks\":2000}");
        eventually(() -> dispatcher.listens(TriggerKind.PLAYER_JOIN));
        dispatcher.publish(ServerEvent.join(player, false));
        eventually(() -> dispatcher.snapshot().active() == 1);
        enabled.set(new ServerBehaviorList(2, List.of(), List.of()));
        eventually(() -> dispatcher.snapshot().active() == 0);
        // The dispatcher forgets the run before the engine publishes its final report, so wait for the report too.
        eventually(() -> runtime.snapshot().executions().values().iterator().next().report().state() == BehaviorEngine.State.CANCELLED);
        BehaviorRuntime.Execution execution = runtime.snapshot().executions().values().iterator().next();
        assertEquals(BehaviorEngine.State.CANCELLED, execution.report().state());
    }
}
