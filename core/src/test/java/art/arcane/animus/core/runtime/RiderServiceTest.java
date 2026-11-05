package art.arcane.animus.core.runtime;

import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiderServiceTest {
    record Rider(UUID id, String name) {}

    static final String WORLD = "world";
    final Rider rider = new Rider(UUID.randomUUID(), "Rider");
    final List<String> events = new ArrayList<>();
    final List<ControlInbox> inboxes = new ArrayList<>();
    boolean online = true;

    @SuppressWarnings("unchecked")
    final RuntimeHost<Rider, Object> host = (RuntimeHost<Rider, Object>) Proxy.newProxyInstance(RuntimeHost.class.getClassLoader(), new Class<?>[]{RuntimeHost.class},
            (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
                case "id" -> ((Rider) arguments[0]).id();
                case "name" -> ((Rider) arguments[0]).name();
                case "online" -> online;
                case "permitted", "teleport", "worldLoaded", "chunkLoaded" -> true;
                case "dead", "sleeping", "spectator", "inVehicle", "ownerThread" -> false;
                case "position" -> new WorldPosition(WORLD, new Vec3(1.5, 61, 0.5));
                case "yaw", "pitch" -> 0f;
                case "collision" -> (MotionValidator.Sampler) (int x, int y, int z) -> y < 61 ? VoxelSnapshot.Cell.SOLID : VoxelSnapshot.Cell.AIR;
                case "tell" -> { events.add("tell:" + arguments[1]); yield null; }
                case "warn" -> { events.add("warn:" + arguments[0]); yield null; }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            });

    final RiderService.Presentation<Rider> presentation = new RiderService.Presentation<>() {
        @Override public BodyTransport.ControlSubscription subscribe(Rider player) {
            ControlInbox inbox = new ControlInbox(System.nanoTime());
            inboxes.add(inbox);
            return new BodyTransport.ControlSubscription() {
                @Override public ControlInbox.Snapshot latest() { return inbox.snapshot(); }
                @Override public void close() { inbox.close("subscription-closed"); }
            };
        }
        @Override public void attach(Rider player, RiderService.Vehicle<Rider> vehicle, Runnable attached, Consumer<String> failed) { events.add("attach"); attached.run(); }
        @Override public void detach(Rider player, RiderService.Vehicle<Rider> vehicle) { events.add("detach"); }
        @Override public void place(Rider player, Vec3 feet) {}
    };

    final class Horse implements RiderService.Vehicle<Rider> {
        final UUID uuid = UUID.randomUUID();
        final VirtualNpcState state = new VirtualNpcState(uuid, new WorldPosition(WORLD, new Vec3(0.5, 61, 0.5)), 1.4, 1.6, 20);
        Horse() { state.spawn(); }
        @Override public UUID uuid() { return uuid; }
        @Override public String id() { return "horse"; }
        @Override public RideSettings settings() { return new RideSettings("animus.ride", 5, 48, 900); }
        @Override public VirtualNpcState.Snapshot snapshot() { return state.snapshot(); }
        @Override public float yaw() { return 0; }
        @Override public boolean current() { return true; }
        @Override public boolean mounted() { return false; }
        @Override public boolean presentedTo(Rider player) { return true; }
        @Override public double hullWidth() { return 1.4; }
        @Override public double hullHeight() { return 3.2; }
        @Override public MovementProfile body() { return BodyMobility.forBody("minecraft:horse", hullWidth(), hullHeight()); }
        @Override public void claim() { events.add("claim"); }
        @Override public void commit(Vec3 position, float yaw) {}
        @Override public void released() { events.add("released"); }
    }

    @Test void everyEndRepublishesTheBodyAfterTheDetachAndFreesItForTheNextRide() {
        RiderService<Rider, Object> riders = new RiderService<>(host, presentation, null);
        Horse horse = new Horse();
        assertNull(riders.request(rider, horse));
        assertTrue(riders.riding(rider));
        inboxes.getLast().offer(new art.arcane.animus.core.control.ControlInput(false, false, false, false, false, true, false), System.nanoTime());
        riders.tick();
        assertFalse(riders.riding(rider));
        assertEquals(List.of("claim", "attach", "detach", "released"), events.subList(0, 4));

        for (int ride = 0; ride < 3; ride++) {
            events.clear();
            assertNull(riders.request(rider, horse), "remount " + ride);
            riders.playerLeaving(rider, ControlInbox.CONNECTION_CLOSED);
            assertEquals(List.of("claim", "attach", "detach", "released"), events.subList(0, 4));
        }
        assertEquals(0, riders.count());
        online = false;
        assertNull(riders.request(rider, horse));
        riders.tick();
        assertEquals(0, riders.count(), "an offline rider ends the ride");
        online = true;
        assertNull(riders.request(rider, horse), "a rejoined rider rides again");
    }
}
