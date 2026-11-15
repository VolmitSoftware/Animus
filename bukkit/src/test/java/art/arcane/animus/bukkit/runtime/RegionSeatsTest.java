package art.arcane.animus.bukkit.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionSeatsTest {
    @Test void shouldSeatEachPlayerOnceAndManyPlayersOnOneNpc() {
        RegionSeats<String> seats = new RegionSeats<>();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), bench = UUID.randomUUID(), sofa = UUID.randomUUID();
        RegionSeats.Lease<String> lease = seats.admit(alice, bench, "bench", 32).lease();
        assertNotNull(lease);
        assertEquals(RegionSeats.Rejection.ALREADY_SEATED, seats.admit(alice, sofa, "sofa", 32).rejection());
        assertNotNull(seats.admit(bob, bench, "bench", 32).lease(), "a second player sits on the same NPC, on the first");
        assertSame(lease, seats.of(alice));
        assertTrue(seats.release(lease));
        assertFalse(seats.release(lease), "a lease ends once");
        assertEquals(1, seats.size());
    }

    @Test void shouldCapSeatedPlayersAtTheConfiguredMaximum() {
        RegionSeats<String> seats = new RegionSeats<>();
        for (int i = 0; i < 3; i++) assertNotNull(seats.admit(UUID.randomUUID(), UUID.randomUUID(), "npc", 3).lease());
        assertEquals(RegionSeats.Rejection.CAPACITY, seats.admit(UUID.randomUUID(), UUID.randomUUID(), "npc", 3).rejection());
        assertNotNull(seats.admit(UUID.randomUUID(), UUID.randomUUID(), "npc", 4).lease(), "a raised maximum applies to the next seat");
    }

    @Test void shouldIgnoreAnEndRequestForAnEarlierSeatOfTheSamePlayer() {
        RegionSeats<String> seats = new RegionSeats<>();
        UUID alice = UUID.randomUUID(), bench = UUID.randomUUID();
        RegionSeats.Lease<String> first = seats.admit(alice, bench, "bench", 32).lease();
        seats.release(first);
        RegionSeats.Lease<String> second = seats.admit(alice, bench, "bench", 32).lease();
        assertNotEquals(first.generation(), second.generation(), "every seat is its own epoch");
        assertFalse(seats.current(first));
        assertTrue(seats.current(second));
    }

    @Test void shouldNeverSeatMoreThanTheMaximumUnderConcurrentAdmission() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            RegionSeats<String> seats = new RegionSeats<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) threads.add(new Thread(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { return; }
                for (int i = 0; i < 6; i++) if (seats.admit(UUID.randomUUID(), UUID.randomUUID(), "npc", 10).lease() != null) admitted.incrementAndGet();
            }));
            threads.forEach(Thread::start); start.countDown();
            for (Thread thread : threads) thread.join();
            assertEquals(10, admitted.get());
            assertEquals(10, seats.size());
        }
    }
}
