package art.arcane.animus.core.behavior;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorEngine.Context;
import art.arcane.animus.core.behavior.BehaviorEngine.Limits;
import art.arcane.animus.core.behavior.BehaviorEngine.Report;
import art.arcane.animus.core.behavior.BehaviorEngine.State;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedVariableEngineTest {
    private static final String ACTION = "{\"type\":\"action\",\"capability\":\"sample:act\"}";
    private static final String NOTHING = "{\"type\":\"sequence\",\"steps\":[]}";
    private static final UUID NPC_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NPC_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @TempDir Path root;
    private DocumentStore documents;
    private BehaviorStateStore state;

    @BeforeEach
    void open() throws Exception {
        documents = new DocumentStore(root);
        state = BehaviorStateStore.open(documents, BehaviorStateStore.Limits.defaults());
    }

    @AfterEach
    void close() throws Exception {
        documents.close();
    }

    private void reopen(BehaviorStateStore.Limits limits) throws Exception {
        documents.close();
        documents = new DocumentStore(root);
        state = BehaviorStateStore.open(documents, limits);
    }

    private static final class Invoker implements BehaviorInvoker {
        final List<Invocation> actions = new ArrayList<>();
        CompletableFuture<Outcome<String>> held;

        @Override public boolean available(Capabilities.Key key, Capabilities.Kind kind) { return true; }
        @Override public Call<String> action(Invocation invocation) {
            actions.add(invocation);
            return new Call<>(held == null ? CompletableFuture.completedFuture(Outcome.ok("{}")) : held, () -> {});
        }
        @Override public Call<Boolean> condition(Invocation invocation) {
            return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)), () -> {});
        }
    }

    private static BehaviorProgram program(String variables, String main) {
        return BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"example:quest\",\"entry\":\"main\",\"variables\":"
                + variables + ",\"routines\":{\"main\":" + main + "}}").program().orElseThrow();
    }

    private static String declare(String name, String scope, String lifetime) {
        return "\"" + name + "\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"" + scope + "\",\"lifetime\":\"" + lifetime + "\"}";
    }

    private static String set(String name, long value) {
        return "{\"type\":\"set\",\"variable\":\"" + name + "\",\"value\":" + value + "}";
    }

    private static Context context(UUID npc, UUID player) {
        return new Context(new NpcHandle(npc), Optional.ofNullable(player), false);
    }

    private BehaviorEngine engine(Invoker invoker) {
        return new BehaviorEngine(Limits.defaults(), invoker, report -> {}, state);
    }

    private static Report finish(BehaviorEngine engine, UUID id) {
        for (int index = 0; index < 1000; index++) {
            Report report = engine.inspect(id).orElseThrow();
            if (report.state() != State.RUNNING) return report;
            engine.tick();
        }
        throw new AssertionError("Execution did not finish");
    }

    private static Report run(BehaviorEngine engine, BehaviorProgram program, Context context) {
        return finish(engine, engine.start(program, context).value().orElseThrow());
    }

    @Test
    void npcScopeIsSharedAcrossExecutionsButIsolatedBetweenNpcs() {
        BehaviorEngine engine = engine(new Invoker());
        BehaviorProgram write = program("{" + declare("v", "npc", "persistent") + "}", set("v", 7));
        BehaviorProgram read = program("{" + declare("v", "npc", "persistent") + "}", NOTHING);
        run(engine, write, context(NPC_A, null));
        assertEquals(7L, run(engine, read, context(NPC_A, null)).variables().get("v").value());
        assertEquals(0L, run(engine, read, context(NPC_B, null)).variables().get("v").value());
    }

    @Test
    void relationshipIsolatesEachNpcPlayerPairAndPlayerFollowsThePlayer() {
        BehaviorEngine engine = engine(new Invoker());
        String declarations = "{" + declare("r", "relationship", "persistent") + "," + declare("p", "player", "persistent") + "}";
        BehaviorProgram write = program(declarations, "{\"type\":\"sequence\",\"steps\":[" + set("r", 3) + "," + set("p", 4) + "]}");
        BehaviorProgram read = program(declarations, NOTHING);
        run(engine, write, context(NPC_A, PLAYER_A));
        Report samePair = run(engine, read, context(NPC_A, PLAYER_A));
        assertEquals(3L, samePair.variables().get("r").value());
        assertEquals(4L, samePair.variables().get("p").value());
        Report otherNpc = run(engine, read, context(NPC_B, PLAYER_A));
        assertEquals(0L, otherNpc.variables().get("r").value());
        assertEquals(4L, otherNpc.variables().get("p").value());
        Report otherPlayer = run(engine, read, context(NPC_A, PLAYER_B));
        assertEquals(0L, otherPlayer.variables().get("r").value());
        assertEquals(0L, otherPlayer.variables().get("p").value());
    }

    @Test
    void globalScopeBelongsToTheBehaviorNotToOtherBehaviors() {
        BehaviorEngine engine = engine(new Invoker());
        BehaviorProgram quest = program("{" + declare("g", "global", "transient") + "}", set("g", 9));
        BehaviorProgram other = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"example:other\",\"entry\":\"main\",\"variables\":{"
                + declare("g", "global", "transient") + "},\"routines\":{\"main\":" + NOTHING + "}}").program().orElseThrow();
        run(engine, quest, context(NPC_A, null));
        assertEquals(9L, run(engine, program("{" + declare("g", "global", "transient") + "}", NOTHING), context(NPC_B, null)).variables().get("g").value());
        assertEquals(0L, run(engine, other, context(NPC_A, null)).variables().get("g").value());
    }

    @Test
    void playerScopesWithoutAPlayerAreRejectedAtAdmissionWithTheVariableField() {
        BehaviorEngine engine = engine(new Invoker());
        Outcome<UUID> outcome = engine.start(program("{" + declare("r", "relationship", "transient") + "}", set("r", 1)), context(NPC_A, null));
        assertEquals(Outcome.Status.INVALID, outcome.status());
        Diagnostic diagnostic = outcome.diagnostics().getFirst();
        assertEquals("behavior-state-player-required", diagnostic.code());
        assertEquals("$.variables.r.scope", diagnostic.field());
    }

    @Test
    void engineWithoutAStateServiceRejectsScopedProgramsButRunsExecutionPrograms() {
        BehaviorEngine engine = new BehaviorEngine(Limits.defaults(), new Invoker(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        Outcome<UUID> outcome = engine.start(program("{" + declare("v", "npc", "transient") + "}", set("v", 1)), context(NPC_A, null));
        assertEquals(Outcome.Status.UNAVAILABLE, outcome.status());
        assertEquals("behavior-state-unavailable", outcome.diagnostics().getFirst().code());
        assertEquals(State.COMPLETED, run(engine, program("{\"v\":{\"type\":\"integer\",\"initial\":0}}", set("v", 1)), context(NPC_A, null)).state());
    }

    @Test
    void parallelBranchWritesStayPrivateUntilJoinThenPublishTogether() {
        Invoker invoker = new Invoker();
        invoker.held = new CompletableFuture<>();
        BehaviorEngine engine = engine(invoker);
        String declarations = "{" + declare("a", "npc", "transient") + "," + declare("b", "npc", "transient") + "}";
        String branches = "{\"type\":\"parallel\",\"branches\":[" + set("a", 1) + ",{\"type\":\"sequence\",\"steps\":[" + set("b", 2) + "," + ACTION + "]}]}";
        UUID id = engine.start(program(declarations, branches), context(NPC_A, null)).value().orElseThrow();
        for (int tick = 0; tick < 12; tick++) engine.tick();
        BehaviorProgram read = program(declarations, "{\"type\":\"wait\",\"ticks\":1}");
        Report during = run(engine, read, context(NPC_A, null));
        assertEquals(0L, during.variables().get("a").value(), "Finished branch must not publish before the join");
        assertEquals(0L, during.variables().get("b").value());
        assertEquals(2L, invoker.actions.getFirst().variables().get("b").value(), "A branch reads its own write");
        assertEquals(0L, invoker.actions.getFirst().variables().get("a").value(), "A branch never sees a sibling write");
        invoker.held.complete(Outcome.ok("{}"));
        assertEquals(State.COMPLETED, finish(engine, id).state());
        Report after = run(engine, read, context(NPC_A, null));
        assertEquals(1L, after.variables().get("a").value());
        assertEquals(2L, after.variables().get("b").value());
    }

    @Test
    void overlappingBranchWritesFailAndPublishNothingFromAnyBranch() {
        BehaviorEngine engine = engine(new Invoker());
        String declarations = "{" + declare("a", "npc", "persistent") + "," + declare("b", "npc", "persistent") + "}";
        String conflict = "{\"type\":\"parallel\",\"branches\":[{\"type\":\"sequence\",\"steps\":[" + set("a", 1) + "," + set("b", 5) + "]}," + set("a", 1) + "]}";
        Report report = run(engine, program(declarations, conflict), context(NPC_A, null));
        assertEquals(State.FAILED, report.state());
        assertEquals("parallel-write-conflict:a", report.reason());
        Report after = run(engine, program(declarations, NOTHING), context(NPC_A, null));
        assertEquals(0L, after.variables().get("a").value());
        assertEquals(0L, after.variables().get("b").value());
        assertEquals(0, state.status().persistentKeys());
    }

    @Test
    void cancellingBeforeJoinDiscardsBranchWritesAndKeepsEarlierRootWrites() {
        Invoker invoker = new Invoker();
        invoker.held = new CompletableFuture<>();
        BehaviorEngine engine = engine(invoker);
        String declarations = "{" + declare("a", "npc", "transient") + "," + declare("b", "npc", "transient") + "}";
        String main = "{\"type\":\"sequence\",\"steps\":[" + set("a", 1) + ",{\"type\":\"parallel\",\"branches\":[{\"type\":\"sequence\",\"steps\":[" + set("b", 2) + "," + ACTION + "]}]}]}";
        UUID id = engine.start(program(declarations, main), context(NPC_A, null)).value().orElseThrow();
        for (int tick = 0; tick < 12; tick++) engine.tick();
        assertTrue(engine.cancel(id, "test"));
        Report after = run(engine, program(declarations, NOTHING), context(NPC_A, null));
        assertEquals(1L, after.variables().get("a").value(), "A root write is not rolled back by later failure");
        assertEquals(0L, after.variables().get("b").value());
    }

    @Test
    void rootReadsObserveWritesFromOtherExecutionsAtEachUse() {
        Invoker invoker = new Invoker();
        BehaviorEngine engine = engine(invoker);
        String declarations = "{" + declare("g", "global", "transient") + "}";
        String bound = "{\"type\":\"sequence\",\"steps\":[{\"type\":\"wait\",\"ticks\":3},{\"type\":\"action\",\"capability\":\"sample:act\",\"arguments\":{\"value\":null},\"bindings\":{\"/value\":{\"variable\":\"g\"}}}]}";
        UUID reader = engine.start(program(declarations, bound), context(NPC_A, null)).value().orElseThrow();
        engine.tick();
        run(engine, program(declarations, set("g", 11)), context(NPC_B, null));
        assertEquals(State.COMPLETED, finish(engine, reader).state());
        assertTrue(invoker.actions.getFirst().argumentsJson().contains("11"), invoker.actions.getFirst().argumentsJson());
    }

    @Test
    void previewExecutionsNeverWriteSharedState() {
        BehaviorEngine engine = engine(new Invoker());
        BehaviorProgram write = program("{" + declare("v", "npc", "persistent") + "}", set("v", 7));
        Report preview = run(engine, write, new Context(new NpcHandle(NPC_A), Optional.empty(), true));
        assertEquals(7L, preview.variables().get("v").value());
        assertEquals(0, state.status().persistentKeys());
        assertEquals(0L, run(engine, program("{" + declare("v", "npc", "persistent") + "}", NOTHING), context(NPC_A, null)).variables().get("v").value());
    }

    @Test
    void redeclaredTypeRejectsAdmissionAndPreservesStoredValueWithoutCoercion() {
        BehaviorEngine engine = engine(new Invoker());
        run(engine, program("{" + declare("v", "npc", "persistent") + "}", set("v", 7)), context(NPC_A, null));
        BehaviorProgram changed = program("{\"v\":{\"type\":\"string\",\"initial\":\"x\",\"scope\":\"npc\",\"lifetime\":\"persistent\"}}", NOTHING);
        Outcome<UUID> outcome = engine.start(changed, context(NPC_A, null));
        assertEquals(Outcome.Status.CONFLICT, outcome.status());
        assertEquals("behavior-state-type-mismatch", outcome.diagnostics().getFirst().code());
        assertEquals("$.variables.v.type", outcome.diagnostics().getFirst().field());
        assertEquals(7L, run(engine, program("{" + declare("v", "npc", "persistent") + "}", NOTHING), context(NPC_A, null)).variables().get("v").value());
    }

    @Test
    void keyBudgetExhaustionFailsTheExecutionWithAnExactDiagnostic() throws Exception {
        reopen(new BehaviorStateStore.Limits(1, 8, 4096, 1024 * 1024, 5000));
        BehaviorEngine engine = engine(new Invoker());
        String declarations = "{" + declare("a", "npc", "persistent") + "," + declare("b", "npc", "persistent") + "}";
        Report report = run(engine, program(declarations, "{\"type\":\"sequence\",\"steps\":[" + set("a", 1) + "," + set("b", 2) + "]}"), context(NPC_A, null));
        assertEquals(State.FAILED, report.state());
        assertEquals("behavior-state-key-budget", report.reason());
        assertEquals("behavior-state-key-budget", report.diagnostics().getFirst().code());
        assertEquals("$.variables.b", report.diagnostics().getFirst().field());
        assertEquals(1, state.status().persistentKeys());
    }

    @Test
    void joinPublicationIsAllOrNothingUnderTheKeyBudget() throws Exception {
        reopen(new BehaviorStateStore.Limits(1, 8, 4096, 1024 * 1024, 5000));
        BehaviorEngine engine = engine(new Invoker());
        String declarations = "{" + declare("a", "npc", "persistent") + "," + declare("b", "npc", "persistent") + "}";
        Report report = run(engine, program(declarations, "{\"type\":\"parallel\",\"branches\":[" + set("a", 1) + "," + set("b", 2) + "]}"), context(NPC_A, null));
        assertEquals(State.FAILED, report.state());
        assertEquals("behavior-state-key-budget", report.reason());
        assertEquals(0, state.status().persistentKeys());
    }

    @Test
    void waitsNameTheConditionCapabilityAndTheWaitNode() {
        CompletableFuture<Outcome<Boolean>> answer = new CompletableFuture<>();
        BehaviorInvoker invoker = new BehaviorInvoker() {
            @Override public boolean available(Capabilities.Key key, Capabilities.Kind kind) { return true; }
            @Override public Call<String> action(Invocation invocation) { return new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")), () -> {}); }
            @Override public Call<Boolean> condition(Invocation invocation) { return new Call<>(answer, () -> {}); }
        };
        BehaviorEngine engine = new BehaviorEngine(Limits.defaults(), invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        UUID id = engine.start(program("{}", "{\"type\":\"condition\",\"capability\":\"sample:allowed\",\"then\":{\"type\":\"wait\",\"ticks\":9}}"), context(NPC_A, null)).value().orElseThrow();
        engine.tick();
        assertEquals(List.of("condition:sample:allowed@$.routines.main"), engine.inspect(id).orElseThrow().waits());
        answer.complete(Outcome.ok(true));
        for (int tick = 0; tick < 4; tick++) engine.tick();
        List<String> waits = engine.inspect(id).orElseThrow().waits();
        assertEquals(1, waits.size());
        assertTrue(waits.getFirst().startsWith("tick:") && waits.getFirst().endsWith("@$.routines.main.then"), waits.toString());
        assertTrue(engine.inspect(id).orElseThrow().trace().stream().anyMatch(trace -> trace.event().equals("condition-result:true")));
    }

    @Test
    void traceRingIsBoundedToTheNewestEntries() {
        BehaviorEngine engine = new BehaviorEngine(new Limits(4, 8, 4, 16, 1000, 1000, 4, 4, 4), new Invoker(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        Report report = run(engine, program("{\"v\":{\"type\":\"integer\",\"initial\":0}}", "{\"type\":\"repeat\",\"count\":20,\"body\":" + set("v", 1) + "}"), context(NPC_A, null));
        assertEquals(4, report.trace().size());
        assertTrue(report.trace().getLast().event().startsWith("completed:"));
        long previous = 0;
        for (BehaviorEngine.Trace trace : report.trace()) {
            assertTrue(trace.tick() >= previous);
            previous = trace.tick();
        }
    }
}
