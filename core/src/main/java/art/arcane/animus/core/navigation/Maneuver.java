package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Kinematic legs for one move between two resting places. A climb rises in place to the highest point of the crossing,
 * moves across and settles; a descent moves across and then drops. Planning and execution use the same legs, so a
 * planned route is checked with the clearance test that will run before each committed step.
 */
public final class Maneuver {
    /** One straight segment. A landing leg is checked once more at its end against {@code landingMedium}. */
    public record Leg(Vec3 target, MotionValidator.Medium medium, boolean support, boolean landing,
                      MotionValidator.Medium landingMedium, boolean onGround) {}
    private static final double EPSILON = MotionValidator.EPSILON, PIECE_LIMIT = 1.5;
    private Maneuver() {}

    public static List<Leg> legs(Vec3 from, Vec3 to, double peak, boolean fromSwimming, boolean toSwimming, MovementProfile profile) {
        List<Leg> legs = new ArrayList<>(3);
        if (profile.supports(MovementProfile.Mode.FLY)) {
            add(legs, from, new Leg(to, MotionValidator.Medium.AIR, false, false, MotionValidator.Medium.AIR, false));
            return legs;
        }
        if (!profile.supports(MovementProfile.Mode.WALK)) {
            add(legs, from, new Leg(to, MotionValidator.Medium.WATER, false, false, MotionValidator.Medium.WATER, false));
            return legs;
        }
        MotionValidator.Medium loose = profile.supports(MovementProfile.Mode.SWIM) ? MotionValidator.Medium.AMPHIBIOUS_AIR : MotionValidator.Medium.GROUND;
        MotionValidator.Medium settle = toSwimming ? MotionValidator.Medium.WATER : MotionValidator.Medium.GROUND;
        double top = Math.max(peak, Math.max(from.y(), to.y()));
        boolean level = Math.abs(from.y() - to.y()) <= EPSILON && top - from.y() <= EPSILON;
        if (level && fromSwimming == toSwimming) {
            add(legs, from, toSwimming ? new Leg(to, MotionValidator.Medium.WATER, false, false, settle, false)
                    : new Leg(to, MotionValidator.Medium.GROUND, true, false, settle, true));
            return legs;
        }
        Vec3 raised = new Vec3(from.x(), top, from.z()), across = new Vec3(to.x(), top, to.z());
        add(legs, from, new Leg(raised, loose, false, false, settle, false));
        boolean drops = top - to.y() > EPSILON;
        add(legs, raised, new Leg(across, loose, false, !drops, settle, !drops && !toSwimming));
        if (drops) add(legs, across, new Leg(to, loose, false, true, settle, !toSwimming));
        return legs;
    }
    /** A straight climb up or down a ladder, vine or scaffolding: it needs no ground, only something to hold or stand on. */
    public static List<Leg> climb(Vec3 from, Vec3 to) {
        List<Leg> legs = new ArrayList<>(2);
        add(legs, from, new Leg(to, MotionValidator.Medium.CLIMB, false, false, MotionValidator.Medium.CLIMB, false));
        return legs;
    }
    /** Long drops and climbs are split into pieces of at most one block, so every check stays small. */
    private static void add(List<Leg> legs, Vec3 from, Leg leg) {
        double distance = from.distance(leg.target());
        if (distance <= EPSILON) return;
        int pieces = distance > PIECE_LIMIT ? (int) Math.ceil(distance - EPSILON) : 1;
        for (int i = 1; i < pieces; i++)
            legs.add(new Leg(from.add(leg.target().subtract(from).multiply((double) i / pieces)), leg.medium(), leg.support(), false, leg.landingMedium(), false));
        legs.add(leg);
    }
}
