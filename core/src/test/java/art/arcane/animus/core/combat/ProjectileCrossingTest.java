package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectileCrossingTest {
    private static final UUID NEAR = UUID.randomUUID(), FAR = UUID.randomUUID(), IGNORED = UUID.randomUUID();
    private static VirtualNpcState.Snapshot body(UUID id, double x) {
        Vec3 feet = new Vec3(x, 64, 0);
        return new VirtualNpcState.Snapshot(id, new WorldPosition("w", feet), Hitbox.atFeet(feet, 0.6, 1.8), 20, true, 1);
    }
    private static final ProjectileFlight.Scanner SCANNER = (WorldPosition center, double radius, SpatialNpcIndex.Budget budget) ->
            new SpatialNpcIndex.Scan(List.of(body(FAR, 6), body(NEAR, 2), body(IGNORED, 4)), java.util.Optional.empty(), 1, 3);

    @Test void everyWatchedBodyTheSegmentPassesIsReportedNearestFirstAndOnlyOnce() {
        ProjectileFlight flight = new ProjectileFlight(0);
        flight.launched(new WorldPosition("w", new Vec3(0, 65, 0)));
        List<ProjectileFlight.Hit> crossed = flight.crossings(new WorldPosition("w", new Vec3(8, 65, 0)), new Vec3(8, 0, 0), SCANNER, Set.of(NEAR, FAR)::contains);
        assertEquals(List.of(NEAR, FAR), crossed.stream().map(ProjectileFlight.Hit::npc).toList());
        assertEquals(1.7, crossed.getFirst().point().x(), 1e-9);
        assertFalse(flight.spent(), "crossing a body never spends the flight");
        assertEquals(List.of(), flight.crossings(new WorldPosition("w", new Vec3(0, 65, 0)), new Vec3(-8, 0, 0), SCANNER, Set.of(NEAR, FAR)::contains));
    }
}
