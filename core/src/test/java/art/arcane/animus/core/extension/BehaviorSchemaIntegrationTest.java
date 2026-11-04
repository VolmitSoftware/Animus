package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import art.arcane.animus.core.behavior.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorSchemaIntegrationTest {
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        public DefinitionService scoped(java.util.function.BooleanSupplier available) { return this; }
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private static TrustedModule module(AtomicInteger calls, AtomicReference<String> received) { return new TrustedModule() {
        public ModuleManifest manifest() { return new ModuleManifest("sample", new Version(1, 0, 0), new ApiRange(1, 1), Set.of(Platform.PAPER), List.of()); }
        public void enable(ModuleContext context) {
            context.registrations().action(new Descriptor(new Key("sample", "take"), Kind.ACTION, "Resolved arguments",
                    "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"integer\"}},\"required\":[\"amount\"],\"additionalProperties\":false}", Set.of(Platform.PAPER), false), invocation -> {
                calls.incrementAndGet(); received.set(invocation.argumentsJson()); return CompletableFuture.completedFuture("{}");
            });
        }
        public void disable() { }
    }; }
    private static BehaviorProgram program(String type, String value) {
        String json = "{\"schemaVersion\":1,\"id\":\"sample:workflow\",\"entry\":\"main\",\"variables\":{\"amount\":{\"type\":\"" + type + "\",\"initial\":" + value + "},\"private\":{\"type\":\"string\",\"initial\":\"never-inject\"}},\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"sample:take\",\"arguments\":{\"amount\":null},\"bindings\":{\"/amount\":{\"variable\":\"amount\"}}}}}";
        var compiled = BehaviorCompiler.compile(json); assertTrue(compiled.diagnostics().isEmpty()); return compiled.program().orElseThrow();
    }
    private static BehaviorEngine.Report run(BehaviorEngine engine, BehaviorProgram program) throws Exception {
        var started = engine.start(program, new BehaviorEngine.Context(new NpcHandle(UUID.randomUUID()), Optional.empty(), false));
        UUID id = started.value().orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            engine.tick();
            var report = engine.inspect(id).orElseThrow();
            if (report.state() != BehaviorEngine.State.RUNNING && engine.status().callsInFlight() == 0) return report;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        fail("Combined behavior/schema workflow did not settle"); return null;
    }
    @Test void boundTypedValuesAreValidatedAfterResolutionAndReachTheHandlerExactly() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> received = new AtomicReference<>();
        try (var host = new ModuleHost(Platform.PAPER, DEFINITIONS, 4, Duration.ofSeconds(2));
             var engine = new BehaviorEngine(BehaviorEngine.Limits.defaults(), new HostBehaviorInvoker(host),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE)) {
            host.install(module(calls, received));
            var report = run(engine, program("integer", "9223372036854775807"));
            assertEquals(BehaviorEngine.State.COMPLETED, report.state()); assertEquals(1, calls.get());
            assertEquals("{\"amount\":9223372036854775807}", received.get());
            assertFalse(received.get().contains("never-inject"));
        }
    }
    @Test void schemaMismatchEndsExecutionWithoutEnteringTheModuleOrRetainingAHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> received = new AtomicReference<>();
        try (var host = new ModuleHost(Platform.PAPER, DEFINITIONS, 4, Duration.ofSeconds(2));
             var engine = new BehaviorEngine(BehaviorEngine.Limits.defaults(), new HostBehaviorInvoker(host),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE)) {
            host.install(module(calls, received));
            var report = run(engine, program("string", "\"123\""));
            assertEquals(BehaviorEngine.State.FAILED, report.state()); assertEquals("invocation-invalid", report.reason());
            assertEquals("schema-type", report.diagnostics().getFirst().code()); assertEquals("$/amount", report.diagnostics().getFirst().field());
            assertEquals(0, calls.get()); assertNull(received.get()); assertEquals(0, host.outstandingInvocations());
            assertEquals(ModuleHost.State.ACTIVE, host.modules().getFirst().state());
        }
    }
}
