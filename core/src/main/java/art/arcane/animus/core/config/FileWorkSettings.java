package art.arcane.animus.core.config;

/**
 * Admission covers commands, imports, API edits and their result delivery. At startup rollback receipts beyond the newest
 * {@code backupsKept} that are also older than {@code backupDays} days are removed.
 */
public record FileWorkSettings(int maximumOperations, int deliveryBudgetMillis, int backupsKept, int backupDays) {
    public static final FileWorkSettings DEFAULT = new FileWorkSettings(32, 250, 200, 14);

    public FileWorkSettings {
        require("maximumOperations", maximumOperations, 1, 128);
        require("deliveryBudgetMillis", deliveryBudgetMillis, 1, 60_000);
        require("backupsKept", backupsKept, 1, 100_000);
        require("backupDays", backupDays, 0, 3650);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
