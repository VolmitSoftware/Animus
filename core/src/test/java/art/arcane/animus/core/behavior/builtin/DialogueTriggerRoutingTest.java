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
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.runtime.BehaviorRuntime;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.behavior.trigger.TriggerSink;
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

/** A real dialogue_choice binding, started only by a click that passes validation. */
class DialogueTriggerRoutingTest {
    @TempDir Path temporary;
    private static final UUID NPC = UUID.randomUUID(), PLAYER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private static final String THANKS = "say:true:0.0:Thanks";
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private final RecordingPlatform platform = new RecordingPlatform();
    private final List<TriggerEvent> published = new CopyOnWriteArrayList<>();
    private BuiltinCapabilities builtins;
    private ExtensionRuntime extensions;
    private TriggerDispatcher dispatcher;
    private BehaviorRuntime runtime;

    @BeforeEach void start() throws Exception {
        Path root = temporary.toRealPath();
        Path file = root.resolve(BehaviorPath.forId("town:greeter"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"schemaVersion":1,"revision":1,"id":"town:greeter","entry":"main",
                 "routines":{
                   "main":{"type":"action","capability":"animus:choices","arguments":{"id":"greeting","prompt":"Well?","options":[{"id":"yes","text":"Yes"},{"id":"no","text":"No"}]}},
                   "answered":{"type":"action","capability":"animus:say","arguments":{"text":"Thanks"}}},
                 "triggers":[{"id":"picked","event":"dialogue_choice","dialogue":"greeting","choice":"yes","routine":"answered","cooldownTicks":0}]}
                """);
        Map<String, NpcDefinition> npcs = Map.of("guard", NpcDefinition.parse("""
                {"schemaVersion":2,"revision":1,"uuid":"%s","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":true,"behaviors":["town:greeter"],
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
                """.formatted(NPC)));
        TriggerDispatcher[] holder = new TriggerDispatcher[1];
        TriggerSink spying = new TriggerSink() {
            @Override public boolean available() { return holder[0].available(); }
            @Override public String availability() { return holder[0].availability(); }
            @Override public Outcome<Boolean> publish(TriggerEvent event) { published.add(event); return holder[0].publish(event); }
        };
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), new DialogueTriggers(() -> spying), BuiltinActionSettings.DEFAULT, line -> {});
        extensions = new ExtensionRuntime(root.resolve("modules"), Platform.PAPER, DEFINITIONS, new ModuleSettings(true, 16, 5000, 250), line -> {}, state -> {}, builtins);
        extensions.start();
        eventually(() -> extensions.status().state() == ExtensionRuntime.State.READY);
        BehaviorCatalog catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), extensions.catalogResolver(), BehaviorCheckpoints.disabled());
        assertTrue(catalog.reload(catalog.snapshot().generation(), Set.of()).published());
        dispatcher = new TriggerDispatcher(TriggerSettings.DEFAULT, () -> npcs, catalog::snapshot);
        dispatcher.connect();
        holder[0] = dispatcher;
        runtime = new BehaviorRuntime(BehaviorRuntime.Limits.defaults(), BehaviorEngine.Limits.defaults(), () -> npcs, catalog::snapshot, extensions.behaviorInvoker(), dispatcher, new MemoryBehaviorState());
        eventually(() -> dispatcher.snapshot().bindings() > 0);
        dispatcher.activated(NPC);
        assertEquals(Outcome.Status.OK, runtime.run("guard", "town:greeter", Optional.of(PLAYER)).toCompletableFuture().get(4, TimeUnit.SECONDS).status());
        eventually(() -> { platform.pump(); return platform.presentedToken != null; });
    }
    @AfterEach void stop() throws Exception {
        if (runtime != null) { runtime.close(); assertTrue(runtime.awaitStopped(Duration.ofSeconds(4))); }
        if (extensions != null) { extensions.close(); assertTrue(extensions.awaitStopped(Duration.ofSeconds(4))); }
    }
    private static void eventually(BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(check.getAsBoolean());
    }
    private PendingChoices.Selection click(UUID player, String token, String option) {
        return platform.onOwner(() -> builtins.selectChoice(player, token, option));
    }
    private long saidThanks() { return platform.calls.stream().filter(THANKS::equals).count(); }

    @Test void validClickStartsTheBoundRoutineWithThePlayer() throws Exception {
        assertEquals(PendingChoices.Selection.ACCEPTED, click(PLAYER, platform.presentedToken, "yes"));
        assertEquals(List.of(new TriggerEvent.DialogueChoice(NPC, PLAYER, "greeting", "yes")), published);
        eventually(() -> { platform.pump(); return saidThanks() == 1; });
    }

    @Test void forgedAndForeignClicksPublishNothingAndLeaveThePromptAnswerable() throws Exception {
        assertEquals(PendingChoices.Selection.UNKNOWN, click(PLAYER, "f".repeat(32), "yes"));
        assertEquals(PendingChoices.Selection.WRONG_PLAYER, click(STRANGER, platform.presentedToken, "yes"));
        assertEquals(PendingChoices.Selection.UNKNOWN_OPTION, click(PLAYER, platform.presentedToken, "maybe"));
        assertTrue(published.isEmpty());
        for (int i = 0; i < 40; i++) { platform.pump(); Thread.sleep(2); }
        assertEquals(0, saidThanks());
        assertEquals(PendingChoices.Selection.ACCEPTED, click(PLAYER, platform.presentedToken, "yes"));
        eventually(() -> { platform.pump(); return saidThanks() == 1; });
    }

    @Test void staleClickAfterAnAnswerStartsNothingMore() throws Exception {
        String token = platform.presentedToken;
        assertEquals(PendingChoices.Selection.ACCEPTED, click(PLAYER, token, "yes"));
        eventually(() -> { platform.pump(); return saidThanks() == 1; });
        assertEquals(PendingChoices.Selection.UNKNOWN, click(PLAYER, token, "yes"));
        assertEquals(1, published.size());
        for (int i = 0; i < 40; i++) { platform.pump(); Thread.sleep(2); }
        assertEquals(1, saidThanks());
    }

    @Test void anotherOptionDoesNotMatchTheBindingFilter() throws Exception {
        assertEquals(PendingChoices.Selection.ACCEPTED, click(PLAYER, platform.presentedToken, "no"));
        assertEquals(List.of(new TriggerEvent.DialogueChoice(NPC, PLAYER, "greeting", "no")), published);
        for (int i = 0; i < 40; i++) { platform.pump(); Thread.sleep(2); }
        assertEquals(0, saidThanks());
    }

    @Test void missingTriggerSinkDropsTheChoiceQuietly() {
        new DialogueTriggers(() -> null).publish(new DialogueChoice(new NpcHandle(NPC), PLAYER, Optional.empty(), "greeting", "yes"));
        assertTrue(published.isEmpty());
    }
}
