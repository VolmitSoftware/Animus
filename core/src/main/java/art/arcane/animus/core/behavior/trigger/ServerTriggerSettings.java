package art.arcane.animus.core.behavior.trigger;

/** TOML budgets under {@code [behaviors.server]} for server-wide behaviors. Ticks are behavior-owner ticks. Requires restart. */
public record ServerTriggerSettings(boolean enabled, int eventQueue, int eventsPerTick, int startsPerTick, int queuePerTrigger,
                                    int concurrentPerTrigger, int concurrentTotal, int defaultCooldownTicks, int minimumTimerTicks,
                                    int maximumCommands, int maximumAreas) {
    public static final ServerTriggerSettings DEFAULT = new ServerTriggerSettings(true, 512, 64, 16, 64, 8, 32, 0, 20, 64, 256);

    public ServerTriggerSettings {
        bound("eventQueue", eventQueue, 1, 4096);
        bound("eventsPerTick", eventsPerTick, 1, 1024);
        bound("startsPerTick", startsPerTick, 1, 64);
        bound("queuePerTrigger", queuePerTrigger, 1, 256);
        bound("concurrentPerTrigger", concurrentPerTrigger, 1, 64);
        bound("concurrentTotal", concurrentTotal, 1, 128);
        bound("defaultCooldownTicks", defaultCooldownTicks, 0, 72000);
        bound("minimumTimerTicks", minimumTimerTicks, TriggerCompiler.MIN_SERVER_INTERVAL_TICKS, 72000);
        bound("maximumCommands", maximumCommands, 0, 64);
        bound("maximumAreas", maximumAreas, 0, 4096);
    }
    private static void bound(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
