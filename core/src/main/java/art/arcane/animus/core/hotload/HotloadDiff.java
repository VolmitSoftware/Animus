package art.arcane.animus.core.hotload;

import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The keys a hotload changed, read from the parsed TOML or JSON on both sides. Text that does not parse is compared whole under {@code $}. */
public final class HotloadDiff {
    public static final String MISSING = "<missing>", REMOVED = "<removed>";

    /** One changed key: its dotted path ({@code npcs.viewDistance}, {@code pages[0].title}) and both values as written. */
    public record Change(String key, String before, String after) {}

    private HotloadDiff() {}

    /** Changed keys in key order. {@code path} picks the parser: {@code .toml} is TOML, anything else JSON. */
    public static List<Change> diff(String path, String before, String after) {
        boolean toml = path.toLowerCase(Locale.ROOT).endsWith(".toml");
        Map<String, String> left = flatten(before, toml), right = flatten(after, toml);
        Set<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            String old = left.getOrDefault(key, MISSING), next = right.getOrDefault(key, REMOVED);
            if (left.containsKey(key) && right.containsKey(key) && Objects.equals(old, next)) continue;
            changes.add(new Change(key, old, next));
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

    private static Map<String, String> flatten(String raw, boolean toml) {
        Map<String, String> out = new TreeMap<>();
        if (raw == null || raw.isBlank()) return out;
        JsonElement parsed;
        try { parsed = toml ? TomlCodec.toJsonElement(raw) : JsonParser.parseString(raw); }
        catch (java.io.IOException | RuntimeException unparsable) { parsed = null; }
        if (parsed == null || parsed.isJsonNull()) { out.put("$", raw); return out; }
        flatten("", parsed, out);
        return out;
    }

    private static void flatten(String path, JsonElement element, Map<String, String> out) {
        String key = path.isEmpty() ? "$" : path;
        if (element == null || element.isJsonNull()) { out.put(key, "null"); return; }
        if (element.isJsonPrimitive()) { out.put(key, element.toString()); return; }
        if (element.isJsonArray()) {
            if (element.getAsJsonArray().isEmpty()) { out.put(key, "[]"); return; }
            for (int index = 0; index < element.getAsJsonArray().size(); index++)
                flatten(path + "[" + index + "]", element.getAsJsonArray().get(index), out);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.isEmpty()) { out.put(key, "{}"); return; }
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            flatten(path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), entry.getValue(), out);
    }
}
