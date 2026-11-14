package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Optional;

/** Where a player or a new NPC stands so that neither ends up inside the other, turned to face it. */
final class Landing {
    static final double GAP = 2;

    record Spot(Vec3 at, float yaw) {}

    private Landing() {}

    /** Where a new or brought NPC stands: {@link #GAP} blocks in front of the player's feet, facing them. */
    static Spot ahead(ScreenHost.Pose pose) { return ahead(new Vec3(pose.x(), pose.y(), pose.z()), pose.yaw()); }

    /** {@link #GAP} blocks ahead of {@code from} along {@code yaw}, turned back toward {@code from}. */
    static Spot ahead(Vec3 from, float yaw) {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians), dz = Math.cos(radians);
        return new Spot(new Vec3(from.x() + dx * GAP, from.y(), from.z() + dz * GAP), facing(-dx, -dz));
    }

    /** {@link #GAP} blocks from {@code npc} on the side {@code from} comes from, south when there is no side, facing the NPC. */
    static Spot beside(Vec3 npc, Optional<Vec3> from) {
        double dx = 0, dz = 1;
        if (from.isPresent()) {
            double ox = from.get().x() - npc.x(), oz = from.get().z() - npc.z(), length = Math.hypot(ox, oz);
            if (length > 0.1) { dx = ox / length; dz = oz / length; }
        }
        return new Spot(new Vec3(npc.x() + dx * GAP, npc.y(), npc.z() + dz * GAP), facing(-dx, -dz));
    }

    /** Minecraft's yaw for looking along {@code (dx, dz)}, in [-180, 180). */
    private static float facing(double dx, double dz) {
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) (yaw >= 180 ? yaw - 360 : yaw);
    }
}
