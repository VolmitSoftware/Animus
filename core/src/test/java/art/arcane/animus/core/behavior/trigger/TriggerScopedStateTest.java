package art.arcane.animus.core.behavior.trigger;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.BehaviorInvoker;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real owner thread, interpreter, dispatcher and state store; only the module invoker is replaced. */
class TriggerScopedStateTest {
    private static final String BEHAVIOR = "example:quest";
    private static final Value ZERO = new Value(Type.INTEGER, 0L);
    private final UUID npc = UUID.randomUUID(), firstPlayer = UUID.randomUUID(), secondPlayer = UUID.randomUUID();
    private final AtomicReference<Map<String, NpcDefinition>> definitions = new AtomicReference<>();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>();
    private final ExecutorService lane = Executors.newSingleThreadExecutor();

    @TempDir Path root;
    private DocumentStore documents;
    private BehaviorStateStore state;
    private TriggerDispatcher dispatcher;
    private ManualBehaviorRuntime runtime;

    private static void eventually(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(check.getAsBoolean());
    }

    private void start(String variables, String routines, String triggers) throws Exception {
        BehaviorProgram program = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"entry\":\"main\",\"variables\":" + variables
                + ",\"routines\":" + routines + ",\"triggers\":" + triggers + "}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, "1".repeat(64), 100, program, Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.of(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(accepted), List.of())), List.of()));
        definitions.set(Map.of("test", NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + npc + "\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":true,\"behaviors\":[\"" + BEHAVIOR + "\"],\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}")));
        documents = new DocumentStore(root);
        state = BehaviorStateStore.open(documents, new BehaviorStateStore.Limits(64, 64, 4096, 65536, 100));
        state.attach(lane);
        dispatcher = new TriggerDispatcher(new TriggerSettings(true, 16, 32, 8, 64, 4, 4, 1000, 32, 4, 4, 0, 0, 4096, 1024, 1, 32, 5, 16, 32, 64), definitions::get, catalog::get);
        dispatcher.connect();
        BehaviorInvoker invoker = new BehaviorInvoker() {
            @Override public boolean available(Capabilities.Key key, Capabilities.Kind kind) { return true; }
            @Override public Call<String> action(Invocation invocation) { return new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")), () -> {}); }
            @Override public Call<Boolean> condition(Invocation invocation) { return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)), () -> {}); }
        };
        runtime = new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8, 8, 50, 20), BehaviorEngine.Limits.defaults(), definitions::get, catalog::get, invoker, dispatcher, state);
        dispatcher.activated(npc);
        eventually(() -> dispatcher.snapshot().bindings() > 0);
    }

    private void interact(UUID player) {
        assertEquals(Outcome.Status.OK, dispatcher.publish(new TriggerEvent.Interaction(npc, player, TriggerEvent.Action.INTERACT)).status());
    }

    private Value stored(String name, ScopedVariable declaration, UUID player) {
        return state.read(new BehaviorState.Subject(BEHAVIOR, npc, Optional.ofNullable(player)), name, declaration, ZERO);
    }

    @AfterEach
    void stop() throws Exception {
        if (runtime != null) {
            runtime.close();
            assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));
        }
        lane.shutdown();
        assertTrue(lane.awaitTermination(4, TimeUnit.SECONDS));
        if (documents != null) documents.close();
    }

    @Test
    void playerAndRelationshipScopesResolveFromTheTriggeringPlayer() throws Exception {
        ScopedVariable relationship = new ScopedVariable(Scope.RELATIONSHIP, Lifetime.PERSISTENT), player = new ScopedVariable(Scope.PLAYER, Lifetime.PERSISTENT);
        start("{\"r\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"relationship\",\"lifetime\":\"persistent\"},\"p\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"player\",\"lifetime\":\"persistent\"}}",
                "{\"main\":{\"type\":\"sequence\",\"steps\":[{\"type\":\"set\",\"variable\":\"r\",\"value\":3},{\"type\":\"set\",\"variable\":\"p\",\"value\":4}]}}",
                "[{\"id\":\"click\",\"event\":\"interaction\"}]");
        interact(firstPlayer);
        eventually(() -> stored("p", player, firstPlayer).value().equals(4L));
        assertEquals(3L, stored("r", relationship, firstPlayer).value());
        assertEquals(0L, stored("r", relationship, secondPlayer).value(), "Another player's state is untouched");
        assertEquals(0L, stored("p", player, secondPlayer).value());
    }

    @Test
    void triggerWithoutAPlayerRefusesPlayerScopesAtAdmissionAndIsCounted() throws Exception {
        start("{\"r\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"relationship\",\"lifetime\":\"transient\"}}",
                "{\"main\":{\"type\":\"set\",\"variable\":\"r\",\"value\":3}}",
                "[{\"id\":\"beat\",\"event\":\"timer\",\"intervalTicks\":1}]");
        eventually(() -> dispatcher.snapshot().dropped().getOrDefault(TriggerDispatcher.DropReason.START_FAILED, 0L) > 0);
        TriggerDispatcher.Snapshot snapshot = dispatcher.snapshot();
        assertEquals(0, snapshot.started());
        assertEquals("behavior-state-player-required", snapshot.diagnostics().getFirst().code());
        assertTrue(snapshot.diagnostics().getFirst().field().contains("trigger=" + BEHAVIOR + "#beat"), snapshot.diagnostics().getFirst().field());
        assertTrue(snapshot.diagnostics().getFirst().field().endsWith("$.variables.r.scope"), snapshot.diagnostics().getFirst().field());
        assertEquals(0, state.status().transientKeys());
        assertTrue(runtime.snapshot().executions().isEmpty(), "A refused start leaves no execution");
    }

    @Test
    void npcScopeWorksFromATimerAndReachesDiskWhileRunning() throws Exception {
        start("{\"n\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\"}}",
                "{\"main\":{\"type\":\"set\",\"variable\":\"n\",\"value\":9}}",
                "[{\"id\":\"beat\",\"event\":\"timer\",\"intervalTicks\":5}]");
        Path file = root.resolve("state/behavior-state/npc/" + npc + "/example/quest.json");
        eventually(() -> Files.exists(file));
        assertTrue(Files.readString(file).contains("\"value\": 9"));
    }

    @Test
    void routinesOfOneBehaviorShareItsScopedVariables() throws Exception {
        start("{\"n\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"transient\"}}",
                "{\"main\":{\"type\":\"sequence\",\"steps\":[]},\"greet\":{\"type\":\"set\",\"variable\":\"n\",\"value\":5}}",
                "[{\"id\":\"click\",\"event\":\"interaction\",\"routine\":\"greet\"}]");
        interact(firstPlayer);
        ScopedVariable declaration = new ScopedVariable(Scope.NPC, Lifetime.TRANSIENT);
        eventually(() -> stored("n", declaration, null).value().equals(5L));
    }

    @Test
    void npcTraceAndObserveShowConcurrentTriggerStartedRuns() throws Exception {
        start("{}", "{\"main\":{\"type\":\"wait\",\"ticks\":2000}}", "[{\"id\":\"click\",\"event\":\"interaction\"}]");
        interact(firstPlayer);
        interact(secondPlayer);
        eventually(() -> dispatcher.snapshot().started() == 2);
        ManualBehaviorRuntime.NpcTrace trace = runtime.observeNpc("test").toCompletableFuture().get(4, TimeUnit.SECONDS).value().orElseThrow();
        assertEquals(2, trace.active().size());
        for (ManualBehaviorRuntime.Execution execution : trace.active()) {
            assertEquals(Optional.of(BEHAVIOR + "#click"), execution.trigger());
            assertTrue(execution.report().waits().getFirst().startsWith("tick:"), execution.report().waits().toString());
            ManualBehaviorRuntime.Execution observed = runtime.observe(execution.report().id()).toCompletableFuture().get(4, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals(Optional.of(BEHAVIOR + "#click"), observed.trigger());
        }
        assertEquals(Set.of(firstPlayer, secondPlayer), Set.of(trace.active().getFirst().player().orElseThrow(), trace.active().getLast().player().orElseThrow()));
    }
}
