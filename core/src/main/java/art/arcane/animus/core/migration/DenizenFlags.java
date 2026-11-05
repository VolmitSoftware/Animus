package art.arcane.animus.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Denizen flag data. A flag file ({@code server_flags.dat}, {@code player_flags/<uuid>.dat}) holds one flag per line:
 * an optional {@code \ex} marker for a flag that can expire, the key with {@code \bs}, {@code \co} and {@code \nl}
 * escapes, a colon, and the value with {@code \bs} and {@code \nl} escapes. A value is either the flag's own value,
 * such as {@code true} or {@code 5}, or the full flag map {@code map@[__value=...;__expiration=time@...]} when it
 * expires or holds sub-flags. Citizens saves NPC flags under {@code denizen_flags.full_flag_data} with the same values.
 * Values flatten to dotted names, as Denizen addresses sub-flags. The browser converter has a line-for-line copy.
 */
final class DenizenFlags {
    static final int MAXIMUM_LINES = 100_000, MAXIMUM_DEPTH = 16;

    private DenizenFlags() {}

    /** One flattened flag. {@code expires} marks a flag that carries an expiration time. */
    record Flag(String name, String value, boolean expires) {}

    /** The flags of one object, by dotted lowercase name, and what could not be read. */
    record Values(TreeMap<String, Flag> flags, List<String> problems) {}

    static final class Invalid extends Exception {
        Invalid(String message) { super(message); }
    }

    static Values file(String text) {
        Values values = new Values(new TreeMap<>(), new ArrayList<>());
        String body = text.replace("\r", "");
        String[] lines = body.split("\n", -1);
        if (lines.length > MAXIMUM_LINES) { values.problems().add("file exceeds " + MAXIMUM_LINES + " lines"); return values; }
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) continue;
            boolean expires = line.startsWith("\\ex");
            String rest = expires ? line.substring(3) : line;
            int colon = rest.indexOf(':');
            if (colon <= 0) { values.problems().add("line " + (index + 1) + ": expected key:value"); continue; }
            String key = unescapeKey(rest.substring(0, colon));
            String value = unescapeValue(rest.substring(colon + 1));
            add(values, key, value, index + 1);
        }
        return values;
    }

    /** The {@code full_flag_data} mapping of a Citizens {@code denizen_flags} trait, already read as strings. */
    static Values mapping(Map<String, String> data) {
        Values values = new Values(new TreeMap<>(), new ArrayList<>());
        for (Map.Entry<String, String> entry : new TreeMap<>(data).entrySet()) add(values, entry.getKey(), entry.getValue(), 0);
        return values;
    }

    private static void add(Values values, String key, String value, int line) {
        try { flatten(key.toLowerCase(Locale.ROOT), value, false, values, 0); }
        catch (Invalid invalid) { values.problems().add("flag " + (line > 0 ? "on line " + line : key) + ": " + invalid.getMessage()); }
    }

    private static void flatten(String name, String value, boolean expires, Values values, int depth) throws Invalid {
        if (depth > MAXIMUM_DEPTH) throw new Invalid("sub-flags nest deeper than " + MAXIMUM_DEPTH);
        if (!value.startsWith("map@")) {
            values.flags().put(name, new Flag(name, value, expires));
            return;
        }
        TreeMap<String, String> map = map(value);
        boolean expiring = expires || map.containsKey("__expiration");
        String inner = map.get("__value");
        if (inner == null) throw new Invalid("flag map has no __value");
        if (!inner.startsWith("map@")) {
            values.flags().put(name, new Flag(name, inner, expiring));
            return;
        }
        for (Map.Entry<String, String> sub : map(inner).entrySet())
            flatten(name + "." + sub.getKey().toLowerCase(Locale.ROOT), sub.getValue(), expiring, values, depth + 1);
    }

    /** A {@code map@[k=v;...]} savable map. Values that are maps or lists keep their brackets; keys and other values are unescaped. */
    static TreeMap<String, String> map(String text) throws Invalid {
        if (!text.startsWith("map@[") || !text.endsWith("]")) throw new Invalid("expected map@[...]");
        String body = text.substring(5, text.length() - 1);
        TreeMap<String, String> map = new TreeMap<>();
        if (body.isEmpty()) return map;
        List<String> entries = new ArrayList<>();
        int depth = 0, start = 0;
        for (int index = 0; index < body.length(); index++) {
            char at = body.charAt(index);
            if (at == '[') depth++;
            else if (at == ']') { if (--depth < 0) throw new Invalid("unbalanced brackets"); }
            else if (at == ';' && depth == 0) { entries.add(body.substring(start, index)); start = index + 1; }
        }
        if (depth != 0) throw new Invalid("unbalanced brackets");
        if (start < body.length()) entries.add(body.substring(start));
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            if (equals <= 0) throw new Invalid("map entry without key");
            String key = unescapeProperty(entry.substring(0, equals));
            String value = entry.substring(equals + 1);
            if (!value.contains("[")) value = unescapeProperty(value);
            if (map.put(key, value) != null) throw new Invalid("duplicate map key");
        }
        return map;
    }

    static String unescapeKey(String text) { return text.replace("\\co", ":").replace("\\nl", "\n").replace("\\bs", "\\"); }

    static String unescapeValue(String text) { return text.replace("\\nl", "\n").replace("\\bs", "\\"); }

    static String unescapeProperty(String text) { return text.replace("&sc", ";").replace("&lb", "[").replace("&rb", "]").replace("&eq", "=").replace("&amp", "&"); }

    /** An integer flag value Animus quest stages can hold, 0 to 10^12. */
    static Optional<Long> stage(String value) {
        if (!value.matches("[0-9]{1,13}")) return Optional.empty();
        long number = Long.parseLong(value);
        return number <= 1_000_000_000_000L ? Optional.of(number) : Optional.empty();
    }
}
