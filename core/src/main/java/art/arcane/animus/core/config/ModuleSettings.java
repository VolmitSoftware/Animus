package art.arcane.animus.core.config;

/**
 * {@code [modules]} in {@code config.toml}. Every key applies on hotload: the limits apply to the next invocation, and
 * {@code enabled} suspends or resumes discovered modules without a restart. Module archives load once per server start,
 * so adding, removing or replacing a module jar requires a restart.
 */
public record ModuleSettings(boolean enabled, int maximumInvocations, int invocationTimeoutMillis,
                             int deliveryBudgetMillis) {
    public static final ModuleSettings DEFAULT = new ModuleSettings(true, 64, 1000, 250);

    public ModuleSettings {
        require("maximumInvocations", maximumInvocations, 1, 256);
        require("invocationTimeoutMillis", invocationTimeoutMillis, 1, 60_000);
        require("deliveryBudgetMillis", deliveryBudgetMillis, 1, 60_000);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
