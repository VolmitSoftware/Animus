package art.arcane.animus.core.schedule;

import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.random.RandomGenerator;

/**
 * The schedule's choices, free of any world: which time of day is in effect, which location the NPC goes to, and where
 * a wander leg aims. Times are world ticks of the day, 0-23999.
 */
public final class SchedulePlan {
    /** How many draws a round wander gets to land inside its circle before it goes back to the location itself. */
    private static final int WANDER_DRAWS = 8;

    private SchedulePlan() {}

    /**
     * The time in effect: the latest location time at or before {@code time} among locations the weather allows; before
     * the first time of the day, the latest time of all, which began the evening before. Empty when the weather allows none.
     */
    public static OptionalInt slot(List<ScheduleSettings.Location> locations, long time, boolean storm) {
        long day = Math.floorMod(time, ScheduleSettings.DAY_TICKS);
        int latest = -1, earlier = -1;
        for (ScheduleSettings.Location location : locations) {
            if (!location.weather().allows(storm)) continue;
            latest = Math.max(latest, location.time());
            if (location.time() <= day) earlier = Math.max(earlier, location.time());
        }
        if (latest < 0) return OptionalInt.empty();
        return OptionalInt.of(earlier >= 0 ? earlier : latest);
    }

    /** The indexes of the locations that start at {@code slot} and that the weather allows, in order. */
    public static List<Integer> candidates(List<ScheduleSettings.Location> locations, int slot, boolean storm) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < locations.size(); index++) {
            ScheduleSettings.Location location = locations.get(index);
            if (location.time() == slot && location.weather().allows(storm)) result.add(index);
        }
        return result;
    }

    /** One candidate drawn by its chance: a location with chance 30 beside one with 70 is picked three times in ten. */
    public static int pick(List<ScheduleSettings.Location> locations, List<Integer> candidates, RandomGenerator random) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("No location to pick from");
        if (candidates.size() == 1) return candidates.getFirst();
        int total = 0;
        for (int index : candidates) total += locations.get(index).chance();
        int roll = random.nextInt(total);
        for (int index : candidates) {
            roll -= locations.get(index).chance();
            if (roll < 0) return index;
        }
        return candidates.getLast();
    }

    /** The block centre a leg to this location aims at; a walking goal names the block the NPC stands in. */
    public static Vec3 goal(ScheduleSettings.Location location) { return new Vec3(location.x() + 0.5, location.y(), location.z() + 0.5); }

    /**
     * A random block centre to wander to: within the radius on x and z around the location at its height, or anywhere
     * inside the area at the location's height held inside the area's.
     */
    public static Vec3 wander(ScheduleSettings.Location location, ScheduleSettings.Wander wander, RandomGenerator random) {
        if (wander.area().isPresent()) {
            ScheduleSettings.Area area = wander.area().get();
            int y = Math.clamp(location.y(), area.minY(), area.maxY());
            return new Vec3(random.nextInt(area.minX(), area.maxX() + 1) + 0.5, y, random.nextInt(area.minZ(), area.maxZ() + 1) + 0.5);
        }
        int radius = wander.radius();
        for (int draw = 0; draw < WANDER_DRAWS; draw++) {
            int dx = random.nextInt(-radius, radius + 1), dz = random.nextInt(-radius, radius + 1);
            if (dx * dx + dz * dz <= radius * radius) return new Vec3(location.x() + dx + 0.5, location.y(), location.z() + dz + 0.5);
        }
        return goal(location);
    }

    /** A point straight ahead of an eye at {@code from} for the yaw and pitch, as Minecraft measures them. */
    public static Vec3 facing(Vec3 from, double yaw, double pitch, double distance) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
        double flat = Math.cos(pitchRadians);
        return new Vec3(from.x() - Math.sin(yawRadians) * flat * distance, from.y() - Math.sin(pitchRadians) * distance, from.z() + Math.cos(yawRadians) * flat * distance);
    }
}
