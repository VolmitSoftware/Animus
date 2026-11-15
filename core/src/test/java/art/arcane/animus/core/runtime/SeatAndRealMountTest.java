package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.control.ControlInput;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NPCs ride real entities, following them for every viewer, and players sit on NPCs, moving with them as they walk and
 * getting off when they sneak, as GSit seats players.
 */
class SeatAndRealMountTest {
    private static final UUID RIDER = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d01"), BENCH = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d02"),
            HORSE = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d03"), OTHER_HORSE = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d04"),
            SECOND = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d05");
    private static final SeatGeometry HORSE_SEAT = new SeatGeometry(0, 1.2, 0, 0);
    @TempDir Path directory;

    private ViewerWatchRaceTest.Harness harness(String... npcs) throws IOException {
        Path folder = directory.resolve("npcs/test");
        Files.createDirectories(folder);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, folder);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.transport.riders = null;
        harness.transport.types = java.util.Set.of("minecraft:player", "minecraft:villager", "minecraft:horse");
        for (int i = 0; i < npcs.length; i += 2) Files.writeString(folder.resolve(npcs[i] + ".json"), npcs[i + 1]);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        runtime.npcs().reload();
        harness.service.refresh();
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }
    private static String npc(UUID uuid, String id, String type, double x, double z, String modules) {
        return """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test", "name": "%s", "type": "%s", "spawned": true,
                 "modules": %s, "position": {"world": "world", "x": %s, "y": 64, "z": %s, "yaw": 180, "pitch": 0}}
                """.formatted(uuid, id, id, type, modules, x, z);
    }
    private static RuntimeHost.RealEntity horse(UUID uuid, int id, double x, double z, float yaw) {
        return new RuntimeHost.RealEntity(uuid, id, "minecraft:horse", new WorldPosition("world", new Vec3(x, 64, z)), yaw, HORSE_SEAT, List.of());
    }
    private static Vec3 at(ViewerWatchRaceTest.Harness harness, UUID npc) { return harness.service.state(npc).orElseThrow().position().point(); }

    @Test void anNpcSitsOnARealHorseFollowsItAndStandsAtHomeWhenItIsGone() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("rider", npc(RIDER, "rider", "minecraft:player", 0.5, 2.5, "{\"animus:mount\": {\"entity\": \"" + HORSE + "\"}}"));
        harness.entities.put(HORSE, horse(HORSE, 777, 4.5, 6.5, 90));
        harness.tick(6);
        assertEquals(new Vec3(4.5, 65.2, 6.5), at(harness, RIDER), "the NPC sits where the horse seats a passenger");
        assertEquals(90, harness.service.yaw(RIDER));
        int body = harness.transport.lastSpawned.entityId();
        assertTrue(harness.transport.carried.contains("Watcher 777 own=[] ids=[" + body + "]"), harness.transport.carried.toString());
        assertTrue(harness.service.inspect("rider").contains("mounted-on=minecraft:horse " + HORSE), harness.service.inspect("rider"));
        IllegalStateException walking = assertThrows(IllegalStateException.class,
                () -> harness.service.navigate("rider", new WorldPosition("world", new Vec3(9.5, 64, 9.5)), MovementProfile.Mode.WALK, 2));
        assertTrue(walking.getMessage().contains("riding a minecraft:horse"), walking.getMessage());

        harness.entities.put(HORSE, horse(HORSE, 777, 8.5, 6.5, 0));
        harness.tick(1);
        assertEquals(new Vec3(8.5, 65.2, 6.5), at(harness, RIDER), "a led horse carries its rider along");

        harness.entities.remove(HORSE);
        harness.tick(2);
        assertEquals(new Vec3(0.5, 64, 2.5), at(harness, RIDER), "without its horse the NPC stands at its own position");
        assertEquals("Watcher 777 own=[] ids=[]", harness.transport.carried.getLast(), "viewers are told the horse no longer carries it");
        assertTrue(harness.service.inspect("rider").contains("mount-problem=$.modules.animus:mount.entity: entity " + HORSE + " is gone or not loaded"), harness.service.inspect("rider"));

        harness.entities.put(HORSE, horse(HORSE, 778, 2.5, 2.5, 0));
        harness.tick(2);
        assertEquals(new Vec3(2.5, 65.2, 2.5), at(harness, RIDER), "the horse coming back into view seats the NPC again");
        assertTrue(harness.transport.carried.getLast().startsWith("Watcher 778 "), harness.transport.carried.toString());
    }

    @Test void nearestTypeMountsPickTheClosestFreeVehicleOneNpcEach() throws IOException {
        UUID otherRider = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0d06");
        String type = "{\"animus:mount\": {\"entity\": \"minecraft:horse\", \"range\": 12}}";
        ViewerWatchRaceTest.Harness harness = harness("rider", npc(RIDER, "rider", "minecraft:player", 0.5, 2.5, type),
                "rider-two", npc(otherRider, "rider-two", "minecraft:player", 6.5, 2.5, type));
        harness.entities.put(HORSE, horse(HORSE, 777, 0.5, 4.5, 0));
        harness.entities.put(OTHER_HORSE, horse(OTHER_HORSE, 778, 6.5, 4.5, 0));
        harness.tick(4);
        Vec3 first = at(harness, RIDER), second = at(harness, otherRider);
        assertEquals(new Vec3(0.5, 65.2, 4.5), first, "each rider takes the horse closest to its own position");
        assertEquals(new Vec3(6.5, 65.2, 4.5), second);
        harness.entities.remove(OTHER_HORSE);
        harness.tick(2);
        assertEquals(new Vec3(0.5, 65.2, 4.5), at(harness, RIDER), "a horse never carries two NPCs");
        assertEquals(new Vec3(6.5, 64, 2.5), at(harness, otherRider), "the other rider waits at its own position");
    }

    /** A click as the player sends it; clicks closer together than the runtime's action interval are refused, as a fast clicker's are. */
    private static void click(ViewerWatchRaceTest.Harness harness, boolean sneaking) {
        try { Thread.sleep(PersistedNpcService.VIEWER_LIMITS.minimumActionIntervalNanos() / 1_000_000 + 10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        BodyTransport.Body bench = harness.transport.lastSpawned;
        assertEquals(BENCH, bench.uuid());
        harness.transport.interactions.accept(new BodyTransport.Interaction(bench, BodyTransport.Action.INTERACT, "MAIN_HAND", sneaking, System.nanoTime()));
    }

    @Test void aPluginSeatsAPlayerOnAnNpcWithoutASeatButAClickDoesNot() throws Exception {
        ViewerWatchRaceTest.Harness harness = harness("bench", npc(BENCH, "bench", "minecraft:player", 0.5, 2.5, "{}"));
        harness.tick(6);
        click(harness, false);
        harness.tick(2);
        assertEquals("Seated 0/32", harness.service.seatStatus(), "a click needs a seat and animus.seat");
        java.util.concurrent.CompletableFuture<java.util.Optional<String>> seated = harness.service.seat(harness.player.id(), BENCH).toCompletableFuture();
        harness.tick(2);
        assertEquals(java.util.Optional.empty(), seated.get(1, java.util.concurrent.TimeUnit.SECONDS));
        harness.tick(4);
        assertTrue(harness.service.seatStatus().contains("Watcher on bench"), "the seat outlasts the checks a click needs: " + harness.service.seatStatus());
        assertEquals(java.util.Optional.of("the NPC is not active"), harness.service.seat(harness.player.id(), UUID.randomUUID()).toCompletableFuture().get(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test void aPlayerSitsOnAWalkingNpcMovesWithItAndGetsOffBySneaking() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("bench", npc(BENCH, "bench", "minecraft:player", 0.5, 2.5, "{\"animus:seat\": {}}"));
        harness.nodes.add("animus.seat");
        harness.tick(6);
        click(harness, true);
        harness.tick(2);
        assertEquals(List.of(), harness.told, "a sneaking click is not this seat's click");
        assertEquals("Seated 0/32", harness.service.seatStatus());

        click(harness, false);
        harness.tick(2);
        assertTrue(harness.service.seatStatus().contains("Watcher on bench: seated"), harness.service.seatStatus());
        PersistedNpcService<ViewerWatchRaceTest.Player, Object>.Value seat = harness.service.entries.get("bench").value;
        assertEquals("", harness.service.seats.request(harness.player, seat, true), "clicking the NPC you sit on says nothing");
        assertTrue(harness.transport.rosters.contains("Watcher " + BENCH + " bodies=0 rider=Watcher"), harness.transport.rosters.toString());
        assertEquals(new Vec3(0.5, 64, 2.5), harness.transport.placed.get(harness.player.id()), "the server holds the seated player on the ground under the NPC");

        harness.service.navigate("bench", new WorldPosition("world", new Vec3(0.5, 64, 6.5)), MovementProfile.Mode.WALK, 2);
        for (int tick = 0; tick < 200 && !harness.service.navigationStatus("bench").contains("arrived"); tick++) harness.tick(1);
        assertTrue(harness.service.navigationStatus("bench").contains("arrived"), "a seated player does not stop the NPC walking: " + harness.service.navigationStatus("bench"));
        assertEquals(at(harness, BENCH).z(), harness.transport.placed.get(harness.player.id()).z(), 1e-9, "the seated player moves with the NPC");

        harness.transport.controls.get(harness.player.id()).offer(new ControlInput(false, false, false, false, false, true, false), System.nanoTime());
        harness.tick(1);
        assertEquals("Seated 0/32", harness.service.seatStatus());
        assertEquals(1, harness.teleports.size(), "sneaking puts the player down beside the NPC: " + harness.teleports);
        assertTrue(harness.transport.rosters.getLast().endsWith("rider=none"), harness.transport.rosters.toString());
        assertEquals(List.of(), harness.told, "getting off by sneaking needs no message");
    }

    @Test void theSeatToggleTheConfiguredDefaultAndRidingDecideWhoSits() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("bench", npc(BENCH, "bench", "minecraft:player", 0.5, 2.5,
                "{\"animus:riding\": {\"enabled\": true}, \"animus:seat\": {\"click\": \"sneak-right\"}}"));
        harness.nodes.add("animus.seat");
        harness.tick(6);
        assertFalse(harness.service.seatToggle(harness.player.id(), Optional.of(Boolean.FALSE)));
        click(harness, true);
        harness.tick(2);
        assertEquals("Seated 0/32", harness.service.seatStatus(), "a player who turned sitting off does not sit");
        assertEquals(List.of(), harness.told, "and is told nothing, as GSit tells nothing");
        assertTrue(harness.service.seatToggle(harness.player.id(), Optional.empty()));
        click(harness, true);
        harness.tick(2);
        assertTrue(harness.service.seatStatus().contains("Watcher on bench"), harness.service.seatStatus());

        ViewerWatchRaceTest.Player second = new ViewerWatchRaceTest.Player(SECOND, "Second");
        harness.online.add(second);
        harness.nodes.add("animus.ride");
        PersistedNpcService<ViewerWatchRaceTest.Player, Object>.Value bench = harness.service.entries.get("bench").value;
        assertEquals("someone is sitting on this NPC", harness.service.riders.request(second, harness.service.mountRuntime.vehicle(bench)));
    }

    @Test void aSecondPlayerSitsOnTheFirstAndLeavesWhenTheFirstGetsOff() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("bench", npc(BENCH, "bench", "minecraft:villager", 0.5, 2.5, "{\"animus:seat\": {\"stack\": 2}}"));
        harness.nodes.add("animus.seat");
        ViewerWatchRaceTest.Player second = new ViewerWatchRaceTest.Player(SECOND, "Second");
        harness.online.add(second);
        harness.service.joined(second);
        harness.tick(8);
        PersistedNpcService<ViewerWatchRaceTest.Player, Object>.Value bench = harness.service.entries.get("bench").value;
        assertNull(harness.service.seats.request(harness.player, bench, true));
        harness.tick(2);
        assertNull(harness.service.seats.request(second, bench, true));
        harness.tick(3);
        assertTrue(harness.service.seatStatus().contains("Second on bench, seat 2: seated"), harness.service.seatStatus());
        assertEquals("Second 1 own=[] ids=[2]", harness.transport.carried.getFirst(), "the second player's own client is told first that it sits on the first player");
        assertTrue(harness.transport.carried.contains("Watcher 1 own=[] ids=[2]"), "and every other viewer sees it there: " + harness.transport.carried);
        assertEquals("its seats are full", harness.service.seats.request(new ViewerWatchRaceTest.Player(UUID.randomUUID(), "Third"), bench, true));

        harness.transport.controls.get(harness.player.id()).offer(new ControlInput(false, false, false, false, false, true, false), System.nanoTime());
        harness.tick(2);
        assertEquals("Seated 0/32", harness.service.seatStatus(), "the player on top gets off with the one below");
        assertTrue(harness.transport.carried.getLast().endsWith("ids=[]"), harness.transport.carried.toString());
    }

    @Test void aPlayerOnAnNpcThatRidesAHorseStandsOnTheGroundUnderTheHorse() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("rider", npc(RIDER, "rider", "minecraft:player", 0.5, 2.5,
                "{\"animus:mount\": {\"entity\": \"" + HORSE + "\"}, \"animus:seat\": {}}"));
        harness.nodes.add("animus.seat");
        harness.entities.put(HORSE, horse(HORSE, 777, 4.5, 6.5, 0));
        harness.tick(6);
        PersistedNpcService<ViewerWatchRaceTest.Player, Object>.Value rider = harness.service.entries.get("rider").value;
        assertNull(harness.service.seats.request(harness.player, rider, true));
        harness.tick(2);
        assertEquals(new Vec3(4.5, 64, 6.5), harness.transport.placed.get(harness.player.id()), "the server holds the player on the ground, not up on the horse");
        assertEquals(java.util.Set.of(HORSE, harness.player.id()), harness.service.attached(RIDER), "its horse and the player on it move with the NPC");
        harness.entities.put(HORSE, horse(HORSE, 777, 9.5, 6.5, 0));
        harness.tick(1);
        assertEquals(new Vec3(9.5, 64, 6.5), harness.transport.placed.get(harness.player.id()), "and moves it along with the horse");
    }

    @Test void aSaddledHorseNpcRefusesSeatsBecauseItsRidersClientWouldSteerIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("bench", npc(BENCH, "bench", "minecraft:horse", 0.5, 2.5, "{\"animus:seat\": {}}")
                .replace("\"spawned\": true,", "\"spawned\": true, \"equipment\": {\"saddle\": {\"id\": \"minecraft:saddle\"}},"));
        harness.nodes.add("animus.seat");
        harness.tick(6);
        PersistedNpcService<ViewerWatchRaceTest.Player, Object>.Value bench = harness.service.entries.get("bench").value;
        assertEquals("its rider's own client steers a saddled body; give it the riding module instead", harness.service.seats.request(harness.player, bench, true));
    }
}
