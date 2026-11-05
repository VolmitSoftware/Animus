package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerTriggerCompilerTest {
    private static final String ROUTINES = "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}";

    private static String server(String variables, String triggers) {
        return "{\"schemaVersion\":1,\"id\":\"denizen:world/welcome\",\"server\":true,\"entry\":\"main\"," + variables + ROUTINES + ",\"triggers\":" + triggers + "}";
    }
    private static String npc(String triggers) {
        return "{\"schemaVersion\":1,\"id\":\"town:greeter\",\"entry\":\"main\"," + ROUTINES + ",\"triggers\":" + triggers + "}";
    }
    private static Diagnostic error(String source) {
        BehaviorCompiler.Result result = BehaviorCompiler.compile(source);
        assertTrue(result.program().isEmpty(), "expected a compile error");
        return result.diagnostics().getFirst();
    }

    @Test void serverDocumentCompilesEveryServerEventIntoItsTypedBinding() {
        BehaviorCompiler.Result result = BehaviorCompiler.compile(server("", """
                [{"id":"hello","event":"player_join","first":true},
                 {"id":"bye","event":"player_quit"},
                 {"id":"chat","event":"player_chat","pattern":"*hello*"},
                 {"id":"spawn-cmd","event":"command","name":"spawn","aliases":["hub","lobby"],"permission":"town.spawn","description":"Go home","usage":"/spawn"},
                 {"id":"boot","event":"server_start"},
                 {"id":"clock","event":"timer","intervalTicks":20},
                 {"id":"died","event":"player_death"},
                 {"id":"back","event":"player_respawn"},
                 {"id":"moved","event":"player_world_change","from":"world","to":"world_nether"},
                 {"id":"door","event":"module","key":"sample:door_open"}]"""));
        assertEquals(List.of(), result.diagnostics());
        BehaviorProgram program = result.program().orElseThrow();
        assertTrue(program.server());
        List<TriggerBinding> triggers = program.triggers();
        assertEquals(new TriggerBinding.PlayerJoin(Optional.of(true)), triggers.get(0).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.PLAYER_QUIT), triggers.get(1).spec());
        assertEquals(new TriggerBinding.PlayerChat(Optional.of("*hello*")), triggers.get(2).spec());
        assertEquals(new TriggerBinding.Command("spawn", List.of("hub", "lobby"), Optional.of("town.spawn"), Optional.of("Go home"), Optional.of("/spawn")), triggers.get(3).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.SERVER_START), triggers.get(4).spec());
        assertEquals(new TriggerBinding.Timer(20), triggers.get(5).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.PLAYER_DEATH), triggers.get(6).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.PLAYER_RESPAWN), triggers.get(7).spec());
        assertEquals(new TriggerBinding.WorldChange(Optional.of("world"), Optional.of("world_nether")), triggers.get(8).spec());
        assertEquals(new TriggerBinding.ModuleEvent(new Capabilities.Key("sample", "door_open")), triggers.get(9).spec());
    }

    @Test void documentsWithoutServerAreNpcBehaviors() {
        BehaviorCompiler.Result result = BehaviorCompiler.compile(npc("[{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":5}]"));
        assertEquals(List.of(), result.diagnostics());
        assertFalse(result.program().orElseThrow().server());
        assertEquals("$.server", error(npc("[]").replace("\"entry\"", "\"server\":\"yes\",\"entry\"")).field());
        assertFalse(BehaviorCompiler.compile(npc("[]").replace("\"entry\"", "\"server\":false,\"entry\"")).program().orElseThrow().server());
    }

    @Test void serverBehaviorsDeclareOnlyExecutionPlayerAndGlobalVariables() {
        String allowed = "\"variables\":{\"count\":{\"type\":\"integer\",\"initial\":0},\"seen\":{\"type\":\"boolean\",\"initial\":false,\"scope\":\"player\",\"lifetime\":\"persistent\"},"
                + "\"total\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"global\",\"lifetime\":\"persistent\"}},";
        assertEquals(List.of(), BehaviorCompiler.compile(server(allowed, "[{\"id\":\"hello\",\"event\":\"player_join\"}]")).diagnostics());
        for (String scope : List.of("npc", "relationship")) {
            Diagnostic problem = error(server("\"variables\":{\"x\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"" + scope + "\",\"lifetime\":\"transient\"}},", "[]"));
            assertEquals("$.variables.x.scope", problem.field(), scope);
            assertTrue(problem.message().contains("server behavior"), problem.message());
        }
    }

    @Test void serverBehaviorsRefuseNpcEventsAndNpcBehaviorsRefuseServerEvents() {
        for (String event : List.of("interaction", "proximity", "spawn", "despawn", "damage", "death", "waypoint", "dialogue_choice", "build_finished", "build_paused", "animation_finished")) {
            Diagnostic problem = error(server("", "[{\"id\":\"t\",\"event\":\"" + event + "\"}]"));
            assertEquals("$.triggers[0].event", problem.field(), event);
        }
        for (String event : List.of("player_join", "player_quit", "player_chat", "server_start", "player_death", "player_respawn", "player_world_change")) {
            Diagnostic problem = error(npc("[{\"id\":\"t\",\"event\":\"" + event + "\"}]"));
            assertEquals("$.triggers[0].event", problem.field(), event);
            assertTrue(problem.message().contains("\"server\": true"), problem.message());
        }
        assertEquals("$.triggers[0].event", error(npc("[{\"id\":\"t\",\"event\":\"command\",\"name\":\"spawn\"}]")).field());
    }

    @Test void serverTimersAndCommandsAreBounded() {
        assertEquals("$.triggers[0].intervalTicks", error(server("", "[{\"id\":\"t\",\"event\":\"timer\",\"intervalTicks\":19}]")).field());
        assertEquals("$.triggers[0].intervalTicks", error(server("", "[{\"id\":\"t\",\"event\":\"timer\",\"intervalTicks\":72001}]")).field());
        assertEquals("$.triggers[0].name", error(server("", "[{\"id\":\"t\",\"event\":\"command\"}]")).field());
        assertEquals("$.triggers[0].name", error(server("", "[{\"id\":\"t\",\"event\":\"command\",\"name\":\"Spawn\"}]")).field());
        assertEquals("$.triggers[0].name", error(server("", "[{\"id\":\"t\",\"event\":\"command\",\"name\":\"" + "a".repeat(33) + "\"}]")).field());
        assertEquals("$.triggers[0].aliases", error(server("", "[{\"id\":\"t\",\"event\":\"command\",\"name\":\"spawn\",\"aliases\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}]")).field());
        assertEquals("$.triggers[0].aliases[0]", error(server("", "[{\"id\":\"t\",\"event\":\"command\",\"name\":\"spawn\",\"aliases\":[\"spawn\"]}]")).field());
        assertEquals("$.triggers[0].permission", error(server("", "[{\"id\":\"t\",\"event\":\"command\",\"name\":\"spawn\",\"permission\":\"has space\"}]")).field());
        assertEquals("$.triggers[1].aliases[0]", error(server("", "[{\"id\":\"a\",\"event\":\"command\",\"name\":\"spawn\"},{\"id\":\"b\",\"event\":\"command\",\"name\":\"hub\",\"aliases\":[\"spawn\"]}]")).field());
        assertEquals("$.triggers[0].first", error(server("", "[{\"id\":\"t\",\"event\":\"player_join\",\"first\":\"yes\"}]")).field());
        assertEquals("$.triggers[0].pattern", error(server("", "[{\"id\":\"t\",\"event\":\"player_chat\",\"pattern\":\"" + "x".repeat(257) + "\"}]")).field());
    }

    @Test void playerVariablesWarnOnPlayerlessServerTriggers() {
        String player = "\"variables\":{\"seen\":{\"type\":\"boolean\",\"initial\":false,\"scope\":\"player\",\"lifetime\":\"persistent\"}},";
        BehaviorCompiler.Result result = BehaviorCompiler.compile(server(player, "[{\"id\":\"boot\",\"event\":\"server_start\"},{\"id\":\"hello\",\"event\":\"player_join\"}]"));
        assertEquals(1, result.diagnostics().size());
        assertEquals("$.triggers[0]", result.diagnostics().getFirst().field());
    }
}
