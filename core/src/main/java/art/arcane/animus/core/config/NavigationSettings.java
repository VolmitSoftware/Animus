package art.arcane.animus.core.config;

/**
 * The {@code [navigation]} table of {@code config.toml}: route work allowed in one tick, on the server or, on Folia, in
 * each region. {@code routesPerTick} bounds the routes that plan or take a step, {@code motionCellsPerTick} the
 * collision cells their steps read, {@code planningStartsPerTick} the new path plans, and {@code wakeChecksPerTick} how
 * many sleeping routes look for a nearby player. A route with no player within {@code [npcs] viewDistance} sleeps and
 * uses none of these until a player comes near. There is no limit on how many routes exist.
 */
public record NavigationSettings(int routesPerTick, int motionCellsPerTick, int planningStartsPerTick, int wakeChecksPerTick) {
    public static final NavigationSettings DEFAULT = new NavigationSettings(128, 16384, 16, 64);

    public NavigationSettings {
        require("routesPerTick", routesPerTick, 1, 4096);
        require("motionCellsPerTick", motionCellsPerTick, 512, 1_048_576);
        require("planningStartsPerTick", planningStartsPerTick, 1, 1024);
        require("wakeChecksPerTick", wakeChecksPerTick, 1, 4096);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
