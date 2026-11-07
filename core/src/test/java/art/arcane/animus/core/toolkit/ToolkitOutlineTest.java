package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolkitOutlineTest {
    static ScreenHost.Pose pose(double x, double z, boolean sneaking) { return new ScreenHost.Pose(Set.of("world", "minecraft:overworld"), x, 64, z, 65.62, 0, 0, sneaking); }

    static VirtualNpcState.Snapshot npc(String world, double x, double z) {
        Vec3 at = new Vec3(x, 64, z);
        return new VirtualNpcState.Snapshot(UUID.randomUUID(), new WorldPosition(world, at), new Hitbox(at.add(new Vec3(-0.3, 0, -0.3)), at.add(new Vec3(0.3, 1.8, 0.3))), 20, true, 1);
    }

    @Test void eachNpcInRangeGetsItsEightCornersAndFarOrOtherWorldNpcsNone() {
        List<Vec3> points = ToolkitOutline.outline(pose(0, 0, false), List.of(npc("world", 5, 5), npc("world", 100, 0), npc("nether", 1, 1)));
        assertEquals(ToolkitOutline.POINTS, points.size());
        assertTrue(points.contains(new Vec3(4.7, 64, 4.7)));
        assertTrue(points.contains(new Vec3(5.3, 65.8, 5.3)));
    }

    @Test void theOutlineDrawsOnlyTheNearestNpcsWithinTheBudget() {
        List<VirtualNpcState.Snapshot> many = new ArrayList<>();
        for (int i = 0; i < ToolkitOutline.NPCS + 10; i++) many.add(npc("world", i, 0));
        List<Vec3> points = ToolkitOutline.outline(pose(0, 0, false), many);
        assertEquals(ToolkitOutline.NPCS * ToolkitOutline.POINTS, points.size());
        assertFalse(points.stream().anyMatch((Vec3 point) -> point.x() > ToolkitOutline.NPCS), "the farthest are left out");
    }

    @Test void captureSnapsToBlockCentresSavesAValidRouteAndUndoes() throws Exception {
        WaypointCapture capture = new WaypointCapture(pose(0, 0, false).worlds());
        assertEquals(1, capture.add(pose(10.2, -3.9, false)));
        assertEquals(2, capture.add(pose(20.7, 4.1, false)));
        assertEquals(new Vec3(10.5, 64, -3.5), capture.points().getFirst());
        assertTrue(capture.removeLast());
        assertEquals(1, capture.points().size());
        capture.add(pose(30, 30, false));
        WaypointSettings route = WaypointSettings.document(capture.document());
        assertEquals(2, route.points().size());
        assertTrue(route.loop());
        assertFalse(capture.in(new ScreenHost.Pose(Set.of("nether"), 0, 64, 0, 65, 0, 0, false)));
        assertThrows(IllegalStateException.class, () -> capture.add(new ScreenHost.Pose(Set.of("nether"), 0, 64, 0, 65, 0, 0, false)));
    }

    @Test void captureIsCappedAndShowsOnlyNearbyPoints() {
        WaypointCapture capture = new WaypointCapture(Set.of("world"));
        for (int i = 0; i < WaypointSettings.MAXIMUM_POINTS; i++) capture.add(pose(i % 40, i < 200 ? 0 : 500, false));
        assertThrows(IllegalStateException.class, () -> capture.add(pose(0, 0, false)));
        capture.select(0);
        List<ToolkitOutline.Marker> shown = ToolkitOutline.waypoints(pose(0, 0, false), capture);
        assertEquals(ToolkitOutline.WAYPOINTS, shown.size());
        assertTrue(shown.stream().allMatch((ToolkitOutline.Marker marker) -> marker.point().z() < ToolkitOutline.RANGE), "far points are left out");
    }

    @Test void theCurrentPointIsMarkedAndShownEvenPastTheBudget() {
        WaypointCapture capture = new WaypointCapture(Set.of("world"));
        for (int i = 0; i < ToolkitOutline.WAYPOINTS + 5; i++) capture.add(pose(i % 40 == 0 ? 0 : i % 40, 0, false));
        capture.select(39);
        List<ToolkitOutline.Marker> shown = ToolkitOutline.waypoints(pose(0, 0, false), capture);
        assertEquals(ToolkitOutline.WAYPOINTS, shown.size());
        assertEquals(new ToolkitOutline.Marker(capture.points().get(39), true), shown.getFirst());
        assertEquals(1, shown.stream().filter(ToolkitOutline.Marker::current).count());
    }

    @Test void anEmptyCaptureCannotBeSaved() {
        assertThrows(IllegalStateException.class, () -> new WaypointCapture(Set.of("world")).document());
    }
}
