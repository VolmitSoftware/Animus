package art.arcane.animus.core.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RouteSchedulerTest {
    /** A patrol: the scheduler sees only its due tick and whether a player is near. */
    private static final class Patrol {
        final int id; long due; boolean watched = true; int served, slept, woke;
        Patrol(int id) { this.id = id; }
    }
    private static RouteScheduler<Patrol> scheduler(int routesPerTick, int wakeChecks) {
        return new RouteScheduler<>(routesPerTick, wakeChecks, (Patrol patrol) -> patrol.due);
    }
    private static List<Patrol> patrols(RouteScheduler<Patrol> routes, int count, long now) {
        List<Patrol> all = new ArrayList<>();
        for (int i = 0; i < count; i++) { Patrol patrol = new Patrol(i); all.add(patrol); routes.add(patrol, now); }
        return all;
    }
    private static RouteScheduler.Listener<Patrol> listener() {
        return new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return route.watched; }
            @Override public RouteScheduler.Result work(Patrol route) { route.served++; return RouteScheduler.Result.KEEP; }
            @Override public void slept(Patrol route) { route.slept++; }
            @Override public void woke(Patrol route) { route.woke++; }
        };
    }

    @Test void shouldLetEveryOneOfThreeHundredPatrolsMoveWhenAPlayerIsNearAll() {
        RouteScheduler<Patrol> routes = scheduler(128, 64);
        List<Patrol> all = patrols(routes, 300, 0);
        for (long tick = 0; tick < 30; tick++) {
            RouteScheduler.Tick result = routes.tick(tick, listener());
            assertTrue(result.served() <= 128, "served " + result.served());
        }
        int least = all.stream().mapToInt((Patrol p) -> p.served).min().orElseThrow();
        int most = all.stream().mapToInt((Patrol p) -> p.served).max().orElseThrow();
        assertTrue(least >= 12, "every patrol moves; least=" + least);
        assertTrue(most - least <= 1, "round robin keeps turns equal; least=" + least + " most=" + most);
        assertEquals(300, routes.size());
    }

    @Test void shouldNeverServeMoreRoutesThanTheTickBudget() {
        RouteScheduler<Patrol> routes = scheduler(16, 64);
        patrols(routes, 297, 0);
        RouteScheduler.Tick first = routes.tick(0, listener());
        assertEquals(16, first.served());
        assertEquals(297 - 16, first.deferred());
    }

    @Test void shouldSleepPatrolsWithNoPlayerNearAndHoldNoSlot() {
        RouteScheduler<Patrol> routes = scheduler(32, 64);
        List<Patrol> all = patrols(routes, 300, 0);
        for (int i = 20; i < 300; i++) all.get(i).watched = false;
        for (long tick = 0; tick < 20; tick++) routes.tick(tick, listener());
        assertEquals(280, routes.sleeping());
        RouteScheduler.Tick tick = routes.tick(20, listener());
        assertEquals(20, tick.served(), "only the watched patrols use the budget");
        assertEquals(0, tick.deferred());
        for (int i = 20; i < 300; i++) assertEquals(1, all.get(i).slept);
        for (int i = 0; i < 20; i++) assertTrue(all.get(i).served >= 20);
    }

    @Test void shouldWakeASleepingPatrolWhenAPlayerComesNear() {
        RouteScheduler<Patrol> routes = scheduler(32, 8);
        List<Patrol> all = patrols(routes, 64, 0);
        for (Patrol patrol : all) patrol.watched = false;
        routes.tick(0, listener()); routes.tick(1, listener());
        assertEquals(64, routes.sleeping());
        for (Patrol patrol : all) patrol.watched = true;
        for (long tick = 2; tick < 2 + 64 / 8 + 1; tick++) routes.tick(tick, listener());
        assertEquals(0, routes.sleeping());
        for (Patrol patrol : all) { assertEquals(1, patrol.woke); assertTrue(patrol.served >= 1); }
    }

    @Test void shouldNotCountWaitingPatrolsAsDeferredOrServeThemEarly() {
        RouteScheduler<Patrol> routes = scheduler(8, 8);
        List<Patrol> all = patrols(routes, 4, 0);
        Patrol waiting = new Patrol(99); waiting.due = 10; routes.add(waiting, 0);
        RouteScheduler.Tick tick = routes.tick(0, listener());
        assertEquals(4, tick.served()); assertEquals(0, tick.deferred()); assertEquals(1, routes.waiting());
        for (long now = 1; now < 10; now++) routes.tick(now, listener());
        assertEquals(0, waiting.served);
        routes.tick(10, listener());
        assertEquals(1, waiting.served);
        assertEquals(0, routes.waiting());
        assertEquals(11, all.get(0).served);
    }

    @Test void shouldServeADeferredRouteFirstOnTheNextTick() {
        RouteScheduler<Patrol> routes = scheduler(8, 8);
        List<Patrol> all = patrols(routes, 6, 0);
        RouteScheduler.Listener<Patrol> outOfCells = new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return true; }
            @Override public RouteScheduler.Result work(Patrol route) { return route.id == 2 ? RouteScheduler.Result.DEFER : RouteScheduler.Result.KEEP; }
            @Override public void slept(Patrol route) {}
            @Override public void woke(Patrol route) {}
        };
        RouteScheduler.Tick first = routes.tick(0, outOfCells);
        assertEquals(2, first.served());
        assertEquals(4, first.deferred());
        List<Integer> next = new ArrayList<>();
        routes.tick(1, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return true; }
            @Override public RouteScheduler.Result work(Patrol route) { next.add(route.id); return RouteScheduler.Result.KEEP; }
            @Override public void slept(Patrol route) {}
            @Override public void woke(Patrol route) {}
        });
        assertEquals(List.of(2, 3, 4, 5, 0, 1), next);
        assertEquals(6, all.size());
    }

    @Test void shouldLetOthersWorkAndPutAYieldedRouteFirstNextTick() {
        RouteScheduler<Patrol> routes = scheduler(8, 8);
        patrols(routes, 5, 0);
        List<Integer> worked = new ArrayList<>();
        RouteScheduler.Tick first = routes.tick(0, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return true; }
            @Override public RouteScheduler.Result work(Patrol route) {
                if (route.id == 1 || route.id == 3) return RouteScheduler.Result.YIELD;
                worked.add(route.id); return RouteScheduler.Result.KEEP;
            }
            @Override public void slept(Patrol route) {}
            @Override public void woke(Patrol route) {}
        });
        assertEquals(List.of(0, 2, 4), worked);
        assertEquals(3, first.served()); assertEquals(2, first.deferred());
        List<Integer> next = new ArrayList<>();
        routes.tick(1, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return true; }
            @Override public RouteScheduler.Result work(Patrol route) { next.add(route.id); return RouteScheduler.Result.KEEP; }
            @Override public void slept(Patrol route) {}
            @Override public void woke(Patrol route) {}
        });
        assertEquals(List.of(1, 3, 0, 2, 4), next);
    }

    @Test void shouldForgetARouteThatIsRemovedOrDone() {
        RouteScheduler<Patrol> routes = scheduler(8, 8);
        List<Patrol> all = patrols(routes, 3, 0);
        Patrol sleeping = new Patrol(7); sleeping.watched = false; routes.add(sleeping, 0);
        routes.tick(0, listener());
        assertEquals(1, routes.sleeping());
        routes.remove(sleeping); routes.remove(all.get(0));
        assertEquals(2, routes.size()); assertEquals(0, routes.sleeping());
        Set<Integer> seen = new HashSet<>();
        routes.tick(1, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Patrol route) { return true; }
            @Override public RouteScheduler.Result work(Patrol route) { seen.add(route.id); return RouteScheduler.Result.DONE; }
            @Override public void slept(Patrol route) {}
            @Override public void woke(Patrol route) {}
        });
        assertEquals(Set.of(1, 2), seen);
        assertEquals(0, routes.size());
    }

    @Test void shouldServeOnlyTheLoweredBudgetAndKeepEveryRouteAfterAHotload() {
        RouteScheduler<Patrol> routes = scheduler(64, 64);
        List<Patrol> all = patrols(routes, 200, 0);
        assertEquals(64, routes.tick(0, listener()).served());
        routes.budgets(8, 4);
        RouteScheduler.Tick lowered = routes.tick(1, listener());
        assertEquals(8, lowered.served(), "the next tick spends the lowered budget");
        assertEquals(200, routes.size(), "no route is dropped when the budget shrinks");
        for (long tick = 2; tick < 60; tick++) assertTrue(routes.tick(tick, listener()).served() <= 8);
        for (Patrol patrol : all) assertTrue(patrol.served >= 1, "patrol " + patrol.id + " still gets turns");
    }

    @Test void shouldServeMoreRoutesAfterRaisingTheBudget() {
        RouteScheduler<Patrol> routes = scheduler(4, 4);
        patrols(routes, 100, 0);
        assertEquals(4, routes.tick(0, listener()).served());
        routes.budgets(50, 4);
        assertEquals(50, routes.tick(1, listener()).served());
    }

    @Test void shouldCheckOnlyTheLoweredNumberOfSleepingRoutesPerTick() {
        RouteScheduler<Patrol> routes = scheduler(32, 64);
        List<Patrol> all = patrols(routes, 64, 0);
        for (Patrol patrol : all) patrol.watched = false;
        routes.tick(0, listener()); routes.tick(1, listener());
        assertEquals(64, routes.sleeping());
        routes.budgets(32, 2);
        for (Patrol patrol : all) patrol.watched = true;
        routes.tick(2, listener());
        assertEquals(62, routes.sleeping(), "two wake checks a tick after the hotload");
    }

    @Test void shouldRefuseNonPositiveBudgetsOnHotload() {
        RouteScheduler<Patrol> routes = scheduler(4, 4);
        assertThrows(IllegalArgumentException.class, () -> routes.budgets(0, 4));
        assertThrows(IllegalArgumentException.class, () -> routes.budgets(4, 0));
    }
}
