package art.arcane.animus.bukkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The cross-region parts of manual attacks and mob control: one hit in flight per NPC, one controller per mob. */
@Timeout(10)
class ManualCombatHandoffTest {
    private static final long COOLDOWN = TimeUnit.MILLISECONDS.toNanos(500);
    private final UUID npc = UUID.fromString("00000000-0000-4000-8000-0000000000a1"), other = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    @Test void aSecondAttackWaitsForTheFirstToComeBackAndForTheCooldown() {
        StrikeGate gate = new StrikeGate(8);
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(npc, 0, COOLDOWN));
        assertEquals(StrikeGate.Admission.IN_FLIGHT, gate.reserve(npc, COOLDOWN * 4, COOLDOWN), "the hit is still on the target's region");
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(other, 0, COOLDOWN), "another NPC has its own gate");
        gate.release(npc);
        assertEquals(StrikeGate.Admission.COOLDOWN, gate.reserve(npc, COOLDOWN - 1, COOLDOWN));
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(npc, COOLDOWN, COOLDOWN));
    }

    @Test void theGateHoldsAtMostItsCapacityOfNpcsInFlight() {
        StrikeGate gate = new StrikeGate(2);
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(npc, 0, COOLDOWN));
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(other, 0, COOLDOWN));
        assertEquals(StrikeGate.Admission.FULL, gate.reserve(UUID.randomUUID(), 0, COOLDOWN));
        gate.release(npc);
        assertEquals(StrikeGate.Admission.ADMITTED, gate.reserve(UUID.randomUUID(), COOLDOWN, COOLDOWN), "a settled NPC past its cooldown gives its place up");
    }

    @Test void racingAttacksOfOneNpcAdmitExactlyOne() throws InterruptedException {
        StrikeGate gate = new StrikeGate(8);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { return; }
                if (gate.reserve(npc, 0, COOLDOWN) == StrikeGate.Admission.ADMITTED) admitted.incrementAndGet();
            });
            thread.start(); threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, admitted.get());
    }

    @Test void aMobHasOneControllerAndTheTotalIsBounded() {
        MobLeases leases = new MobLeases(2);
        UUID zombie = UUID.randomUUID(), husk = UUID.randomUUID();
        MobLeases.Claim first = leases.claim(zombie, npc);
        assertNotNull(first);
        assertNull(leases.claim(zombie, other), "a mob already controlled cannot be taken by another NPC");
        assertNotNull(leases.claim(husk, other));
        assertNull(leases.claim(UUID.randomUUID(), npc), "the limit holds");
        assertEquals(List.of(first), leases.of(npc));
        assertTrue(leases.release(first));
        assertFalse(leases.release(first), "a lease is released once");
        assertNotNull(leases.claim(zombie, other), "a released mob can be controlled again");
        assertEquals(2, leases.size());
    }

    @Test void aStaleClaimCannotReleaseItsSuccessor() {
        MobLeases leases = new MobLeases(4);
        UUID zombie = UUID.randomUUID();
        MobLeases.Claim old = leases.claim(zombie, npc);
        leases.release(old);
        MobLeases.Claim next = leases.claim(zombie, npc);
        assertFalse(leases.release(old));
        assertTrue(leases.current(next));
        assertEquals(1, leases.size());
    }
}
