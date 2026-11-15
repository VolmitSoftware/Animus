package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Teleports on Folia: the destination's region finds the ground, the NPC's region moves the NPC there. */
class RegionPlacementTest {
    private static final String WORLD = "w";
    private static final WorldPosition HERE = new WorldPosition(WORLD, new Vec3(1, 64, 1)), FAR = new WorldPosition(WORLD, new Vec3(4000, 64, 4000));

    /** Two regions: the NPC's own chunk at the origin and everything else; work queued on either runs when the test says. */
    private static final class Host implements RegionPlacement.Host {
        final List<String> calls = new ArrayList<>();
        final List<Runnable> destinationRegion = new ArrayList<>(), ownerRegion = new ArrayList<>();
        Set<ChunkPosition> loaded = Set.of(FAR.chunk());
        boolean placeable = true, ground = true, ownerTakes = true;

        @Override public boolean ownsDestination(ChunkPosition chunk) { return chunk.equals(HERE.chunk()); }
        @Override public boolean knownLoaded(ChunkPosition chunk) { return loaded.contains(chunk); }
        @Override public boolean onDestination(ChunkPosition chunk, Runnable work) { calls.add("hand to " + chunk.x()); destinationRegion.add(work); return true; }
        @Override public Optional<WorldPosition> clearSpot(WorldPosition destination) { calls.add("ground"); return ground ? Optional.of(destination) : Optional.empty(); }
        @Override public boolean onOwner(Runnable work, Runnable refused) { if (!ownerTakes) return false; ownerRegion.add(work); return true; }
        @Override public boolean placeable() { return placeable; }
        @Override public void place(WorldPosition spot) { calls.add("place " + spot.point().brief()); }
    }

    @Test void aDestinationInTheNpcsOwnRegionIsPlacedAtOnce() {
        Host host = new Host();
        List<Boolean> done = new ArrayList<>();
        assertTrue(RegionPlacement.place(host, HERE, done::add));
        assertEquals(List.of("ground", "place 1 64 1"), host.calls);
        assertEquals(List.of(true), done);
    }

    @Test void anotherRegionFindsTheGroundAndTheNpcsRegionMovesIt() {
        Host host = new Host();
        List<Boolean> done = new ArrayList<>();
        assertTrue(RegionPlacement.place(host, FAR, done::add));
        assertEquals(List.of("hand to " + FAR.chunk().x()), host.calls, "nothing is read before the destination's region runs");
        host.destinationRegion.removeFirst().run();
        assertTrue(done.isEmpty());
        host.ownerRegion.removeFirst().run();
        assertEquals(List.of("hand to " + FAR.chunk().x(), "ground", "place 4000 64 4000"), host.calls);
        assertEquals(List.of(true), done);
    }

    @Test void noGroundAnUnloadedChunkOrAGoneOwnerReportFailure() {
        Host blocked = new Host();
        blocked.ground = false;
        List<Boolean> done = new ArrayList<>();
        RegionPlacement.place(blocked, FAR, done::add);
        blocked.destinationRegion.removeFirst().run();
        assertEquals(List.of(false), done);

        Host unloaded = new Host();
        unloaded.loaded = Set.of();
        assertFalse(RegionPlacement.place(unloaded, FAR, (Boolean moved) -> fail("an unloaded destination is refused at once")));

        Host dormant = new Host();
        dormant.ownerTakes = false;
        List<Boolean> lost = new ArrayList<>();
        RegionPlacement.place(dormant, FAR, lost::add);
        dormant.destinationRegion.removeFirst().run();
        assertEquals(List.of(false), lost);

        Host seated = new Host();
        List<Boolean> moved = new ArrayList<>();
        RegionPlacement.place(seated, FAR, moved::add);
        seated.destinationRegion.removeFirst().run();
        seated.placeable = false;
        seated.ownerRegion.removeFirst().run();
        assertEquals(List.of(false), moved, "the owner checks again before it moves the NPC");
    }
}
