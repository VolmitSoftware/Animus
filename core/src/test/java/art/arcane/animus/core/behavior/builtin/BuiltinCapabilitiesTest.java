package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities.Availability;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
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
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.schema.CapabilitySchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinCapabilitiesTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final UUID PLAYER = UUID.randomUUID();
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private static final Set<String> ACTIONS = Set.of("say", "choices", "command", "navigate", "stop-navigation", "play-recording", "resume-patrol", "look", "pose", "set-appearance",
            "set-equipment", "set-attribute", "play-sound", "spawn-particle", "combat-control", "build", "set-flag", "set-quest-state", "advance-quest-state", "open-shop", "open-gui",
            "play-animation", "stop-animation", "message", "title", "actionbar", "teleport", "give-item", "take-item", "give-book");
    private static final Set<String> CONDITIONS = Set.of("has-permission", "npc-state", "player-in-range", "flag-state", "quest-state");

    private final RecordingPlatform platform = new RecordingPlatform();
    private final List<String> log = new ArrayList<>();
    private final List<DialogueChoice> dialogue = new ArrayList<>();
    private final List<Integer> randomBounds = new ArrayList<>();
    private ModuleHost host;
    private BuiltinCapabilities builtins;

    private void start(Platform runningOn, long deadlineMillis, BuiltinActionSettings settings) {
        host = new ModuleHost(runningOn, DEFINITIONS, 64, Duration.ofMillis(deadlineMillis));
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), dialogue::add, settings, line -> { synchronized (log) { log.add(line); } },
                (int bound) -> { synchronized (randomBounds) { randomBounds.add(bound); } return bound - 1; });
        Outcome<ModuleHost.ModuleStatus> installed = host.installBuiltin(builtins, builtins);
        assertEquals(Outcome.Status.OK, installed.status(), installed.diagnostics().toString());
    }
    private void start() { start(Platform.PAPER, 5000, BuiltinActionSettings.DEFAULT); }
    @AfterEach void stop() { if (host != null) host.close(); }

    private static BuiltinActionSettings grants(List<String> behaviors, List<String> namespaces) {
        BuiltinActionSettings defaults = BuiltinActionSettings.DEFAULT;
        return new BuiltinActionSettings(defaults.actionsPerTick(), defaults.actionsPerNpcPerTick(), defaults.queuedActions(), defaults.pendingChoices(), defaults.choiceLifetimeSeconds(), behaviors, namespaces, defaults.navigationWaits(), List.of());
    }
    private static Key key(String name) { return new Key("animus", name); }

    /** Waits for the host worker to admit the handler, then drives owner ticks until the result is delivered. */
    private <T> Outcome<T> settle(CompletionStage<Outcome<T>> stage) throws Exception {
        CompletableFuture<Outcome<T>> future = stage.toCompletableFuture();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!future.isDone() && System.nanoTime() < until) { platform.pump(); Thread.sleep(1); }
        return future.get(1, TimeUnit.MILLISECONDS);
    }
    private Outcome<String> action(String name, String json, Optional<UUID> player, boolean preview, boolean granted, Optional<String> behavior) throws Exception {
        return settle(host.actionHandle(key(name), NPC, player, json, preview, granted, () -> true, behavior).result());
    }
    private Outcome<String> action(String name, String json) throws Exception {
        return action(name, json, Optional.of(PLAYER), false, false, Optional.of("town:greeter"));
    }
    private Outcome<Boolean> condition(String name, String json, Optional<UUID> player) throws Exception {
        return settle(host.conditionHandle(key(name), NPC, player, json, false, () -> true, Optional.of("town:greeter")).result());
    }
    private static String code(Outcome<?> outcome) { return outcome.diagnostics().getFirst().code(); }

    @Test void everyCapabilityIsRegisteredThroughTheSharedRegistryWithACompiledBoundedSchema() {
        start();
        List<Availability> listed = host.capabilities();
        assertEquals(ACTIONS, listed.stream().filter(entry -> entry.descriptor().kind() == Kind.ACTION).map(entry -> entry.descriptor().key().name()).collect(Collectors.toSet()));
        assertEquals(CONDITIONS, listed.stream().filter(entry -> entry.descriptor().kind() == Kind.CONDITION).map(entry -> entry.descriptor().key().name()).collect(Collectors.toSet()));
        for (Availability entry : listed) {
            assertEquals("animus", entry.descriptor().key().namespace());
            assertDoesNotThrow(() -> CapabilitySchema.compile(entry.descriptor().configurationSchemaJson()));
            assertTrue(entry.descriptor().configurationSchemaJson().contains("\"additionalProperties\":false"), entry.descriptor().key().toString());
            assertEquals(entry.descriptor().key().name().equals("command"), entry.descriptor().privileged(), entry.descriptor().key().toString());
            assertEquals(Set.of(Platform.values()), entry.descriptor().platforms());
        }
    }

    @Test void externalModulesCannotClaimTheBuiltinNamespaceOrSeeItAsAModule() {
        start();
        assertTrue(host.modules().isEmpty());
        Outcome<ModuleHost.ModuleStatus> second = host.installBuiltin(builtins, builtins);
        assertEquals(Outcome.Status.INVALID, second.status());
    }

    @Test void sayRunsOnlyWhenTheOwnerTicks() throws Exception {
        start();
        ModuleHost.InvocationHandle<String> handle = host.actionHandle(key("say"), NPC, Optional.of(PLAYER), "{\"text\":\"Hello\"}", false, false, () -> true, Optional.empty());
        Thread.sleep(30);
        assertTrue(platform.calls.isEmpty());
        Outcome<String> outcome = settle(handle.result());
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        assertEquals(List.of("say:true:0.0:Hello"), platform.calls);
    }

    @Test void openShopOpensForTheTriggeringPlayerOnTheOwner() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("open-shop", "{\"shop\":\"town:baker\"}").status());
        assertEquals(List.of("screen:SHOP:town:baker"), platform.calls);
        assertEquals(Outcome.Status.OK, action("open-gui", "{\"gui\":\"town:menu\"}").status());
        assertEquals("screen:GUI:town:menu", platform.calls.get(1));
        assertEquals("no-triggering-player", code(action("open-shop", "{\"shop\":\"town:baker\"}", Optional.empty(), false, false, Optional.empty())));
        assertEquals("preview-refused", code(action("open-shop", "{\"shop\":\"town:baker\"}", Optional.of(PLAYER), true, false, Optional.empty())));
    }

    @Test void openGuiOpensMenusAndGlossMenusByKind() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("open-gui", "{\"gui\":\"town:hall\",\"kind\":\"menu\"}").status());
        assertEquals(Outcome.Status.OK, action("open-gui", "{\"gui\":\"lobby/Main\",\"kind\":\"gloss\"}").status());
        assertEquals(Outcome.Status.OK, action("open-gui", "{\"gui\":\"town:baker\",\"kind\":\"shop\"}").status());
        assertEquals(List.of("screen:MENU:town:hall", "screen:GLOSS:lobby/Main", "screen:SHOP:town:baker"), platform.calls);
        assertEquals(Outcome.Status.INVALID, action("open-gui", "{\"gui\":\"lobby/Main\",\"kind\":\"menu\"}").status());
        assertEquals(Outcome.Status.INVALID, action("open-gui", "{\"gui\":\"town:hall\",\"kind\":\"window\"}").status());
    }

    @Test void openShopNamesAShopId() throws Exception {
        start();
        assertEquals(Outcome.Status.INVALID, action("open-shop", "{\"shop\":\"Baker Shop\"}").status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void sayCanReachNearbyViewers() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("say", "{\"text\":\"All\",\"audience\":\"nearby\",\"radius\":12}").status());
        assertEquals(List.of("say:false:12.0:All"), platform.calls);
    }

    @Test void sayToTheTriggeringViewerRequiresOne() throws Exception {
        start();
        Outcome<String> outcome = action("say", "{\"text\":\"Hello\"}", Optional.empty(), false, false, Optional.empty());
        assertEquals(Outcome.Status.INVALID, outcome.status());
        assertEquals("no-triggering-player", code(outcome));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void schemaBoundsRejectBadArgumentsBeforeTheOwner() throws Exception {
        start();
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("say", "{\"text\":\"" + "x".repeat(1025) + "\"}"),
                Map.entry("look", "{\"x\":1,\"y\":2}"),
                Map.entry("pose", "{\"pose\":\"Sit Down\"}"),
                Map.entry("navigate", "{\"x\":1,\"y\":2,\"z\":3,\"speed\":99}"),
                Map.entry("set-equipment", "{\"slot\":\"tail\"}"),
                Map.entry("play-sound", "{\"sound\":\"minecraft:a\",\"volume\":99}"),
                Map.entry("spawn-particle", "{\"particle\":\"minecraft:flame\",\"count\":100000}"),
                Map.entry("combat-control", "{\"operation\":\"explode\"}"),
                Map.entry("set-quest-state", "{\"name\":\"q\"}"),
                Map.entry("play-animation", "{\"animation\":\"town:golem/wave\",\"speed\":9}"),
                Map.entry("stop-animation", "{\"fadeTicks\":41}"),
                Map.entry("choices", "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[]}"));
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            Outcome<String> outcome = action(entry.getKey(), entry.getValue());
            assertEquals(Outcome.Status.INVALID, outcome.status(), entry.getKey());
        }
        assertEquals(Outcome.Status.INVALID, action("say", "{\"text\":\"Hi\",\"extra\":1}").status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void semanticChecksRejectWhatTheSchemaSubsetCannotExpress() throws Exception {
        start();
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("say", "{\"text\":\"bad\\u0007bell\"}"),
                Map.entry("choices", "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[{\"id\":\"a\",\"text\":\"A\"},{\"id\":\"a\",\"text\":\"B\"}]}"),
                Map.entry("command", "{\"command\":\"/say hi\"}"),
                Map.entry("navigate", "{\"route\":\"Bad Route\"}"),
                Map.entry("play-recording", "{\"recording\":\"Bad Walk\"}"),
                Map.entry("set-appearance", "{\"skin\":\"abc\"}"),
                Map.entry("set-equipment", "{\"slot\":\"head\",\"item\":\"NOT A KEY\"}"),
                Map.entry("play-sound", "{\"sound\":\"NOT A KEY\"}"),
                Map.entry("play-animation", "{\"animation\":\"Not An Id\"}"),
                Map.entry("combat-control", "{\"operation\":\"target\",\"target\":\"not-a-uuid\"}"));
        for (Map.Entry<String, String> entry : invalid.entrySet())
            assertFalse(builtins.validate(key(entry.getKey()), entry.getValue()).isEmpty(), entry.getKey());
        assertEquals(Outcome.Status.INVALID, action("say", "{\"text\":\"bad\\u0007bell\"}").status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void titlesAndActionBarsMayBeWhitespaceButNotEmpty() throws Exception {
        start();
        for (String blank : List.of("{\"title\":\" \"}", "{\"subtitle\":\" \"}", "{\"title\":\" \",\"subtitle\":\"Welcome\"}"))
            assertTrue(builtins.validate(key("title"), blank).isEmpty(), blank + ": " + builtins.validate(key("title"), blank));
        assertTrue(builtins.validate(key("actionbar"), "{\"text\":\" \"}").isEmpty(), builtins.validate(key("actionbar"), "{\"text\":\" \"}").toString());
        assertFalse(builtins.validate(key("title"), "{\"title\":\"\"}").isEmpty());
        assertFalse(builtins.validate(key("actionbar"), "{\"text\":\"\"}").isEmpty());
        assertFalse(builtins.validate(key("title"), "{\"title\":\"bad\\u0007bell\"}").isEmpty());
        assertFalse(builtins.validate(key("give-book"), "{\"title\":\"Notes\",\"author\":\" \",\"pages\":[\"Hi\"]}").isEmpty(), "a book author must not be blank");
    }

    @Test void validActionsReachThePlatform() throws Exception {
        start();
        String target = UUID.randomUUID().toString();
        Map<String, String> valid = new java.util.LinkedHashMap<>();
        valid.put("navigate", "{\"x\":1,\"y\":64,\"z\":3,\"mode\":\"fly\",\"speed\":3}");
        valid.put("stop-navigation", "{}");
        valid.put("play-recording", "{\"recording\":\"market-walk\",\"playback\":\"once\"}");
        valid.put("resume-patrol", "{}");
        valid.put("look", "{\"x\":1,\"y\":64,\"z\":3}");
        valid.put("pose", "{\"pose\":\"rest\"}");
        valid.put("set-appearance", "{\"name\":\"Guard\"}");
        valid.put("set-equipment", "{\"slot\":\"main_hand\",\"item\":\"minecraft:iron_sword\"}");
        valid.put("play-sound", "{\"sound\":\"minecraft:entity.villager.yes\",\"volume\":1,\"pitch\":1}");
        valid.put("spawn-particle", "{\"particle\":\"minecraft:flame\",\"count\":8}");
        valid.put("combat-control", "{\"operation\":\"target\",\"target\":\"" + target + "\"}");
        valid.put("play-animation", "{\"animation\":\"town:golem/wave\",\"speed\":2,\"loop\":\"pingpong\",\"layer\":2}");
        valid.put("stop-animation", "{\"layer\":2,\"fadeTicks\":10}");
        for (Map.Entry<String, String> entry : valid.entrySet()) {
            Outcome<String> outcome = action(entry.getKey(), entry.getValue());
            assertEquals(Outcome.Status.OK, outcome.status(), entry.getKey() + outcome.diagnostics());
        }
        assertEquals(List.of("navigate:FLY:3.0", "stop", "recording:market-walk:once", "resume", "look-point", "pose:rest", "appearance:Guard:false", "equipment:MAIN_HAND:minecraft:iron_sword",
                "sound:minecraft:entity.villager.yes", "particle:minecraft:flame:8", "combat:TARGET:true",
                "animation:town:golem/wave:2.0:pingpong:2", "stop-animation:2:10"), platform.calls);
    }

    @Test void bareRegistryIdsReachThePlatformNamespaced() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("set-equipment", "{\"slot\":\"main_hand\",\"item\":\"netherite_axe\"}").status());
        assertEquals(Outcome.Status.OK, action("play-sound", "{\"sound\":\"entity.villager.yes\"}").status());
        assertEquals(Outcome.Status.OK, action("spawn-particle", "{\"particle\":\"flame\",\"count\":2}").status());
        assertEquals(List.of("equipment:MAIN_HAND:minecraft:netherite_axe", "sound:minecraft:entity.villager.yes", "particle:minecraft:flame:2"), platform.calls);
    }

    @Test void setAttributeSetsOrResetsOneAttributeAndChecksItsRange() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("set-attribute", "{\"attribute\":\"minecraft:scale\",\"value\":2}").status());
        assertEquals(Outcome.Status.OK, action("set-attribute", "{\"attribute\":\"movement_speed\",\"reset\":true}").status());
        assertEquals(List.of("attribute:scale:2.0", "attribute:movement_speed:reset"), platform.calls);
        assertFalse(builtins.validate(key("set-attribute"), "{\"attribute\":\"bogus\",\"value\":1}").isEmpty());
        assertFalse(builtins.validate(key("set-attribute"), "{\"attribute\":\"scale\",\"value\":99}").isEmpty());
        assertFalse(builtins.validate(key("set-attribute"), "{\"attribute\":\"scale\",\"value\":2,\"reset\":true}").isEmpty(), "a value and a reset at once");
        assertEquals(Outcome.Status.INVALID, action("set-attribute", "{\"attribute\":\"scale\"}").status(), "a value or a reset is required");
    }

    @Test void combatControlGuardsAParticipantOrStopsGuarding() throws Exception {
        start();
        String charge = UUID.randomUUID().toString();
        assertEquals(Outcome.Status.OK, action("combat-control", "{\"operation\":\"guard\",\"target\":\"" + charge + "\"}").status());
        assertEquals(Outcome.Status.OK, action("combat-control", "{\"operation\":\"guard\"}").status());
        assertEquals(Outcome.Status.INVALID, action("combat-control", "{\"operation\":\"release\",\"target\":\"" + charge + "\"}").status());
        assertEquals(List.of("combat:GUARD:true", "combat:GUARD:false"), platform.calls);
    }

    @Test void combatControlCanTargetOrGuardTheTriggeringPlayer() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("combat-control", "{\"operation\":\"guard\",\"target\":\"player\"}").status());
        assertEquals(Outcome.Status.OK, action("combat-control", "{\"operation\":\"target\",\"target\":\"player\"}").status());
        Outcome<String> alone = action("combat-control", "{\"operation\":\"guard\",\"target\":\"player\"}", Optional.empty(), false, false, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.INVALID, alone.status());
        assertEquals("no-triggering-player", code(alone));
        assertEquals(List.of("combat:GUARD:" + PLAYER, "combat:TARGET:" + PLAYER), platform.targets);
    }

    @Test void navigateFollowsReusableRoutesAndLookFacesTheTriggeringPlayer() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("navigate", "{\"route\":\"town-patrol\"}").status());
        assertEquals(Outcome.Status.OK, action("look", "{\"target\":\"player\"}").status());
        assertEquals(List.of("route:town-patrol", "look-player"), platform.calls);
        assertEquals(Outcome.Status.INVALID, action("navigate", "{\"route\":\"a\",\"x\":1,\"y\":2,\"z\":3}").status());
    }

    @Test void previewRefusesCommandCombatAndWorldMutationWithoutTouchingThePlatform() throws Exception {
        start();
        Map<String, String> refused = Map.ofEntries(
                Map.entry("command", "{\"command\":\"say hi\"}"),
                Map.entry("combat-control", "{\"operation\":\"enable\"}"),
                Map.entry("navigate", "{\"x\":1,\"y\":64,\"z\":3}"),
                Map.entry("stop-navigation", "{}"),
                Map.entry("play-recording", "{\"recording\":\"market-walk\"}"),
                Map.entry("resume-patrol", "{}"),
                Map.entry("look", "{\"x\":1,\"y\":64,\"z\":3}"),
                Map.entry("set-appearance", "{\"name\":\"Guard\"}"),
                Map.entry("set-equipment", "{\"slot\":\"head\"}"),
                Map.entry("play-animation", "{\"animation\":\"town:golem/wave\"}"),
                Map.entry("choices", "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[{\"id\":\"a\",\"text\":\"A\"}]}"),
                Map.entry("set-flag", "{\"name\":\"met\"}"),
                Map.entry("set-quest-state", "{\"name\":\"main\",\"value\":1}"),
                Map.entry("advance-quest-state", "{\"name\":\"main\"}"),
                Map.entry("say", "{\"text\":\"All\",\"audience\":\"nearby\"}"),
                Map.entry("play-sound", "{\"sound\":\"minecraft:a\",\"audience\":\"nearby\"}"),
                Map.entry("spawn-particle", "{\"particle\":\"minecraft:flame\",\"audience\":\"nearby\"}"));
        for (Map.Entry<String, String> entry : refused.entrySet()) {
            Outcome<String> outcome = action(entry.getKey(), entry.getValue(), Optional.of(PLAYER), true, true, Optional.of("town:greeter"));
            assertEquals(Outcome.Status.UNAVAILABLE, outcome.status(), entry.getKey());
        }
        assertTrue(platform.calls.isEmpty());
    }

    @Test void previewStillShowsTheViewerTheirOwnDialogueAndReadsConditions() throws Exception {
        start();
        assertEquals(Outcome.Status.OK, action("say", "{\"text\":\"Hi\"}", Optional.of(PLAYER), true, false, Optional.empty()).status());
        Outcome<Boolean> read = settle(host.conditionHandle(key("npc-state"), NPC, Optional.of(PLAYER), "{\"spawned\":true}", true, () -> true, Optional.empty()).result());
        assertEquals(Outcome.Status.OK, read.status());
        assertEquals(List.of("say:true:0.0:Hi", "status"), platform.calls);
    }

    @Test void commandIsDeniedByDefaultAndNeverRunsWithoutAGrant() throws Exception {
        start();
        Outcome<String> outcome = action("command", "{\"command\":\"say hi\"}");
        assertEquals(Outcome.Status.UNAVAILABLE, outcome.status());
        assertEquals("privileged-capability-not-granted", code(outcome));
        assertFalse(host.capabilityAvailable(key("command"), Kind.ACTION, false, false));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void grantedCommandRunsAndLogsTheBehaviorAndNpc() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        assertTrue(builtins.commandAllowed("town:greeter"));
        Outcome<String> outcome = action("command", "{\"command\":\"say hi\",\"as\":\"console\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        assertEquals(List.of("command:CONSOLE:say hi"), platform.calls);
        synchronized (log) {
            assertEquals(1, log.size());
            assertTrue(log.getFirst().contains("behavior=town:greeter"), log.getFirst());
            assertTrue(log.getFirst().contains("npc=" + NPC.uuid()), log.getFirst());
            assertTrue(log.getFirst().contains("say hi"), log.getFirst());
        }
    }

    @Test void handlerRechecksTheAllowListEvenIfAGrantFlagIsForged() throws Exception {
        start();
        Outcome<String> outcome = action("command", "{\"command\":\"say hi\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.UNAVAILABLE, outcome.status());
        assertEquals("privileged-capability-not-granted", code(outcome));
        Outcome<String> anonymous = action("command", "{\"command\":\"say hi\"}", Optional.of(PLAYER), false, true, Optional.empty());
        assertEquals(Outcome.Status.UNAVAILABLE, anonymous.status());
        assertTrue(platform.calls.isEmpty());
        synchronized (log) { assertTrue(log.isEmpty()); }
    }

    @Test void namespaceGrantCoversOnlyThatNamespace() {
        start(Platform.PAPER, 5000, grants(List.of(), List.of("town")));
        assertTrue(builtins.commandAllowed("town:quest/gate"));
        assertFalse(builtins.commandAllowed("towns:gate"));
        assertFalse(builtins.commandAllowed("other:town"));
    }

    @Test void consoleCommandNamesTheTriggeringPlayerByUuid() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        Outcome<String> outcome = action("command", "{\"command\":\"clear {player} minecraft:emerald 1\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        Outcome<String> alone = action("command", "{\"command\":\"clear {player} minecraft:emerald 1\"}", Optional.empty(), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.INVALID, alone.status());
        assertEquals("no-triggering-player", code(alone));
        assertEquals(List.of("command:CONSOLE:clear " + PLAYER + " minecraft:emerald 1"), platform.calls);
    }

    @Test void playerCommandRequiresATriggeringPlayer() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        Outcome<String> outcome = action("command", "{\"command\":\"spawn\",\"as\":\"player\"}", Optional.empty(), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.INVALID, outcome.status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void unavailablePlatformReportsWorldActionsUnavailableAndNeverSchedulesThem() throws Exception {
        platform.unavailable = Optional.of("general NPC runtime is disabled on Folia");
        start(Platform.FOLIA, 5000, BuiltinActionSettings.DEFAULT);
        assertFalse(platform.tickBound());
        Outcome<String> outcome = action("say", "{\"text\":\"Hi\"}");
        assertEquals(Outcome.Status.UNAVAILABLE, outcome.status());
        assertEquals("platform-unavailable", code(outcome));
        assertTrue(outcome.diagnostics().getFirst().message().contains("Folia"));
        assertFalse(host.capabilityAvailable(key("navigate"), Kind.ACTION, false, false));
        Availability listed = host.capabilities().stream().filter(entry -> entry.descriptor().key().name().equals("look")).findFirst().orElseThrow();
        assertFalse(listed.available());
        assertTrue(listed.reason().contains("Folia"), listed.reason());
        assertEquals(Outcome.Status.UNAVAILABLE, condition("has-permission", "{\"permission\":\"a.b\"}", Optional.of(PLAYER)).status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void stateCapabilitiesKeepWorkingWithoutAWorldOwner() throws Exception {
        platform.unavailable = Optional.of("general NPC runtime is disabled on Folia");
        start(Platform.FOLIA, 5000, BuiltinActionSettings.DEFAULT);
        assertTrue(host.capabilityAvailable(key("set-quest-state"), Kind.ACTION, false, false));
        assertEquals(Outcome.Status.OK, action("set-quest-state", "{\"name\":\"main\",\"value\":2}").status());
        assertEquals(Optional.of(true), condition("quest-state", "{\"name\":\"main\",\"equals\":2}", Optional.of(PLAYER)).value());
    }

    @Test void setQuestStateRandomPicksAStageBelowTheBound() throws Exception {
        start();
        Outcome<String> outcome = action("set-quest-state", "{\"name\":\"fortune\",\"random\":6}");
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        synchronized (randomBounds) { assertEquals(List.of(6), randomBounds); }
        assertEquals(Optional.of(true), condition("quest-state", "{\"name\":\"fortune\",\"equals\":5}", Optional.of(PLAYER)).value());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void setQuestStateNeedsExactlyOneOfValueOrRandom() throws Exception {
        start();
        Outcome<String> both = action("set-quest-state", "{\"name\":\"main\",\"value\":1,\"random\":3}");
        assertEquals(Outcome.Status.INVALID, both.status());
        assertTrue(both.diagnostics().getFirst().message().contains("random"), both.diagnostics().toString());
        assertTrue(both.diagnostics().getFirst().message().contains("value"), both.diagnostics().toString());
        Outcome<String> neither = action("set-quest-state", "{\"name\":\"main\"}");
        assertEquals(Outcome.Status.INVALID, neither.status());
        assertTrue(neither.diagnostics().getFirst().message().contains("random"), neither.diagnostics().toString());
        assertEquals(Outcome.Status.INVALID, action("set-quest-state", "{\"name\":\"main\",\"random\":0}").status());
        assertEquals(Outcome.Status.INVALID, action("set-quest-state", "{\"name\":\"main\",\"random\":1000001}").status());
        assertEquals(Outcome.Status.OK, action("set-quest-state", "{\"name\":\"main\",\"random\":1000000}").status());
        synchronized (randomBounds) { assertEquals(List.of(1_000_000), randomBounds); }
    }

    @Test void sayAndCommandReplacePlayerNameWithTheTriggeringPlayersName() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        platform.playerName = Optional.of("Alex");
        Outcome<String> said = action("say", "{\"text\":\"Hello {player_name}, {player_name}!\",\"audience\":\"nearby\"}");
        assertEquals(Outcome.Status.OK, said.status(), said.diagnostics().toString());
        Outcome<String> ran = action("command", "{\"command\":\"give {player_name} minecraft:bread 1 {player}\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.OK, ran.status(), ran.diagnostics().toString());
        assertEquals(List.of("say:false:16.0:Hello Alex, Alex!", "command:CONSOLE:give Alex minecraft:bread 1 " + PLAYER), platform.calls);
    }

    @Test void playerNameNeedsATriggeringPlayer() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        Outcome<String> said = action("say", "{\"text\":\"Hello {player_name}\",\"audience\":\"nearby\"}", Optional.empty(), false, false, Optional.of("town:greeter"));
        assertEquals("no-triggering-player", code(said));
        Outcome<String> ran = action("command", "{\"command\":\"give {player_name} minecraft:bread\"}", Optional.empty(), false, true, Optional.of("town:greeter"));
        assertEquals("no-triggering-player", code(ran));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void unknownPlayerNameRefusesWithoutRunningTheCommand() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        platform.playerName = Optional.empty();
        Outcome<String> ran = action("command", "{\"command\":\"give {player_name} minecraft:bread\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals("player-name-unknown", code(ran));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void conditionsReadThePlatform() throws Exception {
        start();
        assertEquals(Optional.of(true), condition("has-permission", "{\"permission\":\"town.vip\"}", Optional.of(PLAYER)).value());
        platform.permission = false;
        assertEquals(Optional.of(false), condition("has-permission", "{\"permission\":\"town.vip\"}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(false), condition("has-permission", "{\"permission\":\"town.vip\"}", Optional.empty()).value());
        assertEquals(Optional.of(true), condition("npc-state", "{\"spawned\":true,\"minimumHealth\":10}", Optional.empty()).value());
        assertEquals(Optional.of(false), condition("npc-state", "{\"minimumHealth\":21}", Optional.empty()).value());
        assertEquals(Optional.of(false), condition("npc-state", "{\"maximumHealth\":5}", Optional.empty()).value());
        platform.status = new BehaviorPlatform.NpcStatus(false, 0);
        assertEquals(Optional.of(true), condition("npc-state", "{\"spawned\":false}", Optional.empty()).value());
        assertEquals(Optional.of(true), condition("player-in-range", "{\"radius\":8}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(false), condition("player-in-range", "{\"radius\":8}", Optional.empty()).value());
        assertEquals(Optional.of(true), condition("player-in-range", "{\"radius\":8,\"anyPlayer\":true}", Optional.empty()).value());
        platform.inRange = false;
        assertEquals(Optional.of(false), condition("player-in-range", "{\"radius\":8}", Optional.of(PLAYER)).value());
    }

    @Test void flagAndQuestStateRoundTripThroughScopes() throws Exception {
        start();
        assertEquals(Optional.of(false), condition("flag-state", "{\"name\":\"met\"}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(true), condition("flag-state", "{\"name\":\"met\",\"value\":false}", Optional.of(PLAYER)).value());
        assertEquals(Outcome.Status.OK, action("set-flag", "{\"name\":\"met\"}").status());
        assertEquals(Optional.of(true), condition("flag-state", "{\"name\":\"met\"}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(false), condition("flag-state", "{\"name\":\"met\",\"scope\":\"global\"}", Optional.of(PLAYER)).value());
        assertEquals(Outcome.Status.OK, action("advance-quest-state", "{\"name\":\"main\",\"by\":2,\"maximum\":3,\"scope\":\"relationship\"}").status());
        assertEquals(Outcome.Status.OK, action("advance-quest-state", "{\"name\":\"main\",\"by\":2,\"maximum\":3,\"scope\":\"relationship\"}").status());
        assertEquals(Optional.of(true), condition("quest-state", "{\"name\":\"main\",\"scope\":\"relationship\",\"equals\":3}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(true), condition("quest-state", "{\"name\":\"main\",\"scope\":\"relationship\",\"atLeast\":1,\"atMost\":3}", Optional.of(PLAYER)).value());
        assertEquals(Optional.of(false), condition("quest-state", "{\"name\":\"absent\",\"atLeast\":1}", Optional.of(PLAYER)).value());
        Outcome<Boolean> needsPlayer = condition("quest-state", "{\"name\":\"main\",\"atLeast\":1}", Optional.empty());
        assertEquals(Outcome.Status.INVALID, needsPlayer.status());
        assertEquals("no-triggering-player", code(needsPlayer));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void choiceStylingIsValidatedAndReachesThePlatform() throws Exception {
        start();
        assertFalse(builtins.validate(key("choices"), "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[{\"id\":\"a\",\"text\":\"A\",\"color\":\"chartreuse\"}]}").isEmpty());
        assertFalse(builtins.validate(key("choices"), "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[{\"id\":\"a\",\"text\":\"A\",\"style\":\"wavy\"}]}").isEmpty());
        String styled = "{\"id\":\"c\",\"prompt\":\"&6Well?\",\"options\":[{\"id\":\"a\",\"text\":\"&lA\",\"color\":\"#55ff55\",\"hover\":\"Line one\\nline two\",\"style\":\"bold\"}]}";
        assertTrue(builtins.validate(key("choices"), styled).isEmpty(), builtins.validate(key("choices"), styled).toString());
        assertEquals(Outcome.Status.OK, action("choices", styled).status());
        BehaviorPlatform.ChoiceOption option = platform.presentedOptions.getFirst();
        assertEquals(Optional.of("#55ff55"), option.colour());
        assertEquals(Optional.of("Line one\nline two"), option.hover());
        assertEquals(Optional.of("bold"), option.style());
    }

    @Test void choicesPublishASelectionThroughTheNarrowSinkAndRejectForgedClicks() throws Exception {
        start();
        Outcome<String> outcome = action("choices", "{\"id\":\"greeting\",\"prompt\":\"Well?\",\"options\":[{\"id\":\"yes\",\"text\":\"Yes\"},{\"id\":\"no\",\"text\":\"No\"}]}");
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        String token = platform.presentedToken;
        assertEquals(List.of("yes", "no"), platform.presentedOptions.stream().map(BehaviorPlatform.ChoiceOption::id).toList());
        assertEquals(PendingChoices.Selection.WRONG_PLAYER, builtins.selectChoice(UUID.randomUUID(), token, "yes"));
        assertEquals(PendingChoices.Selection.UNKNOWN, builtins.selectChoice(PLAYER, "f".repeat(32), "yes"));
        assertTrue(dialogue.isEmpty());
        PendingChoices.Selection selection = selectOnOwner(token, "yes");
        assertEquals(PendingChoices.Selection.ACCEPTED, selection);
        assertEquals(List.of(new DialogueChoice(NPC, PLAYER, Optional.of("town:greeter"), "greeting", "yes")), dialogue);
        assertEquals(PendingChoices.Selection.UNKNOWN, selectOnOwner(token, "yes"));
    }

    private PendingChoices.Selection selectOnOwner(String token, String option) throws Exception {
        return platform.onOwner(() -> builtins.selectChoice(PLAYER, token, option));
    }

    @Test void clickRejectedWhenTheNpcIsGoneOrThePlayerLeftRange() throws Exception {
        start();
        action("choices", "{\"id\":\"greeting\",\"prompt\":\"Well?\",\"options\":[{\"id\":\"yes\",\"text\":\"Yes\"}]}");
        String token = platform.presentedToken;
        platform.inRange = false;
        assertEquals(PendingChoices.Selection.NPC_UNAVAILABLE, selectOnOwner(token, "yes"));
        platform.inRange = true;
        platform.status = new BehaviorPlatform.NpcStatus(false, 0);
        assertEquals(PendingChoices.Selection.NPC_UNAVAILABLE, selectOnOwner(token, "yes"));
        assertTrue(dialogue.isEmpty());
    }

    @Test void clickIsRefusedWhileThePlatformIsUnavailable() throws Exception {
        start();
        action("choices", "{\"id\":\"greeting\",\"prompt\":\"Well?\",\"options\":[{\"id\":\"yes\",\"text\":\"Yes\"}]}");
        platform.unavailable = Optional.of("stopped");
        assertEquals(PendingChoices.Selection.NPC_UNAVAILABLE, builtins.selectChoice(PLAYER, platform.presentedToken, "yes"));
        assertTrue(dialogue.isEmpty());
    }

    @Test void undeliveredPromptLeavesNothingToClick() throws Exception {
        start();
        platform.delivered = 0;
        Outcome<String> outcome = action("choices", "{\"id\":\"greeting\",\"prompt\":\"Well?\",\"options\":[{\"id\":\"yes\",\"text\":\"Yes\"}]}");
        assertEquals(Outcome.Status.OK, outcome.status());
        assertEquals(PendingChoices.Selection.UNKNOWN, selectOnOwner(platform.presentedToken, "yes"));
    }

    @Test void failingChoiceListenerIsLoggedAndStillAcceptsTheClick() throws Exception {
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 64, Duration.ofSeconds(5));
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), choice -> { throw new IllegalStateException("listener"); }, BuiltinActionSettings.DEFAULT, line -> { synchronized (log) { log.add(line); } });
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
        action("choices", "{\"id\":\"greeting\",\"prompt\":\"Well?\",\"options\":[{\"id\":\"yes\",\"text\":\"Yes\"}]}");
        assertEquals(PendingChoices.Selection.ACCEPTED, selectOnOwner(platform.presentedToken, "yes"));
        synchronized (log) { assertTrue(log.getFirst().contains("IllegalStateException"), log.toString()); }
    }

    @Test void closingBeforeEnableNeverStartsOwnerTicks() {
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 64, Duration.ofSeconds(5));
        builtins = new BuiltinCapabilities(platform, new MemoryBehaviorState(), dialogue::add, BuiltinActionSettings.DEFAULT, line -> {});
        builtins.close();
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
        assertFalse(platform.tickBound());
    }

    @Test void slowOwnerTimesOutWithoutSuspendingBuiltinsOrRunningTheAction() throws Exception {
        start(Platform.PAPER, 200, BuiltinActionSettings.DEFAULT);
        CompletableFuture<Outcome<String>> slow = host.actionHandle(key("stop-navigation"), NPC, Optional.empty(), "{}", false, false, () -> true, Optional.empty()).result().toCompletableFuture();
        Outcome<String> timedOut = slow.get(4, TimeUnit.SECONDS);
        assertEquals(Outcome.Status.TIMED_OUT, timedOut.status());
        platform.pump();
        assertTrue(platform.calls.isEmpty());
        assertTrue(host.capabilityAvailable(key("say"), Kind.ACTION, false, false));
        assertEquals(Outcome.Status.OK, action("stop-navigation", "{}").status());
    }

    @Test void platformRefusalsKeepTheirStatusAndUnexpectedFailuresBecomeFailed() throws Exception {
        start();
        platform.failure = new art.arcane.animus.core.extension.CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-inactive", "NPC is not active");
        Outcome<String> refused = action("stop-navigation", "{}");
        assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
        assertEquals("npc-inactive", code(refused));
        platform.failure = new IllegalStateException("boom");
        assertEquals(Outcome.Status.FAILED, action("stop-navigation", "{}").status());
    }

    @Test void revokedSourceDropsQueuedWork() throws Exception {
        start();
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        ModuleHost.InvocationHandle<String> handle = host.actionHandle(key("say"), NPC, Optional.of(PLAYER), "{\"text\":\"Hi\"}", false, false, current::get, Optional.empty());
        Thread.sleep(30);
        current.set(false);
        Outcome<String> outcome = settle(handle.result());
        assertEquals(Outcome.Status.CANCELLED, outcome.status());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void disableStopsOwnerTicksAndRefusesLateWork() throws Exception {
        start();
        assertTrue(platform.tickBound());
        builtins.close();
        builtins.close();
        assertFalse(platform.tickBound());
        assertEquals(1, platform.closedTicks.get());
        assertEquals(Outcome.Status.UNAVAILABLE, settle(host.actionHandle(key("say"), NPC, Optional.of(PLAYER), "{\"text\":\"Hi\"}", false, false, () -> true, Optional.empty()).result()).status());
    }

    private BehaviorProgram program(String routine) {
        String source = "{\"schemaVersion\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"variables\":{\"line\":{\"type\":\"string\",\"initial\":\"Bound hello\"}},\"routines\":{\"main\":" + routine + "}}";
        BehaviorCompiler.Result compiled = BehaviorCompiler.compile(source);
        assertTrue(compiled.program().isPresent(), compiled.diagnostics().toString());
        return compiled.program().orElseThrow();
    }
    private BehaviorEngine.Report run(BehaviorProgram program, boolean preview, java.util.function.Predicate<String> grants) throws Exception {
        return run(program, preview, grants, behavior -> false);
    }
    private BehaviorEngine.Report run(BehaviorProgram program, boolean preview, java.util.function.Predicate<String> grants, java.util.function.Predicate<String> rawText) throws Exception {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Limits.defaults(), new HostBehaviorInvoker(host, grants, rawText), ignored -> {}, art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        UUID id = engine.start(program, new BehaviorEngine.Context(NPC, Optional.of(PLAYER), preview)).value().orElseThrow();
        for (int i = 0; i < 2000 && engine.inspect(id).orElseThrow().state() == BehaviorEngine.State.RUNNING; i++) { engine.tick(); platform.pump(); Thread.sleep(1); }
        BehaviorEngine.Report report = engine.inspect(id).orElseThrow();
        engine.close();
        return report;
    }

    @Test void jsonBehaviorDrivesBuiltinsWithBoundArgumentsInOrder() throws Exception {
        start();
        BehaviorEngine.Report report = run(program("""
                {"type":"condition","capability":"animus:player-in-range","arguments":{"radius":8},
                 "then":{"type":"sequence","steps":[
                    {"type":"action","capability":"animus:say","arguments":{"text":null},"bindings":{"/text":{"variable":"line"}}},
                    {"type":"action","capability":"animus:set-flag","arguments":{"name":"met"}},
                    {"type":"action","capability":"animus:look","arguments":{"target":"player"}}]}}
                """), false, behavior -> false);
        assertEquals(BehaviorEngine.State.COMPLETED, report.state(), report.toString());
        assertEquals(List.of("range:true:8.0", "say:true:0.0:Bound hello", "look-player"), platform.calls);
    }

    @Test void previewExecutionFlagReachesBuiltinsThroughTheEngine() throws Exception {
        start();
        BehaviorEngine.Report report = run(program("{\"type\":\"action\",\"capability\":\"animus:stop-navigation\"}"), true, behavior -> false);
        assertEquals(BehaviorEngine.State.UNAVAILABLE, report.state(), report.toString());
        assertEquals("preview-refused", report.diagnostics().getFirst().code());
        assertTrue(platform.calls.isEmpty());
    }

    @Test void engineDeniesUngrantedCommandAndAllowsGrantedBehaviorIdentity() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        BehaviorProgram program = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"say hi\"}}");
        assertEquals(BehaviorEngine.State.UNAVAILABLE, run(program, false, behavior -> false).state());
        assertTrue(platform.calls.isEmpty());
        assertEquals(BehaviorEngine.State.UNAVAILABLE, run(program, true, builtins::commandAllowed).state());
        assertTrue(platform.calls.isEmpty());
        BehaviorEngine.Report granted = run(program, false, builtins::commandAllowed);
        assertEquals(BehaviorEngine.State.COMPLETED, granted.state(), granted.toString());
        assertEquals(List.of("command:CONSOLE:say hi"), platform.calls);
    }

    @Test void interpolatedTextOutsideTheSafeSetNeverReachesACommand() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        BehaviorProgram bound = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"bindings\":{\"/command\":{\"variable\":\"line\"}}}");
        BehaviorEngine.Report refused = run(bound, false, builtins::commandAllowed);
        assertEquals(BehaviorEngine.State.FAILED, refused.state(), refused.toString());
        BehaviorProgram templated = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"templates\":{\"/command\":\"give {{ line }} minecraft:bread 1\"}}");
        assertEquals(BehaviorEngine.State.FAILED, run(templated, false, builtins::commandAllowed).state());
        assertTrue(platform.calls.isEmpty(), "a space in interpolated text could add arguments");
        BehaviorProgram safe = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"templates\":{\"/command\":\"give {{ 'Alex' }} minecraft:bread {{ 2 }}\"}}");
        BehaviorEngine.Report ran = run(safe, false, builtins::commandAllowed);
        assertEquals(BehaviorEngine.State.COMPLETED, ran.state(), ran.toString());
        assertEquals(List.of("command:CONSOLE:give Alex minecraft:bread 2"), platform.calls);
    }

    @Test void rawTextGrantLetsABehaviorInterpolateWordsButNeverControlCharactersOrASlash() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        BehaviorProgram templated = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"templates\":{\"/command\":\"say {{ line }}\"}}");
        BehaviorEngine.Report ran = run(templated, false, builtins::commandAllowed, "town:greeter"::equals);
        assertEquals(BehaviorEngine.State.COMPLETED, ran.state(), ran.toString());
        assertEquals(List.of("command:CONSOLE:say Bound hello"), platform.calls);
        BehaviorProgram broken = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"templates\":{\"/command\":\"say {{ line }}{{ '\\\\n' }}op Alex\"}}");
        assertEquals(BehaviorEngine.State.FAILED, run(broken, false, builtins::commandAllowed, "town:greeter"::equals).state());
        BehaviorProgram slashed = program("{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":null},\"templates\":{\"/command\":\"{{ '/' }}op Alex\"}}");
        assertEquals(BehaviorEngine.State.FAILED, run(slashed, false, builtins::commandAllowed, "town:greeter"::equals).state());
        assertEquals(1, platform.calls.size());
    }

    @Test void consoleCommandRefusesAPlayerNameOutsideTheSafeSet() throws Exception {
        start(Platform.PAPER, 5000, grants(List.of("town:greeter"), List.of()));
        platform.playerName = Optional.of("Al ex");
        Outcome<String> refused = action("command", "{\"command\":\"give {player_name} minecraft:bread\"}", Optional.of(PLAYER), false, true, Optional.of("town:greeter"));
        assertEquals(Outcome.Status.INVALID, refused.status(), refused.diagnostics().toString());
        assertTrue(platform.calls.isEmpty());
    }
}
