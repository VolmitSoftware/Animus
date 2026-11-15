package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.runtime.RuntimeHost;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** An NPC on a real entity on Folia reads the entity only on the region that owns it. */
class ActorVehicleTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000007", NETHER = "8a0f6f1c-0000-4000-8000-000000000008";
    private static final UUID HORSE = UUID.fromString("00000000-0000-0000-0000-0000000000a1"), MULE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID RIDER = UUID.fromString("00000000-0000-0000-0000-0000000000b1"), OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private record Mob(UUID uuid) {}

    /** The server's entities and which of them this region holds; claims are shared, as across regions. */
    private static final class World {
        final Map<UUID, WorldPosition> at = new HashMap<>();
        final Map<UUID, String> types = new HashMap<>();
        final Set<UUID> held = new HashSet<>(), dead = new HashSet<>();
        final Map<UUID, UUID> claims = new ConcurrentHashMap<>();
        final List<Consumer<RuntimeHost.RealEntity>> asked = new ArrayList<>();
        void put(UUID uuid, String type, double x, double z, boolean owned) {
            at.put(uuid, new WorldPosition(WORLD, new Vec3(x, 64, z))); types.put(uuid, type);
            if (owned) held.add(uuid); else held.remove(uuid);
        }
        RuntimeHost.RealEntity read(UUID uuid) {
            return new RuntimeHost.RealEntity(uuid, uuid.hashCode(), types.get(uuid), at.get(uuid), 90, new SeatGeometry(0, 1, 0, 0), List.of());
        }
        ActorVehicle.Host<Mob> host(UUID npc) {
            return new ActorVehicle.Host<>() {
                @Override public Optional<Mob> entity(UUID uuid) { return at.containsKey(uuid) ? Optional.of(new Mob(uuid)) : Optional.empty(); }
                @Override public boolean owns(Mob entity) { return held.contains(entity.uuid()); }
                @Override public boolean usable(Mob entity) {
                    if (!held.contains(entity.uuid())) throw new IllegalStateException("read off its region");
                    return !dead.contains(entity.uuid());
                }
                @Override public RuntimeHost.RealEntity read(Mob entity) {
                    if (!held.contains(entity.uuid())) throw new IllegalStateException("read off its region");
                    return World.this.read(entity.uuid());
                }
                @Override public Optional<Mob> nearest(WorldPosition near, String type, double range, Predicate<UUID> free) {
                    return at.keySet().stream().filter((UUID uuid) -> held.contains(uuid) && type.equals(types.get(uuid)) && !dead.contains(uuid) && free.test(uuid)
                            && at.get(uuid).point().distance(near.point()) <= range).sorted().findFirst().map(Mob::new);
                }
                @Override public boolean ask(Mob entity, Consumer<RuntimeHost.RealEntity> seen) { asked.add(seen); return true; }
                @Override public boolean claim(UUID entity) { UUID had = claims.putIfAbsent(entity, npc); return had == null || had.equals(npc); }
                @Override public void unclaim(UUID entity) { claims.remove(entity, npc); }
                @Override public boolean free(UUID entity) { UUID had = claims.get(entity); return had == null || had.equals(npc); }
            };
        }
    }
    private final World world = new World();
    private final WorldPosition home = new WorldPosition(WORLD, new Vec3(0.5, 64, 0.5));
    private long turn;

    private ActorVehicle<Mob> rider(UUID npc, MountDeclaration.EntityMount mount) { return new ActorVehicle<>(world.host(npc), mount, 0); }
    private static MountDeclaration.EntityMount entity(UUID uuid) { return new MountDeclaration.EntityMount("$.modules.animus:mount.entity", uuid, null, 8); }
    private static MountDeclaration.EntityMount type(String type) { return new MountDeclaration.EntityMount("$.modules.animus:mount.entity", null, type, 8); }
    private ActorVehicle.Turn tick(ActorVehicle<Mob> vehicle) { return vehicle.tick(turn++, true, null, WORLD, home); }

    @Test void theVehicleTheNpcIsAboutToBoardMovesWithItAlready() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> byType = rider(RIDER, type("minecraft:horse"));
        assertEquals(Optional.of(HORSE), byType.target(home), "the nearest free horse, before a turn boards it");
        assertEquals(Optional.of(MULE), rider(OTHER, entity(MULE)).target(home), "a named vehicle, loaded or not");
        tick(byType);
        assertEquals(Optional.of(HORSE), byType.target(home), "the horse it sits on");
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, false);
        tick(byType);
        assertEquals(Optional.of(HORSE), byType.target(home), "the horse on another region it boards again");
        assertEquals(Optional.empty(), rider(OTHER, type("minecraft:mule")).target(home), "nothing to board");
    }

    @Test void anOwnedVehicleSeatsTheNpcAndIsClaimed() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> vehicle = rider(RIDER, entity(HORSE));
        ActorVehicle.Turn sat = tick(vehicle);
        assertEquals(ActorVehicle.Turn.Kind.BOARD, sat.kind());
        assertEquals(new Vec3(4.5, 65, 6.5), sat.at().point(), "on the horse's seat");
        assertEquals(RIDER, world.claims.get(HORSE));
        world.put(HORSE, "minecraft:horse", 5.5, 6.5, true);
        ActorVehicle.Turn followed = tick(vehicle);
        assertEquals(ActorVehicle.Turn.Kind.SIT, followed.kind());
        assertEquals(new Vec3(5.5, 65, 6.5), followed.at().point(), "the NPC follows its vehicle");
    }

    @Test void aDeadOrUnloadedVehicleLetsTheNpcGo() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> vehicle = rider(RIDER, entity(HORSE));
        tick(vehicle);
        world.dead.add(HORSE);
        assertEquals(ActorVehicle.Turn.Kind.RELEASE, tick(vehicle).kind());
        assertNull(world.claims.get(HORSE));
        world.dead.clear();
        assertEquals(ActorVehicle.Turn.Kind.BOARD, tick(vehicle).kind());
        world.at.remove(HORSE);
        assertEquals(ActorVehicle.Turn.Kind.RELEASE, tick(vehicle).kind());
        assertTrue(vehicle.summary().contains("not loaded"), vehicle.summary());
    }

    @Test void aVehicleOnAnotherRegionIsLetGoAskedAndBoardedAgainThroughItsSeat() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> vehicle = rider(RIDER, entity(HORSE));
        tick(vehicle);
        world.put(HORSE, "minecraft:horse", 900.5, 6.5, false);
        assertEquals(ActorVehicle.Turn.Kind.RELEASE, tick(vehicle).kind(), "never read off its region");
        assertNull(world.claims.get(HORSE));
        assertEquals(1, world.asked.size(), "its own scheduler is asked where it is");
        assertEquals(ActorVehicle.Turn.Kind.STAY, tick(vehicle).kind(), "no answer yet");
        world.asked.getFirst().accept(world.read(HORSE));
        ActorVehicle.Turn step = tick(vehicle);
        assertEquals(ActorVehicle.Turn.Kind.STEP, step.kind());
        assertEquals(new Vec3(900.5, 65, 6.5), step.at().point(), "stepping onto its seat hands the NPC to that region");
        world.held.add(HORSE);
        assertEquals(ActorVehicle.Turn.Kind.BOARD, tick(vehicle).kind(), "on the vehicle's region it gets on again");
    }

    @Test void aVehicleInAnotherWorldIsNotFollowed() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> vehicle = rider(RIDER, entity(HORSE));
        tick(vehicle);
        world.at.put(HORSE, new WorldPosition(NETHER, new Vec3(4.5, 64, 6.5)));
        assertEquals(ActorVehicle.Turn.Kind.RELEASE, tick(vehicle).kind());
        assertTrue(vehicle.summary().contains("another world"), vehicle.summary());
    }

    @Test void nearestTypeMountsTakeOneFreeVehicleEachAcrossRegions() {
        world.put(HORSE, "minecraft:horse", 2.5, 0.5, true);
        world.put(MULE, "minecraft:horse", 3.5, 0.5, true);
        ActorVehicle<Mob> first = rider(RIDER, type("minecraft:horse")), second = rider(OTHER, type("minecraft:horse"));
        assertEquals(ActorVehicle.Turn.Kind.BOARD, tick(first).kind());
        assertEquals(ActorVehicle.Turn.Kind.BOARD, tick(second).kind());
        assertNotEquals(world.claims.get(HORSE), world.claims.get(MULE), "one NPC rides each vehicle");
        assertEquals(2, world.claims.size());
    }

    @Test void forgettingTheNpcDropsItsClaimWithoutTouchingTheWorld() {
        world.put(HORSE, "minecraft:horse", 4.5, 6.5, true);
        ActorVehicle<Mob> vehicle = rider(RIDER, entity(HORSE));
        tick(vehicle);
        vehicle.forget();
        assertTrue(world.claims.isEmpty());
        assertNull(vehicle.vehicle());
    }
}
