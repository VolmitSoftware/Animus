package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.ModuleHost;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeechHeardTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private final RecordingPlatform platform = new RecordingPlatform();
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private ModuleHost host;

    private Outcome<String> say(String json, boolean preview) throws Exception {
        host = new ModuleHost(Platform.PAPER, 64, Duration.ofMillis(5000));
        BuiltinCapabilities builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), (DialogueChoice choice) -> {}, BuiltinActionSettings.DEFAULT, (String line) -> {});
        builtins.speech((NpcHandle npc, String plain) -> heard.add(npc.uuid() + ":" + plain));
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
        CompletableFuture<Outcome<String>> future = host.actionHandle(new art.arcane.animus.api.Capabilities.Key("animus", "say"), NPC, Optional.of(UUID.randomUUID()), json, preview, false, () -> true, Optional.of("town:crier"))
                .result().toCompletableFuture();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!future.isDone() && System.nanoTime() < until) { platform.pump(); Thread.sleep(1); }
        return future.get(1, TimeUnit.MILLISECONDS);
    }
    @AfterEach void stop() { if (host != null) host.close(); }

    @Test void whatAnNpcSaysIsHeardAsPlainText() throws Exception {
        assertEquals(Outcome.Status.OK, say("{\"text\":\"&aHear ye!\"}", false).status());
        assertEquals(List.of(NPC.uuid() + ":Hear ye!"), heard);
    }

    @Test void aPreviewSpeaksToNobodyElse() throws Exception {
        say("{\"text\":\"Hear ye!\"}", true);
        assertEquals(List.of(), heard);
    }
}
