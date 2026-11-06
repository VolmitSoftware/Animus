package art.arcane.animus.core.extension;

import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.BehaviorInvoker;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.MemoryBehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.virtual.Vec3;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionRuntimeBuiltinsTest {
    @TempDir Path root;
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final Key COMMAND = new Key("animus", "command"), FLAG = new Key("animus", "set-flag"), SAY = new Key("animus", "say");
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private static final BehaviorPlatform UNAVAILABLE = new BehaviorPlatform() {
        @Override public Optional<String> unavailable() { return Optional.of("general NPC runtime is disabled on Folia"); }
        @Override public AutoCloseable ownerTicks(Runnable tick) { throw new AssertionError("No owner ticks while unavailable"); }
        @Override public boolean hasPermission(UUID player, String node) { throw new AssertionError(); }
        @Override public NpcStatus npcStatus(NpcHandle npc) { throw new AssertionError(); }
        @Override public boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius) { throw new AssertionError(); }
        @Override public int say(NpcHandle npc, Audience audience, String text) { throw new AssertionError(); }
        @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) { throw new AssertionError(); }
        @Override public Optional<String> playerName(UUID player) { throw new AssertionError(); }
        @Override public void runCommand(CommandSource source, Optional<UUID> player, String command) { throw new AssertionError(); }
        @Override public String navigate(NpcHandle npc, Vec3 destination, MovementProfile.Mode mode, double speed) { throw new AssertionError(); }
        @Override public void followRoute(NpcHandle npc, String route) { throw new AssertionError(); }
        @Override public CompletionStage<Void> routeFinished(NpcHandle npc) { throw new AssertionError(); }
        @Override public String navigationStatus(NpcHandle npc) { throw new AssertionError(); }
        @Override public boolean stopNavigation(NpcHandle npc) { throw new AssertionError(); }
        @Override public void playRecording(NpcHandle npc, String recording, art.arcane.animus.core.recording.Playback playback) { throw new AssertionError(); }
        @Override public boolean resumePatrol(NpcHandle npc) { throw new AssertionError(); }
        @Override public void look(NpcHandle npc, Vec3 target) { throw new AssertionError(); }
        @Override public void lookAtPlayer(NpcHandle npc, UUID player) { throw new AssertionError(); }
        @Override public void pose(NpcHandle npc, String pose) { throw new AssertionError(); }
        @Override public void setAppearance(NpcHandle npc, AppearanceChange change) { throw new AssertionError(); }
        @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) { throw new AssertionError(); }
        @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) { throw new AssertionError(); }
        @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect particle) { throw new AssertionError(); }
        @Override public void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target) { throw new AssertionError(); }
    };
    private ExtensionRuntime runtime;

    private ExtensionRuntime start(boolean modulesEnabled, List<String> commandBehaviors) throws Exception {
        BuiltinActionSettings defaults = BuiltinActionSettings.DEFAULT;
        BuiltinActionSettings settings = new BuiltinActionSettings(defaults.actionsPerTick(), defaults.actionsPerNpcPerTick(), defaults.queuedActions(), defaults.pendingChoices(), defaults.choiceLifetimeSeconds(), commandBehaviors, List.of(), defaults.navigationWaits());
        BuiltinCapabilities builtins = new BuiltinCapabilities(UNAVAILABLE, new MemoryBehaviorState(), choice -> {}, settings, line -> {});
        runtime = new ExtensionRuntime(root.toRealPath().resolve("modules"), Platform.FOLIA, DEFINITIONS, new ModuleSettings(modulesEnabled, 8, 2000, 250), line -> {}, state -> {}, builtins);
        runtime.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (runtime.status().state() == ExtensionRuntime.State.STARTING && System.nanoTime() < until) Thread.sleep(2);
        return runtime;
    }
    @AfterEach void stop() throws Exception {
        if (runtime != null) { runtime.close(); assertTrue(runtime.awaitStopped(Duration.ofSeconds(4))); }
    }

    private static Outcome<String> run(BehaviorInvoker invoker, Key key, String behavior, boolean preview, String json) throws Exception {
        BehaviorInvoker.Call<String> call = invoker.action(new BehaviorInvoker.Invocation(key, NPC, Optional.of(UUID.randomUUID()), json, Map.of(), preview, () -> true, behavior));
        return call.result().toCompletableFuture().get(4, TimeUnit.SECONDS);
    }

    @Test void builtinsBindAtStartupAlongsideDiscoveredModules() throws Exception {
        ExtensionRuntime started = start(true, List.of());
        assertEquals(ExtensionRuntime.State.READY, started.status().state(), started.status().failure());
        assertTrue(started.modules().isEmpty());
        assertFalse(started.capabilities().isEmpty());
    }

    @Test void disablingJavaModulesLeavesBuiltinsBoundAndStateActionsUsable() throws Exception {
        ExtensionRuntime started = start(false, List.of());
        assertEquals(ExtensionRuntime.State.DISABLED, started.status().state());
        assertTrue(started.capabilityAvailable(FLAG, Kind.ACTION));
        assertEquals(Outcome.Status.OK, run(started.behaviorInvoker(), FLAG, "town:greeter", false, "{\"name\":\"met\"}").status());
    }

    @Test void hotloadSwitchingModulesOffAndOnKeepsBuiltinsBound() throws Exception {
        ExtensionRuntime started = start(true, List.of());
        started.settings(new ModuleSettings(false, 8, 2000, 250));
        assertEquals(ExtensionRuntime.State.DISABLED, started.status().state());
        assertEquals(Outcome.Status.OK, run(started.behaviorInvoker(), FLAG, "town:greeter", false, "{\"name\":\"met\"}").status());
        assertFalse(started.catalogResolver().module("example").available());
        started.settings(new ModuleSettings(true, 8, 2000, 250));
        assertEquals(ExtensionRuntime.State.READY, started.status().state());
        assertEquals(Outcome.Status.OK, run(started.behaviorInvoker(), FLAG, "town:greeter", false, "{\"name\":\"met\"}").status());
    }

    @Test void hotloadEnablingModulesStartedOffLoadsThemOnceWithBuiltinsIntact() throws Exception {
        ExtensionRuntime started = start(false, List.of());
        int capabilities = started.capabilities().size();
        started.settings(new ModuleSettings(true, 8, 2000, 250));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (started.status().state() != ExtensionRuntime.State.READY && System.nanoTime() < until) Thread.sleep(2);
        assertEquals(ExtensionRuntime.State.READY, started.status().state(), started.status().failure());
        assertEquals(capabilities, started.capabilities().size(), "built-ins are not installed twice");
        assertEquals(Outcome.Status.OK, run(started.behaviorInvoker(), FLAG, "town:greeter", false, "{\"name\":\"met\"}").status());
    }

    @Test void foliaReportsWorldActionsUnavailableWithItsReason() throws Exception {
        ExtensionRuntime started = start(true, List.of());
        assertFalse(started.capabilityAvailable(SAY, Kind.ACTION));
        Outcome<String> refused = run(started.behaviorInvoker(), SAY, "town:greeter", false, "{\"text\":\"Hi\"}");
        assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
        assertEquals("platform-unavailable", refused.diagnostics().getFirst().code());
    }

    @Test void catalogResolverSeparatesInvalidArgumentsUnavailablePlatformAndMissingGrants() throws Exception {
        ExtensionRuntime started = start(true, List.of("town:allowed"));
        BehaviorCatalog.CapabilityResolver resolver = started.catalogResolver();
        BehaviorCatalog.Requirement say = new BehaviorCatalog.Requirement(SAY, Kind.ACTION);
        BehaviorCatalog.Capability unavailable = resolver.resolve(say);
        assertFalse(unavailable.available());
        assertTrue(unavailable.reason().contains("Folia"), unavailable.reason());
        assertTrue(resolver.validate(say, "{\"text\":\"Hi\"}").isEmpty());
        assertFalse(resolver.validate(say, "{\"text\":1}").isEmpty());
        assertFalse(resolver.validate(say, "{\"text\":\"bad\\u0007\"}").isEmpty());
        BehaviorCatalog.Requirement command = new BehaviorCatalog.Requirement(COMMAND, Kind.ACTION);
        assertTrue(resolver.authorize(command, "town:allowed").available());
        assertFalse(resolver.authorize(command, "town:other").available());
        assertTrue(resolver.authorize(say, "town:other").available());
        BehaviorCatalog.Requirement unknown = new BehaviorCatalog.Requirement(new Key("nobody", "missing"), Kind.ACTION);
        assertFalse(resolver.resolve(unknown).available());
        assertTrue(resolver.validate(unknown, "{\"text\":1}").isEmpty());
    }

    @Test void behaviorInvokerRefusesPrivilegedWorkInPreviewAndWithoutAGrant() throws Exception {
        ExtensionRuntime started = start(true, List.of("town:allowed"));
        BehaviorInvoker invoker = started.behaviorInvoker();
        assertFalse(invoker.available(COMMAND, Kind.ACTION, "town:other"));
        Outcome<String> denied = run(invoker, COMMAND, "town:other", false, "{\"command\":\"say hi\"}");
        assertEquals("privileged-capability-not-granted", denied.diagnostics().getFirst().code());
        Outcome<String> preview = run(invoker, COMMAND, "town:allowed", true, "{\"command\":\"say hi\"}");
        assertEquals(Outcome.Status.UNAVAILABLE, preview.status());
        assertEquals("privileged-capability-not-granted", preview.diagnostics().getFirst().code());
    }

    @Test void previewFlagReachesBuiltinsThroughTheRuntimeBridge() throws Exception {
        ExtensionRuntime started = start(true, List.of());
        Outcome<String> preview = run(started.behaviorInvoker(), FLAG, "town:greeter", true, "{\"name\":\"met\"}");
        assertEquals(Outcome.Status.UNAVAILABLE, preview.status());
        assertEquals("preview-refused", preview.diagnostics().getFirst().code());
    }

    @Test void administrativeDispatchCannotReachTheCommandCapability() throws Exception {
        ExtensionRuntime started = start(true, List.of("town:allowed"));
        Outcome<String> outcome = started.action(COMMAND, NPC, Optional.empty(), "{\"command\":\"say hi\"}").toCompletableFuture().get(4, TimeUnit.SECONDS);
        assertEquals(Outcome.Status.UNAVAILABLE, outcome.status());
    }
}
