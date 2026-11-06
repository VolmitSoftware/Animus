package art.arcane.animus.core.hotload;

import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The keys a hotload changed, read from the parsed TOML or JSON on both sides. Text that does not parse is compared whole under {@code $}. */
public final class HotloadDiff {
    public static final String MISSING = "<missing>", REMOVED = "<removed>";

    /** One changed key: its dotted path ({@code npcs.viewDistance}, {@code pages[0].title}) and both values as written. */
    public record Change(String key, String before, String after) {}

    /** One flattened value as written, and its numeric value when it is a JSON number. */
    private record Leaf(String written, BigDecimal number) {
        static Leaf of(String written) { return new Leaf(written, null); }

        /** Numbers compare by value, so a re-save that writes {@code 0.0} as {@code 0} is no change; everything else as written. */
        boolean same(Leaf other) {
            if (number != null && other.number != null) return number.compareTo(other.number) == 0;
            return number == null && other.number == null && written.equals(other.written);
        }
    }

    private HotloadDiff() {}

    /** Changed keys in key order. {@code path} picks the parser: {@code .toml} is TOML, anything else JSON. */
    public static List<Change> diff(String path, String before, String after) {
        boolean toml = path.toLowerCase(Locale.ROOT).endsWith(".toml");
        Map<String, Leaf> left = flatten(before, toml), right = flatten(after, toml);
        Set<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            Leaf old = left.get(key), next = right.get(key);
            if (old != null && next != null && old.same(next)) continue;
            changes.add(new Change(key, old == null ? MISSING : old.written(), next == null ? REMOVED : next.written()));
        }
        return changes;
    }

    /** One line per value: newlines are shown as {@code \n} and long values end with {@code ...}. */
    public static String compact(String value, int maximum) {
        if (value == null) return "null";
        String compact = value.replace("\r", "\\r").replace("\n", "\\n");
        if (maximum < 4 || compact.length() <= maximum) return compact;
        return compact.substring(0, maximum - 3) + "...";
    }

    private static Map<String, Leaf> flatten(String raw, boolean toml) {
        Map<String, Leaf> out = new TreeMap<>();
        if (raw == null || raw.isBlank()) return out;
        JsonElement parsed;
        try { parsed = toml ? TomlCodec.toJsonElement(raw) : JsonParser.parseString(raw); }
        catch (java.io.IOException | RuntimeException unparsable) { parsed = null; }
        if (parsed == null || parsed.isJsonNull()) { out.put("$", Leaf.of(raw)); return out; }
        flatten("", parsed, out);
        return out;
    }

    private static void flatten(String path, JsonElement element, Map<String, Leaf> out) {
        String key = path.isEmpty() ? "$" : path;
        if (element == null || element.isJsonNull()) { out.put(key, Leaf.of("null")); return; }
        if (element.isJsonPrimitive()) { out.put(key, primitive(element.getAsJsonPrimitive())); return; }
        if (element.isJsonArray()) {
            if (element.getAsJsonArray().isEmpty()) { out.put(key, Leaf.of("[]")); return; }
            for (int index = 0; index < element.getAsJsonArray().size(); index++)
                flatten(path + "[" + index + "]", element.getAsJsonArray().get(index), out);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.isEmpty()) { out.put(key, Leaf.of("{}")); return; }
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            flatten(path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), entry.getValue(), out);
    }

    private static Leaf primitive(JsonPrimitive value) {
        if (!value.isNumber()) return Leaf.of(value.toString());
        try { return new Leaf(value.toString(), new BigDecimal(value.getAsString())); }
        catch (NumberFormatException special) { return Leaf.of(value.toString()); }
    }
}
