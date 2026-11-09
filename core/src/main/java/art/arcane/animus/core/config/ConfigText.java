package art.arcane.animus.core.config;

import art.arcane.animus.core.behavior.schema.Suggestions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each table and key of a {@code config.toml} sits, for messages that name a line. It reads plain {@code [table]}
 * headers and {@code key = value} lines, which is what Animus writes and what owners edit; anything it cannot place simply
 * has no line in the message.
 */
final class ConfigText {
    private static final Pattern HEADER = Pattern.compile("^\\s*\\[\\s*([A-Za-z0-9_.\"' -]+?)\\s*]\\s*(?:#.*)?$");
    private static final Pattern KEY = Pattern.compile("^\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z0-9_.-]+)\\s*=(.*)$");
    private static final Pattern PARSER_LINE = Pattern.compile("^(.*?) on line (\\d+)(?:: (.*))?$", Pattern.DOTALL);
    private final String[] source;
    /** Dotted path, such as {@code npcs.viewDistance} or {@code editor.notify}, to its 1-based line. */
    private final Map<String, Integer> lines = new HashMap<>();
    private final List<String> empty = new ArrayList<>();

    private ConfigText(String text) {
        source = text == null ? new String[0] : text.split("\n", -1);
        String table = "";
        boolean multiline = false;
        for (int index = 0; index < source.length; index++) {
            String line = source[index];
            int quotes = count(line, "\"\"\"") + count(line, "'''");
            if (multiline) { if (quotes % 2 == 1) multiline = false; continue; }
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                table = unquote(header.group(1).replace(" ", ""));
                lines.putIfAbsent(table, index + 1);
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (!key.matches()) continue;
            String path = (table.isEmpty() ? "" : table + ".") + unquote(key.group(1));
            lines.putIfAbsent(path, index + 1);
            String value = key.group(2).strip();
            if (value.isEmpty() || value.startsWith("#")) empty.add("line " + (index + 1) + ": " + path + " has no value");
            if (quotes % 2 == 1) multiline = true;
        }
    }

    static ConfigText of(String text) { return new ConfigText(text); }

    /** {@code " line N"} for a dotted path, or empty when it is not on its own line. */
    String at(String path) {
        Integer line = lines.get(path);
        return line == null ? "" : " line " + line;
    }

    /** The first {@code key =} with nothing after it; the TOML reader would otherwise drop the key without a word. */
    String emptyValue() { return empty.isEmpty() ? "" : empty.getFirst(); }

    /**
     * Where a validator's message points: {@code table} plus the key it starts with, such as {@code viewDistance must be}
     * or {@code engine.executions must be}.
     */
    String atMessage(String table, String message) {
        String first = message.split("[ :]", 2)[0];
        String found = at(table + "." + first);
        if (!found.isEmpty() || !message.startsWith(first + ": ")) return found;
        String rest = message.substring(first.length() + 2).split(" ", 2)[0];
        return at(table + "." + first + "." + rest);
    }

    /**
     * Warns about every key and table of {@code data} that {@code template} does not have, and removes it, so a typo or a
     * key from another version never stops a start or a hotload.
     */
    void dropUnknown(Path label, JsonObject data, JsonObject template, String table, Consumer<String> warnings) {
        for (String key : List.copyOf(data.keySet())) {
            String path = table.isEmpty() ? key : table + "." + key;
            if (!template.has(key)) {
                data.remove(key);
                String where = table.isEmpty() ? "at the top level" : "in [" + table + "]";
                warnings.accept(label + at(path) + ": " + key + " is not a setting " + where + "; it is ignored"
                        + Suggestions.didYouMean(key, template.keySet()));
                continue;
            }
            JsonElement value = data.get(key), known = template.get(key);
            if (value.isJsonObject() && known.isJsonObject()) dropUnknown(label, value.getAsJsonObject(), known.getAsJsonObject(), path, warnings);
        }
    }

    /**
     * A TOML syntax error as one line with the line and column, such as
     * {@code line 275, column 1: Invalid key: [npcs}. The reader's own message is the innermost cause.
     */
    String syntax(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage().strip();
        Matcher found = PARSER_LINE.matcher(detail);
        if (!found.matches()) return "invalid TOML: " + detail;
        int line = Integer.parseInt(found.group(2));
        String near = found.group(3) == null ? "" : found.group(3).strip();
        String column = "";
        if (!near.isEmpty() && line >= 1 && line <= source.length) {
            int index = source[line - 1].indexOf(near);
            if (index >= 0) column = ", column " + (index + 1);
        }
        return "line " + line + column + ": invalid TOML: " + found.group(1).strip() + (near.isEmpty() ? "" : " near " + near);
    }

    private static String unquote(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (char character : key.toCharArray()) if (character != '"' && character != '\'') out.append(character);
        return out.toString();
    }

    private static int count(String line, String token) {
        int found = 0;
        for (int index = line.indexOf(token); index >= 0; index = line.indexOf(token, index + token.length())) found++;
        return found;
    }
}
