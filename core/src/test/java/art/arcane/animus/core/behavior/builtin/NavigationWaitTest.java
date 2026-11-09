package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.HostBehaviorInvoker;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationWaitTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final String WALK = "{\"x\":1,\"y\":64,\"z\":3,\"wait\":true}";
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private final RecordingPlatform platform = new RecordingPlatform();
    private ModuleHost host;
    private BuiltinCapabilities builtins;

    private void start(long deadlineMillis, int waits) {
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 16, Duration.ofMillis(deadlineMillis));
        BuiltinActionSettings d = BuiltinActionSettings.DEFAULT;
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), choice -> {},
                new BuiltinActionSettings(d.actionsPerTick(), d.actionsPerNpcPerTick(), d.queuedActions(), d.pendingChoices(), d.choiceLifetimeSeconds(), d.commandBehaviors(), d.commandNamespaces(), waits, d.rawTextBehaviors()), line -> {});
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
    }
    @AfterEach void stop() { if (host != null) host.close(); }

    private ModuleHost.InvocationHandle<String> navigate(String json) {
        return host.actionHandle(new Key("animus", "navigate"), NPC, Optional.empty(), json, false, false, () -> true, Optional.of("town:patrol"));
    }
    private void pumpUntil(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!check.getAsBoolean() && System.nanoTime() < until) { platform.pump(); Thread.sleep(1); }
        assertTrue(check.getAsBoolean(), platform.calls.toString());
    }
    private void waiting() throws Exception { pumpUntil(() -> platform.calls.contains("await")); }
    private Outcome<String> settled(ModuleHost.InvocationHandle<String> handle) throws Exception {
        CompletableFuture<Outcome<String>> result = handle.result().toCompletableFuture();
        pumpUntil(result::isDone);
        return result.get();
    }

    @Test void completesOnlyOnArrival() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        for (int i = 0; i < 20; i++) { platform.pump(); Thread.sleep(1); }
        assertFalse(handle.result().toCompletableFuture().isDone());
        platform.finishRoute(null);
        Outcome<String> outcome = settled(handle);
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        assertTrue(outcome.value().orElseThrow().contains("\"arrived\":true"), outcome.value().orElseThrow());
        assertEquals(List.of("navigate:WALK:2.0", "await"), platform.calls);
    }

    @Test void withoutTheOptionCompletesOnRequest() throws Exception {
        start(5000, 8);
        Outcome<String> outcome = settled(navigate("{\"x\":1,\"y\":64,\"z\":3}"));
        assertEquals(Outcome.Status.OK, outcome.status());
        assertEquals(List.of("navigate:WALK:2.0"), platform.calls);
    }

    @Test void reusableRouteCanBeAwaited() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate("{\"route\":\"patrol\",\"wait\":true}");
        waiting();
        platform.finishRoute(null);
        assertEquals(Outcome.Status.OK, settled(handle).status());
        assertEquals(List.of("route:patrol", "await"), platform.calls);
    }

    @Test void blockedRouteFailsWithItsDiagnostic() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        platform.finishRoute(new CapabilityRefusal(Outcome.Status.FAILED, "navigation-blocked", "blocked: COLLISION"));
        Outcome<String> outcome = settled(handle);
        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals("navigation-blocked", outcome.diagnostics().getFirst().code());
        assertEquals("blocked: COLLISION", outcome.diagnostics().getFirst().message());
    }

    @Test void unavailableTerrainReportsUnavailable() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        platform.finishRoute(new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "navigation-unavailable", "unavailable: terrain"));
        assertEquals(Outcome.Status.UNAVAILABLE, settled(handle).status());
    }

    @Test void timesOutInOwnerTicksStopsTheRouteAndReportsItsLastStatus() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate("{\"x\":1,\"y\":64,\"z\":3,\"wait\":true,\"timeoutTicks\":20}");
        waiting();
        Outcome<String> outcome = settled(handle);
        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals("navigation-timeout", outcome.diagnostics().getFirst().code());
        assertTrue(outcome.diagnostics().getFirst().message().contains("suspended somewhere"), outcome.diagnostics().toString());
        assertTrue(platform.calls.contains("stop"));
    }

    @Test void cancellingTheCallStopsTheRouteAndReleasesItsSlot() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        assertTrue(handle.cancel());
        pumpUntil(() -> platform.calls.contains("stop") && host.outstandingInvocations() == 0);
        assertEquals(Outcome.Status.CANCELLED, handle.result().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        assertTrue(handle.isQuiescent());
    }

    @Test void cancellingAnExecutionStopsTheRoute() throws Exception {
        start(5000, 8);
        BehaviorProgram program = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"town:patrol\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"animus:navigate\",\"arguments\":" + WALK + "}}}").program().orElseThrow();
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Limits.defaults(), new HostBehaviorInvoker(host), ignored -> {}, art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        UUID id = engine.start(program, new BehaviorEngine.Context(NPC, Optional.empty(), false)).value().orElseThrow();
        engine.tick();
        waiting();
        assertTrue(engine.cancel(id, "npc despawned"));
        pumpUntil(() -> platform.calls.contains("stop"));
        assertEquals(BehaviorEngine.State.CANCELLED, engine.inspect(id).orElseThrow().state());
        engine.close();
    }

    @Test void slowRoutesOutliveTheModuleInvocationDeadline() throws Exception {
        start(100, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        for (int i = 0; i < 300; i++) { platform.pump(); Thread.sleep(1); }
        assertFalse(handle.result().toCompletableFuture().isDone());
        platform.finishRoute(null);
        assertEquals(Outcome.Status.OK, settled(handle).status());
    }

    @Test void waitsAreBoundedSoTheyCannotStarveOtherActions() throws Exception {
        start(5000, 1);
        ModuleHost.InvocationHandle<String> first = navigate(WALK);
        waiting();
        Outcome<String> refused = settled(navigate(WALK));
        assertEquals(Outcome.Status.BUSY, refused.status());
        assertEquals("navigation-wait-capacity", refused.diagnostics().getFirst().code());
        assertEquals(List.of("navigate:WALK:2.0", "await"), platform.calls);
        platform.finishRoute(null);
        assertEquals(Outcome.Status.OK, settled(first).status());
        ModuleHost.InvocationHandle<String> second = navigate(WALK);
        pumpUntil(() -> platform.calls.stream().filter("await"::equals).count() == 2);
        platform.finishRoute(null);
        assertEquals(Outcome.Status.OK, settled(second).status());
    }

    @Test void closingFailsWaitersAndDoesNotTouchThePlatform() throws Exception {
        start(5000, 8);
        ModuleHost.InvocationHandle<String> handle = navigate(WALK);
        waiting();
        builtins.close();
        assertEquals(Outcome.Status.UNAVAILABLE, handle.result().toCompletableFuture().get(4, TimeUnit.SECONDS).status());
        assertFalse(platform.calls.contains("stop"));
    }

    @Test void waitArgumentsAreValidated() {
        start(5000, 8);
        Key key = new Key("animus", "navigate");
        assertTrue(host.validateArguments(key, art.arcane.animus.api.Capabilities.Kind.ACTION, WALK).isEmpty());
        assertFalse(host.validateArguments(key, art.arcane.animus.api.Capabilities.Kind.ACTION, "{\"x\":1,\"y\":64,\"z\":3,\"timeoutTicks\":100}").isEmpty());
        assertFalse(host.validateArguments(key, art.arcane.animus.api.Capabilities.Kind.ACTION, "{\"x\":1,\"y\":64,\"z\":3,\"wait\":false,\"timeoutTicks\":100}").isEmpty());
        assertFalse(host.validateArguments(key, art.arcane.animus.api.Capabilities.Kind.ACTION, "{\"x\":1,\"y\":64,\"z\":3,\"wait\":true,\"timeoutTicks\":5}").isEmpty());
        assertFalse(host.validateArguments(key, art.arcane.animus.api.Capabilities.Kind.ACTION, "{\"x\":1,\"y\":64,\"z\":3,\"wait\":true,\"timeoutTicks\":12001}").isEmpty());
    }

    @Test void previewRefusesWaiting() throws Exception {
        start(5000, 8);
        Outcome<String> refused = settled(host.actionHandle(new Key("animus", "navigate"), NPC, Optional.empty(), WALK, true, false, () -> true, Optional.of("town:patrol")));
        assertEquals("preview-refused", refused.diagnostics().getFirst().code());
        assertTrue(platform.calls.isEmpty());
    }
}
