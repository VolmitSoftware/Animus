package art.arcane.animus.core.gui.exchange;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites Gloss's PlaceholderAPI calls into the {@code %placeholder%} text Animus fills. A {@code {{ }}} hole that holds
 * only {@code papi(key)}, {@code papi(key, fallback)}, {@code papiNumber(...)} of the same shape, or the three-argument
 * form for the {@code viewer} or {@code player} role becomes {@code %key%}. A call inside a larger expression, or for
 * another role, stays as written, and the validator refuses it. The fallback is dropped: an unfilled placeholder shows
 * as written.
 */
final class GlossPlaceholders {
    private static final String LITERAL = "'[^'\\\\]*'|\"[^\"\\\\]*\"";
    private static final String ARGUMENT = "(?:" + LITERAL + "|-?\\d+(?:\\.\\d+)?)";
    private static final Pattern CALL = Pattern.compile("\\{\\{\\s*papi(?:Number)?\\s*\\(\\s*(" + LITERAL + ")(?:\\s*,\\s*(" + ARGUMENT + "))?(?:\\s*,\\s*(" + ARGUMENT + "))?\\s*\\)\\s*}}");
    private static final Pattern KEY = Pattern.compile("%?([^%\\s{}]+)%?");
    private static final Set<String> VIEWER_ROLES = Set.of("viewer", "player");

    private GlossPlaceholders() {}

    /** Text with its translatable calls rewritten, and how many there were. */
    record Translated(String text, int calls) {}

    static Translated translate(String text) {
        if (!text.contains("papi")) return new Translated(text, 0);
        Matcher call = CALL.matcher(text);
        StringBuilder out = new StringBuilder();
        int calls = 0;
        while (call.find()) {
            String key = unquote(call.group(1));
            if (call.group(3) != null) {
                if (!VIEWER_ROLES.contains(key) || !isLiteral(call.group(2))) { call.appendReplacement(out, Matcher.quoteReplacement(call.group())); continue; }
                key = unquote(call.group(2));
            }
            Matcher name = KEY.matcher(key);
            if (!name.matches()) { call.appendReplacement(out, Matcher.quoteReplacement(call.group())); continue; }
            call.appendReplacement(out, Matcher.quoteReplacement("%" + name.group(1) + "%"));
            calls++;
        }
        call.appendTail(out);
        return new Translated(out.toString(), calls);
    }

    /** Rewrites every string in {@code value} in place and returns the number of calls rewritten. */
    static int translate(JsonElement value) {
        int calls = 0;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
                if (isString(entry.getValue())) {
                    Translated translated = translate(entry.getValue().getAsString());
                    if (translated.calls() > 0) { object.addProperty(entry.getKey(), translated.text()); calls += translated.calls(); }
                } else calls += translate(entry.getValue());
            }
        } else if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<JsonElement> items = new ArrayList<>(array.asList());
            for (int index = 0; index < items.size(); index++) {
                JsonElement item = items.get(index);
                if (isString(item)) {
                    Translated translated = translate(item.getAsString());
                    if (translated.calls() > 0) { array.set(index, new JsonPrimitive(translated.text())); calls += translated.calls(); }
                } else calls += translate(item);
            }
        }
        return calls;
    }

    private static boolean isString(JsonElement value) { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }

    private static boolean isLiteral(String argument) { return argument.startsWith("'") || argument.startsWith("\""); }

    private static String unquote(String literal) { return literal.substring(1, literal.length() - 1); }
}
