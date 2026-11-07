package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Where the selection outline and the captured route points draw, within a fixed budget: every {@link #PERIOD} ticks,
 * only NPCs within {@link #RANGE} blocks in the player's world, the nearest {@link #NPCS} of them, {@link #POINTS}
 * corners each, and at most {@link #WAYPOINTS} route points. The capture's current point always shows, in its own particle.
 */
public final class ToolkitOutline {
    public static final int PERIOD = 10, POINTS = 8, NPCS = 32, WAYPOINTS = 64;
    public static final double RANGE = 48;
    public static final String OUTLINE_PARTICLE = "minecraft:end_rod", WAYPOINT_PARTICLE = "minecraft:happy_villager", CURRENT_PARTICLE = "minecraft:flame";

    /** A route point to draw; {@code current} is the one inserts go after. */
    public record Marker(Vec3 point, boolean current) {}

    private ToolkitOutline() {}

    /** The corners of each selected NPC's hitbox that the player should see now. */
    public static List<Vec3> outline(ScreenHost.Pose pose, Collection<VirtualNpcState.Snapshot> selected) {
        Vec3 eye = new Vec3(pose.x(), pose.y(), pose.z());
        List<VirtualNpcState.Snapshot> near = new ArrayList<>();
        for (VirtualNpcState.Snapshot npc : selected)
            if (npc.active() && pose.in(npc.position().world()) && npc.position().point().distance(eye) <= RANGE) near.add(npc);
        near.sort(Comparator.comparingDouble((VirtualNpcState.Snapshot npc) -> npc.position().point().distance(eye)));
        List<Vec3> points = new ArrayList<>();
        for (VirtualNpcState.Snapshot npc : near.subList(0, Math.min(NPCS, near.size()))) {
            Hitbox box = npc.bounds();
            for (int corner = 0; corner < POINTS; corner++)
                points.add(new Vec3((corner & 1) == 0 ? box.min().x() : box.max().x(), (corner & 2) == 0 ? box.min().y() : box.max().y(), (corner & 4) == 0 ? box.min().z() : box.max().z()));
        }
        return points;
    }

    /** The captured route points the player should see now: the current point first, then the rest nearest first. */
    public static List<Marker> waypoints(ScreenHost.Pose pose, WaypointCapture capture) {
        if (!capture.in(pose)) return List.of();
        Vec3 at = new Vec3(pose.x(), pose.y(), pose.z());
        List<Vec3> points = capture.points();
        int current = capture.current();
        List<Marker> shown = new ArrayList<>();
        if (current >= 0 && current < points.size() && points.get(current).distance(at) <= RANGE) shown.add(new Marker(points.get(current), true));
        List<Vec3> rest = new ArrayList<>(points);
        if (!shown.isEmpty()) rest.remove(current);
        rest.stream().filter((Vec3 point) -> point.distance(at) <= RANGE).sorted(Comparator.comparingDouble((Vec3 point) -> point.distance(at)))
                .limit(WAYPOINTS - shown.size()).forEach((Vec3 point) -> shown.add(new Marker(point, false)));
        return shown;
    }
}
