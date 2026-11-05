package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
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
 * Sub-flags flatten to dotted names, as Denizen addresses them; a {@code __value} map whose entries are not flag maps is
 * the flag's own map value. The browser converter has a line-for-line copy.
 */
final class DenizenFlags {
    static final int MAXIMUM_LINES = 100_000, MAXIMUM_DEPTH = 16;
    /** The value bounds of behavior state: items per list or map, nesting, map key length and canonical JSON bytes. */
    static final int MAXIMUM_ITEMS = 256, MAXIMUM_NESTING = 8, MAXIMUM_KEY = 256, MAXIMUM_BYTES = 4096;

    private DenizenFlags() {}

    /** One flattened flag. {@code expires} marks a flag that carries an expiration; {@code expiresAt} is its epoch millisecond, or 0 when unreadable. */
    record Flag(String name, String value, boolean expires, long expiresAt) {}

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
        try { flatten(key.toLowerCase(Locale.ROOT), value, false, 0, values, 0); }
        catch (Invalid invalid) { values.problems().add("flag " + (line > 0 ? "on line " + line : key) + ": " + invalid.getMessage()); }
    }

    private static void flatten(String name, String value, boolean expires, long expiresAt, Values values, int depth) throws Invalid {
        if (depth > MAXIMUM_DEPTH) throw new Invalid("sub-flags nest deeper than " + MAXIMUM_DEPTH);
        if (!value.startsWith("map@")) {
            values.flags().put(name, new Flag(name, value, expires, expiresAt));
            return;
        }
        TreeMap<String, String> map = map(value);
        boolean expiring = expires || map.containsKey("__expiration");
        long at = map.containsKey("__expiration") ? expiration(map.get("__expiration")) : expiresAt;
        String inner = map.get("__value");
        if (inner == null) throw new Invalid("flag map has no __value");
        if (!inner.startsWith("map@") || !subFlags(map(inner))) {
            values.flags().put(name, new Flag(name, inner, expiring, at));
            return;
        }
        for (Map.Entry<String, String> sub : map(inner).entrySet())
            flatten(name + "." + sub.getKey().toLowerCase(Locale.ROOT), sub.getValue(), expiring, at, values, depth + 1);
    }

    /** Whether a {@code __value} map holds sub-flags: every entry is itself a flag map with a {@code __value}. */
    private static boolean subFlags(TreeMap<String, String> map) throws Invalid {
        if (map.isEmpty()) return false;
        for (String entry : map.values()) if (!entry.startsWith("map@") || !map(entry).containsKey("__value")) return false;
        return true;
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

    // ---- Typed values ----

    /** A typed value or the reason it cannot be one. */
    record Typed(JsonElement value, String type, String reason) {}

    /**
     * A Denizen value as a behavior state value: {@code true}/{@code false} a boolean, a whole number an integer, a
     * decimal a decimal, {@code li@a|b|} a list, {@code map@[k=v]} a map, anything else a string. List items and map
     * values follow the same rules. The canonical JSON must fit the state bounds.
     */
    static Typed typed(String text) {
        try {
            JsonElement value = element(text, 0);
            String type = value.isJsonArray() ? "list" : value.isJsonObject() ? "map" : value.getAsJsonPrimitive().isBoolean() ? "boolean"
                    : value.getAsJsonPrimitive().isString() ? "string" : value.getAsString().contains(".") || value.getAsString().contains("E") ? "decimal" : "integer";
            if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) return new Typed(null, "", "the value is longer than " + MAXIMUM_BYTES + " bytes as JSON");
            return new Typed(value, type, "");
        } catch (Invalid invalid) { return new Typed(null, "", invalid.getMessage()); }
    }

    private static JsonElement element(String text, int depth) throws Invalid {
        if (depth > MAXIMUM_NESTING) throw new Invalid("the value nests deeper than " + MAXIMUM_NESTING);
        if (text.startsWith("li@")) {
            JsonArray array = new JsonArray();
            for (String item : listItems(text.substring(3))) {
                if (array.size() >= MAXIMUM_ITEMS) throw new Invalid("the list holds more than " + MAXIMUM_ITEMS + " items");
                array.add(element(item, depth + 1));
            }
            return array;
        }
        if (text.startsWith("map@[")) {
            JsonObject object = new JsonObject();
            TreeMap<String, String> map = map(text);
            if (map.size() > MAXIMUM_ITEMS) throw new Invalid("the map holds more than " + MAXIMUM_ITEMS + " entries");
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey().isEmpty() || entry.getKey().length() > MAXIMUM_KEY) throw new Invalid("a map key is empty or longer than " + MAXIMUM_KEY + " characters");
                object.add(entry.getKey(), element(entry.getValue(), depth + 1));
            }
            return object;
        }
        return scalar(text);
    }

    /** A plain Denizen element as a JSON scalar by the typed rules. */
    static JsonPrimitive scalar(String text) {
        if (text.equals("true") || text.equals("false")) return new JsonPrimitive(text.equals("true"));
        if (text.matches("-?(?:0|[1-9][0-9]{0,17})")) return new JsonPrimitive(Long.parseLong(text));
        if (text.matches("-?(?:0|[1-9][0-9]{0,17})\\.[0-9]{1,17}")) return new JsonPrimitive(Double.parseDouble(text));
        return new JsonPrimitive(text);
    }

    /** The items of a savable list body {@code a|b|}: split on {@code |} outside brackets, one final {@code |} dropped, {@code &pipe} unescaped. */
    static List<String> listItems(String body) throws Invalid {
        List<String> items = new ArrayList<>();
        String text = body.endsWith("|") ? body.substring(0, body.length() - 1) : body;
        if (text.isEmpty()) return items;
        int depth = 0, start = 0;
        for (int index = 0; index < text.length(); index++) {
            char at = text.charAt(index);
            if (at == '[') depth++;
            else if (at == ']') { if (--depth < 0) throw new Invalid("unbalanced brackets"); }
            else if (at == '|' && depth == 0) { items.add(item(text.substring(start, index))); start = index + 1; }
        }
        if (depth != 0) throw new Invalid("unbalanced brackets");
        items.add(item(text.substring(start)));
        return items;
    }

    private static String item(String text) { return text.contains("[") ? text : text.replace("&pipe", "|").replace("&amp", "&"); }

    // ---- Expiration ----

    /**
     * The epoch millisecond of a Denizen expiration: a number of milliseconds, or a time tag
     * {@code time@2030/01/01_00:00:00:0000_+00:00} (year/month/day, hour:minute:second:millisecond, UTC offset). 0 when
     * it cannot be read. Computed with whole-number calendar arithmetic, so both converters give the same number.
     */
    static long expiration(String text) {
        if (text.matches("[0-9]{1,15}")) return Long.parseLong(text);
        String value = text.startsWith("time@") ? text.substring(5) : text;
        if (!value.matches("[0-9]{4}/[0-9]{2}/[0-9]{2}_[0-9]{2}:[0-9]{2}:[0-9]{2}:[0-9]{1,4}_[+-][0-9]{2}:[0-9]{2}")) return 0;
        long year = Long.parseLong(value.substring(0, 4)), month = Long.parseLong(value.substring(5, 7)), day = Long.parseLong(value.substring(8, 10));
        String[] clock = value.substring(11, value.indexOf('_', 11)).split(":");
        String zone = value.substring(value.indexOf('_', 11) + 1);
        long hour = Long.parseLong(clock[0]), minute = Long.parseLong(clock[1]), second = Long.parseLong(clock[2]), milli = Long.parseLong(clock[3]);
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return 0;
        long offset = (Long.parseLong(zone.substring(1, 3)) * 60 + Long.parseLong(zone.substring(4, 6))) * (zone.charAt(0) == '-' ? -1 : 1);
        long days = daysFromCivil(year, month, day);
        long millis = ((days * 24 + hour) * 60 + minute - offset) * 60_000 + second * 1000 + milli;
        return millis > 0 ? millis : 0;
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm). */
    static long daysFromCivil(long year, long month, long day) {
        long y = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
