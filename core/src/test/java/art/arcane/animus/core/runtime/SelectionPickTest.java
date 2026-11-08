package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code /animus select} without an id: the NPC in the crosshair wins over a nearer one, then the nearest within reach. */
class SelectionPickTest {
    private static final Vec3 FEET = new Vec3(0, 64, 0), EYE = new Vec3(0, 65.62, 0), EAST = new Vec3(1, 0, 0);

    private static SelectionPick.Candidate npc(String id, double x, double z) { return npc("world", id, x, z); }

    private static SelectionPick.Candidate npc(String world, String id, double x, double z) {
        VirtualNpcState state = new VirtualNpcState(UUID.randomUUID(), new WorldPosition(world, new Vec3(x, 64, z)), .6, 1.8, 20);
        state.spawn();
        return new SelectionPick.Candidate(id, state.snapshot(), ViewerSession.HitShape.BOUNDS);
    }

    private static Optional<String> pick(Vec3 direction, SelectionPick.Candidate... candidates) {
        return SelectionPick.choose("world", EYE, direction, FEET, List.of(candidates));
    }

    @Test void theLookedAtNpcWinsOverANearerOne() {
        assertEquals(Optional.of("ahead"), pick(EAST, npc("beside", 0, 2), npc("ahead", 10, 0)));
    }

    @Test void theFirstAlongTheRayWins() {
        assertEquals(Optional.of("front"), pick(EAST, npc("back", 12, 0), npc("front", 6, 0)));
    }

    @Test void withoutOneInViewTheNearestWithinReachWins() {
        assertEquals(Optional.of("near"), pick(new Vec3(0, 1, 0), npc("far", 9, 9), npc("near", -3, 1)));
        assertEquals(Optional.of("near"), pick(new Vec3(0, 0, 0), npc("near", 2, 0)), "a zero direction falls back to the nearest");
    }

    @Test void nothingBeyondReachIsChosen() {
        assertEquals(Optional.empty(), pick(EAST, npc("far", 20, 0), npc("away", 0, -17)));
        assertEquals(Optional.empty(), pick(EAST, npc("nether", "elsewhere", 1, 0)), "another world is out of reach");
    }
}
