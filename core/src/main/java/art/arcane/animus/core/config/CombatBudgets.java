package art.arcane.animus.core.config;

/** Plugin-wide combat work limits, read from TOML. Requires restart. */
public record CombatBudgets(boolean enabled, int maximumNpcs, int brainsPerTick, int targetScansPerTick, int scanCandidates, int scanCells,
                            int squadAlertsPerTick, int pathRequestsPerTick, int maximumHelpers, int helpersPerNpc,
                            int helperUpdatesPerTick, int helperLifetimeTicks, int idleSleepTicks) {
    public static final CombatBudgets DEFAULT = new CombatBudgets(true, 64, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40);

    public CombatBudgets {
        require("maximumNpcs", maximumNpcs, 1, 128);
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
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
