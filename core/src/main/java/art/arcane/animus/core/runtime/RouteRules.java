package art.arcane.animus.core.runtime;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.BodyTerrain;
import art.arcane.animus.core.navigation.LongLeg;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;

/** Stateless route and body rules shared by every runtime: the single owner thread and Folia's region owners. */
public final class RouteRules {
    /** Fastest route leg in blocks per second; combat pursuit is limited further by {@code [combat] maximumSpeed}. */
    public static final double MAXIMUM_SPEED = 10;
    /** How long a leg waits after a failed plan or a blocked step before it is planned again. */
    public static final int REPLAN_BACKOFF_TICKS = 10;
    /** How many blocks above a buried waypoint a stuck teleport looks for room, as Citizens' stuck teleport does. */
    public static final int STUCK_LIFT = 10;
    /** Collision cells one step may check; a route waits for a later tick when fewer are left. */
    static final int MOTION_CELLS = 256;
    private static final int STUCK_CELLS = 1024;
    private static final double HULL_WIDTH_LIMIT = 4, HULL_HEIGHT_LIMIT = 8;

    private RouteRules() {}

    /** Terrain checks use a central hull. A dragon's outstretched wings and tail are presentation, so they may overhang terrain. */
    public static double hullWidth(double bodyWidth) { return Math.min(HULL_WIDTH_LIMIT, bodyWidth); }
    public static double hullHeight(double bodyHeight) { return Math.min(HULL_HEIGHT_LIMIT, bodyHeight); }
    /** A small body keeps the flat per-move budget; a large hull gets the most its sweep can read, which stays bounded by the hull limits. */
    public static int motionCells(double hullWidth, double hullHeight, double distance) {
        return Math.max(MOTION_CELLS, MotionValidator.cellBudget(hullWidth, hullHeight, distance));
    }
    /**
     * One route leg in the NPC's own world. A leg of any length is planned as a {@link LongLeg}, one bounded capture at
     * a time.
     */
    public static void validateLeg(WorldPosition origin, WorldPosition destination, MovementProfile.Mode mode, double speed, double hullWidth, double hullHeight) {
        if (!Double.isFinite(speed) || speed < 1 || speed > MAXIMUM_SPEED) throw new IllegalArgumentException("Navigation speed must be 1..10 blocks/second");
        if (!origin.world().equals(destination.world())) throw new IllegalArgumentException("Navigation requires the same world");
        // The origin may rest on a partial surface (path, farmland, slab) where an earlier route ended; the goal names a block.
        if (mode == MovementProfile.Mode.WALK && destination.point().y() != Math.rint(destination.point().y()))
            throw new IllegalArgumentException("Walking goals require integer feet height");
        // Refuses a goal outside the world's coordinate bounds.
        BlockPos.containing(destination.point());
    }
    /**
     * Where a stuck leg's teleport puts the body. A walking or swimming body stands on the footing of its waypoint's
     * block, on top of it when the waypoint names the ground under the feet. A waypoint buried in terrain that changed
     * since it was recorded lifts to the first place the body fits up to {@link #STUCK_LIFT} blocks above it, as Citizens'
     * stuck teleport does. A flyer, or terrain that cannot be read, keeps the waypoint itself.
     */
    public static WorldPosition stuckTarget(WorldPosition goal, MovementProfile.Mode mode, double hullWidth, double hullHeight, MotionValidator.Sampler terrain) {
        if (mode == MovementProfile.Mode.FLY) return goal;
        Vec3 point = goal.point();
        int block = (int) Math.floor(point.y());
        BodyTerrain body = new BodyTerrain(hullWidth, hullHeight, mode == MovementProfile.Mode.SWIM, STUCK_CELLS, terrain);
        BodyTerrain.Rest rest = body.footing(point.x(), point.z(), block);
        for (int lift = 1; !rest.usable() && rest.kind() != BodyTerrain.Kind.UNAVAILABLE && lift < STUCK_LIFT; lift++)
            rest = body.onTop(point.x(), point.z(), block + lift);
        return rest.usable() ? new WorldPosition(goal.world(), new Vec3(point.x(), rest.feet(), point.z())) : goal;
    }
    /**
     * The routine diagnostic kind of a blocked leg, counted per NPC while debug mode is off: terrain that blocks it, or a
     * leg no plan found a way for.
     */
    public static String blockedLegs(boolean terrain) {
        return terrain ? "NPCs hit route legs blocked by terrain" : "NPCs hit route legs the planner could not path";
    }
    /** The one log line for an NPC whose leg is blocked. An NPC whose movement teleports when stuck goes on from the point instead of stopping. */
    public static String blockedLeg(String npc, int waypoint, Vec3 from, WorldPosition to, String reason, boolean teleports, boolean terrain) {
        return "NPC " + npc + (teleports ? " teleported" : " stopped") + (terrain ? ": terrain blocks its route leg" : ": the planner found no path for its route leg")
                + (waypoint < 0 ? "" : " to waypoint " + waypoint)
                + " from " + block(from) + " to " + block(to.point()) + " in " + to.world() + " (" + reason + "); later blocked legs of this NPC are not logged";
    }
    /** The latest blocked leg as the summary repeats it: the NPC, the waypoint the leg led to and the reason. */
    public static String blockedLegCase(String npc, int waypoint, String reason) {
        return npc + (waypoint < 0 ? "" : " at waypoint " + waypoint) + ", " + reason;
    }
    /** The status detail of a route walking a planned path. */
    public static String moving(boolean segment) {
        return segment ? "kinematic; live collision revalidation; long leg segment" : "kinematic; live collision revalidation";
    }
    public static double maximumHealth(DefinitionSpec<?> spec) {
        return HealthSettings.maximum(spec.definition().json());
    }
    /** The body size multiplier: hitbox, reach and path clearance follow it, as the viewers' clients draw it. */
    public static double scale(DefinitionSpec<?> spec) { return spec.appearance().traits().attributes().scale(); }
    private static String block(Vec3 point) {
        return (int) Math.floor(point.x()) + " " + (int) Math.floor(point.y()) + " " + (int) Math.floor(point.z());
    }
}
