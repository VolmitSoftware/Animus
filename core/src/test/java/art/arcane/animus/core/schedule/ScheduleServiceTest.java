package art.arcane.animus.core.schedule;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleServiceTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A world that records what the schedule asks for and lets the test finish legs. */
    static final class FakeWorld implements ScheduleWorld {
        long time; boolean storm, active = true;
        WorldPosition at = new WorldPosition("world", new Vec3(0.5, 64, 0.5));
        final List<String> calls = new ArrayList<>();
        final List<WorldPosition> legs = new ArrayList<>();
        CompletableFuture<Void> leg = new CompletableFuture<>();
        @Override public boolean onOwner(UUID npc, Runnable work, Runnable refused) { if (!active) return false; work.run(); return true; }
        @Override public Optional<WorldPosition> position(UUID npc) { return active ? Optional.of(at) : Optional.empty(); }
        @Override public Optional<Clock> clock(String world) { return Optional.of(new Clock(time, storm)); }
        @Override public void walk(String id, WorldPosition goal, double speed) { legs.add(goal); calls.add("walk " + goal.point().brief() + " " + speed); leg = new CompletableFuture<>(); }
        @Override public CompletableFuture<Void> arrival(String id) { return leg; }
        @Override public void look(String id, WorldPosition target) { calls.add("look"); }
        @Override public void pose(String id, NpcPose pose) { calls.add("pose " + pose.key()); }
        @Override public void equip(String id, EquipmentSlot slot, Optional<NpcItem> item) { calls.add("equip " + slot.key() + " " + item.map(NpcItem::id).orElse("none")); }
        @Override public void skin(String id, String skin) { calls.add("skin " + skin.substring(0, 4)); }
        @Override public void command(String command) { calls.add("command " + command); }
    }

    private final FakeWorld world = new FakeWorld();
    private final AtomicReference<Map<String, NpcDefinition>> definitions = new AtomicReference<>(Map.of());
    private final List<String> namespaces = new ArrayList<>();
    private final ScheduleService service = new ScheduleService(definitions::get, world, () -> namespaces, new Random(1), (String line) -> {});

    private void define(String module, String extra) throws Exception {
        definitions.set(Map.of("baker", NpcDefinition.parse(JsonDocuments.write(ScheduleSettingsTest.definition("minecraft:player", module, extra)))));
    }
    private void seconds(int count) { for (int tick = 0; tick < count * ScheduleService.STEP_TICKS; tick++) service.tick(); }

    @Test void walksToTheLocationOfTheTimeAndMovesWhenTheTimeChanges() throws Exception {
        define("""
                {"speed":3,"locations":[{"name":"shop","time":1000,"x":10,"y":64,"z":0},{"name":"home","time":13000,"x":-10,"y":64,"z":0}]}
                """, "");
        world.time = 2000;
        seconds(1);
        assertEquals(List.of("walk 10.5 64 0.5 3.0"), world.calls);
        seconds(3);
        assertEquals(1, world.legs.size(), "a leg in progress is not started again");
        world.time = 14000;
        seconds(1);
        assertEquals(new Vec3(-9.5, 64, 0.5), world.legs.getLast().point());
    }

    @Test void arrivalAppliesPoseEquipmentSkinAndCommandsThenStandsBeforeLeaving() throws Exception {
        define("""
                {"locations":[{"time":13000,"x":4,"y":64,"z":4,"yaw":90,"pose":"sleeping","clearEquipment":true,
                  "equipment":{"head":{"id":"minecraft:leather_helmet"}},"skin":"%s","commands":["say night"]},
                 {"time":0,"x":0,"y":64,"z":0}]}
                """.formatted("c".repeat(64)), "");
        world.time = 13500;
        seconds(1);
        world.calls.clear();
        world.leg.complete(null);
        seconds(1);
        assertTrue(world.calls.contains("look"));
        assertTrue(world.calls.contains("equip head minecraft:leather_helmet"));
        assertTrue(world.calls.contains("equip main_hand none"), "other slots are cleared");
        assertFalse(world.calls.contains("equip head none"), "a slot the location fills is not cleared first");
        assertTrue(world.calls.contains("skin cccc"));
        assertTrue(world.calls.contains("pose sleeping"));
        assertEquals("command say night", world.calls.getLast());
        world.calls.clear();
        world.time = 23000 + 1500;
        seconds(1);
        assertEquals(List.of("pose standing", "walk 0.5 64 0.5 2.0"), world.calls);
    }

    @Test void importedCommandsWaitForTheCitizensNamespace() throws Exception {
        define("{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4,\"commands\":[\"say hi\"]}]}", ",\"migration\":{}");
        seconds(1);
        world.leg.complete(null);
        seconds(1);
        assertTrue(world.calls.stream().noneMatch((String call) -> call.startsWith("command")), world.calls.toString());
    }

    @Test void importedCommandsRunOnceTheCitizensNamespaceIsAllowed() throws Exception {
        namespaces.add(ScheduleService.IMPORTED_COMMANDS);
        define("{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4,\"commands\":[\"say hi\"]}]}", ",\"migration\":{}");
        seconds(1);
        world.leg.complete(null);
        seconds(1);
        assertTrue(world.calls.contains("command say hi"), world.calls.toString());
    }

    @Test void anEditThatLeavesTheScheduleAloneDoesNotRunArrivalAgain() throws Exception {
        String module = "{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4,\"commands\":[\"say hi\"]}]}";
        define(module, "");
        seconds(1);
        world.leg.complete(null);
        seconds(1);
        assertEquals(1, world.calls.stream().filter("command say hi"::equals).count());
        define(module, ",\"attributes\":{\"minecraft:gravity\":0.04}");
        world.calls.clear();
        seconds(3);
        assertTrue(world.calls.isEmpty(), "the NPC stays where it is: " + world.calls);
        define(module.replace("say hi", "say hello"), "");
        seconds(1);
        world.leg.complete(null);
        seconds(1);
        assertTrue(world.calls.contains("command say hello"), "a changed schedule starts again: " + world.calls);
    }

    @Test void aMissedLegIsTriedAgainLater() throws Exception {
        define("{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4}]}", "");
        seconds(1);
        world.leg.completeExceptionally(new IllegalStateException("blocked"));
        seconds(ScheduleService.RETRY_SECONDS);
        assertEquals(1, world.legs.size());
        seconds(1);
        assertEquals(2, world.legs.size());
    }

    @Test void wanderLegsStartAfterTheWaitAndStayAroundTheLocation() throws Exception {
        define("{\"locations\":[{\"time\":0,\"x\":20,\"y\":64,\"z\":20,\"wander\":{\"radius\":3,\"waitSeconds\":2}}]}", "");
        seconds(1);
        world.leg.complete(null);
        seconds(2);
        assertEquals(1, world.legs.size(), "waits before the first wander leg");
        seconds(1);
        assertEquals(2, world.legs.size());
        Vec3 target = world.legs.getLast().point();
        assertTrue(Math.abs(target.x() - 20.5) <= 3 && Math.abs(target.z() - 20.5) <= 3, target.toString());
    }

    @Test void aStayDrawsAgainBetweenLocationsThatShareATime() throws Exception {
        define("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"staySeconds\":1},{\"time\":0,\"x\":8,\"y\":64,\"z\":0,\"staySeconds\":1}]}", "");
        int moves = 0;
        for (int round = 0; round < 20; round++) {
            seconds(1);
            if (!world.leg.isDone()) world.leg.complete(null);
            moves = world.legs.size();
        }
        assertTrue(moves > 2, "it moves between the two locations: " + moves);
    }

    @Test void inactiveAndDespawnedNpcsAreLeftAlone() throws Exception {
        define("{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4}]}", "");
        world.active = false;
        seconds(2);
        assertTrue(world.legs.isEmpty());
        world.active = true;
        com.google.gson.JsonObject despawned = ScheduleSettingsTest.definition("minecraft:player", "{\"locations\":[{\"time\":0,\"x\":4,\"y\":64,\"z\":4}]}", "");
        despawned.addProperty("spawned", false);
        definitions.set(Map.of("baker", NpcDefinition.parse(JsonDocuments.write(despawned))));
        seconds(2);
        assertTrue(world.legs.isEmpty());
    }
}
