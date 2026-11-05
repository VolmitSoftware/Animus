package art.arcane.animus.core.config;

import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import java.util.Objects;

/**
 * Plugin-wide behavior budgets are TOML; behavior sources and runtime data remain JSON. Every key hotloads: each owner adopts
 * new budgets on its next tick, and a lowered cap refuses new work until usage drains under it without stopping running work.
 */
public record BehaviorSettings(boolean enabled, int maximumOperations, int operationsPerTick, int tickMillis,
                               int deliveryBudgetMillis, BehaviorEngine.Limits engine, BehaviorCatalog.Limits catalog,
                               TriggerSettings triggers, BehaviorStateStore.Limits state, BuiltinActionSettings actions) {
    public static final BehaviorSettings DEFAULT = new BehaviorSettings(true, 64, 8, 50, 250,
            BehaviorEngine.Limits.defaults(), BehaviorCatalog.Limits.defaults(), TriggerSettings.DEFAULT, BehaviorStateStore.Limits.defaults(), BuiltinActionSettings.DEFAULT);

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
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(actions, "actions");
    }
}
