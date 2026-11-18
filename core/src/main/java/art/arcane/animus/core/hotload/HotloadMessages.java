package art.arcane.animus.core.hotload;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.PlainWords;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Operator chat for hotloads: one plain sentence per file that names it as players do (an NPC by its name, a behavior
 * by its id, {@code config.toml} as Settings) and says what happened, such as {@code Changed NPC Guard: name and skin.}
 * The key-by-key detail with old and new values goes to the server log through {@link #logged}.
 */
public final class HotloadMessages {
    /** Changed keys the server log lists for one file before {@code +N more}; each value is cut to {@link #MAXIMUM_VALUE}. */
    public static final int LOGGED_PER_FILE = 10, MAXIMUM_VALUE = 120;
    /** A line lists at most this many changed fields, within this many characters; more are counted instead. */
    private static final int LISTED = 3, LISTED_LENGTH = 48;
    /** Fields every hand edit may bump; named only when nothing else changed. */
    private static final Set<String> BOOKKEEPING = Set.of("revision", "schemaVersion");

    private HotloadMessages() {}

    /**
     * The line for an applied file: added, removed, or changed with its fields in words, or their count when there are
     * many. {@code before} is null for a new file and {@code after} null for a deleted one.
     */
    public static ChatLine applied(HotloadTargets.Target target, String before, String after, List<HotloadDiff.Change> changes, Predicate<String> restartRequired) {
        String subject = FileWords.subject(target, after, before);
        if (before == null) return ChatLine.success("Added " + subject + ".");
        if (after == null) return ChatLine.success("Removed " + subject + ".");
        if (target.area() == HotloadTargets.Area.SCHEMATIC) return ChatLine.success("Reloaded " + subject + ".");
        if (target.area() == HotloadTargets.Area.CONFIG) return settings(changes, restartRequired);
        List<String> fields = new ArrayList<>(), real = new ArrayList<>();
        for (HotloadDiff.Change change : changes) {
            String field = change.key().split("[.\\[]", 2)[0];
            if (field.equals("$") || fields.contains(field)) continue;
            fields.add(field);
            if (!BOOKKEEPING.contains(field)) real.add(field);
        }
        List<String> named = real.isEmpty() ? fields : real;
        if (named.isEmpty()) return ChatLine.success("Changed " + subject + ".");
        String listed = PlainWords.list(named.stream().map((String field) -> FileWords.words(field, false)).toList());
        if (named.size() <= LISTED && listed.length() <= LISTED_LENGTH) return ChatLine.success("Changed " + subject + ": " + listed + ".");
        return ChatLine.success("Changed " + subject + ": " + named.size() + " fields.");
    }

    /** An NPC or group file below the current schema, migrated before it loaded, with the rollback that restores the original. */
    public static ChatLine upgraded(HotloadTargets.Target target, String after, String transaction) {
        ChatLine line = ChatLine.success("Upgraded " + FileWords.subject(target, after) + " to the current file format.");
        if (transaction.isEmpty() || transaction.contains(",")) return line;
        return line.suggest("Rollback", "/animus rollback " + transaction,
                "Puts the rollback command in your chat box. It restores the original file, which this version of Animus does not load.");
    }

    /**
     * A refused edit, why in words, and what stays in use. {@code known} is the last applied text, null when the file
     * never loaded; {@code content} is the refused text, which may not parse.
     */
    public static ChatLine refused(HotloadTargets.Target target, String known, String content, String error) {
        HotloadTargets.Area area = target.area();
        String reason = area == HotloadTargets.Area.CONFIG ? FileWords.configReason(error)
                : area == HotloadTargets.Area.SCHEMATIC ? FileWords.schematicReason(error) : FileWords.reason(error);
        String kept = area == HotloadTargets.Area.CONFIG ? "the running settings stay" : area == HotloadTargets.Area.SCHEMATIC ? "builds cannot use it until it is fixed"
                : known == null ? "it was not loaded" : "the last working version stays";
        return ChatLine.error("Refused " + FileWords.subject(target, content, known) + ": " + reason + "; " + kept + ".");
    }

    /** The server log's account of an applied file: each changed key with both values, at most {@link #LOGGED_PER_FILE}. */
    public static String logged(String file, String before, String after, List<HotloadDiff.Change> changes) {
        if (before == null) return file + " (new file)";
        if (after == null) return file + " (removed)";
        StringBuilder line = new StringBuilder(file).append(" (").append(PlainWords.count(changes.size(), "change", "changes")).append(')');
        int shown = Math.min(LOGGED_PER_FILE, changes.size());
        for (int index = 0; index < shown; index++) {
            HotloadDiff.Change change = changes.get(index);
            line.append(index == 0 ? ": " : "; ").append(change.key()).append(' ').append(HotloadDiff.compact(change.before(), MAXIMUM_VALUE))
                    .append(" -> ").append(HotloadDiff.compact(change.after(), MAXIMUM_VALUE));
        }
        if (changes.size() > shown) line.append("; +").append(changes.size() - shown).append(" more");
        return line.toString();
    }

    private static ChatLine settings(List<HotloadDiff.Change> changes, Predicate<String> restartRequired) {
        // A list setting counts once, however many of its entries changed.
        List<String> keys = changes.stream().map((HotloadDiff.Change change) -> change.key().replaceAll("\\[\\d+]", "")).distinct().toList();
        List<String> restart = keys.stream().filter(restartRequired).map((String key) -> FileWords.words(key, false)).toList();
        if (keys.size() <= LISTED && PlainWords.list(keys.stream().map((String key) -> FileWords.words(key, false)).toList()).length() <= LISTED_LENGTH) {
            List<String> named = keys.stream().map((String key) -> FileWords.words(key, false) + (restartRequired.test(key) ? " (needs a restart)" : "")).toList();
            return ChatLine.success("Changed Settings: " + PlainWords.list(named) + ".");
        }
        String needs = restart.isEmpty() ? "" : "; " + PlainWords.list(restart) + (restart.size() == 1 ? " needs" : " need") + " a restart";
        return ChatLine.success("Changed " + keys.size() + " settings" + needs + ".");
    }
}
