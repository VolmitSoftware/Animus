package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.control.RideLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionRidesTest {
    @Test void shouldAdmitOneRiderPerPlayerAndPerNpc() {
        RegionRides<String> rides = new RegionRides<>();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), horse = UUID.randomUUID(), mule = UUID.randomUUID();
        RegionRides.Lease<String> lease = rides.admit(alice, horse, "horse").lease();
        assertNotNull(lease);
        assertEquals(RideLedger.Rejection.ALREADY_RIDING, rides.admit(alice, mule, "mule").rejection());
        assertEquals(RideLedger.Rejection.OCCUPIED, rides.admit(bob, horse, "horse").rejection());
        assertSame(lease, rides.ofRider(alice));
        assertSame(lease, rides.ofNpc(horse));
        assertTrue(rides.release(lease));
        assertFalse(rides.release(lease), "a lease ends once");
        assertNotNull(rides.admit(bob, horse, "horse").lease());
    }

    @Test void shouldCapRidersServerWide() {
        RegionRides<String> rides = new RegionRides<>();
        for (int i = 0; i < RideLedger.MAX_RIDERS; i++) assertNotNull(rides.admit(UUID.randomUUID(), UUID.randomUUID(), "npc").lease());
        assertEquals(RideLedger.Rejection.CAPACITY, rides.admit(UUID.randomUUID(), UUID.randomUUID(), "npc").rejection());
        assertEquals(RideLedger.MAX_RIDERS, rides.size());
    }

    @Test void shouldIgnoreAnEndRequestForAnEarlierRideOfTheSamePlayer() {
        RegionRides<String> rides = new RegionRides<>();
        UUID alice = UUID.randomUUID(), horse = UUID.randomUUID();
        RegionRides.Lease<String> first = rides.admit(alice, horse, "horse").lease();
        rides.release(first);
        RegionRides.Lease<String> second = rides.admit(alice, horse, "horse").lease();
        assertNotEquals(first.generation(), second.generation(), "every ride is its own epoch");
        assertFalse(rides.current(first), "a request carrying the first ride's epoch must not end the second ride");
        assertTrue(rides.current(second));
    }

    @Test void shouldNeverAdmitMoreThanTheCapUnderConcurrentAdmission() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            RegionRides<String> rides = new RegionRides<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) threads.add(new Thread(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { return; }
                for (int i = 0; i < 6; i++) if (rides.admit(UUID.randomUUID(), UUID.randomUUID(), "npc").lease() != null) admitted.incrementAndGet();
            }));
            threads.forEach(Thread::start); start.countDown();
            for (Thread thread : threads) thread.join();
            assertEquals(RideLedger.MAX_RIDERS, admitted.get());
            assertEquals(RideLedger.MAX_RIDERS, rides.size());
        }
    }
}
