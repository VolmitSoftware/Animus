package art.arcane.animus.core.hotload;

import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Operator chat lines for hotloads, section-coded through {@link RichText#format}; values are shown as written, never as colour codes. */
public final class HotloadMessages {
    public static final int MAXIMUM_PER_FILE = 5, MAXIMUM_VALUE = 120;
    public static final String APPLIED = Palette.PREFIX + "&aHotloaded &f[{file}] " + Palette.PINK_LIGHT + "[{key}] &7[{oldValue} -> {newValue}]";
    public static final String TRUNCATED = Palette.PREFIX + "&aHotloaded &f[{file}] &7[+{remaining} more changes]";
    public static final String RESTART = " &c[restart required]";
    public static final String REFUSED = Palette.PREFIX + "&cHotload refused &f[{file}] &c{error} &7[{kept}]";
    public static final String KEPT = "kept the last valid version";
    public static final String UPGRADED = Palette.PREFIX + "&aUpgraded &f[{file}] &7[schemaVersion {from} -> {to}] [rollback {transaction}]";

    private HotloadMessages() {}

    /** One line per changed key, at most {@link #MAXIMUM_PER_FILE}, then how many were left out. */
    public static List<String> applied(String file, List<HotloadDiff.Change> changes, Predicate<String> restartRequired) {
        List<String> lines = new ArrayList<>();
        int shown = Math.min(MAXIMUM_PER_FILE, changes.size());
        for (int index = 0; index < shown; index++) {
            HotloadDiff.Change change = changes.get(index);
            String template = restartRequired.test(change.key()) ? APPLIED + RESTART : APPLIED;
            lines.add(RichText.format(template, Map.of("file", file, "key", change.key(),
                    "oldValue", HotloadDiff.compact(change.before(), MAXIMUM_VALUE), "newValue", HotloadDiff.compact(change.after(), MAXIMUM_VALUE))));
        }
        if (changes.size() > shown)
            lines.add(RichText.format(TRUNCATED, Map.of("file", file, "remaining", Integer.toString(changes.size() - shown))));
        return lines;
    }

    /** A file below the current schema that was migrated before it loaded, with the transaction that undoes it. */
    public static String upgraded(String file, int from, int to, String transaction) {
        return RichText.format(UPGRADED, Map.of("file", file, "from", Integer.toString(from), "to", Integer.toString(to), "transaction", transaction));
    }

    /** The refusal of an invalid edit, with the exact field error. */
    public static String refused(String file, String error, String kept) {
        return RichText.format(REFUSED, Map.of("file", file, "error", HotloadDiff.compact(error, 240), "kept", kept));
    }
}
