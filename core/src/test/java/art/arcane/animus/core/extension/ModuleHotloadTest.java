package art.arcane.animus.core.extension;

import art.arcane.animus.api.ApiRange;
import art.arcane.animus.api.Capabilities.Descriptor;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.ModuleContext;
import art.arcane.animus.api.ModuleManifest;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.api.TrustedModule;
import art.arcane.animus.api.Version;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.DocumentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hotload of {@code [modules]}: limits swap for the next invocation and the switch suspends or resumes modules live. */
class ModuleHotloadTest {
    @TempDir Path directory;
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final Key HOLD = new Key("sample", "hold");
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };

    private final List<CompletableFuture<String>> held = new CopyOnWriteArrayList<>();

    private TrustedModule holding() {
        return new TrustedModule() {
            @Override public ModuleManifest manifest() { return new ModuleManifest("sample", new Version(1, 0, 0), new ApiRange(1, 1), Set.of(Platform.PAPER), List.of()); }
            @Override public void enable(ModuleContext context) {
                context.registrations().action(new Descriptor(HOLD, Kind.ACTION, "Test", "{}", Set.of(Platform.PAPER), false), (art.arcane.animus.api.Capabilities.Invocation invocation) -> {
                    CompletableFuture<String> result = new CompletableFuture<>();
                    held.add(result);
                    return result;
                });
            }
            @Override public void disable() {}
        };
    }

    private static CompletableFuture<Outcome<String>> call(ModuleHost host) { return host.action(HOLD, NPC, Optional.empty(), "{}", false, false).toCompletableFuture(); }

    private static <T> T await(CompletableFuture<T> value) throws Exception { return value.get(3, TimeUnit.SECONDS); }

    private static void eventually(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(check.getAsBoolean());
    }

    private void releaseAll() { for (CompletableFuture<String> future : held) future.complete("{}"); }

    @Test
    void limits_raiseAndLowerUnderLoad_applyToNextInvocation() throws Exception {
        try (ModuleHost host = new ModuleHost(Platform.PAPER, DEFINITIONS, 2, Duration.ofSeconds(2))) {
            host.install(holding());
            List<CompletableFuture<Outcome<String>>> running = new CopyOnWriteArrayList<>(List.of(call(host), call(host)));
            eventually(() -> held.size() == 2);
            assertEquals(Outcome.Status.BUSY, await(call(host)).status());
            host.limits(3, Duration.ofSeconds(2), Duration.ofMillis(250));
            running.add(call(host));
            eventually(() -> held.size() == 3);
            host.limits(1, Duration.ofSeconds(2), Duration.ofMillis(250));
            assertEquals(Outcome.Status.BUSY, await(call(host)).status(), "new work waits while three run under a maximum of one");
            releaseAll();
            for (CompletableFuture<Outcome<String>> result : running) assertEquals(Outcome.Status.OK, await(result).status(), "work in flight finishes");
            eventually(() -> host.outstandingInvocations() == 0);
            CompletableFuture<Outcome<String>> one = call(host);
            eventually(() -> held.size() == 4);
            assertEquals(Outcome.Status.BUSY, await(call(host)).status(), "the lowered maximum holds once drained");
            releaseAll();
            assertEquals(Outcome.Status.OK, await(one).status());
        }
    }

    @Test
    void timeout_shortened_appliesToNextInvocation() throws Exception {
        try (ModuleHost host = new ModuleHost(Platform.PAPER, DEFINITIONS, 4, Duration.ofSeconds(30))) {
            host.install(holding());
            host.limits(4, Duration.ofMillis(50), Duration.ofMillis(250));
            assertEquals(Outcome.Status.TIMED_OUT, await(call(host)).status());
            releaseAll();
        }
    }

    @Test
    void suspend_refusesNewWork_letsAdmittedFinish_resumeRestores() throws Exception {
        try (ModuleHost host = new ModuleHost(Platform.PAPER, DEFINITIONS, 4, Duration.ofSeconds(2))) {
            host.install(holding());
            CompletableFuture<Outcome<String>> admitted = call(host);
            eventually(() -> held.size() == 1);
            host.suspendModules(true);
            Outcome<String> refused = await(call(host));
            assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
            assertEquals("modules-disabled", refused.diagnostics().getFirst().code());
            assertFalse(host.capabilityAvailable(HOLD, Kind.ACTION, false, false));
            releaseAll();
            assertEquals(Outcome.Status.OK, await(admitted).status(), "work admitted before the switch finishes");
            host.suspendModules(false);
            assertTrue(host.capabilityAvailable(HOLD, Kind.ACTION, false, false));
            CompletableFuture<Outcome<String>> resumed = call(host);
            eventually(() -> held.size() == 2);
            releaseAll();
            assertEquals(Outcome.Status.OK, await(resumed).status());
        }
    }

    @Test
    void runtimeFollowsModulesSwitch_andReloadsBehaviors() throws Exception {
        Path data = directory.resolve("server");
        Files.createDirectories(data);
        String on = "statusMessage = \"Hello\"\n[modules]\nenabled = true\n", off = "statusMessage = \"Hello\"\n[modules]\nenabled = false\n";
        Files.writeString(data.resolve("config.toml"), off);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(art.arcane.animus.core.Platform.BUKKIT, "26.2", data));
        runtime.start();
        List<CatalogChange> reloads = new CopyOnWriteArrayList<>();
        runtime.catalogs((CatalogChange change, java.util.function.Consumer<String> problems) -> reloads.add(change));
        List<String> report = new CopyOnWriteArrayList<>();
        ExtensionRuntime modules = new ExtensionRuntime(directory.toRealPath().resolve("modules"), Platform.PAPER, DEFINITIONS, runtime.moduleSettings(), report::add);
        try {
            modules.follow(runtime);
            modules.start();
            assertEquals(ExtensionRuntime.State.DISABLED, modules.status().state());
            runtime.applySettings(PluginSettings.parse(on, data.resolve("config.toml")));
            eventually(() -> modules.status().state() == ExtensionRuntime.State.READY);
            eventually(() -> reloads.size() == 1);
            assertEquals(Set.of(DocumentKind.BEHAVIOR), reloads.getFirst().kinds());
            runtime.applySettings(PluginSettings.parse(off, data.resolve("config.toml")));
            assertEquals(ExtensionRuntime.State.DISABLED, modules.status().state());
            assertEquals(Outcome.Status.UNAVAILABLE, await(modules.action(HOLD, NPC, Optional.empty(), "{}").toCompletableFuture()).status());
            runtime.applySettings(PluginSettings.parse(on.replace("enabled = true", "enabled = true\nmaximumInvocations = 9"), data.resolve("config.toml")));
            assertEquals(ExtensionRuntime.State.READY, modules.status().state());
            assertEquals(3, reloads.size(), "each switch reloads behaviors; a limit change alone does not");
            runtime.applySettings(PluginSettings.parse(on.replace("enabled = true", "enabled = true\nmaximumInvocations = 12"), data.resolve("config.toml")));
            assertEquals(3, reloads.size());
            assertTrue(report.contains("Modules disabled by config.toml; built-in capabilities stay available"));
        } finally {
            modules.close();
            assertTrue(modules.awaitStopped(Duration.ofSeconds(4)));
            runtime.close();
        }
    }
}
