package art.arcane.animus.core.behavior.trigger;

/** TOML budgets under {@code [behaviors.triggers]}. Ticks are behavior-owner ticks except where noted. Requires restart. */
public record TriggerSettings(boolean enabled, int eventQueue, int eventsPerTick, int startsPerTick, int maximumQueued,
                              int queuePerNpc, int queuePerTrigger, int queueLifetimeTicks, int concurrentTotal,
                              int concurrentPerNpc, int concurrentPerTrigger, int defaultCooldownTicks, int npcCooldownTicks,
                              int maximumBindings, int maximumTimers, int minimumTimerTicks, int maximumProximityRadius,
                              int proximityIntervalTicks, int proximityViewersPerTick, int proximityCandidates,
                              int proximityTrackedPerViewer) {
    public static final TriggerSettings DEFAULT = new TriggerSettings(true, 256, 32, 8, 128, 4, 2, 100, 32, 2, 1, 20, 0,
            4096, 1024, 20, 32, 5, 16, 32, 64);

    public TriggerSettings {
        bound("eventQueue", eventQueue, 1, 4096);
        bound("eventsPerTick", eventsPerTick, 1, 1024);
        bound("startsPerTick", startsPerTick, 1, 64);
        bound("maximumQueued", maximumQueued, 1, 4096);
        bound("queuePerNpc", queuePerNpc, 1, 64);
        bound("queuePerTrigger", queuePerTrigger, 1, 64);
        bound("queueLifetimeTicks", queueLifetimeTicks, 1, 72000);
        bound("concurrentTotal", concurrentTotal, 1, 128);
        bound("concurrentPerNpc", concurrentPerNpc, 1, 16);
        bound("concurrentPerTrigger", concurrentPerTrigger, 1, 16);
        bound("defaultCooldownTicks", defaultCooldownTicks, 0, 72000);
        bound("npcCooldownTicks", npcCooldownTicks, 0, 72000);
        bound("maximumBindings", maximumBindings, 1, 65536);
        bound("maximumTimers", maximumTimers, 1, 16384);
        bound("minimumTimerTicks", minimumTimerTicks, 1, 72000);
        bound("maximumProximityRadius", maximumProximityRadius, 1, 64);
        bound("proximityIntervalTicks", proximityIntervalTicks, 1, 200);
        bound("proximityViewersPerTick", proximityViewersPerTick, 1, 256);
        bound("proximityCandidates", proximityCandidates, 1, 1024);
        bound("proximityTrackedPerViewer", proximityTrackedPerViewer, 1, 1024);
    }
    private static void bound(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
    public ProximityTracker.Limits proximityLimits() {
        return new ProximityTracker.Limits(proximityIntervalTicks, proximityViewersPerTick, proximityCandidates, proximityTrackedPerViewer);
    }
}
