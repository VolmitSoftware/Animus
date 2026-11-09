package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerTriggerDispatcherTest {
    private static final String ROUTINES = "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]},\"greet\":{\"type\":\"sequence\",\"steps\":[]}}";
    private final UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
    private final Map<String, BehaviorCatalog.Resolution> resolutions = new LinkedHashMap<>();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>(new BehaviorCatalog.Snapshot(0, Map.of(), Map.of(), List.of()));
    private final AtomicReference<ServerBehaviorList> enabled = new AtomicReference<>(ServerBehaviorList.UNLOADED);
    private final List<ServerTriggerDispatcher.Request> started = new ArrayList<>();
    private ServerTriggerDispatcher dispatcher;
    private long revision;

    private static ServerTriggerSettings settings(int queue, int perTrigger, int concurrentPerTrigger, int cooldown, int commands) {
        return new ServerTriggerSettings(true, queue, 64, 16, perTrigger, concurrentPerTrigger, 32, cooldown, 20, commands, 256);
    }
    private void dispatcher(ServerTriggerSettings settings) {
        dispatcher = new ServerTriggerDispatcher(settings, catalog::get, enabled::get);
        dispatcher.connect();
    }
    private void behavior(String id, boolean server, String triggers) {
        revision++;
        String source = "{\"schemaVersion\":1,\"id\":\"" + id + "\"," + (server ? "\"server\":true," : "") + "\"entry\":\"main\"," + ROUTINES + ",\"triggers\":" + triggers + "}";
        BehaviorCompiler.Result compiled = BehaviorCompiler.compile(source);
        BehaviorProgram program = compiled.program().orElseThrow(() -> new AssertionError(compiled.diagnostics().toString()));
        resolutions.put(id, new BehaviorCatalog.Resolution(id, true, Optional.of(new BehaviorCatalog.Accepted(revision, String.format("%064x", revision), 100, program, Set.of())), List.of()));
        catalog.set(new BehaviorCatalog.Snapshot(revision, Map.of(), Map.copyOf(resolutions), List.of()));
    }
    private void enable(String... ids) { enabled.set(new ServerBehaviorList(enabled.get().generation() + 1, List.of(ids), List.of())); }
    private Outcome<UUID> start(ServerTriggerDispatcher.Request request) { started.add(request); return Outcome.ok(UUID.randomUUID()); }
    private void pump() { dispatcher.pump(this::start); }
    private void pump(int count) { for (int i = 0; i < count; i++) pump(); }
    private void finishAll() { for (ServerTriggerDispatcher.Request request : List.copyOf(started)) dispatcher.finished(request); }
    private List<String> keys() { return started.stream().map(request -> request.binding().key()).toList(); }
    private long dropped(ServerTriggerDispatcher.DropReason reason) { return dispatcher.snapshot().dropped().getOrDefault(reason, 0L); }

    @Test void enabledServerBehaviorsStartWithThePlayerAndTheEventContext() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_join\",\"routine\":\"greet\"}]");
        enable("denizen:world/welcome");
        pump();
        assertTrue(dispatcher.publish(ServerEvent.join(alex, true)).value().orElseThrow());
        pump();
        assertEquals(1, started.size());
        ServerTriggerDispatcher.Request request = started.getFirst();
        assertEquals("denizen:world/welcome#hello", request.binding().key());
        assertEquals("greet", request.program().entry());
        assertEquals(Optional.of(alex), request.player());
        assertEquals(Map.of("first", true), request.event());
        assertTrue(request.current().getAsBoolean());
        enable();
        pump();
        assertFalse(request.current().getAsBoolean(), "a behavior removed from server-behaviors.json is no longer current");
    }

    @Test void unlistedAndNonServerBehaviorsNeverRun() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_join\"}]");
        behavior("town:greeter", false, "[{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":20}]");
        enable("town:greeter", "town:missing");
        pump();
        assertFalse(dispatcher.publish(ServerEvent.join(alex, false)).value().orElseThrow());
        pump(30);
        assertEquals(List.of(), started);
        List<String> codes = dispatcher.snapshot().diagnostics().stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains("server-behavior-not-server"), codes.toString());
        assertTrue(codes.contains("server-behavior-missing"), codes.toString());
    }

    @Test void filtersMatchFirstJoinsChatPatternsCommandsAndWorlds() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/filters", true, """
                [{"id":"first","event":"player_join","first":true},{"id":"returning","event":"player_join","first":false},
                 {"id":"hello","event":"player_chat","pattern":"*hello*"},{"id":"spawn","event":"command","name":"spawn","aliases":["hub"]},
                 {"id":"nether","event":"player_world_change","to":"world_nether"},{"id":"door","event":"module","key":"sample:door"}]""");
        enable("denizen:world/filters");
        pump();
        dispatcher.publish(ServerEvent.join(alex, false));
        dispatcher.publish(ServerEvent.chat(alex, "well HELLO there"));
        dispatcher.publish(ServerEvent.chat(alex, "bye"));
        dispatcher.publish(ServerEvent.command(Optional.of(alex), "spawn", "hub", "  one two  "));
        dispatcher.publish(ServerEvent.worldChange(alex, "world", "world_the_end"));
        dispatcher.publish(ServerEvent.worldChange(alex, "world", "world_nether"));
        dispatcher.publish(ServerEvent.module(Optional.empty(), new Capabilities.Key("sample", "door")));
        pump();
        assertEquals(List.of("denizen:world/filters#returning", "denizen:world/filters#hello", "denizen:world/filters#spawn",
                "denizen:world/filters#nether", "denizen:world/filters#door"), keys());
        assertEquals(Map.of("command", "spawn", "alias", "hub", "args", List.of("one", "two"), "raw_args", "one two"), started.get(2).event());
        assertEquals(Map.of("from", "world", "to", "world_nether"), started.get(3).event());
    }

    @Test void playerEventCooldownsApplyPerTriggerAndPlayer() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_chat\",\"cooldownTicks\":40}]");
        enable("denizen:world/welcome");
        pump();
        dispatcher.publish(ServerEvent.chat(alex, "a"));
        dispatcher.publish(ServerEvent.chat(alex, "b"));
        dispatcher.publish(ServerEvent.chat(sam, "c"));
        pump();
        assertEquals(List.of(Optional.of(alex), Optional.of(sam)), started.stream().map(ServerTriggerDispatcher.Request::player).toList());
        assertEquals(1, dispatcher.snapshot().suppressed());
        pump(40);
        dispatcher.publish(ServerEvent.chat(alex, "d"));
        pump();
        assertEquals(3, started.size());
    }

    @Test void aFullCooldownTableNeverForgetsAPlayerStillCoolingDown() {
        dispatcher(new ServerTriggerSettings(true, 4096, 1024, 64, 256, 64, 128, 0, 20, 64, 256));
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_chat\",\"cooldownTicks\":1000}]");
        enable("denizen:world/welcome");
        pump();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 4097; i++) players.add(UUID.randomUUID());
        int starts = 0;
        for (int from = 0; from < players.size(); from += 64) {
            for (UUID player : players.subList(from, Math.min(from + 64, players.size()))) dispatcher.publish(ServerEvent.chat(player, "x"));
            pump();
            starts += started.size();
            finishAll();
            started.clear();
        }
        assertEquals(4096, starts, "A table full of live cooldowns refuses the next player instead of forgetting one");
        dispatcher.publish(ServerEvent.chat(players.getFirst(), "again"));
        pump();
        assertEquals(List.of(), started, "The first player is still cooling down");
        pump(1000);
        dispatcher.publish(ServerEvent.chat(players.getLast(), "later"));
        pump();
        assertEquals(1, started.size(), "Expired cooldowns make room");
    }

    @Test void overflowDropsAndCountsInsteadOfQueueing() {
        dispatcher(settings(2, 1, 1, 0, 64));
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_chat\"}]");
        enable("denizen:world/welcome");
        pump();
        assertTrue(dispatcher.publish(ServerEvent.chat(alex, "1")).value().orElseThrow());
        assertTrue(dispatcher.publish(ServerEvent.chat(alex, "2")).value().orElseThrow());
        Outcome<Boolean> full = dispatcher.publish(ServerEvent.chat(alex, "3"));
        assertEquals(Outcome.Status.BUSY, full.status());
        assertEquals(1, dropped(ServerTriggerDispatcher.DropReason.EVENT_QUEUE));
        pump();
        assertEquals(1, started.size(), "the second event overflowed the trigger queue of one");
        assertEquals(1, dropped(ServerTriggerDispatcher.DropReason.TRIGGER_QUEUE));
        dispatcher.publish(ServerEvent.chat(alex, "4"));
        pump();
        assertEquals(1, dropped(ServerTriggerDispatcher.DropReason.TRIGGER_QUEUE));
        finishAll();
        pump();
        assertEquals(2, started.size());
    }

    @Test void timersFireOnTheirIntervalAndServerStartFiresOnce() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/clock", true, "[{\"id\":\"boot\",\"event\":\"server_start\"},{\"id\":\"tick\",\"event\":\"timer\",\"intervalTicks\":20}]");
        pump(3);
        assertEquals(List.of(), started, "nothing starts before server-behaviors.json is read");
        enable("denizen:world/clock");
        pump();
        assertEquals(List.of("denizen:world/clock#boot"), keys());
        assertEquals(Optional.empty(), started.getFirst().player());
        finishAll();
        pump(20);
        assertEquals(List.of("denizen:world/clock#boot", "denizen:world/clock#tick"), keys());
        finishAll();
        enable("denizen:world/clock");
        pump(20);
        assertEquals(List.of("denizen:world/clock#boot", "denizen:world/clock#tick", "denizen:world/clock#tick"), keys(), "a reload keeps timers and does not start again");
        assertEquals(1, dispatcher.snapshot().timers());
    }

    @Test void commandsAreListedOncePerNameWithinTheConfiguredMaximum() {
        dispatcher(settings(64, 8, 1, 0, 2));
        behavior("denizen:command/a", true, "[{\"id\":\"a\",\"event\":\"command\",\"name\":\"spawn\",\"aliases\":[\"hub\"],\"permission\":\"town.spawn\",\"usage\":\"/spawn\"}]");
        behavior("denizen:command/b", true, "[{\"id\":\"b\",\"event\":\"command\",\"name\":\"hub\"},{\"id\":\"c\",\"event\":\"command\",\"name\":\"warp\"}]");
        behavior("denizen:command/c", true, "[{\"id\":\"d\",\"event\":\"command\",\"name\":\"home\"},{\"id\":\"e\",\"event\":\"command\",\"name\":\"bank\"}]");
        enable("denizen:command/a", "denizen:command/b", "denizen:command/c");
        pump();
        ServerTriggerDispatcher.Commands commands = dispatcher.commands();
        assertEquals(List.of("spawn", "warp"), commands.commands().stream().map(ServerTriggerDispatcher.Command::name).toList());
        assertEquals(List.of("hub"), commands.commands().getFirst().aliases());
        assertEquals(Optional.of("town.spawn"), commands.commands().getFirst().permission());
        List<String> codes = dispatcher.snapshot().diagnostics().stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains("server-command-duplicate"), codes.toString());
        assertTrue(codes.contains("server-command-capacity"), codes.toString());
        long generation = commands.generation();
        pump();
        assertEquals(generation, dispatcher.commands().generation(), "an unchanged index keeps its command generation");
    }

    @Test void rowsShowEveryBindingWithItsCounts() {
        dispatcher(ServerTriggerSettings.DEFAULT);
        behavior("denizen:world/welcome", true, "[{\"id\":\"hello\",\"event\":\"player_join\"},{\"id\":\"bye\",\"event\":\"player_quit\"}]");
        enable("denizen:world/welcome");
        pump();
        dispatcher.publish(ServerEvent.join(alex, true));
        pump();
        List<ServerTriggerDispatcher.Row> rows = dispatcher.rows(0, 20);
        assertEquals(2, rows.size());
        assertEquals("player_join", rows.getFirst().event());
        assertEquals(1, rows.getFirst().fired());
        assertEquals(0, rows.get(1).fired());
        ServerTriggerDispatcher.Snapshot snapshot = dispatcher.snapshot();
        assertEquals(1, snapshot.behaviors());
        assertEquals(2, snapshot.bindings());
        assertEquals(1, snapshot.started());
    }

    @Test void unavailableDispatchRefusesAndCounts() {
        dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, catalog::get, enabled::get);
        assertEquals(Outcome.Status.UNAVAILABLE, dispatcher.publish(ServerEvent.join(alex, true)).status());
        assertEquals(1, dropped(ServerTriggerDispatcher.DropReason.UNAVAILABLE));
    }
}
