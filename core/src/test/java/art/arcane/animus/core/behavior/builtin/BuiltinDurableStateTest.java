package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Flag and quest capabilities against the real behavior state store and its budgets and purge selectors. */
class BuiltinDurableStateTest {
    @TempDir Path root;
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID()), OTHER_NPC = new NpcHandle(UUID.randomUUID());
    private static final UUID PLAYER = UUID.randomUUID(), OTHER_PLAYER = UUID.randomUUID();
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private DocumentStore documents;
    private BehaviorStateStore state;
    private ModuleHost host;

    private void open(BehaviorStateStore.Limits limits) throws Exception {
        documents = new DocumentStore(root);
        state = BehaviorStateStore.open(documents, limits);
        host = new ModuleHost(Platform.FOLIA, DEFINITIONS, 16, Duration.ofSeconds(5));
        RecordingPlatform platform = new RecordingPlatform();
        platform.unavailable = Optional.of("no world here");
        BuiltinCapabilities builtins = new BuiltinCapabilities(platform, state, choice -> {}, BuiltinActionSettings.DEFAULT, line -> {});
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
    }
    private void close() throws Exception {
        if (host != null) host.close();
        if (state != null) state.close();
        if (documents != null) documents.close();
        host = null; state = null; documents = null;
    }
    @BeforeEach void start() throws Exception { open(BehaviorStateStore.Limits.defaults()); }
    @AfterEach void stop() throws Exception { close(); }

    private Outcome<String> action(String name, String json, NpcHandle npc, UUID player, String behavior) throws Exception {
        return host.actionHandle(new Key("animus", name), npc, Optional.ofNullable(player), json, false, false, () -> true, Optional.ofNullable(behavior)).result().toCompletableFuture().get(4, TimeUnit.SECONDS);
    }
    private boolean quest(String json, NpcHandle npc, UUID player, String behavior) throws Exception {
        Outcome<Boolean> outcome = host.conditionHandle(new Key("animus", "quest-state"), npc, Optional.ofNullable(player), json, false, () -> true, Optional.of(behavior)).result().toCompletableFuture().get(4, TimeUnit.SECONDS);
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        return outcome.value().orElseThrow();
    }
    private boolean flag(String json, NpcHandle npc, UUID player, String behavior) throws Exception {
        Outcome<Boolean> outcome = host.conditionHandle(new Key("animus", "flag-state"), npc, Optional.ofNullable(player), json, false, () -> true, Optional.of(behavior)).result().toCompletableFuture().get(4, TimeUnit.SECONDS);
        assertEquals(Outcome.Status.OK, outcome.status(), outcome.diagnostics().toString());
        return outcome.value().orElseThrow();
    }
    private static final String STAGE_TWO = "{\"name\":\"main\",\"equals\":2}";

    @Test void questsSurviveARestart() throws Exception {
        assertEquals(Outcome.Status.OK, action("set-quest-state", "{\"name\":\"main\",\"value\":2}", NPC, PLAYER, "town:greeter").status());
        assertEquals(Outcome.Status.OK, action("set-flag", "{\"name\":\"met\",\"scope\":\"relationship\"}", NPC, PLAYER, "town:greeter").status());
        close();
        open(BehaviorStateStore.Limits.defaults());
        assertTrue(quest(STAGE_TWO, NPC, PLAYER, "town:greeter"));
        assertTrue(flag("{\"name\":\"met\",\"scope\":\"relationship\"}", NPC, PLAYER, "town:greeter"));
        assertTrue(Files.exists(root.resolve("state/behavior-state/player/" + PLAYER + "/animus/quests/town.json")));
    }

    @Test void questsAreSharedAcrossBehaviorsAndNpcsInANamespaceAndSeparateBetweenNamespaces() throws Exception {
        action("set-quest-state", "{\"name\":\"main\",\"value\":2}", NPC, PLAYER, "town:greeter");
        assertTrue(quest(STAGE_TWO, OTHER_NPC, PLAYER, "town:quest/gatekeeper"));
        assertFalse(quest(STAGE_TWO, NPC, PLAYER, "harbor:greeter"));
        assertFalse(quest(STAGE_TWO, NPC, OTHER_PLAYER, "town:greeter"));
    }

    @Test void flagScopesKeepTheirOwnIdentity() throws Exception {
        action("set-flag", "{\"name\":\"open\",\"scope\":\"npc\"}", NPC, null, "town:gate");
        action("set-flag", "{\"name\":\"event\",\"scope\":\"global\"}", NPC, null, "town:gate");
        assertTrue(flag("{\"name\":\"open\",\"scope\":\"npc\"}", NPC, null, "town:other"));
        assertFalse(flag("{\"name\":\"open\",\"scope\":\"npc\"}", OTHER_NPC, null, "town:other"));
        assertTrue(flag("{\"name\":\"event\",\"scope\":\"global\"}", OTHER_NPC, null, "town:other"));
    }

    @Test void purgeSelectorsReachBuiltinState() throws Exception {
        action("set-quest-state", "{\"name\":\"main\",\"value\":2}", NPC, PLAYER, "town:greeter");
        action("set-quest-state", "{\"name\":\"main\",\"value\":2}", NPC, OTHER_PLAYER, "town:greeter");
        action("set-flag", "{\"name\":\"met\",\"scope\":\"relationship\"}", NPC, PLAYER, "town:greeter");
        action("set-flag", "{\"name\":\"gate\",\"scope\":\"npc\"}", NPC, null, "town:greeter");
        assertEquals(1, state.purge(BehaviorStateStore.Purge.relationship(NPC.uuid(), PLAYER)).keys());
        assertFalse(flag("{\"name\":\"met\",\"scope\":\"relationship\"}", NPC, PLAYER, "town:greeter"));
        assertEquals(1, state.purge(BehaviorStateStore.Purge.npc(NPC.uuid())).keys());
        assertEquals(1, state.purge(BehaviorStateStore.Purge.player(PLAYER)).keys());
        assertFalse(quest(STAGE_TWO, NPC, PLAYER, "town:greeter"));
        assertTrue(quest(STAGE_TWO, NPC, OTHER_PLAYER, "town:greeter"));
        assertEquals(1, state.purge(BehaviorStateStore.Purge.behavior("animus:quests/town")).keys());
        assertFalse(quest(STAGE_TWO, NPC, OTHER_PLAYER, "town:greeter"));
        close();
        open(BehaviorStateStore.Limits.defaults());
        assertFalse(quest(STAGE_TWO, NPC, OTHER_PLAYER, "town:greeter"));
    }

    @Test void stateBudgetsRefuseWithTheirDiagnostic() throws Exception {
        close();
        open(new BehaviorStateStore.Limits(1, 1, 16, 1024, 5000));
        assertEquals(Outcome.Status.OK, action("set-flag", "{\"name\":\"first\"}", NPC, PLAYER, "town:greeter").status());
        Outcome<String> refused = action("set-flag", "{\"name\":\"second\"}", NPC, PLAYER, "town:greeter");
        assertEquals(Outcome.Status.FAILED, refused.status());
        assertEquals("behavior-state-key-budget", refused.diagnostics().getFirst().code());
        assertEquals(Outcome.Status.OK, action("set-flag", "{\"name\":\"first\",\"value\":false}", NPC, PLAYER, "town:greeter").status());
    }

    @Test void concurrentAdvancesAreNotLost() throws Exception {
        List<CompletionStage<Outcome<String>>> advances = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++)
            advances.add(host.actionHandle(new Key("animus", "advance-quest-state"), NPC, Optional.of(PLAYER), "{\"name\":\"main\"}", false, false, () -> true, Optional.of("town:greeter")).result());
        for (CompletionStage<Outcome<String>> advance : advances) assertEquals(Outcome.Status.OK, advance.toCompletableFuture().get(4, TimeUnit.SECONDS).status());
        assertTrue(quest("{\"name\":\"main\",\"equals\":12}", NPC, PLAYER, "town:greeter"));
    }

    @Test void directDispatchHasNoNamespaceToStoreUnder() throws Exception {
        Outcome<String> refused = action("set-flag", "{\"name\":\"met\"}", NPC, PLAYER, null);
        assertEquals(Outcome.Status.INVALID, refused.status());
        assertEquals("no-behavior-namespace", refused.diagnostics().getFirst().code());
    }

    @Test void behaviorsCannotClaimTheStateIdentitiesOfBuiltins() {
        for (String id : List.of("animus:quests/town", "animus:flags/town", "animus:quests", "animus:flags")) {
            BehaviorCompiler.Result result = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}}");
            assertTrue(result.program().isEmpty(), id);
            assertEquals("$.id", result.diagnostics().getFirst().field());
        }
        assertTrue(BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"animus:questline\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}}").program().isPresent());
    }

    @Test void stateNamesAreLimitedToWhatTheStoreCanReload() throws Exception {
        assertEquals(Outcome.Status.INVALID, action("set-flag", "{\"name\":\"has.dot\"}", NPC, PLAYER, "town:greeter").status());
    }
}
