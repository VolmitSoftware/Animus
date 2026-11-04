package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSchemaTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        public DefinitionService scoped(java.util.function.BooleanSupplier available) { return this; }
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private static ModuleHost host() { return new ModuleHost(Platform.PAPER, DEFINITIONS, 16, Duration.ofSeconds(2)); }
    private static Descriptor descriptor(String name, Kind kind, String schema) {
        return new Descriptor(new Key("schema", name), kind, "Schema validation fixture", schema, Set.of(Platform.PAPER), false);
    }
    private static TrustedModule module(Consumer<ModuleContext> enable) { return new TrustedModule() {
        public ModuleManifest manifest() { return new ModuleManifest("schema", new Version(1, 0, 0), new ApiRange(1, 1), Set.of(Platform.PAPER), List.of()); }
        public void enable(ModuleContext context) { enable.accept(context); }
        public void disable() { }
    }; }
    private static <T> T await(CompletionStage<T> result) throws Exception { return result.toCompletableFuture().get(3, TimeUnit.SECONDS); }
    @Test void invalidResolvedArgumentsNeverEnterActionHandlerAndValidValuesStayExact() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> received = new AtomicReference<>();
        try (ModuleHost host = host()) {
            String schema = "{\"properties\":{\"amount\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"amount\"],\"additionalProperties\":false}";
            assertEquals(Outcome.Status.OK, host.install(module(context -> context.registrations().action(descriptor("act", Kind.ACTION, schema), invocation -> {
                calls.incrementAndGet(); received.set(invocation.argumentsJson()); return CompletableFuture.completedFuture("{}");
            }))).status());
            for (String bad : List.of("{}", "{\"amount\":null}", "{\"amount\":\"5\"}", "{\"amount\":0}", "{\"amount\":1,\"extra\":true}")) {
                var result = await(host.action(new Key("schema", "act"), NPC, Optional.empty(), bad, false, false));
                assertEquals(Outcome.Status.INVALID, result.status()); assertFalse(result.diagnostics().isEmpty());
            }
            assertEquals(0, calls.get()); assertEquals(ModuleHost.State.ACTIVE, host.modules().getFirst().state());
            String good = "{\"amount\":9223372036854775807}";
            assertEquals(Outcome.Status.OK, await(host.action(new Key("schema", "act"), NPC, Optional.empty(), good, false, false)).status());
            assertEquals(good, received.get()); assertEquals(1, calls.get());
        }
    }
    @Test void conditionsUseTheSameCompiledSchemaBeforeCallback() throws Exception {
        AtomicInteger called = new AtomicInteger();
        try (ModuleHost host = host()) {
            host.install(module(context -> context.registrations().condition(descriptor("check", Kind.CONDITION, "{\"required\":[\"ready\"],\"properties\":{\"ready\":{\"const\":true}}}"), invocation -> {
                called.incrementAndGet(); return CompletableFuture.completedFuture(false);
            })));
            assertEquals(Outcome.Status.INVALID, await(host.condition(new Key("schema", "check"), NPC, Optional.empty(), "{\"ready\":1}", false)).status());
            assertEquals(0, called.get());
            var valid = await(host.condition(new Key("schema", "check"), NPC, Optional.empty(), "{\"ready\":true}", false));
            assertEquals(Outcome.Status.OK, valid.status()); assertFalse(valid.value().orElseThrow()); assertEquals(1, called.get());
        }
    }
    @Test void unsupportedSchemaRollsBackAllStagedCapabilitiesAndReportsField() {
        AtomicInteger cleaned = new AtomicInteger();
        try (ModuleHost host = host()) {
            var result = host.install(module(context -> {
                context.onClose(cleaned::incrementAndGet);
                context.registrations().action(descriptor("first", Kind.ACTION, "{}"), invocation -> CompletableFuture.completedFuture("{}"));
                context.registrations().action(descriptor("bad", Kind.ACTION, "{\"properties\":{\"name\":{\"pattern\":\".*\"}}}"), invocation -> CompletableFuture.completedFuture("{}"));
            }));
            assertEquals(Outcome.Status.FAILED, result.status()); assertEquals(1, cleaned.get());
            assertTrue(host.capabilities().isEmpty());
            assertEquals("schema-keyword-unavailable", result.diagnostics().getFirst().code());
            assertEquals("$/properties/name/pattern", result.diagnostics().getFirst().field());
        }
    }
    @Test void registryMetadataAndValidationKeepTheSameSchemaBytes() throws Exception {
        String schema = "{\"properties\":{\"x\":{\"type\":\"boolean\"}},\"required\":[\"x\"]}";
        AtomicReference<String> worker = new AtomicReference<>();
        try (ModuleHost host = host()) {
            host.install(module(context -> context.registrations().action(descriptor("act", Kind.ACTION, schema), invocation -> {
                worker.set(Thread.currentThread().getName()); return CompletableFuture.completedFuture("{}");
            })));
            assertEquals(schema, host.capabilities().getFirst().descriptor().configurationSchemaJson());
            var handle = host.actionHandle(new Key("schema", "act"), NPC, Optional.empty(), "{\"x\":true}", false, false);
            assertEquals(Outcome.Status.OK, await(handle.result()).status());
            assertEquals("Animus-module", worker.get());
            var invalid = host.actionHandle(new Key("schema", "act"), NPC, Optional.empty(), "{\"x\":\"true\"}", false, false);
            assertEquals(Outcome.Status.INVALID, await(invalid.result()).status());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!invalid.isQuiescent() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(invalid.isQuiescent());
        }
    }
    @Test void oversizedOrMalformedUnicodePayloadsAreRejectedWithoutCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ModuleHost host = host()) {
            host.install(module(context -> context.registrations().action(descriptor("act", Kind.ACTION, "{}"), invocation -> {
                calls.incrementAndGet(); return CompletableFuture.completedFuture("{}");
            })));
            for (String input : List.of("{\"x\":\"\\ud800\"}", "{\"x\":1e2147483647}", "{\"x\":\"" + "😀".repeat(20_000) + "\"}"))
                assertEquals(Outcome.Status.INVALID, await(host.action(new Key("schema", "act"), NPC, Optional.empty(), input, false, false)).status());
            assertEquals(0, calls.get());
        }
    }
}
