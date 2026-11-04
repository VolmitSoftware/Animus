package art.arcane.animus.core.config;

/** Trusted modules load at startup; changing these settings requires restart. */
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
