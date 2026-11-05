package art.arcane.animus.core.config;

/**
 * Plugin-wide combat work limits, read from TOML. Requires restart.
 *
 * @param maximumNpcs      combat NPCs that fight or scan at once; every combat NPC registers, idle ones with no player near
 *                         sleep without a slot and the rest wait their turn
 * @param eventsPerTick    fights and chat messages examined per tick for fight and chat rules
 * @param maximumScanRange the most any NPC's {@code scanRange} is allowed to reach, in blocks
 * @param maximumSpeed     the fastest any NPC's {@code pursuit.speed} is allowed to move, in blocks per second
 */
public record CombatBudgets(boolean enabled, int maximumNpcs, int brainsPerTick, int targetScansPerTick, int scanCandidates, int scanCells,
                            int squadAlertsPerTick, int pathRequestsPerTick, int maximumHelpers, int helpersPerNpc,
                            int helperUpdatesPerTick, int helperLifetimeTicks, int idleSleepTicks, int eventsPerTick, int maximumScanRange, int maximumSpeed) {
    public static final CombatBudgets DEFAULT = new CombatBudgets(true, 256, 48, 8, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);

    public CombatBudgets {
        require("maximumNpcs", maximumNpcs, 1, 4096);
        require("brainsPerTick", brainsPerTick, 1, 128);
        require("targetScansPerTick", targetScansPerTick, 1, 64);
        require("scanCandidates", scanCandidates, 1, 256);
        require("scanCells", scanCells, 1, 1024);
        require("squadAlertsPerTick", squadAlertsPerTick, 1, 64);
        require("pathRequestsPerTick", pathRequestsPerTick, 1, 16);
        require("maximumHelpers", maximumHelpers, 1, 1024);
        require("helpersPerNpc", helpersPerNpc, 1, 64);
        require("helperUpdatesPerTick", helperUpdatesPerTick, 1, 256);
        require("helperLifetimeTicks", helperLifetimeTicks, 1, 1200);
        require("idleSleepTicks", idleSleepTicks, 1, 1200);
        require("eventsPerTick", eventsPerTick, 1, 64);
        require("maximumScanRange", maximumScanRange, 16, 256);
        require("maximumSpeed", maximumSpeed, 1, 10);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
