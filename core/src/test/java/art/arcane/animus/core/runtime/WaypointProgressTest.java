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
    private static final String FOUR = "{\"x\":0,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":0},{\"x\":8,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":4}";
    private static final UUID ALPHA = UUID.fromString("00000000-0000-0000-0000-00000000000a"), BRAVO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    @Test void npcSeedRepeatsPerNpcAndRouteAndDiffersBetweenNpcs() throws Exception {
        WaypointSettings shared = route("\"order\":\"random\",\"points\":[" + FOUR + "]");
        List<Integer> alpha = walk(WaypointProgress.of(shared, HOME, ALPHA, "market"), 40);
        assertEquals(alpha, walk(WaypointProgress.of(shared, HOME, ALPHA, "market"), 40), "the same NPC on the same route makes the same choices after a restart");
        assertNotEquals(alpha, walk(WaypointProgress.of(shared, HOME, BRAVO, "market"), 40), "another NPC makes its own choices");
        assertNotEquals(alpha, walk(WaypointProgress.of(shared, HOME, ALPHA, "inline"), 40), "the route identity is part of the seed");
        WaypointSettings clock = route("\"seed\":\"random\",\"order\":\"random\",\"points\":[" + FOUR + "]");
        Set<List<Integer>> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(walk(WaypointProgress.of(clock, HOME, ALPHA, "market"), 40));
        assertTrue(seen.size() > 1, "seed random mixes the clock");
    }
    @Test void randomStartOffsetPicksAPointAndAWaitUpToTheRouteWait() throws Exception {
        WaypointSettings spread = route("\"startOffset\":\"random\",\"loop\":true,\"waitTicks\":30,\"points\":[" + FOUR + "]");
        Set<Integer> starts = new HashSet<>(), waits = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            WaypointProgress.Leg first = progress(spread, seed).start(HOME).orElseThrow();
            starts.add(first.arrival()); waits.add(first.waitTicks());
            assertTrue(first.waitTicks() >= 0 && first.waitTicks() <= 30);
        }
        assertEquals(Set.of(0, 1, 2, 3), starts);
        assertTrue(waits.size() > 8, "first waits are spread: " + waits);
        WaypointProgress walking = progress(spread, 5);
        WaypointProgress.Leg leg = walking.start(HOME).orElseThrow();
        WaypointProgress.Leg after = walking.arrived(leg, leg.goal()).orElseThrow();
        assertEquals((leg.arrival() + 1) % 4, after.arrival()); assertEquals(30, after.waitTicks());
        assertEquals(0, progress(route("\"loop\":true,\"waitTicks\":30,\"points\":[" + FOUR + "]"), 5).start(HOME).orElseThrow().arrival());
    }
    @Test void waitRangesDrawEachArrivalWithinTheirBounds() throws Exception {
        WaypointProgress progress = progress(route("\"loop\":true,\"waitTicks\":[10,12],\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"waitTicks\":[0,1]},{\"x\":2,\"y\":1,\"z\":0}]"), 3);
        Set<Integer> route = new HashSet<>(), point = new HashSet<>();
        WaypointProgress.Leg leg = progress.start(HOME).orElseThrow();
        route.add(leg.waitTicks());
        for (int i = 0; i < 200; i++) {
            WaypointProgress.Leg next = progress.arrived(leg, leg.goal()).orElseThrow();
            (leg.arrival() == 0 ? point : route).add(next.waitTicks());
            leg = next;
        }
        assertEquals(Set.of(10, 11, 12), route); assertEquals(Set.of(0, 1), point);
    }
    @Test void jitterOffsetsTheGoalByWholeBlocksAndFallsBackToTheExactPointOnce() throws Exception {
        WaypointSettings jittered = route("\"loop\":true,\"jitter\":3,\"points\":[{\"x\":10.5,\"y\":1,\"z\":-4.5},{\"x\":0.5,\"y\":1,\"z\":0.5,\"jitter\":0}]");
        WaypointProgress progress = progress(jittered, 7);
        Set<Vec3> goals = new HashSet<>();
        WaypointProgress.Leg leg = progress.start(HOME).orElseThrow();
        for (int i = 0; i < 200; i++) {
            Vec3 point = jittered.points().get(leg.arrival()).position();
            assertEquals(point, leg.point());
            double dx = leg.goal().x() - point.x(), dz = leg.goal().z() - point.z();
            assertEquals(Math.rint(dx), dx); assertEquals(Math.rint(dz), dz); assertEquals(point.y(), leg.goal().y());
            assertTrue(dx * dx + dz * dz <= 9, leg.goal() + " near " + point);
            if (leg.arrival() == 0) goals.add(leg.goal()); else assertFalse(leg.jittered());
            leg = progress.arrived(leg, leg.goal()).orElseThrow();
        }
        assertTrue(goals.size() > 10, "jitter spreads goals: " + goals.size());
        while (!leg.jittered()) leg = progress.arrived(leg, leg.goal()).orElseThrow();
        WaypointProgress.Leg exact = progress.exact(leg).orElseThrow();
        assertEquals(leg.point(), exact.goal()); assertEquals(leg.arrival(), exact.arrival()); assertEquals(0, exact.waitTicks()); assertFalse(exact.jittered());
        assertTrue(progress.exact(exact).isEmpty(), "the exact point is the last fallback");
        WaypointProgress.Leg replaced = leg;
        assertThrows(IllegalStateException.class, () -> progress.arrived(replaced, replaced.goal()));
        assertEquals(1, progress.arrived(exact, exact.goal()).orElseThrow().arrival());
    }
    @Test void randomOrderFollowsPointWeights() throws Exception {
        WaypointProgress progress = progress(route("\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"weight\":1000},{\"x\":4,\"y\":1,\"z\":0},{\"x\":8,\"y\":1,\"z\":0,\"weight\":1000}]"), 11);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int arrival : walk(progress, 600)) counts.merge(arrival, 1, Integer::sum);
        assertTrue(counts.getOrDefault(1, 0) < 20, "the light point is rarely chosen: " + counts);
        assertTrue(counts.get(0) > 250 && counts.get(2) > 250, counts.toString());
    }
    @Test void junctionsDrawTheNextPointByWeightAndLoopOnlyFromPlainPoints() throws Exception {
        WaypointSettings fork = route("""
                "loop":true,"points":[{"x":0,"y":1,"z":0,"next":[{"to":1,"weight":3},{"to":2}]},{"x":2,"y":1,"z":0,"next":[{"to":3}]},
                {"x":4,"y":1,"z":0},{"x":6,"y":1,"z":0}]""");
        List<Integer> walked = walk(progress(fork, 13), 400);
        Map<Integer, Integer> afterStart = new HashMap<>();
        for (int i = 0; i + 1 < walked.size(); i++) {
            int at = walked.get(i), next = walked.get(i + 1);
            if (at == 0) { assertTrue(next == 1 || next == 2); afterStart.merge(next, 1, Integer::sum); }
            if (at == 1) assertEquals(3, next);
            if (at == 2) assertEquals(3, next);
            if (at == 3) assertEquals(0, next, "loop returns to 0 from the last plain point");
        }
        assertTrue(afterStart.get(1) > afterStart.get(2) * 2, afterStart.toString());
        assertEquals(walked, walk(progress(fork, 13), 400));
        WaypointProgress once = progress(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":2}]},{\"x\":2,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":0}]"), 1);
        assertEquals(List.of(0, 2), walk(once, 10)); assertTrue(once.complete());
    }
    private static final String STEP = "{\"recording\":\"walk\",\"fromTick\":10,\"toTick\":50}";
    /** Like {@link #walk}, but a step leg arrives where its segment ended, here at x 100. */
    private static List<String> legs(WaypointProgress progress, int legs) {
        List<String> walked = new ArrayList<>();
        Optional<WaypointProgress.Leg> leg = progress.start(HOME);
        while (leg.isPresent() && walked.size() < legs) {
            WaypointProgress.Leg current = leg.get();
            if (current.pause()) { leg = progress.resume(current, HOME); continue; }
            walked.add((current.step() == null ? "p" : "s") + current.arrival());
            leg = progress.arrived(current, current.step() == null ? current.goal() : new Vec3(100, 1, 0));
        }
        return walked;
    }
    @Test void aStepLegHasNoGoalAndCarriesItsSegmentInSequence() throws Exception {
        WaypointSettings mixed = route("\"waitTicks\":5,\"jitter\":3,\"points\":[{\"x\":0,\"y\":1,\"z\":0}," + STEP.replace("}", ",\"waitTicks\":30}") + ",{\"x\":4,\"y\":1,\"z\":0}]");
        WaypointProgress progress = progress(mixed, 1);
        WaypointProgress.Leg first = progress.start(HOME).orElseThrow();
        WaypointProgress.Leg step = progress.arrived(first, first.goal()).orElseThrow();
        assertEquals(1, step.arrival()); assertNull(step.goal()); assertFalse(step.pause()); assertFalse(step.jittered());
        assertEquals(new WaypointSettings.Step("walk", 10, 50), step.step()); assertEquals(5, step.waitTicks(), "the wait before a step is the previous point's");
        assertTrue(progress.current(step)); assertTrue(progress.exact(step).isEmpty(), "a step has no exact-point fallback");
        WaypointProgress.Leg last = progress.arrived(step, new Vec3(100, 1, 0)).orElseThrow();
        assertEquals(2, last.arrival()); assertEquals(30, last.waitTicks(), "the step's own wait applies after it");
        assertFalse(progress.current(step));
        assertEquals(List.of("p0", "s1", "p2", "p0", "s1"), legs(progress(route("\"loop\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0}," + STEP + ",{\"x\":4,\"y\":1,\"z\":0}]"), 1), 5));
        WaypointProgress halted = progress(mixed, 1);
        WaypointProgress.Leg start = halted.start(HOME).orElseThrow();
        WaypointProgress.Leg blocked = halted.arrived(start, start.goal()).orElseThrow();
        assertTrue(halted.failed(blocked).isEmpty(), "a blocked step halts a sequence like any failed leg");
    }
    @Test void stepsTakePartInPingpongAndJunctions() throws Exception {
        assertEquals(List.of("s0", "p1", "s2", "p1", "s0", "p1"),
                legs(progress(route("\"order\":\"pingpong\",\"points\":[" + STEP + ",{\"x\":0,\"y\":1,\"z\":0}," + STEP + "]"), 1), 6));
        WaypointSettings fork = route("\"loop\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":1},{\"to\":2}]}," + STEP.replace("}", ",\"next\":[{\"to\":0}]}")
                + ",{\"x\":4,\"y\":1,\"z\":0}]");
        List<String> walked = legs(progress(fork, 13), 200);
        assertTrue(walked.contains("s1") && walked.contains("p2"), walked.toString());
        for (int i = 0; i + 1 < walked.size(); i++) if (walked.get(i).equals("s1")) assertEquals("p0", walked.get(i + 1), "the step's junction leads back to 0");
    }
    @Test void randomOrderGoesToAStepDirectlyAndLeavesItFromWhereItEnded() throws Exception {
        WaypointSettings guided = route("\"order\":\"random\",\"linkDistance\":3,\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":8,\"y\":1,\"z\":0},"
                + STEP.replace("}", ",\"weight\":5}") + "],\"guides\":[{\"x\":2,\"y\":1,\"z\":0},{\"x\":4,\"y\":1,\"z\":0},{\"x\":6,\"y\":1,\"z\":0}]");
        List<String> walked = legs(progress(guided, 3), 300);
        long steps = walked.stream().filter((String leg) -> leg.equals("s2")).count();
        assertTrue(steps > 40, "the heavy step is chosen often: " + walked);
        for (int i = 0; i + 1 < walked.size(); i++) assertFalse(walked.get(i).equals("s2") && walked.get(i + 1).equals("s2"), "a step never follows itself");
        assertEquals(walked, legs(progress(guided, 3), 300));
        assertEquals(List.of("s0"), legs(progress(route("\"order\":\"random\",\"points\":[" + STEP + "]"), 1), 1));
    }
}
