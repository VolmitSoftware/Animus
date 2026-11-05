package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** A mounted group has one owner: the vehicle's region ticks every passenger and the group hands off as one. */
@Timeout(30)
class RegionGroupTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000002";
    private static final ChunkPosition A = new ChunkPosition(WORLD, 0, 0), B = new ChunkPosition(WORLD, 1, 0), FAR = new ChunkPosition(WORLD, 40, 0);

    private static final class Body implements RegionOwner.Body {
        final FakeRegions regions;
        final String name;
        volatile ChunkPosition at;
        volatile ChunkPosition moveTo;
        volatile Consumer<RegionOwner> onTick;
        RegionOwner owner;
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<Integer, AtomicInteger> ticksByRegion = new ConcurrentHashMap<>();
        Body(FakeRegions regions, String name, ChunkPosition at) { this.regions = regions; this.name = name; this.at = at; }
        @Override public RegionOwner.Footprint footprint() { return new RegionOwner.Footprint(WORLD, at.x(), at.z(), at.x(), at.z()); }
        @Override public ChunkPosition anchor() { return at; }
        @Override public boolean loaded(RegionOwner.Footprint footprint) { return regions.owns(footprint.world(), footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ()); }
        @Override public boolean addressable(ChunkPosition destination) { return true; }
        @Override public void handedOff() { events.add("handed-off"); }
        @Override public void rested() { events.add("rested"); }
        @Override public ChunkPosition tick() {
            ticksByRegion.computeIfAbsent(regions.currentRegion(), ignored -> new AtomicInteger()).incrementAndGet();
            ChunkPosition next = moveTo;
            if (next != null) { moveTo = null; at = next; }
            Consumer<RegionOwner> hook = onTick;
            if (hook != null) { onTick = null; hook.accept(owner); }
            return at;
        }
        @Override public void owned(boolean woken) { events.add((woken ? "woken@" : "adopted@") + regions.currentRegion()); }
        @Override public void released() { events.add("released@" + regions.currentRegion()); }
        @Override public void suspended(String reason) { events.add("suspended: " + reason); }
        @Override public void retired() { events.add("retired"); }
        int ticksOn(int region) { AtomicInteger count = ticksByRegion.get(region); return count == null ? 0 : count.get(); }
    }

    private static RegionBudget budget(FakeRegions regions) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        return new RegionBudget(regions, limits);
    }
    private static Body body(FakeRegions regions, RegionBudget budget, String name, ChunkPosition at) {
        Body body = new Body(regions, name, at);
        body.owner = new RegionOwner(UUID.randomUUID(), regions, budget, 8, 8, body);
        return body;
    }
    private static void wake(FakeRegions regions, int region, Body body) {
        List<Boolean> woke = new ArrayList<>();
        regions.execute(body.anchor(), () -> woke.add(body.owner.wake()));
        regions.tick(region);
        assertEquals(List.of(true), woke, body.name + " did not wake");
    }
    /** Both bodies awake on region 1; the passenger boards the vehicle from its own turn. */
    private static boolean board(FakeRegions regions, Body passenger, Body vehicle) {
        List<Boolean> boarded = new ArrayList<>();
        passenger.onTick = owner -> boarded.add(owner.board(vehicle.owner));
        regions.tick(1);
        return boarded.getFirst();
    }

    @Test void shouldTickAPassengerOnItsVehiclesOwnerAfterItBoards() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        assertEquals(NpcOwnership.Phase.CARRIED, rider.owner.ownership().state().phase());
        assertTrue(rider.owner.carried());
        assertSame(horse.owner, rider.owner.root());
        assertEquals(List.of(rider.owner), horse.owner.riders());
        assertEquals(1, regions.liveRepeats(), "the passenger's own task stopped: the group has one task");
        int before = rider.ticksOn(1);
        regions.tick(1); regions.tick(1);
        assertEquals(before + 2, rider.ticksOn(1), "the vehicle's turn ticks its passenger once per tick");
    }

    @Test void shouldRefuseBoardingAVehicleThatAnotherRegionOwns() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, FAR);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", FAR), rider = body(regions, budget, "rider", A);
        wake(regions, 2, horse); wake(regions, 1, rider);
        assertFalse(board(regions, rider, horse));
        assertEquals(NpcOwnership.Phase.ACTIVE, rider.owner.ownership().state().phase());
        assertEquals(2, regions.liveRepeats());
    }

    @Test void shouldHandTheWholeGroupOffInOneTransfer() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        rider.events.clear();
        horse.moveTo = B; rider.moveTo = B;
        regions.tick(1);
        regions.assign(2, B);
        assertEquals(NpcOwnership.Phase.TRANSFERRING, horse.owner.ownership().state().phase());
        assertEquals(NpcOwnership.Phase.CARRIED, rider.owner.ownership().state().phase(), "passengers move with the vehicle's transfer");
        int sourceTicks = rider.ticksOn(1);
        regions.tick(2);
        assertEquals(NpcOwnership.Phase.ACTIVE, horse.owner.ownership().state().phase());
        assertEquals(List.of("released@1", "adopted@2"), rider.events, "the passenger is released and adopted with its vehicle");
        regions.tick(1); regions.tick(2); regions.tick(2);
        assertEquals(sourceTicks, rider.ticksOn(1), "the old region never ticks the passenger again");
        assertEquals(2, rider.ticksOn(2));
        List<Boolean> owned = new ArrayList<>();
        regions.execute(B, () -> owned.add(rider.owner.owned()));
        regions.tick(2);
        assertEquals(List.of(true), owned, "the new region owns the passenger with its vehicle");
    }

    @Test void shouldRunWorkForAPassengerOnTheGroupOwner() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        List<Integer> ranOn = new CopyOnWriteArrayList<>();
        assertTrue(rider.owner.submit(work(() -> ranOn.add(regions.currentRegion()), reason -> fail(reason))));
        assertEquals(List.of(), ranOn);
        regions.tick(1);
        assertEquals(List.of(1), ranOn);
    }

    @Test void shouldSuspendPassengersWithTheirVehicle() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        regions.execute(A, () -> horse.owner.unloading(A));
        regions.tick(1);
        assertEquals(NpcOwnership.Phase.DORMANT, horse.owner.ownership().state().phase());
        assertEquals(NpcOwnership.Phase.DORMANT, rider.owner.ownership().state().phase());
        assertFalse(rider.owner.carried());
        assertTrue(rider.events.getLast().equals("rested"), rider.events.toString());
        assertEquals(0, regions.liveRepeats());
        wake(regions, 1, rider);
        assertEquals(NpcOwnership.Phase.ACTIVE, rider.owner.ownership().state().phase(), "a dormant passenger wakes on its own again");
    }

    @Test void shouldGiveAnAlightingPassengerItsOwnTaskAgain() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        NpcOwnership.Token carried = rider.owner.ownership().state().token();
        List<Boolean> alit = new ArrayList<>();
        rider.onTick = owner -> alit.add(owner.alight());
        regions.tick(1);
        assertEquals(List.of(true), alit);
        assertEquals(NpcOwnership.Phase.ACTIVE, rider.owner.ownership().state().phase());
        assertTrue(rider.owner.ownership().state().token().epoch() > carried.epoch(), "alighting starts a new epoch");
        assertEquals(List.of(), horse.owner.riders());
        assertEquals(2, regions.liveRepeats());
        int before = rider.ticksOn(1);
        regions.tick(1);
        assertEquals(before + 1, rider.ticksOn(1), "exactly one turn per tick after alighting");
    }

    @Test void shouldRetireAPassengerFromItsOwnTurnAndLetTheVehicleGoOn() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        List<Boolean> retired = new ArrayList<>();
        rider.onTick = owner -> retired.add(owner.retire());
        regions.tick(1);
        assertEquals(List.of(true), retired);
        assertEquals(NpcOwnership.Phase.RETIRED, rider.owner.ownership().state().phase());
        assertEquals(List.of(), horse.owner.riders());
        int horseTicks = horse.ticksOn(1);
        regions.tick(1);
        assertEquals(horseTicks + 1, horse.ticksOn(1));
    }

    @Test void shouldLetPassengersAlightWhenTheirVehicleRetires() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        horse.onTick = RegionOwner::retire;
        regions.tick(1);
        assertEquals(NpcOwnership.Phase.RETIRED, horse.owner.ownership().state().phase());
        assertEquals(NpcOwnership.Phase.ACTIVE, rider.owner.ownership().state().phase(), "a passenger outlives its removed vehicle");
        int before = rider.ticksOn(1);
        regions.tick(1);
        assertEquals(before + 1, rider.ticksOn(1));
    }

    @Test void shouldJoinAChainToItsRootSoTheWholeChainHasOneOwner() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), knight = body(regions, budget, "knight", A), squire = body(regions, budget, "squire", A);
        wake(regions, 1, horse); wake(regions, 1, knight); wake(regions, 1, squire);
        assertTrue(board(regions, squire, knight));
        assertTrue(board(regions, knight, horse));
        assertSame(horse.owner, squire.owner.root(), "a group that boards brings its own passengers");
        assertEquals(List.of(knight.owner, squire.owner), horse.owner.riders(), "vehicles come before their passengers");
        assertEquals(1, regions.liveRepeats());
    }

    @Test void shouldPutPassengersToSleepWhenTheirVehicleIsRemovedDuringATransfer() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        RegionBudget budget = budget(regions);
        Body horse = body(regions, budget, "horse", A), rider = body(regions, budget, "rider", A);
        wake(regions, 1, horse); wake(regions, 1, rider);
        assertTrue(board(regions, rider, horse));
        horse.moveTo = B; rider.moveTo = B;
        regions.tick(1);
        assertTrue(horse.owner.transferring());
        assertTrue(horse.owner.retireUnowned(), "a vehicle in transfer has no owner to ask");
        assertEquals(NpcOwnership.Phase.DORMANT, rider.owner.ownership().state().phase(), "its passenger is not stranded in a dead group");
        assertEquals("rested", rider.events.getLast());
        regions.tick(1);
        assertEquals(0, regions.liveRepeats());
        wake(regions, 1, rider);
    }

    private static OwnerMailbox.Work work(Runnable run, Consumer<String> refuse) {
        return new OwnerMailbox.Work() {
            @Override public void run() { run.run(); }
            @Override public void refuse(String reason) { refuse.accept(reason); }
        };
    }
}
