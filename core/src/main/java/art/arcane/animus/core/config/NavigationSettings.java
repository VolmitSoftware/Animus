package art.arcane.animus.core.config;

import art.arcane.animus.core.navigation.NavigationLimits;

/**
 * The {@code [navigation]} table of {@code config.toml}: route work allowed in one tick, on the server or, on Folia, in
 * each region. {@code routesPerTick} bounds the routes that plan or take a step, {@code motionCellsPerTick} the
 * collision cells their steps read, {@code planningStartsPerTick} the new path plans, and {@code wakeChecksPerTick} how
 * many sleeping routes look for a nearby player. {@code planningJobs} bounds the path plans in progress at once and
 * {@code captureReadsPerTick} the terrain reads those plans make per tick. A route with no player within {@code [npcs] viewDistance} sleeps and
 * uses none of these until a player comes near. There is no limit on how many routes exist. A hotload applies from the
 * owner's next tick (on Folia, each region's next tick); routes and plans in progress continue, and a lowered
 * {@code planningJobs} only refuses new plans until fewer are in progress.
 */
public record NavigationSettings(int routesPerTick, int motionCellsPerTick, int planningStartsPerTick, int wakeChecksPerTick,
                                 int planningJobs, int captureReadsPerTick) {
    public static final NavigationSettings DEFAULT = new NavigationSettings(128, 16384, 16, 64, 32, 2048);

    public NavigationSettings {
        require("routesPerTick", routesPerTick, 1, 4096);
        require("motionCellsPerTick", motionCellsPerTick, 512, 1_048_576);
        require("planningStartsPerTick", planningStartsPerTick, 1, 1024);
        require("wakeChecksPerTick", wakeChecksPerTick, 1, 4096);
        require("planningJobs", planningJobs, 1, 256);
        require("captureReadsPerTick", captureReadsPerTick, 256, 65_536);
    }

    /** The path-planning limits these settings allow: plans in progress, terrain reads per tick, and the largest capture. */
    public NavigationLimits limits() { return new NavigationLimits(captureReadsPerTick, planningJobs, NavigationLimits.DEFAULT.maximumCaptureVoxels()); }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
