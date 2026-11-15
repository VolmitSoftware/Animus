package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.config.SeatSettings;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.ControlInput;
import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.control.SeatModule;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Players sitting on an NPC on Folia: the seats live on the NPC's owner and read a player only while it holds them. */
class ActorSeatsTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000006";
    private static final UUID BENCH = UUID.fromString("00000000-0000-0000-0000-0000000005e1");
    private record Person(UUID id, String name) {}

    private static final class Host implements ActorSeats.Host<Person> {
        SeatModule offer = new SeatModule(true, SeatModule.Click.RIGHT, "animus.seat", 2, true);
        SeatSettings settings = new SeatSettings(false, 32, true);
        WorldPosition feet = new WorldPosition(WORLD, new Vec3(0.5, 64, 0.5));
        boolean available = true, stacking = true, ownsGround = true;
        String occupied, gsit, unavailable;
        final Set<UUID> off = new HashSet<>(), elsewhere = new HashSet<>(), sneaking = new HashSet<>(), riding = new HashSet<>();
        final Map<UUID, WorldPosition> at = new HashMap<>();
        final List<String> placed = new ArrayList<>(), released = new ArrayList<>();
        int changes;
        @Override public String unavailable() { return unavailable; }
        @Override public SeatSettings settings() { return settings; }
        @Override public boolean toggledOn(UUID player) { return !off.contains(player); }
        @Override public RideEntry.Rule entry() { return RideEntry.OPEN; }
        @Override public boolean riding(UUID player) { return riding.contains(player); }
        @Override public boolean stacking() { return stacking; }
        @Override public SeatModule offer() { return offer; }
        @Override public boolean available() { return available; }
        @Override public String occupied() { return occupied; }
        @Override public boolean steered() { return false; }
        @Override public String npcName() { return "Bench"; }
        @Override public String npcId() { return "bench"; }
        @Override public WorldPosition feet() { return feet; }
        @Override public double height() { return 1.8; }
        @Override public double width() { return 0.6; }
        @Override public float yaw() { return 0; }
        @Override public WorldPosition ground() { return feet; }
        @Override public WorldPosition standingPlace(WorldPosition ground) { return new WorldPosition(WORLD, new Vec3(1.5, 64, 0.5)); }
        @Override public boolean holds(WorldPosition position) { return ownsGround; }
        @Override public UUID id(Person player) { return player.id(); }
        @Override public String name(Person player) { return player.name(); }
        @Override public boolean owns(Person player) { return !elsewhere.contains(player.id()); }
        @Override public boolean online(Person player) { return true; }
        @Override public boolean dead(Person player) { return false; }
        @Override public boolean busy(Person player) { return false; }
        @Override public boolean inVehicle(Person player) { return false; }
        @Override public boolean permitted(Person player, String node) { return true; }
        @Override public boolean emptyHand(Person player) { return true; }
        @Override public WorldPosition position(Person player) { return at.getOrDefault(player.id(), new WorldPosition(WORLD, new Vec3(2, 64, 2))); }
        @Override public String gsit(Person player, WorldPosition npc, double height, int stacked) { return gsit; }
        @Override public BodyTransport.ControlSubscription subscribe(Person player) {
            return new BodyTransport.ControlSubscription() {
                @Override public ControlInbox.Snapshot latest() {
                    boolean shift = sneaking.contains(player.id());
                    return new ControlInbox.Snapshot(null, System.nanoTime(), !shift, shift ? "shift" : null);
                }
                @Override public void close() {}
            };
        }
        @Override public boolean presents(Person player) { return true; }
        @Override public void place(Person player, Vec3 feet) { placed.add(player.name()); at.put(player.id(), new WorldPosition(WORLD, feet)); }
        @Override public void released(Person player, WorldPosition target, String message) { released.add(player.name() + (target == null ? " in place" : " beside") + (message == null ? "" : ": " + message)); }
        @Override public void changed() { changes++; }
    }

    private final Host host = new Host();
    private final RegionSeats<String> ledger = new RegionSeats<>();
    private final ActorSeats<Person, String> seats = new ActorSeats<>(host, ledger, "bench", BENCH);
    private final Person alice = new Person(UUID.randomUUID(), "Alice"), bob = new Person(UUID.randomUUID(), "Bob"), cara = new Person(UUID.randomUUID(), "Cara");

    private void seat(Person player) {
        assertNull(seats.request(player));
        seats.attached(seats.generation(player), true);
    }

    @Test void aSeatedPlayerStandsOnTheNpcsGroundAndGetsOffBySneaking() {
        seat(alice);
        assertEquals(List.of(alice), seats.players());
        assertEquals(host.feet, host.position(alice), "attaching places the player on the ground under the NPC");
        host.feet = new WorldPosition(WORLD, new Vec3(1.5, 64, 0.5));
        seats.tick();
        assertEquals(host.feet, host.position(alice), "the player moves with the NPC");
        host.sneaking.add(alice.id());
        seats.tick();
        assertTrue(seats.empty());
        assertEquals(0, ledger.size());
        assertEquals(List.of("Alice beside"), host.released, "sneaking gets off beside the NPC without a message");
    }

    @Test void aSecondPlayerSitsOnTheFirstAndBothGetOffWhenTheFirstDoes() {
        seat(alice); seat(bob);
        assertEquals(alice, seats.below(bob));
        assertEquals(alice, seats.bottom());
        assertEquals("its seats are full", seats.request(cara));
        host.sneaking.add(alice.id());
        seats.tick();
        assertTrue(seats.empty());
        assertEquals(List.of("Bob beside", "Alice beside"), host.released, "the top seat ends first");
    }

    @Test void aStackNeedsCarriedPassengersOnThisServer() {
        host.stacking = false;
        seat(alice);
        assertEquals("its seat is taken", seats.request(bob));
    }

    @Test void aPlayerAnotherRegionHoldsIsNeverReadAndTheSeatEndsInPlaceAfterAWhile() {
        seat(alice);
        host.elsewhere.add(alice.id());
        for (int i = 0; i < ActorSeats.AWAY_TURNS; i++) seats.tick();
        assertFalse(seats.empty(), "a short absence is waited out");
        seats.tick();
        assertTrue(seats.empty());
        assertEquals(List.of("Alice in place"), host.released, "the destination belongs to another region, so nothing teleports the player");
    }

    @Test void aPlayerMovedAwayBySomethingElseGetsOffThere() {
        seat(alice);
        host.at.put(alice.id(), new WorldPosition(WORLD, new Vec3(20, 64, 20)));
        seats.tick();
        assertTrue(seats.empty());
        assertEquals(List.of("Alice beside: Got off Bench: teleported"), host.released);
    }

    @Test void theGroundIsPlacedOnlyInAChunkThisRegionHolds() {
        seat(alice);
        host.placed.clear();
        host.ownsGround = false;
        host.feet = new WorldPosition(WORLD, new Vec3(1.5, 64, 0.5));
        seats.tick();
        assertTrue(host.placed.isEmpty());
        assertFalse(seats.empty(), "the seat waits for the handoff that follows the NPC");
    }

    @Test void refusalsMatchThePaperSeat() {
        host.off.add(alice.id());
        assertEquals("", seats.request(alice), "sitting turned off passes silently, as with GSit");
        host.off.clear();
        host.riding.add(alice.id());
        assertEquals("you are riding an NPC", seats.request(alice));
        host.riding.clear();
        host.occupied = "someone is riding this NPC";
        assertEquals("someone is riding this NPC", seats.request(alice));
        host.occupied = null;
        host.gsit = "sitting here is not allowed";
        assertEquals("sitting here is not allowed", seats.request(alice));
        host.gsit = null;
        host.settings = new SeatSettings(false, 1, true);
        seat(alice);
        assertEquals("the server limit of 1 seated players is reached", seats.request(bob));
        assertEquals("", seats.request(alice), "a click on the seat a player already has needs no answer");
    }

    @Test void anEndRequestForAnEarlierSeatEndsNothing() {
        seat(alice);
        java.util.UUID first = seats.generation(alice);
        host.sneaking.add(alice.id()); seats.tick(); host.sneaking.clear();
        seat(alice);
        seats.end(first, "teleported", ActorSeats.Exit.LEAVE_POSITION);
        assertEquals(List.of(alice), seats.players());
    }

    @Test void theNpcEndingEndsEverySeatOnIt() {
        seat(alice); seat(bob);
        seats.endAll("npc-waiting", ActorSeats.Exit.RESTORE_POSITION);
        assertTrue(seats.empty());
        assertEquals(List.of("Bob beside", "Alice beside: Got off Bench: npc waiting"), host.released);
        assertEquals(0, ledger.size());
    }
}
