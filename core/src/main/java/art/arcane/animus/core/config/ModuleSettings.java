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
        if (maximumInvocations < 1 || maximumInvocations > 256
                || invocationTimeoutMillis < 1 || invocationTimeoutMillis > 60_000
                || deliveryBudgetMillis < 1 || deliveryBudgetMillis > 60_000)
            throw new IllegalArgumentException("Module budgets require maximumInvocations=1..256, invocationTimeoutMillis=1..60000 and deliveryBudgetMillis=1..60000");
    }
}
