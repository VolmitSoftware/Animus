package art.arcane.animus.core.control;

import art.arcane.animus.core.navigation.BodyTerrain;
import art.arcane.animus.core.navigation.Maneuver;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Objects;

/**
 * Resolves one proposed ride step against real surface heights. A walking body settles on the highest surface it can
 * climb to, up to its jump height, or drops at most three blocks; a swimming body stays at the water surface; a flying
 * body moves freely through clear air and settles on what it flies into. Client coordinates are never an input.
 */
public final class RideSteering {
    @FunctionalInterface public interface Probe {
        /** Moves from {@code from} towards the horizontal position of {@code to}; flying bodies also use its height. */
        Result check(Vec3 from, Vec3 to);
    }
    public enum Outcome { MOVED, BLOCKED, TERRAIN_UNAVAILABLE }
    /** {@code grounded} is false while the body flies or swims. */
    public record Result(Outcome outcome, Vec3 position, boolean grounded, String detail) {}
    public static final int MAX_DROP = 3;
    public static final double DISMOUNT_CLEARANCE = 0.5;
    private static final double LANDING_DROP = 0.25;
    private RideSteering() {}

    public static Probe probe(MovementProfile body, int maximumCells, MotionValidator.Sampler terrain) {
        Objects.requireNonNull(body); Objects.requireNonNull(terrain);
        BodyTerrain ground = new BodyTerrain(body.width(), body.height(), body.supports(MovementProfile.Mode.SWIM), maximumCells, terrain);
        Steering steering = new Steering(body, ground, maximumCells, terrain);
        return body.supports(MovementProfile.Mode.FLY) ? steering::fly : steering::walk;
    }

    private record Steering(MovementProfile body, BodyTerrain ground, int cells, MotionValidator.Sampler terrain) {
        Result walk(Vec3 from, Vec3 to) {
            double climb = Math.max(body.stepHeight(), body.jumpHeight());
            BodyTerrain.Rest rest = ground.rest(to.x(), to.z(), from.y(), climb, MAX_DROP, false);
            // A body already touching a hazard may still move; the clearance check refuses any hazard it was not touching.
            if (rest.kind() == BodyTerrain.Kind.HAZARD && ground.rest(from.x(), from.z(), from.y(), 0, 0, false).kind() == BodyTerrain.Kind.HAZARD)
                rest = ground.rest(to.x(), to.z(), from.y(), climb, MAX_DROP, true);
            if (rest.kind() == BodyTerrain.Kind.UNAVAILABLE) return new Result(Outcome.TERRAIN_UNAVAILABLE, from, true, rest.detail());
            if (!rest.usable()) return new Result(Outcome.BLOCKED, from, true, rest.detail());
            boolean swimming = rest.kind() == BodyTerrain.Kind.SWIM;
            if (!swimming && !body.supports(MovementProfile.Mode.WALK)) return new Result(Outcome.BLOCKED, from, false, "This body cannot leave the water");
            Vec3 target = new Vec3(to.x(), rest.feet(), to.z());
            Result legs = follow(from, Maneuver.legs(from, target, Math.max(from.y(), target.y()), swimming, swimming, body), !swimming);
            return legs != null ? legs : new Result(Outcome.MOVED, target, !swimming, swimming ? "swimming" : "ground");
        }
        Result fly(Vec3 from, Vec3 to) {
            MotionValidator.Result air = MotionValidator.check(from, to, body.width(), body.height(), MotionValidator.Medium.AIR, false, cells, terrain);
            if (air.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return new Result(Outcome.TERRAIN_UNAVAILABLE, from, false, air.detail());
            if (air.clear()) return new Result(Outcome.MOVED, to, supported(to), "flying");
            // Flying into a surface settles on it, or steps over a low edge.
            BodyTerrain.Rest rest = ground.rest(to.x(), to.z(), to.y(), body.stepHeight(), LANDING_DROP, false);
            if (rest.kind() == BodyTerrain.Kind.UNAVAILABLE) return new Result(Outcome.TERRAIN_UNAVAILABLE, from, false, rest.detail());
            if (rest.kind() != BodyTerrain.Kind.GROUND) return new Result(Outcome.BLOCKED, from, supported(from), air.detail());
            Vec3 target = new Vec3(to.x(), rest.feet(), to.z());
            Result legs = follow(from, Maneuver.legs(from, target, Math.max(from.y(), target.y()), false, false, body), true);
            return legs != null ? legs : new Result(Outcome.MOVED, target, true, "landed");
        }
        /** @return null when every leg is clear, otherwise the refusal */
        private Result follow(Vec3 from, List<Maneuver.Leg> legs, boolean grounded) {
            Vec3 previous = from;
            for (Maneuver.Leg leg : legs) {
                MotionValidator.Result moved = MotionValidator.check(previous, leg.target(), body.width(), body.height(), leg.medium(), leg.support(), cells, terrain);
                if (moved.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return new Result(Outcome.TERRAIN_UNAVAILABLE, from, grounded, moved.detail());
                if (!moved.clear()) return new Result(Outcome.BLOCKED, from, grounded, moved.detail());
                if (leg.landing()) {
                    MotionValidator.Result settled = MotionValidator.check(leg.target(), leg.target(), body.width(), body.height(), leg.landingMedium(),
                            leg.landingMedium() == MotionValidator.Medium.GROUND, cells, terrain);
                    if (settled.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return new Result(Outcome.TERRAIN_UNAVAILABLE, from, grounded, settled.detail());
                    if (!settled.clear()) return new Result(Outcome.BLOCKED, from, grounded, settled.detail());
                }
                previous = leg.target();
            }
            return null;
        }
        private boolean supported(Vec3 at) {
            return MotionValidator.check(at, at, body.width(), body.height(), MotionValidator.Medium.AMPHIBIOUS_AIR, true, cells, terrain).clear();
        }
    }

    /** Places to stand after dismounting, nearest the rider's sides first. Each still needs a live ground check. */
    public static List<Vec3> dismountCandidates(Vec3 feet, float yaw, double halfWidth) {
        double reach = halfWidth + DISMOUNT_CLEARANCE, radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double leftX = forwardZ, leftZ = -forwardX;
        return List.of(
                new Vec3(feet.x() + leftX * reach, feet.y(), feet.z() + leftZ * reach),
                new Vec3(feet.x() - leftX * reach, feet.y(), feet.z() - leftZ * reach),
                new Vec3(feet.x() - forwardX * reach, feet.y(), feet.z() - forwardZ * reach),
                new Vec3(feet.x() + forwardX * reach, feet.y(), feet.z() + forwardZ * reach));
    }
}
