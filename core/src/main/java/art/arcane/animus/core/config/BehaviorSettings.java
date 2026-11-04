package art.arcane.animus.core.config;

import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import java.util.Objects;

/** Plugin-wide behavior budgets are TOML; behavior sources and runtime data remain JSON. Requires restart. */
public record BehaviorSettings(boolean enabled, int maximumOperations, int operationsPerTick, int tickMillis,
                               int deliveryBudgetMillis, BehaviorEngine.Limits engine, BehaviorCatalog.Limits catalog,
                               TriggerSettings triggers) {
    public static final BehaviorSettings DEFAULT = new BehaviorSettings(true, 64, 8, 50, 250,
            BehaviorEngine.Limits.defaults(), BehaviorCatalog.Limits.defaults(), TriggerSettings.DEFAULT);

    public BehaviorSettings {
        if (maximumOperations < 1 || maximumOperations > 256)
            throw new IllegalArgumentException("maximumOperations must be within 1..256");
        if (operationsPerTick < 1 || operationsPerTick > 64 || operationsPerTick > maximumOperations)
            throw new IllegalArgumentException("operationsPerTick must be within 1..64 and must not exceed maximumOperations");
        if (tickMillis < 50 || tickMillis > 1000)
            throw new IllegalArgumentException("tickMillis must be within 50..1000");
        if (deliveryBudgetMillis < 1 || deliveryBudgetMillis > 60_000)
            throw new IllegalArgumentException("deliveryBudgetMillis must be within 1..60000");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(triggers, "triggers");
    }
}
