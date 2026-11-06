package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gravity for a body that is not following a route. A runtime asks for one step per tick only after a block under or
 * beside the body's feet changed, and keeps asking while the body falls. Each step reads at most {@link #SEARCH} blocks
 * below the feet, so a long fall costs the same every tick. Unknown terrain, and a ladder, vine or scaffolding at the
 * feet, hold the body where it is.
 */
public final class IdleFall {
    public enum Outcome { SUPPORTED, FALLING, LANDED, HELD }
    /** @param speed downward speed in blocks per tick after this step */
    public record Step(Outcome outcome, Vec3 to, double speed, int sampled, String detail) {}
    public static final double GRAVITY = 0.08, DRAG = 0.98, TERMINAL = 3.92;
    public static final int SEARCH = 16;
    private static final double EPSILON = MotionValidator.EPSILON, REACH = 4;
    private static final SpatialNpcIndex.Budget LOOKUP = new SpatialNpcIndex.Budget(4, 64);
    private IdleFall() {}

    /**
     * @param feet  the body's current feet position
     * @param speed downward speed from the previous step, 0 when the body was standing
     * @param swims true when the body stays at the surface of deep water
     */
    public static Step step(Vec3 feet, double speed, double hullWidth, double hullHeight, boolean swims, MotionValidator.Sampler terrain) {
        return step(feet, speed, GRAVITY, hullWidth, hullHeight, swims, terrain);
    }

    /** As above, falling with the body's own {@code gravity} attribute; terminal speed scales with it. */
    public static Step step(Vec3 feet, double speed, double gravity, double hullWidth, double hullHeight, boolean swims, MotionValidator.Sampler terrain) {
        int held = holding(feet, hullWidth, terrain);
        if (held > 0) return new Step(speed > 0 ? Outcome.LANDED : Outcome.SUPPORTED, feet, 0, held, "climbing");
        BodyTerrain.Rest rest = new BodyTerrain(hullWidth, hullHeight, swims, cells(hullWidth, hullHeight), terrain).rest(feet.x(), feet.z(), feet.y(), 0, SEARCH, true);
        double next = Math.min(gravity * DRAG / (1 - DRAG), (speed + gravity) * DRAG);
        int sampled = rest.sampled() - held;
        return switch (rest.kind()) {
            case GROUND, SWIM -> {
                if (rest.feet() >= feet.y() - EPSILON) yield new Step(speed > 0 ? Outcome.LANDED : Outcome.SUPPORTED, feet, 0, sampled, rest.detail());
                double y = Math.max(rest.feet(), feet.y() - next);
                boolean landed = y <= rest.feet();
                yield new Step(landed ? Outcome.LANDED : Outcome.FALLING, new Vec3(feet.x(), y, feet.z()), landed ? 0 : next, sampled, rest.detail());
            }
            case NONE -> new Step(Outcome.FALLING, new Vec3(feet.x(), feet.y() - next, feet.z()), next, sampled, rest.detail());
            case BLOCKED, HAZARD, UNAVAILABLE, BUDGET -> new Step(Outcome.HELD, feet, 0, sampled, rest.detail());
        };
    }

    /** Cells read when a climbable cell in the layer of the feet holds the body, or the negated count when none does. */
    private static int holding(Vec3 feet, double hullWidth, MotionValidator.Sampler terrain) {
        double half = hullWidth / 2;
        int y = MotionValidator.floor(feet.y() + EPSILON), read = 0;
        for (int x = MotionValidator.floor(feet.x() - half + EPSILON); x <= MotionValidator.floor(feet.x() + half - EPSILON); x++)
            for (int z = MotionValidator.floor(feet.z() - half + EPSILON); z <= MotionValidator.floor(feet.z() + half - EPSILON); z++) {
                Cell cell = terrain.sample(x, y, z);
                read++;
                if (cell != null && cell.climbable()) return read;
            }
        return -read;
    }

    /** The most cells one step reads for this hull: 88 for a player, 700 for the largest hull. */
    public static int cells(double hullWidth, double hullHeight) {
        int across = (int) Math.ceil(hullWidth) + 1;
        return across * across * (SEARCH + (int) Math.ceil(hullHeight) + 5);
    }

    /** True when a change to this block can take away, or give, the support under a body's feet. */
    public static boolean affects(Vec3 feet, double hullWidth, BlockPos changed) {
        double half = hullWidth / 2;
        return MotionValidator.overlaps(changed.x(), changed.x() + 1, feet.x() - half, feet.x() + half)
                && MotionValidator.overlaps(changed.z(), changed.z() + 1, feet.z() - half, feet.z() + half)
                && feet.y() >= changed.y() - EPSILON && feet.y() <= changed.y() + Cell.MAX_HEIGHT + EPSILON;
    }

    /** Active NPCs whose footing may depend on this block. Reads at most four chunk buckets of the index and 64 NPCs. */
    public static List<UUID> standingOn(SpatialNpcIndex index, String world, BlockPos changed) {
        WorldPosition centre = new WorldPosition(world, new Vec3(changed.x() + .5, changed.y() + 1, changed.z() + .5));
        SpatialNpcIndex.Scan scan = index.scan(new SpatialNpcIndex.Query(centre, REACH), null, LOOKUP, (ChunkPosition chunk) -> true);
        List<UUID> found = new ArrayList<>();
        for (VirtualNpcState.Snapshot candidate : scan.candidates()) {
            Hitbox box = candidate.bounds();
            double width = Math.min(MovementProfile.MAX_WIDTH, box.max().x() - box.min().x());
            if (affects(candidate.position().point(), width, changed)) found.add(candidate.npcId());
        }
        return found;
    }
}
