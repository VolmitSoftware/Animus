package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Region ownership, transfer epochs, stale-task rejection and handoff races, without a server. */
@Timeout(30)
class RegionOwnershipTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000001";
    private static final ChunkPosition A = new ChunkPosition(WORLD, 0, 0), B = new ChunkPosition(WORLD, 1, 0), C = new ChunkPosition(WORLD, 40, 0);

    /** A body standing in one chunk, or straddling two. It records where every owner callback ran. */
    private static class Body implements RegionOwner.Body {
        final FakeRegions regions;
        volatile ChunkPosition at;
        volatile ChunkPosition straddle;
        volatile ChunkPosition moveTo;
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<Integer, AtomicInteger> ticksByRegion = new ConcurrentHashMap<>();
        final AtomicInteger owned = new AtomicInteger(), suspended = new AtomicInteger(), released = new AtomicInteger(), retired = new AtomicInteger();
        volatile String lastSuspension;
        Body(FakeRegions regions, ChunkPosition at) { this.regions = regions; this.at = at; }
        @Override public RegionOwner.Footprint footprint() {
            ChunkPosition other = straddle == null ? at : straddle;
            return new RegionOwner.Footprint(WORLD, Math.min(at.x(), other.x()), Math.min(at.z(), other.z()), Math.max(at.x(), other.x()), Math.max(at.z(), other.z()));
        }
        @Override public ChunkPosition anchor() { return at; }
        @Override public boolean loaded(RegionOwner.Footprint footprint) { return regions.owns(footprint.world(), footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ()); }
        @Override public boolean addressable(ChunkPosition destination) { return true; }
        @Override public void handedOff() { events.add("handed-off"); }
        @Override public void rested() { events.add("rested"); }
        @Override public ChunkPosition tick() {
            ticksByRegion.computeIfAbsent(regions.currentRegion(), ignored -> new AtomicInteger()).incrementAndGet();
            ChunkPosition next = moveTo;
            if (next != null) { moveTo = null; at = next; }
            return at;
        }
        @Override public void owned(boolean woken) { owned.incrementAndGet(); events.add((woken ? "woken@" : "adopted@") + regions.currentRegion()); }
        @Override public void released() { released.incrementAndGet(); events.add("released@" + regions.currentRegion()); }
        @Override public void suspended(String reason) { suspended.incrementAndGet(); lastSuspension = reason; events.add("suspended"); }
        @Override public void retired() { retired.incrementAndGet(); events.add("retired"); }
        int ticksOn(int region) { AtomicInteger count = ticksByRegion.get(region); return count == null ? 0 : count.get(); }
    }

    private static RegionBudget budget(FakeRegions regions) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        return new RegionBudget(regions, limits);
    }
    private static RegionOwner owner(FakeRegions regions, Body body) { return new RegionOwner(UUID.randomUUID(), regions, budget(regions), 8, 8, body); }
    private static void wakeOn(FakeRegions regions, int region, RegionOwner owner, List<Boolean> result) {
        regions.execute(owner.body().anchor(), () -> result.add(owner.wake()));
        regions.tick(region);
    }

    @Test void wakeRequiresTheOwnerOfTheAnchorAndItsLoadedChunks() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        assertFalse(owner.wake(), "no region thread owns the anchor here");
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        List<Boolean> woke = new ArrayList<>(); wakeOn(regions, 1, owner, woke);
        assertEquals(List.of(true), woke);
        assertEquals(NpcOwnership.Phase.ACTIVE, owner.ownership().state().phase());
        assertEquals(List.of("woken@1"), body.events);
        regions.tick(1); regions.tick(1);
        assertEquals(2, body.ticksOn(1), "the actor ticks once per tick of its own region");
    }

    @Test void straddlingFootprintCannotWakeAndSuspendsAnActiveNpc() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
        Body body = new Body(regions, A); body.straddle = B; RegionOwner owner = owner(regions, body);
        List<Boolean> woke = new ArrayList<>(); wakeOn(regions, 1, owner, woke);
        assertEquals(List.of(false), woke, "half of the footprint belongs to another region");
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        body.straddle = null; woke.clear(); wakeOn(regions, 1, owner, woke);
        assertEquals(List.of(true), woke);
        body.straddle = B; regions.tick(1);
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertEquals(1, body.suspended.get());
        assertTrue(body.lastSuspension.contains("chunks"), body.lastSuspension);
        assertEquals(0, regions.liveRepeats(), "suspension cancels the actor's task");
    }

    @Test void sameRegionReanchorReplacesTheTokenAndRejectsTheOldTask() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        NpcOwnership.Token before = owner.ownership().state().token();
        body.moveTo = B; regions.tick(1);
        assertEquals(NpcOwnership.Phase.TRANSFERRING, owner.ownership().state().phase());
        assertFalse(owner.ownership().current(before), "the source token is stale as soon as the handoff begins");
        regions.tick(1);
        NpcOwnership.Token after = owner.ownership().state().token();
        assertEquals(NpcOwnership.Phase.ACTIVE, owner.ownership().state().phase());
        assertEquals(B, after.anchor());
        assertTrue(after.epoch() > before.epoch());
        assertEquals(1, regions.liveRepeats(), "exactly one task drives the NPC after the handoff");
        int ticks = body.ticksOn(1); regions.tick(1);
        assertEquals(ticks + 1, body.ticksOn(1));
    }

    @Test void crossRegionHandoffAdoptsOnTheDestinationAndTheSourceNeverTicksItAgain() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        body.moveTo = B; regions.tick(1);
        regions.assign(2, B); // region 2 split off and owns the destination before adoption runs
        regions.tick(1);
        assertEquals(NpcOwnership.Phase.TRANSFERRING, owner.ownership().state().phase(), "the source cannot adopt a chunk it no longer owns");
        int sourceTicks = body.ticksOn(1);
        regions.tick(2);
        assertEquals(NpcOwnership.Phase.ACTIVE, owner.ownership().state().phase());
        assertEquals(List.of("woken@1", "released@1", "handed-off", "adopted@2"), body.events);
        regions.tick(1); regions.tick(2); regions.tick(2);
        assertEquals(sourceTicks, body.ticksOn(1), "a stale source task is a no-op");
        assertEquals(2, body.ticksOn(2));
    }

    @Test void regionSplitMovesTheActorWithItsAnchorWithoutAHandoff() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        NpcOwnership.Token token = owner.ownership().state().token();
        regions.assign(3, A);
        regions.tick(1); regions.tick(3);
        assertEquals(1, body.ticksOn(3));
        assertTrue(owner.ownership().current(token), "a split keeps the epoch: the anchor chunk did not change");
    }

    @Test void destinationThatUnloadedBeforeAdoptionSuspendsTheNpc() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        body.moveTo = B; regions.tick(1);
        regions.unload(B);
        assertEquals(1, owner.expireTransfer(Long.MAX_VALUE) ? 1 : 0, "a lost adoption times out");
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertEquals(1, body.suspended.get());
        regions.assign(1, B); regions.tick(1);
        assertEquals(1, body.owned.get(), "a late adoption from the expired epoch cannot resurrect the old owner");
    }

    @Test void failingOwnerTurnSuspendsInsteadOfEscapingIntoTheRegionTick() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Body body = new Body(regions, A) {
            @Override public ChunkPosition tick() { throw new IllegalStateException("Stale or conflicting runtime snapshot"); }
        };
        RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        assertDoesNotThrow(() -> regions.tick(1));
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertTrue(body.lastSuspension.contains("conflicting"), body.lastSuspension);
        assertEquals(0, regions.liveRepeats());
    }

    @Test void handoffToAChunkNotKnownLoadedIsNeverScheduled() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A) { @Override public boolean addressable(ChunkPosition destination) { return false; } };
        RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        body.moveTo = B; regions.tick(1);
        assertEquals(0, regions.pendingOnce(), "no location task for an unknown chunk");
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertEquals("rested", body.events.getLast());
    }

    @Test void refusedHandoffSuspends() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        regions.refuseExecutes = true; body.moveTo = B; regions.tick(1);
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertTrue(body.lastSuspension.contains("refused"), body.lastSuspension);
    }

    @Test void workQueuedDuringTransferRunsOnTheNewOwnerAndInlineOnlyOnTheOwner() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        List<Integer> ranOn = new CopyOnWriteArrayList<>();
        assertTrue(owner.submit(work(() -> ranOn.add(regions.currentRegion()), reason -> fail(reason))));
        assertEquals(List.of(), ranOn, "a foreign thread queues instead of running owner work");
        regions.tick(1);
        assertEquals(List.of(1), ranOn);
        regions.execute(A, () -> owner.submit(work(() -> ranOn.add(regions.currentRegion()), reason -> fail(reason))));
        regions.tick(1);
        assertEquals(List.of(1, 1), ranOn, "the owner runs submitted work at once");
        body.moveTo = B; regions.tick(1);
        assertTrue(owner.submit(work(() -> ranOn.add(regions.currentRegion()), reason -> fail(reason))));
        regions.tick(1);
        assertEquals(List.of(1, 1), ranOn, "the source no longer drains the mailbox");
        regions.tick(2); regions.tick(2);
        assertEquals(List.of(1, 1, 2), ranOn);
    }

    @Test void suspensionRefusesQueuedWorkAndDormantNpcsRefuseNewWork() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        List<String> refused = new CopyOnWriteArrayList<>();
        owner.submit(work(() -> fail("must not run"), refused::add));
        regions.execute(A, () -> owner.unloading(A)); regions.tick(1); regions.unload(A);
        assertEquals(NpcOwnership.Phase.DORMANT, owner.ownership().state().phase());
        assertEquals(1, refused.size());
        assertFalse(owner.submit(work(() -> fail("must not run"), refused::add)));
    }

    @Test void retiringAnUnownedNpcDuringTransferMakesTheAdoptionANoOp() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        assertFalse(owner.retireUnowned(), "an active NPC is retired by its owner");
        body.moveTo = B; regions.tick(1);
        assertTrue(owner.retireUnowned());
        regions.tick(1); regions.tick(1);
        assertEquals(NpcOwnership.Phase.RETIRED, owner.ownership().state().phase());
        assertEquals(1, body.owned.get());
        assertEquals(1, body.retired.get());
        assertEquals(0, regions.liveRepeats());
    }

    @Test void shutdownStopsEveryTaskWithoutCallingTheBody() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
        wakeOn(regions, 1, owner, new ArrayList<>());
        owner.shutdown(); regions.tick(1);
        assertEquals(NpcOwnership.Phase.RETIRED, owner.ownership().state().phase());
        assertEquals(0, regions.liveRepeats());
        assertEquals(List.of("woken@1"), body.events);
    }

    @Test void adoptionRacingExpiryHasExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 500; iteration++) {
            FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
            Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
            wakeOn(regions, 1, owner, new ArrayList<>());
            body.moveTo = B; regions.tick(1);
            CountDownLatch start = new CountDownLatch(1);
            Thread destination = new Thread(() -> { await(start); regions.tick(2); });
            Thread expiry = new Thread(() -> { await(start); owner.expireTransfer(Long.MAX_VALUE); });
            destination.start(); expiry.start(); start.countDown(); destination.join(); expiry.join();
            int adopted = body.owned.get() - 1, suspended = body.suspended.get();
            assertEquals(1, adopted + suspended, "adoption and expiry must not both win: " + body.events);
            NpcOwnership.Phase phase = owner.ownership().state().phase();
            assertEquals(adopted == 1 ? NpcOwnership.Phase.ACTIVE : NpcOwnership.Phase.DORMANT, phase);
            assertEquals(adopted == 1 ? 1 : 0, regions.liveRepeats(), "only the winner keeps a task");
        }
    }

    @Test void racingRegionTicksNeverRunTheActorOnTwoThreads() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
            AtomicInteger inside = new AtomicInteger(), overlaps = new AtomicInteger();
            Body body = new Body(regions, A) {
                @Override public ChunkPosition tick() {
                    if (inside.incrementAndGet() > 1) overlaps.incrementAndGet();
                    try { return super.tick(); } finally { inside.decrementAndGet(); }
                }
            };
            RegionOwner owner = owner(regions, body);
            wakeOn(regions, 1, owner, new ArrayList<>());
            CountDownLatch start = new CountDownLatch(1);
            Thread first = new Thread(() -> { await(start); for (int i = 0; i < 20; i++) { if (i == 5) body.moveTo = B; regions.tick(1); } });
            Thread second = new Thread(() -> { await(start); for (int i = 0; i < 20; i++) regions.tick(2); });
            first.start(); second.start(); start.countDown(); first.join(); second.join();
            regions.tick(2);
            assertEquals(0, overlaps.get(), "a single writer per NPC");
            assertEquals(NpcOwnership.Phase.ACTIVE, owner.ownership().state().phase(), body.events.toString());
            assertEquals(B, owner.ownership().state().token().anchor());
        }
    }

    @Test void offersRacingSuspensionAreEachRunOrRefusedExactlyOnce() throws Exception {
        for (int iteration = 0; iteration < 300; iteration++) {
            FakeRegions regions = new FakeRegions().assign(1, A);
            Body body = new Body(regions, A); RegionOwner owner = owner(regions, body);
            wakeOn(regions, 1, owner, new ArrayList<>());
            AtomicInteger outcomes = new AtomicInteger(), accepted = new AtomicInteger(), rejected = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread offers = new Thread(() -> {
                await(start);
                for (int i = 0; i < 8; i++) {
                    if (owner.submit(work(outcomes::incrementAndGet, reason -> outcomes.incrementAndGet()))) accepted.incrementAndGet();
                    else rejected.incrementAndGet();
                }
            });
            Thread unload = new Thread(() -> { await(start); regions.execute(A, () -> owner.unloading(A)); regions.tick(1); regions.unload(A); });
            offers.start(); unload.start(); start.countDown(); offers.join(); unload.join();
            regions.tick(1);
            assertEquals(accepted.get(), outcomes.get(), "every accepted offer settles once");
            assertEquals(8, accepted.get() + rejected.get());
        }
    }

    @Test void ownershipEpochsAreMonotonicAndTokensNeverRevive() {
        NpcOwnership ownership = new NpcOwnership(UUID.randomUUID());
        NpcOwnership.Token first = ownership.activate(A);
        assertNotNull(first);
        assertNull(ownership.activate(A), "an active NPC cannot be woken twice");
        NpcOwnership.Token moving = ownership.beginTransfer(first, B);
        assertNull(ownership.beginTransfer(first, B), "a stale token cannot start another handoff");
        assertFalse(ownership.adopt(first));
        assertTrue(ownership.adopt(moving));
        assertFalse(ownership.adopt(moving), "adoption happens once");
        assertTrue(ownership.suspend(moving));
        assertNull(ownership.activate(B), "a suspending NPC cannot be woken before its teardown settles");
        assertTrue(ownership.settle(moving));
        NpcOwnership.Token again = ownership.activate(B);
        assertTrue(again.epoch() > moving.epoch() && moving.epoch() > first.epoch());
        assertFalse(ownership.current(moving));
        assertTrue(ownership.retire(again));
        assertNull(ownership.activate(B), "retirement is final");
    }

    @Test void budgetsArePerRegionTick() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 10);
        RegionBudget budget = new RegionBudget(regions, limits);
        assertEquals(0, budget.take(RegionBudget.Kind.ROUTES, 4), "no budget away from a region thread");
        List<Integer> granted = new ArrayList<>();
        regions.execute(A, () -> { granted.add(budget.take(RegionBudget.Kind.ROUTES, 7)); granted.add(budget.take(RegionBudget.Kind.ROUTES, 7)); });
        regions.execute(B, () -> granted.add(budget.take(RegionBudget.Kind.ROUTES, 7)));
        regions.tick(1); regions.tick(2);
        assertEquals(List.of(7, 3, 7), granted, "each region has its own allowance");
        regions.execute(A, () -> granted.add(budget.take(RegionBudget.Kind.ROUTES, 7)));
        regions.tick(1);
        assertEquals(7, granted.get(3), "a new region tick restores the allowance");
    }

    @Test void mailboxIsBoundedAndClosingRefusesTheRest() {
        OwnerMailbox mailbox = new OwnerMailbox(2);
        List<String> log = new ArrayList<>();
        assertTrue(mailbox.offer(work(() -> log.add("one"), reason -> log.add("refused one"))));
        assertTrue(mailbox.offer(work(() -> log.add("two"), reason -> log.add("refused two"))));
        assertFalse(mailbox.offer(work(() -> log.add("three"), reason -> log.add("refused three"))));
        assertEquals(1, mailbox.drain(1));
        mailbox.pause("dormant");
        assertEquals(List.of("one", "refused two"), log);
        assertFalse(mailbox.offer(work(() -> log.add("paused"), reason -> log.add("refused paused"))), "a dormant NPC accepts no work");
        mailbox.resume();
        assertTrue(mailbox.offer(work(() -> log.add("four"), reason -> log.add("refused four"))), "waking accepts work again");
        mailbox.close("retired");
        mailbox.resume();
        assertFalse(mailbox.offer(work(() -> log.add("five"), reason -> log.add("refused five"))), "closing is final");
        assertEquals(List.of("one", "refused two", "refused four"), log);
    }

    private static OwnerMailbox.Work work(Runnable run, java.util.function.Consumer<String> refuse) {
        return new OwnerMailbox.Work() {
            @Override public void run() { run.run(); }
            @Override public void refuse(String reason) { refuse.accept(reason); }
        };
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
}
