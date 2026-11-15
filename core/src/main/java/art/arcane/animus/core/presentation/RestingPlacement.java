package art.arcane.animus.core.presentation;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.virtual.Vec3;

/**
 * Where a body in a resting pose is drawn, with GSit's numbers for a player in the same pose. A sitting body's seat sits
 * on the surface under its feet; on the lower step of a stair it moves to the step's middle and faces away from the
 * step behind it. A sleeping body lies on the ground rather than half in it, as GSit's lay does; a bellyflop lies
 * lower than a crawl, and a spin turns the body upright. Hits and movement keep using the body's own position.
 */
public final class RestingPlacement {
    /** GSit's seat lift above the surface since Minecraft 1.20.2, its lift for lying down and its drop for a bellyflop, at scale 1. */
    static final double SEAT_LIFT = 0.025, LAY_LIFT = 0.1125, BELLYFLOP_DROP = 0.19;
    /** How far from the stair's middle GSit moves a seat toward the open side of the step. */
    static final double STAIR_INSET = 0.123;
    /** A surface farther than this from the feet is not the one the body rests on. */
    private static final double REACH = 0.6, EPSILON = 0.000001;

    /** A seat position; {@code turned} when a stair fixes the facing to {@code yaw}. */
    public record Seat(Vec3 at, boolean turned, float yaw) {}

    private RestingPlacement() {}

    /** The seat under these feet. Terrain that is unavailable, or nothing to sit on, leaves the seat at the feet. */
    public static Seat seat(Vec3 feet, MotionValidator.Sampler terrain) {
        int x = (int) Math.floor(feet.x()), y = (int) Math.floor(feet.y()), z = (int) Math.floor(feet.z());
        Cell at = terrain.sample(x, y, z), below = terrain.sample(x, y - 1, z);
        if (at.unavailable() || below.unavailable()) return new Seat(feet, false, 0);
        Seat seat = on(at, x, y, z, feet);
        if (seat == null) seat = on(below, x, y - 1, z, feet);
        return seat == null ? new Seat(feet, false, 0) : seat;
    }

    private static Seat on(Cell cell, int x, int y, int z, Vec3 feet) {
        if (!cell.collides() || cell.climbable()) return null;
        Seat stair = stair(cell, x, y, z);
        if (stair != null) return stair;
        double surface = y + cell.top();
        if (Math.abs(surface - feet.y()) > REACH) return null;
        return new Seat(new Vec3(feet.x(), surface + SEAT_LIFT, feet.z()), false, 0);
    }

    /**
     * The lower step of an upright stair, read from its collision columns whatever boxes they are split into: every
     * quarter of the block rises from the floor to half height or to the top, and both heights occur.
     */
    private static Seat stair(Cell cell, int x, int y, int z) {
        int low = 0, dx = 0, dz = 0;
        for (double qx : QUARTERS) for (double qz : QUARTERS) {
            double top = column(cell, qx, qz);
            if (Math.abs(top - 0.5) < EPSILON) { low++; dx += qx < 0.5 ? -1 : 1; dz += qz < 0.5 ? -1 : 1; }
            else if (Math.abs(top - 1) > EPSILON) return null;
        }
        if (low == 0 || low == 4) return null;
        // The open part of the step faces away from the raised part; the seat moves toward it and faces it.
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-sx, sz));
        return new Seat(new Vec3(x + 0.5 + STAIR_INSET * sx, y + 0.5 + SEAT_LIFT, z + 0.5 + STAIR_INSET * sz), true, yaw);
    }
    private static final double[] QUARTERS = {0.25, 0.75};
    /** How high the collision rises without a gap from the floor at this point of the block. */
    private static double column(Cell cell, double px, double pz) {
        double top = 0;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Cell.Box box : cell.boxes())
                if (box.minX() <= px && px <= box.maxX() && box.minZ() <= pz && pz <= box.maxZ() && box.minY() <= top + EPSILON && box.maxY() > top + EPSILON) { top = box.maxY(); grew = true; }
        }
        return top;
    }

    /**
     * The pose a body in {@code pose} is drawn with: a seated body at its seat, a sleeping body lifted and a bellyflop
     * lowered by their scale, a spin upright. Other poses keep {@code base}.
     */
    public static BodyPose shown(NpcPose pose, BodyPose base, double scale, Seat seat) {
        return switch (pose) {
            case SITTING -> seat == null ? base : new BodyPose(base.world(), seat.at().x(), seat.at().y(), seat.at().z(),
                    seat.turned() ? seat.yaw() : base.yaw(), seat.turned() ? seat.yaw() : base.headYaw(), base.pitch(), base.onGround());
            case SLEEPING -> new BodyPose(base.world(), base.x(), base.y() + LAY_LIFT * scale, base.z(), base.yaw(), base.headYaw(), base.pitch(), base.onGround());
            case BELLYFLOP -> new BodyPose(base.world(), base.x(), base.y() - BELLYFLOP_DROP * scale, base.z(), base.yaw(), base.headYaw(), 0, base.onGround());
            // GSit turns a spinning body to a pitch of -90, which the client draws upright instead of lying in the riptide spin.
            case SPINNING -> new BodyPose(base.world(), base.x(), base.y(), base.z(), base.yaw(), base.headYaw(), -90, base.onGround());
            default -> base;
        };
    }
}
