package art.arcane.animus.core.config;

/** Admission covers commands, imports, API edits and their result delivery. */
public record FileWorkSettings(int maximumOperations, int deliveryBudgetMillis) {
    public static final FileWorkSettings DEFAULT = new FileWorkSettings(32, 250);

    public FileWorkSettings {
        if (maximumOperations < 1 || maximumOperations > 128
                || deliveryBudgetMillis < 1 || deliveryBudgetMillis > 60_000)
            throw new IllegalArgumentException("File budgets require maximumOperations=1..128 and deliveryBudgetMillis=1..60000");
    }
}
