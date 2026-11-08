package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.DefinitionEdit;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Server behavior runs (no NPC) and the player-addressed capabilities. */
class ServerCapabilitiesTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final UUID PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        @Override public DefinitionService scoped(BooleanSupplier available) { return this; }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { throw new UnsupportedOperationException(); }
    };
    private final RecordingPlatform platform = new RecordingPlatform();
    private final MemoryBehaviorState state = new MemoryBehaviorState();
    private ModuleHost host;
    private BuiltinCapabilities builtins;

    @BeforeEach void start() {
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 64, Duration.ofMillis(5000));
        BuiltinActionSettings defaults = BuiltinActionSettings.DEFAULT;
        BuiltinActionSettings settings = new BuiltinActionSettings(defaults.actionsPerTick(), defaults.actionsPerNpcPerTick(), defaults.queuedActions(), defaults.pendingChoices(),
                defaults.choiceLifetimeSeconds(), List.of(), List.of("denizen"), defaults.navigationWaits());
        builtins = new BuiltinCapabilities(platform, state, choice -> {}, settings, line -> {}, (int bound) -> 0, () -> state.now);
        assertEquals(Outcome.Status.OK, host.installBuiltin(builtins, builtins).status());
    }
    @AfterEach void stop() { host.close(); }

    private static Key key(String name) { return new Key("animus", name); }
    private <T> Outcome<T> settle(CompletionStage<Outcome<T>> stage) throws Exception {
        CompletableFuture<Outcome<T>> future = stage.toCompletableFuture();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!future.isDone() && System.nanoTime() < until) { platform.pump(); Thread.sleep(1); }
        return future.get(1, TimeUnit.MILLISECONDS);
    }
    private Outcome<String> server(String name, String json) throws Exception { return server(name, json, Optional.of(PLAYER)); }
    private Outcome<String> server(String name, String json, Optional<UUID> player) throws Exception {
        return settle(host.actionHandle(key(name), ServerNpc.HANDLE, player, json, false, true, () -> true, Optional.of("denizen:world/welcome")).result());
    }
    private Outcome<String> npc(String name, String json, boolean preview) throws Exception {
        return settle(host.actionHandle(key(name), NPC, Optional.of(PLAYER), json, preview, false, () -> true, Optional.of("town:greeter")).result());
    }
    private Outcome<Boolean> condition(NpcHandle npc, String name, String json) throws Exception {
        return settle(host.conditionHandle(key(name), npc, Optional.of(PLAYER), json, false, () -> true, Optional.of("denizen:world/welcome")).result());
    }
    private static String code(Outcome<?> outcome) { return outcome.diagnostics().getFirst().code(); }

    @Test void npcCapabilitiesRefuseServerRunsAsNoNpc() throws Exception {
        Map<String, String> npcOnly = Map.of("say", "{\"text\":\"Hi\"}", "look", "{\"target\":\"player\"}", "stop-navigation", "{}",
                "play-recording", "{\"recording\":\"market-walk\"}", "resume-patrol", "{}",
                "set-appearance", "{\"name\":\"Bob\"}", "play-animation", "{\"animation\":\"town:golem/wave\"}", "choices", "{\"id\":\"c\",\"prompt\":\"p\",\"options\":[{\"id\":\"a\",\"text\":\"A\"}]}");
        for (Map.Entry<String, String> entry : npcOnly.entrySet()) {
            Outcome<String> outcome = server(entry.getKey(), entry.getValue());
            assertEquals(Outcome.Status.INVALID, outcome.status(), entry.getKey());
            assertEquals("no-npc", code(outcome), entry.getKey());
        }
        assertEquals("no-npc", code(condition(ServerNpc.HANDLE, "npc-state", "{\"spawned\":true}")));
        assertEquals("no-npc", code(server("play-sound", "{\"sound\":\"minecraft:a\",\"at\":\"npc\"}")));
        assertEquals("no-npc", code(server("teleport", "{\"to\":\"npc\"}")));
        assertEquals("no-npc", code(server("set-flag", "{\"name\":\"met\",\"scope\":\"npc\"}")));
        assertTrue(platform.calls.isEmpty());
    }

    @Test void serverRunsAddressThePlayerOnThePlayersOwner() throws Exception {
        assertEquals(Outcome.Status.OK, server("message", "{\"text\":\"Welcome {player_name}\"}").status());
        assertEquals(Outcome.Status.OK, server("play-sound", "{\"sound\":\"minecraft:a\"}").status());
        assertEquals(Outcome.Status.OK, server("open-gui", "{\"gui\":\"town:menu\"}").status());
        assertEquals(List.of("message:PLAYER:true:Welcome Steve", "sound-at-player:minecraft:a", "screen:GUI:town:menu"), platform.calls);
        assertEquals(List.of("player:" + PLAYER, "player:" + PLAYER, "player:" + PLAYER), platform.routes);
        platform.routes.clear();
        assertEquals(Outcome.Status.OK, server("message", "{\"text\":\"Hello all\",\"to\":\"all\"}").status());
        assertEquals(Outcome.Status.OK, server("command", "{\"command\":\"say hi\"}").status());
        assertEquals(List.of("global", "global"), platform.routes);
        assertEquals(Outcome.Status.OK, condition(ServerNpc.HANDLE, "has-permission", "{\"permission\":\"town.vip\"}").status());
        assertEquals("player:" + PLAYER, platform.routes.getLast());
    }

    @Test void thePlayerArgumentNamesAnotherPlayer() throws Exception {
        assertEquals(Outcome.Status.OK, server("title", "{\"title\":\"Hi\",\"player\":\"" + OTHER + "\"}").status());
        assertEquals(List.of("player:" + OTHER), platform.routes);
        assertEquals(Outcome.Status.OK, server("give-item", "{\"item\":\"minecraft:bread\",\"player\":\"" + OTHER + "\"}", Optional.empty()).status());
        assertEquals("player:" + OTHER, platform.routes.getLast());
        assertEquals(Outcome.Status.INVALID, server("title", "{\"title\":\"Hi\",\"player\":\"steve\"}").status());
        assertEquals("no-triggering-player", code(server("title", "{\"title\":\"Hi\"}", Optional.empty())));
    }

    @Test void titlesActionBarsItemsAndBooksReachThePlatform() throws Exception {
        assertEquals(Outcome.Status.OK, server("title", "{\"subtitle\":\"Sub\"}").status());
        assertEquals("title::Sub:10:70:20", platform.calls.getLast());
        assertEquals(Outcome.Status.INVALID, server("title", "{\"fadeIn\":5}").status());
        assertEquals(Outcome.Status.OK, server("actionbar", "{\"text\":\"Bar\"}").status());
        assertEquals("actionbar:Bar", platform.calls.getLast());
        Outcome<String> given = server("give-item", "{\"item\":\"minecraft:bread\",\"amount\":3,\"name\":\"Loaf\",\"lore\":[\"warm\"]}");
        assertEquals(Optional.of("{\"given\":3}"), given.value());
        assertEquals("give:minecraft:bread:3:Loaf:1", platform.calls.getLast());
        assertEquals(Optional.of("{\"taken\":3}"), server("take-item", "{\"item\":\"minecraft:bread\",\"amount\":5}").value());
        assertEquals(Optional.of("{\"given\":1}"), server("give-book", "{\"title\":\"Guide\",\"author\":\"Town\",\"pages\":[\"One\",\"Two\"]}").value());
        assertEquals("book:Guide:2", platform.calls.getLast());
        assertEquals(Outcome.Status.INVALID, server("give-item", "{\"item\":\"minecraft:bread\",\"amount\":6401}").status());
        assertEquals(Outcome.Status.INVALID, server("give-book", "{\"title\":\"Guide\",\"author\":\"Town\",\"pages\":[]}").status());
        assertEquals(Optional.of("{\"teleported\":true}"), server("teleport", "{\"x\":1,\"y\":2,\"z\":3,\"world\":\"world_nether\",\"yaw\":90}").value());
        assertEquals("teleport-player:Point", platform.calls.getLast());
    }

    @Test void bareItemIdsGiveAndTakeMinecraftItems() throws Exception {
        assertEquals(Optional.of("{\"given\":2}"), server("give-item", "{\"item\":\"bread\",\"amount\":2}").value());
        assertEquals("give:minecraft:bread:2::0", platform.calls.getLast());
        assertEquals(Optional.of("{\"taken\":1}"), server("take-item", "{\"item\":\"bread\",\"amount\":1}").value());
        assertEquals("take:minecraft:bread:1", platform.calls.getLast());
    }

    @Test void npcRunsKeepTheirOwnerAndPreviewRules() throws Exception {
        assertEquals(Outcome.Status.OK, npc("teleport", "{\"target\":\"npc\",\"to\":\"player\"}", false).status());
        assertEquals("teleport-npc:ToPlayer", platform.calls.getLast());
        assertEquals("npc", platform.routes.getLast());
        assertEquals(Outcome.Status.OK, npc("play-sound", "{\"sound\":\"minecraft:a\",\"at\":\"player\"}", false).status());
        assertEquals("sound-at-player:minecraft:a", platform.calls.getLast());
        assertEquals(Outcome.Status.OK, npc("message", "{\"text\":\"Hi\"}", true).status(), "a message to the previewing player is allowed");
        assertEquals("preview-refused", code(npc("message", "{\"text\":\"Hi\",\"to\":\"all\"}", true)));
        assertEquals("preview-refused", code(npc("title", "{\"title\":\"Hi\",\"player\":\"" + OTHER + "\"}", true)));
        assertEquals("preview-refused", code(npc("give-item", "{\"item\":\"minecraft:bread\"}", true)));
        assertEquals(Outcome.Status.OK, npc("message", "{\"text\":\"World\",\"to\":\"world\"}", false).status());
        assertEquals("npc", platform.routes.getLast());
    }

    @Test void flagsHoldAnyValueExpireAndClear() throws Exception {
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"visits\",\"value\":3}").status());
        assertEquals(Optional.of(true), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"visits\",\"value\":3}").value());
        assertEquals(Optional.of(false), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"visits\",\"value\":3.0}").value(), "a decimal never equals an integer");
        assertEquals(Optional.of(false), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"visits\"}").value(), "another type equals nothing");
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"rank\",\"value\":\"gold\",\"expireSeconds\":10}").status());
        assertEquals(Optional.of(true), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"rank\",\"value\":\"gold\"}").value());
        state.now += 10_000;
        assertEquals(Optional.of(false), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"rank\",\"value\":\"gold\"}").value());
        assertEquals(Optional.of(true), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"rank\",\"value\":false}").value(), "an expired flag is false");
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"met\"}").status());
        assertEquals(Optional.of(true), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"met\"}").value());
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"met\",\"clear\":true}").status());
        assertEquals(Optional.of(false), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"met\"}").value());
        assertEquals(Outcome.Status.INVALID, server("set-flag", "{\"name\":\"met\",\"clear\":true,\"value\":1}").status());
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"items\",\"value\":[1,\"a\",{\"b\":2.5}]}").status());
        assertEquals(Optional.of(true), condition(ServerNpc.HANDLE, "flag-state", "{\"name\":\"items\",\"value\":[1,\"a\",{\"b\":2.5}]}").value(), "lists and maps compare by value");
        assertEquals(Outcome.Status.INVALID, server("set-flag", "{\"name\":\"met\",\"expireSeconds\":0}").status());
        assertTrue(builtins.validate(key("set-flag"), "{\"name\":\"met\",\"value\":{\"a\":1}}").isEmpty());
        assertFalse(builtins.validate(key("set-flag"), "{\"name\":\"met\",\"value\":\"" + "x".repeat(4097) + "\"}").isEmpty());
    }

    @Test void expiryNeedsAStateServiceThatStoresIt() throws Exception {
        host.close();
        host = new ModuleHost(Platform.PAPER, DEFINITIONS, 64, Duration.ofMillis(5000));
        art.arcane.animus.core.behavior.BehaviorState plain = new art.arcane.animus.core.behavior.BehaviorState() {
            @Override public art.arcane.animus.core.behavior.BehaviorProgram.Value read(Subject subject, String variable, art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable declaration, art.arcane.animus.core.behavior.BehaviorProgram.Value initial) { return initial; }
            @Override public void write(Subject subject, Map<String, art.arcane.animus.core.behavior.BehaviorProgram.Value> writes, Map<String, art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable> declarations) {}
        };
        builtins = new BuiltinCapabilities(platform, plain, choice -> {}, BuiltinActionSettings.DEFAULT, line -> {});
        host.installBuiltin(builtins, builtins);
        assertEquals("behavior-state-expiry-unavailable", code(server("set-flag", "{\"name\":\"met\",\"expireSeconds\":5}")));
        assertEquals(Outcome.Status.OK, server("set-flag", "{\"name\":\"met\",\"value\":2}").status());
    }
}
