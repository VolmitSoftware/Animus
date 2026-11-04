package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.npc.NpcDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A source file through catalog validation, manual execution, the host and the mocked owner, with no shortcuts. */
class BuiltinManualRunTest {
    @TempDir Path temporary;
    private static final UUID NPC = UUID.randomUUID(), PLAYER = UUID.randomUUID();
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private final RecordingPlatform platform = new RecordingPlatform();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Path root;
    private ExtensionRuntime extensions;
    private BehaviorCatalog catalog;
    private ManualBehaviorRuntime runtime;
    private Map<String, NpcDefinition> npcs;

    @BeforeEach void start() throws Exception {
        root = temporary.toRealPath();
        npcs = Map.of("guard", NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"%s","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
                """.formatted(NPC)));
        BuiltinActionSettings defaults = BuiltinActionSettings.DEFAULT;
        BuiltinCapabilities builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), choice -> {},
                new BuiltinActionSettings(defaults.actionsPerTick(), defaults.actionsPerNpcPerTick(), defaults.queuedActions(), defaults.pendingChoices(), defaults.choiceLifetimeSeconds(), List.of("town:allowed"), List.of(), defaults.navigationWaits()), log::add);
        extensions = new ExtensionRuntime(root.resolve("modules"), Platform.PAPER, DEFINITIONS, new ModuleSettings(true, 16, 5000, 250), line -> {}, state -> {}, builtins);
        extensions.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (extensions.status().state() != ExtensionRuntime.State.READY && System.nanoTime() < until) Thread.sleep(2);
        assertEquals(ExtensionRuntime.State.READY, extensions.status().state());
        catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), extensions.catalogResolver(), art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints.disabled());
        runtime = new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(), BehaviorEngine.Limits.defaults(), () -> npcs, catalog::snapshot, extensions.behaviorInvoker(), null, art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
    }
    @AfterEach void stop() throws Exception {
        if (runtime != null) { runtime.close(); assertTrue(runtime.awaitStopped(Duration.ofSeconds(4))); }
        if (extensions != null) { extensions.close(); assertTrue(extensions.awaitStopped(Duration.ofSeconds(4))); }
    }

    private void source(String id, String node) throws Exception {
        Path file = root.resolve(BehaviorPath.forId(id));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":1,\"revision\":1,\"id\":\"" + id + "\",\"entry\":\"main\",\"routines\":{\"main\":" + node + "}}");
    }
    private BehaviorEngine.Report run(String id) throws Exception {
        UUID execution = runtime.run("guard", id, Optional.of(PLAYER)).toCompletableFuture().get(4, TimeUnit.SECONDS).value().orElseThrow();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < until) {
            platform.pump();
            Optional<ManualBehaviorRuntime.Execution> found = runtime.inspect(execution);
            if (found.isPresent() && found.get().report().state() != BehaviorEngine.State.RUNNING) return found.get().report();
            Thread.sleep(2);
        }
        throw new AssertionError("Execution did not finish");
    }
    private void reload() { assertTrue(catalog.reload(catalog.snapshot().generation(), Set.of()).published()); }

    @Test void allowListedBehaviorRunsItsCommandAndLogsItsIdentity() throws Exception {
        source("town:allowed", "{\"type\":\"sequence\",\"steps\":[{\"type\":\"action\",\"capability\":\"animus:say\",\"arguments\":{\"text\":\"Opening\"}},{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"gate open\"}}]}");
        reload();
        assertTrue(catalog.snapshot().byId().get("town:allowed").available(), catalog.snapshot().byId().get("town:allowed").diagnostics().toString());
        BehaviorEngine.Report report = run("town:allowed");
        assertEquals(BehaviorEngine.State.COMPLETED, report.state(), report.toString());
        assertEquals(List.of("say:true:0.0:Opening", "command:CONSOLE:gate open"), platform.calls);
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("behavior=town:allowed") && log.getFirst().contains("npc=" + NPC), log.getFirst());
    }

    @Test void ungrantedCommandMakesTheBehaviorUnavailableRatherThanInvalid() throws Exception {
        source("town:other", "{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"gate open\"}}");
        reload();
        BehaviorCatalog.Resolution resolution = catalog.snapshot().byId().get("town:other");
        assertFalse(resolution.available());
        assertTrue(resolution.accepted().isPresent());
        assertTrue(resolution.diagnostics().getFirst().message().contains("privileged-capability-not-granted"), resolution.diagnostics().toString());
        assertEquals(Outcome.Status.UNAVAILABLE, runtime.run("guard", "town:other").toCompletableFuture().get(4, TimeUnit.SECONDS).status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void invalidBuiltinArgumentsAreRejectedAtValidation() throws Exception {
        source("town:broken", "{\"type\":\"action\",\"capability\":\"animus:say\",\"arguments\":{\"text\":\"bad\\u0007\"}}");
        reload();
        BehaviorCatalog.Entry entry = catalog.snapshot().entries().get(BehaviorPath.forId("town:broken"));
        assertEquals(BehaviorCatalog.SourceState.INVALID, entry.state());
        assertTrue(entry.diagnostics().getFirst().field().contains(".arguments"), entry.diagnostics().toString());
    }
}
