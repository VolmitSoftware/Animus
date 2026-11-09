package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A file read finishes on an I/O thread and hands its result back to the NPC's owner. When the owner refuses it (the NPC
 * is dormant, its handoff was abandoned, or its mailbox is full), the actor must hear about it, so it reads again on its
 * next turn instead of waiting for that result forever.
 */
@Timeout(30)
class RegionReadHandBackTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000001";
    private static final ChunkPosition A = new ChunkPosition(WORLD, 0, 0);

    private static final class Body implements RegionOwner.Body {
        final FakeRegions regions;
        Body(FakeRegions regions) { this.regions = regions; }
        @Override public RegionOwner.Footprint footprint() { return new RegionOwner.Footprint(WORLD, 0, 0, 0, 0); }
        @Override public ChunkPosition anchor() { return A; }
        @Override public boolean loaded(RegionOwner.Footprint footprint) { return regions.owns(footprint.world(), footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ()); }
        @Override public boolean addressable(ChunkPosition destination) { return true; }
        @Override public ChunkPosition tick() { return A; }
        @Override public void owned(boolean woken) {}
        @Override public void released() {}
        @Override public void handedOff() {}
        @Override public void suspended(String reason) {}
        @Override public void rested() {}
        @Override public void retired() {}
    }

    private static RegionOwner owner(FakeRegions regions, int mailbox) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        return new RegionOwner(UUID.randomUUID(), regions, new RegionBudget(regions, limits), mailbox, 8, new Body(regions));
    }

    @Test void aDormantOwnerRefusesTheResultAndTheReaderIsTold() {
        RegionOwner dormant = owner(new FakeRegions().assign(1, A), 4);
        AtomicInteger applied = new AtomicInteger(), refused = new AtomicInteger();
        RegionActor.handBack(dormant, applied::incrementAndGet, refused::incrementAndGet);
        assertEquals(0, applied.get());
        assertEquals(1, refused.get(), "a refused hand-back must reach the reader exactly once");
    }

    @Test void aFullMailboxRefusesTheResultAndTheReaderIsTold() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionOwner active = owner(regions, 1);
        List<Boolean> woke = new CopyOnWriteArrayList<>();
        regions.execute(A, () -> woke.add(active.wake()));
        regions.tick(1);
        assertEquals(List.of(true), woke);
        AtomicInteger applied = new AtomicInteger(), refused = new AtomicInteger();
        RegionActor.handBack(active, applied::incrementAndGet, refused::incrementAndGet);
        RegionActor.handBack(active, applied::incrementAndGet, refused::incrementAndGet);
        assertEquals(1, refused.get(), "the second result does not fit the one-slot mailbox");
        regions.tick(1);
        assertEquals(1, applied.get(), "the queued result runs on the owner");
    }

    @Test void aResultDroppedWhenTheNpcGoesDormantIsRefusedToo() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionOwner active = owner(regions, 4);
        regions.execute(A, active::wake);
        regions.tick(1);
        AtomicInteger applied = new AtomicInteger(), refused = new AtomicInteger();
        RegionActor.handBack(active, applied::incrementAndGet, refused::incrementAndGet);
        regions.execute(A, () -> active.unloading(A));
        regions.tick(1);
        assertEquals(0, applied.get());
        assertEquals(1, refused.get());
    }
}
