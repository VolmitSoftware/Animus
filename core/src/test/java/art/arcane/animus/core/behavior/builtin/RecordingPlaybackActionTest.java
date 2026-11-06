package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordingPlaybackActionTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final Key PLAY = new Key("animus", "play-recording");
    private static final Key RESUME = new Key("animus", "resume-patrol");
    private static final String ONCE_WAIT = "{\"recording\":\"market-walk\",\"playback\":\"once\",\"wait\":true}";
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private final RecordingPlatform platform = new RecordingPlatform();
    private ModuleHost host;
    private BuiltinCapabilities builtins;

    private void start(long deadlineMillis) {
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 16, Duration.ofMillis(deadlineMillis));
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), choice -> {}, BuiltinActionSettings.DEFAULT, line -> {});
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
    }
    @AfterEach void stop() { if (host != null) host.close(); }

    private ModuleHost.InvocationHandle<String> call(Key key, NpcHandle npc, String json, boolean preview) {
        return host.actionHandle(key, npc, Optional.empty(), json, preview, false, () -> true, Optional.of("town:market"));
    }
    private ModuleHost.InvocationHandle<String> play(String json) { return call(PLAY, NPC, json, false); }
    private void pumpUntil(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.getAsBoolean() && System.nanoTime() < until) { platform.pump(); Thread.sleep(1); }
        assertTrue(check.getAsBoolean(), platform.calls.toString());
    }
    private Outcome<String> settled(ModuleHost.InvocationHandle<String> handle) throws Exception {
        CompletableFuture<Outcome<String>> result = handle.result().toCompletableFuture();
        pumpUntil(result::isDone);
        return result.get();
    }
    private List<Diagnostic> validate(Key key, String json) { return host.validateArguments(key, Kind.ACTION, json); }

    @Test void playsTheNamedRecordingInTheRequestedMode() throws Exception {
        start(5000);
        Outcome<String> outcome = settled(play("{\"recording\":\"market-walk\",\"playback\":\"pingpong\"}"));
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        assertEquals("{}", outcome.value().orElseThrow());
        assertEquals(List.of("recording:market-walk:pingpong"), platform.calls);
    }

    @Test void unsetPlaybackLeavesTheModeToTheRecording() throws Exception {
        start(5000);
        assertEquals(Outcome.Status.OK, settled(play("{\"recording\":\"market-walk\"}")).status());
        assertEquals(List.of("recording:market-walk:own"), platform.calls);
    }

    @Test void waitingOncePlaybackCompletesWhenTheRecordingEnds() throws Exception {
        start(5000);
        ModuleHost.InvocationHandle<String> handle = play(ONCE_WAIT);
        pumpUntil(() -> platform.calls.contains("await"));
        for (int i = 0; i < 20; i++) { platform.pump(); Thread.sleep(1); }
        assertFalse(handle.result().toCompletableFuture().isDone());
        platform.finishRoute(null);
        Outcome<String> outcome = settled(handle);
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        assertEquals("{\"finished\":true}", outcome.value().orElseThrow());
        assertEquals(List.of("recording:market-walk:once", "await"), platform.calls);
    }

    @Test void stoppedPlaybackFailsTheWaitWithItsDiagnostic() throws Exception {
        start(5000);
        ModuleHost.InvocationHandle<String> handle = play(ONCE_WAIT);
        pumpUntil(() -> platform.calls.contains("await"));
        platform.finishRoute(new CapabilityRefusal(Outcome.Status.FAILED, "navigation-stopped", "stopped"));
        Outcome<String> outcome = settled(handle);
        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals("navigation-stopped", outcome.diagnostics().getFirst().code());
    }

    @Test void waitingPlaybackOutlivesTheModuleDeadlineAndIsBoundedByItsTimeout() throws Exception {
        start(100);
        assertEquals(OptionalLong.empty(), builtins.deadlineMillis(PLAY, "{\"recording\":\"market-walk\"}"));
        assertTrue(builtins.deadlineMillis(PLAY, ONCE_WAIT).orElseThrow() > 1200 * 50);
        ModuleHost.InvocationHandle<String> handle = play(ONCE_WAIT);
        pumpUntil(() -> platform.calls.contains("await"));
        for (int i = 0; i < 300; i++) { platform.pump(); Thread.sleep(1); }
        assertFalse(handle.result().toCompletableFuture().isDone());
        platform.finishRoute(null);
        assertEquals(Outcome.Status.OK, settled(handle).status());
    }

    @Test void timesOutInOwnerTicksAndStopsThePlayback() throws Exception {
        start(5000);
        Outcome<String> outcome = settled(play("{\"recording\":\"market-walk\",\"playback\":\"once\",\"wait\":true,\"timeoutTicks\":20}"));
        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals("navigation-timeout", outcome.diagnostics().getFirst().code());
        assertTrue(platform.calls.contains("stop"));
    }

    @Test void argumentsAreValidated() {
        start(5000);
        assertTrue(validate(PLAY, ONCE_WAIT).isEmpty());
        assertTrue(validate(PLAY, "{\"recording\":\"a\",\"playback\":\"loop\"}").isEmpty());
        assertTrue(validate(PLAY, "{\"recording\":\"market-walk\",\"wait\":true}").isEmpty(), "the recording may play once by its own playback");
        assertEquals("play-recording-wait-loop", validate(PLAY, "{\"recording\":\"market-walk\",\"playback\":\"pingpong\",\"wait\":true}").getFirst().code());
        assertEquals("recording-id", validate(PLAY, "{\"recording\":\"Market Walk\"}").getFirst().code());
        assertEquals("recording-id", validate(PLAY, "{\"recording\":\"-walk\"}").getFirst().code());
        assertFalse(validate(PLAY, "{\"recording\":\"" + "a".repeat(65) + "\"}").isEmpty());
        assertFalse(validate(PLAY, "{}").isEmpty());
        assertFalse(validate(PLAY, "{\"recording\":\"market-walk\",\"playback\":\"reverse\"}").isEmpty());
        assertFalse(validate(PLAY, "{\"recording\":\"market-walk\",\"playback\":\"once\",\"wait\":true,\"timeoutTicks\":19}").isEmpty());
        assertFalse(validate(PLAY, "{\"recording\":\"market-walk\",\"playback\":\"once\",\"wait\":true,\"timeoutTicks\":12001}").isEmpty());
        assertFalse(validate(PLAY, "{\"recording\":\"market-walk\",\"playback\":\"once\",\"timeoutTicks\":100}").isEmpty());
        assertTrue(validate(RESUME, "{}").isEmpty());
        assertFalse(validate(RESUME, "{\"recording\":\"market-walk\"}").isEmpty());
    }

    @Test void invalidWaitIsRefusedBeforeThePlatform() throws Exception {
        start(5000);
        assertEquals(Outcome.Status.INVALID, settled(play("{\"recording\":\"market-walk\",\"playback\":\"loop\",\"wait\":true}")).status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void previewRefusesPlaybackAndResume() throws Exception {
        start(5000);
        assertEquals("preview-refused", settled(call(PLAY, NPC, "{\"recording\":\"market-walk\"}", true)).diagnostics().getFirst().code());
        assertEquals("preview-refused", settled(call(RESUME, NPC, "{}", true)).diagnostics().getFirst().code());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void serverBehaviorsHaveNoNpcToMove() throws Exception {
        start(5000);
        Outcome<String> played = settled(call(PLAY, ServerNpc.HANDLE, "{\"recording\":\"market-walk\"}", false));
        assertEquals(Outcome.Status.INVALID, played.status());
        assertEquals("no-npc", played.diagnostics().getFirst().code());
        assertEquals("no-npc", settled(call(RESUME, ServerNpc.HANDLE, "{}", false)).diagnostics().getFirst().code());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void resumePatrolReportsWhetherThereWasAnythingToResume() throws Exception {
        start(5000);
        Outcome<String> resumed = settled(call(RESUME, NPC, "{}", false));
        assertEquals(Outcome.Status.OK, resumed.status(), resumed.diagnostics().toString());
        assertEquals("{\"result\":\"resumed\"}", resumed.value().orElseThrow());
        platform.resumable = false;
        assertEquals("{\"result\":\"nothing-to-resume\"}", settled(call(RESUME, NPC, "{}", false)).value().orElseThrow());
        assertEquals(List.of("resume", "resume"), platform.calls);
    }
}
