package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.virtual.Vec3;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointProgressTest {
    private static final Vec3 HOME = new Vec3(0, 1, 0);
    private static WaypointSettings settings(boolean loop, int end) throws Exception {
        return WaypointSettings.document("""
                {"schemaVersion":1,"loop":%s,"points":[{"x":0,"y":1,"z":0},{"x":%d,"y":1,"z":0}]}
                """.formatted(loop, end));
    }
    private static WaypointSettings route(String fields) throws Exception {
        return WaypointSettings.document("{\"schemaVersion\":1," + fields + "}");
    }
    private static WaypointProgress progress(WaypointSettings settings, long seed) { return new WaypointProgress(settings, HOME, new SplittableRandom(seed)); }
    /** Walks legs, arriving at each goal, and returns the arrival indexes in order. */
    private static List<Integer> walk(WaypointProgress progress, int legs) {
        List<Integer> arrivals = new ArrayList<>();
        Optional<WaypointProgress.Leg> leg = progress.start(HOME);
        while (leg.isPresent() && arrivals.size() < legs) {
            WaypointProgress.Leg current = leg.get();
            assertFalse(current.pause(), "Unexpected pause");
            arrivals.add(current.arrival());
            leg = progress.arrived(current, current.goal());
        }
        return arrivals;
    }

    @Test void unchangedReloadRetainsCompletedOnceRouteAndItsLastValidData() throws Exception {
        WaypointSettings valid = settings(false, 2); WaypointProgress progress = progress(valid, 1);
        WaypointProgress.Leg first = progress.start(HOME).orElseThrow();
        WaypointProgress.Leg second = progress.arrived(first, first.goal()).orElseThrow();
        assertEquals(1, second.arrival());
        assertTrue(progress.arrived(second, second.goal()).isEmpty()); assertTrue(progress.complete());
        assertFalse(progress.reload(settings(false, 2))); assertTrue(progress.complete()); assertSame(valid, progress.settings());
        assertThrows(IllegalStateException.class, () -> progress.arrived(first, first.goal()));
        assertTrue(progress.start(HOME).isEmpty());
    }
    @Test void changedValidRouteRestartsAtFirstPointAndRejectsOldArrival() throws Exception {
        WaypointProgress progress = progress(settings(false, 2), 1);
        WaypointProgress.Leg first = progress.start(HOME).orElseThrow();
        WaypointProgress.Leg second = progress.arrived(first, first.goal()).orElseThrow();
        assertTrue(progress.reload(settings(false, 4))); assertFalse(progress.complete());
        assertThrows(IllegalStateException.class, () -> progress.arrived(second, second.goal()));
        WaypointProgress.Leg restarted = progress.start(HOME).orElseThrow();
        assertEquals(0, restarted.arrival()); assertEquals(4, progress.arrived(restarted, restarted.goal()).orElseThrow().goal().x());
    }
    @Test void loopingSequenceReturnsToTheFirstPoint() throws Exception {
        assertEquals(List.of(0, 1, 0, 1, 0), walk(progress(settings(true, 2), 1), 5));
        assertEquals(List.of(0, 1), walk(progress(settings(false, 2), 1), 5));
    }
    @Test void pingpongNeverRepeatsEndpointsAndOnePointStops() throws Exception {
        WaypointSettings four = route("\"order\":\"pingpong\",\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":1,\"y\":1,\"z\":0},{\"x\":2,\"y\":1,\"z\":0},{\"x\":3,\"y\":1,\"z\":0}]");
        assertEquals(List.of(0, 1, 2, 3, 2, 1, 0, 1, 2, 3), walk(progress(four, 1), 10));
        WaypointProgress one = progress(route("\"order\":\"pingpong\",\"points\":[{\"x\":3,\"y\":1,\"z\":0}]"), 1);
        assertEquals(List.of(0), walk(one, 5)); assertTrue(one.complete());
    }
    @Test void waitsApplyBeforeTheFirstLegAndAfterEachArrivalWithPointOverrides() throws Exception {
        WaypointProgress progress = progress(route("\"loop\":true,\"waitTicks\":7,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"waitTicks\":0},{\"x\":2,\"y\":1,\"z\":0,\"waitTicks\":300}]"), 1);
        WaypointProgress.Leg first = progress.start(HOME).orElseThrow(); assertEquals(7, first.waitTicks());
        WaypointProgress.Leg second = progress.arrived(first, first.goal()).orElseThrow(); assertEquals(0, second.waitTicks());
        assertEquals(300, progress.arrived(second, second.goal()).orElseThrow().waitTicks());
    }
    @Test void sequenceFailureHaltsWhileRandomAndWanderRetryAfterAPause() throws Exception {
        WaypointProgress sequence = progress(settings(true, 2), 1);
        WaypointProgress.Leg leg = sequence.start(HOME).orElseThrow();
        assertTrue(sequence.failed(leg).isEmpty());
        WaypointProgress random = progress(route("\"order\":\"random\",\"waitTicks\":5,\"points\":[{\"x\":4,\"y\":1,\"z\":0},{\"x\":8,\"y\":1,\"z\":0}]"), 3);
        WaypointProgress.Leg walking = random.start(HOME).orElseThrow();
        WaypointProgress.Leg pause = random.failed(walking).orElseThrow();
        assertTrue(pause.pause()); assertEquals(100, pause.waitTicks());
        WaypointProgress.Leg retry = random.resume(pause, HOME).orElseThrow();
        assertFalse(retry.pause()); assertEquals(1, retry.waitTicks());
        WaypointProgress wander = progress(route("\"order\":\"wander\",\"waitTicks\":40"), 3);
        WaypointProgress.Leg wandering = wander.start(HOME).orElseThrow();
        assertEquals(40, wander.failed(wandering).orElseThrow().waitTicks());
    }
    @Test void randomWalksThroughGuidesAndReportsGuideArrivalsAfterPoints() throws Exception {
        // Points 30 blocks apart with a 20 block link need the guide between them; the first hop must be a guide.
        WaypointSettings guided = route("""
                "order":"random","linkDistance":20,"waitTicks":9,
                "points":[{"x":0,"y":1,"z":0,"waitTicks":50},{"x":30,"y":1,"z":0}],
                "guides":[{"x":15,"y":1,"z":0},{"x":-15,"y":1,"z":0}]""");
        WaypointProgress progress = new WaypointProgress(guided, new Vec3(1, 1, 0), new SplittableRandom(11));
        WaypointProgress.Leg first = progress.start(new Vec3(1, 1, 0)).orElseThrow();
        assertTrue(first.arrival() >= 2, "First hop from a free position is a guide");
        Set<Integer> seen = new TreeSet<>();
        WaypointProgress.Leg leg = first;
        for (int i = 0; i < 40; i++) {
            seen.add(leg.arrival());
            WaypointProgress.Leg next = progress.arrived(leg, leg.goal()).orElseThrow();
            if (leg.arrival() >= 2 && !next.pause()) assertTrue(next.waitTicks() == 1 || next.waitTicks() == 9 || next.waitTicks() == 50);
            if (leg.arrival() == 0 && next.arrival() == 2) assertEquals(50, next.waitTicks(), "Destination applies its own wait");
            assertFalse(next.pause(), "Every destination is reachable");
            assertTrue(leg.goal().distance(next.goal()) <= 20, "Hops follow links");
            leg = next;
        }
        assertTrue(seen.containsAll(List.of(0, 1, 2)), seen.toString());
    }
    @Test void anIsolatedDestinationIsReachedThroughTheClosestNodeInsteadOfNever() throws Exception {
        // Destination 2 lies 170 blocks beyond everything else; the link distance is a preference, not a wall.
        WaypointSettings guided = route("""
                "order":"random","linkDistance":20,
                "points":[{"x":0,"y":1,"z":0},{"x":30,"y":1,"z":0},{"x":200,"y":30,"z":0}],
                "guides":[{"x":15,"y":1,"z":0}]""");
        WaypointProgress progress = progress(guided, 7);
        Set<Integer> seen = new TreeSet<>();
        WaypointProgress.Leg leg = progress.start(HOME).orElseThrow();
        for (int i = 0; i < 60; i++) {
            assertFalse(leg.pause(), "every destination is reachable");
            seen.add(leg.arrival());
            WaypointProgress.Leg next = progress.arrived(leg, leg.goal()).orElseThrow();
            if (next.arrival() == 2 && leg.arrival() != 2) assertEquals(1, leg.arrival(), "the long hop leaves from the closest node");
            leg = next;
        }
        assertTrue(seen.containsAll(List.of(0, 1, 2, 3)), seen.toString());
    }
    @Test void linkedHopsArePreferredOverALongHop() throws Exception {
        WaypointSettings guided = route("""
                "order":"random","linkDistance":20,
                "points":[{"x":0,"y":1,"z":0},{"x":60,"y":1,"z":0}],
                "guides":[{"x":15,"y":1,"z":0},{"x":30,"y":1,"z":0},{"x":45,"y":1,"z":0}]""");
        WaypointProgress progress = progress(guided, 3);
        WaypointProgress.Leg leg = progress.start(HOME).orElseThrow();
        for (int i = 0; i < 30; i++) {
            WaypointProgress.Leg next = progress.arrived(leg, leg.goal()).orElseThrow();
            assertTrue(leg.goal().distance(next.goal()) <= 20, "a linked path exists, so every hop follows a link");
            leg = next;
        }
    }
    @Test void randomLinksIgnoreTheOldLegBounds() throws Exception {
        WaypointSettings tall = route("\"order\":\"random\",\"mode\":\"fly\",\"linkDistance\":64,\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":0,\"y\":20,\"z\":0}]");
        // 19 blocks up was beyond the old 8 block leg and paused the route; now the points link and every leg walks.
        assertEquals(6, walk(progress(tall, 5), 6).size());
    }
    @Test void seededRandomIsDeterministic() throws Exception {
        WaypointSettings many = route("\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":0},{\"x\":8,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":4}]");
        List<Integer> walked = walk(progress(many, 42), 30);
        assertEquals(walked, walk(progress(many, 42), 30));
        for (int i = 1; i < walked.size(); i++) assertNotEquals(walked.get(i - 1), walked.get(i), "A new destination differs from the current point");
    }
    @Test void wanderTargetsStayInsideRadiusAndHeightOfAnyCentre() throws Exception {
        WaypointSettings around = route("\"order\":\"wander\",\"wander\":{\"radius\":5,\"height\":2},\"points\":[{\"x\":100,\"y\":64,\"z\":100},{\"x\":-100,\"y\":64,\"z\":-100}]");
        WaypointProgress progress = progress(around, 9);
        Vec3 at = new Vec3(100, 64, 100);
        WaypointProgress.Leg leg = progress.start(at).orElseThrow();
        for (int i = 0; i < 200; i++) {
            Vec3 goal = leg.goal();
            assertEquals(.5, goal.x() - Math.floor(goal.x())); assertEquals(goal.y(), Math.rint(goal.y()));
            Vec3 centre = around.points().get(leg.arrival()).position();
            assertTrue(Math.abs(goal.x() - .5 - centre.x()) <= 5 && Math.abs(goal.y() - centre.y()) <= 2, goal + " near " + centre);
            at = goal;
            leg = progress.arrived(leg, at).orElseThrow();
        }
        WaypointProgress home = progress(route("\"order\":\"wander\",\"wander\":{\"radius\":1,\"height\":0}"), 9);
        WaypointProgress.Leg near = home.start(HOME).orElseThrow();
        assertEquals(0, near.arrival()); assertTrue(Math.abs(near.goal().x() - .5) <= 1 && near.goal().y() == 1);
    }
}
