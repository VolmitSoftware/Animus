package art.arcane.animus.core.config;

/**
 * Admission covers commands, imports, API edits and their result delivery. At startup rollback receipts beyond the newest
 * {@code backupsKept} that are also older than {@code backupDays} days are removed.
 */
public record FileWorkSettings(int maximumOperations, int deliveryBudgetMillis, int backupsKept, int backupDays) {
    public static final FileWorkSettings DEFAULT = new FileWorkSettings(32, 250, 200, 14);

    public FileWorkSettings {
        if (maximumOperations < 1 || maximumOperations > 128
                || deliveryBudgetMillis < 1 || deliveryBudgetMillis > 60_000)
            throw new IllegalArgumentException("File budgets require maximumOperations=1..128 and deliveryBudgetMillis=1..60000");
        if (backupsKept < 1 || backupsKept > 100_000 || backupDays < 0 || backupDays > 3650)
            throw new IllegalArgumentException("Backup rotation requires backupsKept=1..100000 and backupDays=0..3650");
    }
}
