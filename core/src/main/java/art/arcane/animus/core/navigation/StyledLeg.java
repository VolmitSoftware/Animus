package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;

/**
 * One leg walked in a {@link MovementStyle}, shared by every runtime: the planner profile, the distance of each step and
 * the look each committed step shows. A player body shows the style's pose and flags; other bodies show only the
 * sneaking and sprinting flags, which dim the name and raise sprint dust. With automatic swimming a body in water shows
 * the swimming pose; with automatic crawling, or the crawl style, it plans at crawl height and shows the crawling pose
 * wherever its standing height does not fit.
 */
public final class StyledLeg {
    /** Whether a body of {@code height} standing at {@code at} hits a block; read only for legs that may crawl. */
    @FunctionalInterface public interface Headroom { boolean low(Vec3 at, double height); }

    /** The pose and motion bits a body shows; see {@link BodyLook#moving}. */
    public record Look(NpcPose pose, int motion) {
        public Look { Objects.requireNonNull(pose); }
    }

    private final MovementStyle style;
    private final boolean player, autoSwim;
    private final double standingHeight;
    private final MovementProfile profile;
    private boolean inWater, low;

    private StyledLeg(MovementStyle style, boolean player, boolean autoSwim, double standingHeight, MovementProfile profile) {
        this.style = style; this.player = player; this.autoSwim = autoSwim; this.standingHeight = standingHeight; this.profile = profile;
    }

    /**
     * @param legStyle the route point's or route's style, or null for the NPC's own
     * @param base     the route profile with the NPC's movement settings applied
     */
    public static StyledLeg of(MovementSettings movement, MovementStyle legStyle, MovementProfile base, String type) {
        MovementStyle style = legStyle != null ? legStyle : movement.style();
        boolean player = BodyRules.PLAYER.equals(type);
        MovementProfile profile = style.profile(base, player, BodyMobility.sinks(type), movement.onLand(), movement.autoCrawl());
        return new StyledLeg(style, player, movement.autoSwim(), base.height(), profile);
    }

    public MovementStyle style() { return style; }
    public MovementProfile profile() { return profile; }
    /** The hull height the leg plans and checks steps with. */
    public double height() { return profile.height(); }
    /** Whether the leg plans at crawl height and so checks the headroom of each step. */
    public boolean crawls() { return profile.height() < standingHeight; }

    /** The distance of the next step at {@code speed} blocks per second, at most one block. */
    public double stepDistance(double speed) { return Math.min(1, speed * style.ratio(inWater) / 20); }

    /** The look after a committed step; the headroom is read only when the leg may crawl. */
    public Look moved(PathExecution.Proposal step, NpcPose declared, Headroom headroom) {
        inWater = step.medium() == MotionValidator.Medium.WATER;
        low = crawls() && headroom.low(step.to(), standingHeight);
        return look(declared, true, !step.onGround());
    }

    /** The look while the body waits on its route between legs. */
    public Look resting(NpcPose declared) { return look(declared, false, false); }

    /** The look once the route ends: the definition's own pose, or crawling while the body lies in a gap too low to stand in. */
    public Look finished(NpcPose declared) { return player && low ? new Look(NpcPose.SWIMMING, 0) : new Look(declared, 0); }

    Look look(NpcPose declared, boolean moving, boolean airborne) {
        if (!player) {
            int motion = style == MovementStyle.SNEAK ? BodyLook.SNEAKING : style == MovementStyle.SPRINT && moving ? BodyLook.SPRINTING : 0;
            return new Look(declared, motion);
        }
        if (low || style == MovementStyle.CRAWL) return new Look(NpcPose.SWIMMING, 0);
        if (inWater && moving && (style == MovementStyle.SWIM || autoSwim)) return new Look(NpcPose.SWIMMING, BodyLook.SPRINTING);
        return switch (style) {
            case SNEAK -> new Look(NpcPose.SNEAKING, 0);
            case SPRINT -> new Look(declared, moving ? BodyLook.SPRINTING : 0);
            case GLIDE -> new Look(declared, moving && airborne ? BodyLook.GLIDING : 0);
            default -> new Look(declared, 0);
        };
    }
}
