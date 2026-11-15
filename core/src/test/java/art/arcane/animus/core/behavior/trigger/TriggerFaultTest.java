package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One event whose run cannot start must cost only that event: the dispatcher keeps running and says so once. */
class TriggerFaultTest {
    private static final String ROUTINES = "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}";
    private final List<String> log = new CopyOnWriteArrayList<>();

    private static BehaviorCatalog.Snapshot catalog(String id, boolean server, String triggers) {
        BehaviorProgram program = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"" + id + "\"," + (server ? "\"server\":true," : "") + "\"entry\":\"main\"," + ROUTINES
                + ",\"triggers\":" + triggers + "}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of());
        return new BehaviorCatalog.Snapshot(1, Map.of(), Map.of(id, new BehaviorCatalog.Resolution(id, true, Optional.of(accepted), List.of())), List.of());
    }

    @Test void aRunThatThrowsDropsOnlyItsOwnEventAndIsLoggedOncePerBehaviorAndEvent() throws Exception {
        UUID npc = UUID.randomUUID(), viewer = UUID.randomUUID();
        AtomicReference<Map<String, NpcDefinition>> definitions = new AtomicReference<>(Map.of("keeper", NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + npc
                + "\",\"id\":\"keeper\",\"group\":\"test\",\"name\":\"Keeper\",\"type\":\"minecraft:player\",\"spawned\":true,\"behaviors\":[\"town:keeper\"],"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}")));
        BehaviorCatalog.Snapshot snapshot = catalog("town:keeper", false, "[{\"id\":\"arrive\",\"event\":\"waypoint\"},{\"id\":\"click\",\"event\":\"interaction\"}]");
        TriggerDispatcher dispatcher = new TriggerDispatcher(TriggerSettings.DEFAULT, definitions::get, () -> snapshot);
        dispatcher.log(log::add);
        dispatcher.connect();
        dispatcher.activated(npc);
        List<String> started = new ArrayList<>();
        TriggerDispatcher.Starter starter = (TriggerDispatcher.Request request) -> {
            if (request.binding().key().endsWith("#arrive")) throw new IllegalArgumentException("Unsupported event value");
            started.add(request.binding().key());
            return Outcome.ok(UUID.randomUUID());
        };
        dispatcher.pump(starter);
        dispatcher.publish(new TriggerEvent.Waypoint(npc, 2));
        dispatcher.publish(new TriggerEvent.Interaction(npc, viewer, TriggerEvent.Action.INTERACT));
        dispatcher.pump(starter);
        assertTrue(dispatcher.available(), dispatcher.availability());
        assertEquals(List.of("town:keeper#click"), started);
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("town:keeper") && log.getFirst().contains("waypoint") && log.getFirst().contains("IllegalArgumentException"), log.getFirst());
        assertEquals(1L, dispatcher.snapshot().dropped().get(TriggerDispatcher.DropReason.FAULT));
        for (int tick = 0; tick < 30; tick++) dispatcher.pump(starter);
        dispatcher.publish(new TriggerEvent.Waypoint(npc, 3));
        dispatcher.pump(starter);
        assertEquals(1, log.size(), "the same behavior and event is reported once");
        assertEquals(2L, dispatcher.snapshot().dropped().get(TriggerDispatcher.DropReason.FAULT));
    }

    @Test void aServerRunThatThrowsDropsOnlyItsOwnEvent() {
        BehaviorCatalog.Snapshot snapshot = catalog("town:hall", true, "[{\"id\":\"join\",\"event\":\"player_join\"},{\"id\":\"quit\",\"event\":\"player_quit\"}]");
        ServerTriggerDispatcher dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, () -> snapshot,
                () -> new ServerBehaviorList(1, List.of("town:hall"), List.of()));
        dispatcher.log(log::add);
        dispatcher.connect();
        List<String> started = new ArrayList<>();
        ServerTriggerDispatcher.Starter starter = (ServerTriggerDispatcher.Request request) -> {
            if (request.binding().key().endsWith("#join")) throw new IllegalStateException("broken run");
            started.add(request.binding().key());
            return Outcome.ok(UUID.randomUUID());
        };
        dispatcher.pump(starter);
        UUID player = UUID.randomUUID();
        dispatcher.publish(ServerEvent.join(player, true));
        dispatcher.publish(ServerEvent.quit(player));
        dispatcher.pump(starter);
        assertEquals(List.of("town:hall#quit"), started);
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("town:hall") && log.getFirst().contains("player_join"), log.getFirst());
        assertEquals("available", dispatcher.availability());
    }
}
