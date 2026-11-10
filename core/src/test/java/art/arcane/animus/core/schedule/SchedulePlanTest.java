package art.arcane.animus.core.schedule;

import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulePlanTest {
    static ScheduleSettings.Location at(int time, int x, int chance, ScheduleSettings.Weather weather, Optional<ScheduleSettings.Wander> wander) {
        return new ScheduleSettings.Location(Optional.empty(), time, x, 64, 0, OptionalDouble.empty(), OptionalDouble.empty(), chance, weather,
                Optional.empty(), wander, Optional.empty(), Map.of(), false, Optional.empty(), false, List.of());
    }
    static ScheduleSettings.Location at(int time, int x) { return at(time, x, 100, ScheduleSettings.Weather.ANY, Optional.empty()); }

    @Test void theLatestPassedTimeIsInEffect() {
        List<ScheduleSettings.Location> day = List.of(at(1000, 0), at(13000, 1), at(6000, 2));
        assertEquals(1000, SchedulePlan.slot(day, 1000, false).getAsInt());
        assertEquals(6000, SchedulePlan.slot(day, 12999, false).getAsInt());
        assertEquals(13000, SchedulePlan.slot(day, 23999, false).getAsInt());
        assertEquals(1000, SchedulePlan.slot(day, 24000L * 5 + 1500, false).getAsInt(), "full time counts days");
    }

    @Test void beforeTheFirstTimeTheEveningLocationStillHolds() {
        List<ScheduleSettings.Location> day = List.of(at(1000, 0), at(13000, 1));
        assertEquals(13000, SchedulePlan.slot(day, 500, false).getAsInt());
    }

    @Test void weatherLimitsLocations() {
        List<ScheduleSettings.Location> day = List.of(at(1000, 0, 100, ScheduleSettings.Weather.CLEAR, Optional.empty()),
                at(2000, 1, 100, ScheduleSettings.Weather.STORM, Optional.empty()));
        assertEquals(1000, SchedulePlan.slot(day, 3000, false).getAsInt());
        assertEquals(2000, SchedulePlan.slot(day, 3000, true).getAsInt());
        assertTrue(SchedulePlan.slot(List.of(at(0, 0, 100, ScheduleSettings.Weather.STORM, Optional.empty())), 10, false).isEmpty());
        assertEquals(List.of(0), SchedulePlan.candidates(day, 1000, false));
        assertEquals(List.of(), SchedulePlan.candidates(day, 1000, true));
    }

    @Test void sharedTimesDrawByChance() {
        List<ScheduleSettings.Location> day = List.of(at(1000, 0, 25, ScheduleSettings.Weather.ANY, Optional.empty()),
                at(1000, 1, 75, ScheduleSettings.Weather.ANY, Optional.empty()), at(5000, 2));
        List<Integer> candidates = SchedulePlan.candidates(day, 1000, false);
        assertEquals(List.of(0, 1), candidates);
        Random random = new Random(7);
        int first = 0;
        for (int draw = 0; draw < 4000; draw++) if (SchedulePlan.pick(day, candidates, random) == 0) first++;
        assertTrue(first > 850 && first < 1150, "about one in four: " + first);
        assertEquals(2, SchedulePlan.pick(day, List.of(2), random));
    }

    @Test void legsAimAtBlockCentres() {
        assertEquals(new Vec3(10.5, 64, -3.5), SchedulePlan.goal(new ScheduleSettings.Location(Optional.empty(), 0, 10, 64, -4, OptionalDouble.empty(),
                OptionalDouble.empty(), 100, ScheduleSettings.Weather.ANY, Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), false,
                Optional.empty(), false, List.of())));
    }

    @Test void wanderStaysInsideItsRadiusOrArea() {
        ScheduleSettings.Wander round = new ScheduleSettings.Wander(4, Optional.empty(), new ScheduleSettings.Seconds(1, 1));
        ScheduleSettings.Location center = at(0, 100, 100, ScheduleSettings.Weather.ANY, Optional.of(round));
        Random random = new Random(3);
        for (int draw = 0; draw < 500; draw++) {
            Vec3 target = SchedulePlan.wander(center, round, random);
            double dx = target.x() - 100.5, dz = target.z() - 0.5;
            assertTrue(dx * dx + dz * dz <= 16 + 1e-9, target.toString());
            assertEquals(64, target.y());
        }
        ScheduleSettings.Wander box = new ScheduleSettings.Wander(0, Optional.of(new ScheduleSettings.Area(0, 70, 0, 2, 72, 3)), new ScheduleSettings.Seconds(1, 1));
        for (int draw = 0; draw < 200; draw++) {
            Vec3 target = SchedulePlan.wander(center, box, random);
            assertTrue(target.x() >= 0.5 && target.x() <= 2.5 && target.z() >= 0.5 && target.z() <= 3.5, target.toString());
            assertEquals(70, target.y(), "the location height is held inside the area");
        }
    }

    @Test void facingPointsAlongYawAndPitch() {
        Vec3 south = SchedulePlan.facing(new Vec3(0, 0, 0), 0, 0, 2);
        assertEquals(0, south.x(), 1e-9); assertEquals(2, south.z(), 1e-9);
        Vec3 west = SchedulePlan.facing(new Vec3(0, 0, 0), 90, 0, 2);
        assertEquals(-2, west.x(), 1e-9); assertEquals(0, west.z(), 1e-9);
        assertEquals(-2, SchedulePlan.facing(new Vec3(0, 0, 0), 0, 90, 2).y(), 1e-9);
    }
}
