package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.recording.RecordingPose;
import art.arcane.animus.core.virtual.Vec3;

/** The step and look rules of recording playback, shared by every runtime so a pose plays the same everywhere. */
public final class RecordingSteps {
    /** A tick may move at most this far, a fast elytra glide; a longer move is refused and caught up instead. */
    public static final double MAXIMUM_STEP = 4 * MotionValidator.MAX_SEGMENT;

    private RecordingSteps() {}

    /**
     * Ticks a walk to a recording pose may take before its goal counts as unreachable: three times the straight walk,
     * plus ten seconds to plan. A walk that wanders round an endless wall fails instead of searching for ever.
     */
    public static long walkTicks(double distance, double blocksPerSecond) { return 200 + (long) Math.ceil(3 * distance / blocksPerSecond * 20); }

    /** Segments a step of {@code distance} is checked in, each at most {@link MotionValidator#MAX_SEGMENT}. */
    public static int segments(double distance) { return Math.max(1, (int) Math.ceil(distance / MotionValidator.MAX_SEGMENT - 1e-9)); }

    /** The collision cells checking a step of {@code distance} may read. */
    public static int cells(double hullWidth, double hullHeight, double distance) {
        int segments = segments(Math.min(distance, MAXIMUM_STEP));
        return segments * RouteRules.motionCells(hullWidth, hullHeight, Math.min(distance / segments, MotionValidator.MAX_SEGMENT));
    }

    /**
     * Checks the body moving from {@code from} to the pose: clear of blocks and hazards the whole way, on ground where the
     * recorder stood on ground, in water where it swam. Sampled cells add up over the segments.
     */
    public static MotionValidator.Result check(Vec3 from, RecordingPose pose, double hullWidth, double hullHeight, MotionValidator.Sampler terrain) {
        Vec3 to = new Vec3(pose.x(), pose.y(), pose.z());
        double distance = from.distance(to);
        if (distance > MAXIMUM_STEP) return new MotionValidator.Result(MotionValidator.Status.UNSUPPORTED_GEOMETRY, 0, "Recorded step is longer than " + MAXIMUM_STEP + " blocks");
        MotionValidator.Medium medium = pose.swimming() ? MotionValidator.Medium.WATER : MotionValidator.Medium.AMPHIBIOUS_AIR;
        boolean support = pose.onGround() && !pose.swimming() && !pose.gliding();
        int segments = segments(distance), sampled = 0;
        Vec3 start = from;
        for (int i = 1; i <= segments; i++) {
            Vec3 end = i == segments ? to : from.add(to.subtract(from).multiply((double) i / segments));
            int budget = RouteRules.motionCells(hullWidth, hullHeight, start.distance(end));
            MotionValidator.Result result = MotionValidator.check(start, end, hullWidth, hullHeight, medium, support && i == segments, budget, terrain);
            sampled += result.sampledCells();
            if (!result.clear()) return new MotionValidator.Result(result.status(), sampled, result.detail());
            start = end;
        }
        return new MotionValidator.Result(MotionValidator.Status.CLEAR, sampled, "clear");
    }

    /** The body pose a recorded pose shows: sneaking or swimming, otherwise the definition's own. */
    public static NpcPose pose(RecordingPose pose, NpcPose declared) {
        if (pose.swimming()) return NpcPose.SWIMMING;
        if (pose.sneaking()) return NpcPose.SNEAKING;
        return declared == NpcPose.SNEAKING || declared == NpcPose.SWIMMING ? NpcPose.STANDING : declared;
    }

    /**
     * The shared-flag bits a recorded pose adds to the body: sprinting and gliding on a player body, whose pose shows the
     * sneak; sneaking and sprinting on any other body, which has no sneaking pose.
     */
    public static int motion(RecordingPose pose, boolean player) {
        if (!player) return (pose.sneaking() ? BodyLook.SNEAKING : 0) | (pose.sprinting() ? BodyLook.SPRINTING : 0);
        return (pose.sprinting() ? BodyLook.SPRINTING : 0) | (pose.gliding() ? BodyLook.GLIDING : 0);
    }
}
