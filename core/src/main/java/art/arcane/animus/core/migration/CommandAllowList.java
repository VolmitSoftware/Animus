package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code commandNamespaces} under {@code [behaviors.actions]} in {@code config.toml}, as an import needs it for the command
 * behaviors it wrote. The key is edited in place, so the operator's comments and layout stay; a file this cannot edit
 * safely is refused, with the line to add by hand.
 */
final class CommandAllowList {
    static final String CONFIG = "config.toml";
    private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\s*behaviors\\s*\\.\\s*actions\\s*]\\s*(#.*)?$");
    private static final Pattern ANY_TABLE = Pattern.compile("^\\s*\\[.*");
    private static final Pattern KEY = Pattern.compile("^(\\s*)commandNamespaces\\s*=.*");

    private CommandAllowList() {}

    /** The namespaces of these behavior ids, sorted and without repeats. */
    static List<String> namespaces(Collection<String> behaviors) {
        TreeSet<String> found = new TreeSet<>();
        for (String id : behaviors) { int colon = id.indexOf(':'); if (colon > 0) found.add(id.substring(0, colon)); }
        return List.copyOf(found);
    }

    /** The config file's text, or an empty document when there is none yet. */
    static String read(DocumentStore store) throws IOException {
        Path file = store.resolve(CONFIG);
        return Files.exists(file) ? Files.readString(file) : "";
    }

    /** The namespaces the config already allows. */
    static List<String> allowed(String toml) throws IOException {
        JsonElement document;
        try { document = toml.isBlank() ? new JsonObject() : TomlCodec.toJsonElement(toml); }
        catch (RuntimeException invalid) { throw new IOException("config.toml is not valid TOML: " + invalid.getMessage(), invalid); }
        JsonElement behaviors = document.isJsonObject() ? document.getAsJsonObject().get("behaviors") : null;
        JsonElement actions = behaviors != null && behaviors.isJsonObject() ? behaviors.getAsJsonObject().get("actions") : null;
        JsonElement list = actions != null && actions.isJsonObject() ? actions.getAsJsonObject().get("commandNamespaces") : null;
        if (list == null) return List.of();
        if (!list.isJsonArray()) throw new IOException("config.toml [behaviors.actions] commandNamespaces is not a list");
        List<String> names = new ArrayList<>();
        for (JsonElement name : list.getAsJsonArray()) names.add(name.getAsString());
        return names;
    }

    /** {@code toml} with {@code commandNamespaces} set to exactly {@code namespaces}; the rest of the text is unchanged. */
    static String with(String toml, List<String> namespaces) throws IOException {
        StringBuilder rendered = new StringBuilder("commandNamespaces = [");
        for (int i = 0; i < namespaces.size(); i++) rendered.append(i == 0 ? "" : ", ").append('"').append(namespaces.get(i)).append('"');
        rendered.append(']');
        List<String> lines = new ArrayList<>(List.of(toml.split("\n", -1)));
        int table = -1;
        for (int i = 0; i < lines.size() && table < 0; i++) if (TABLE.matcher(lines.get(i)).matches()) table = i;
        String result;
        if (table < 0) {
            String base = toml.isEmpty() || toml.endsWith("\n") ? toml : toml + "\n";
            result = base + (base.isEmpty() ? "" : "\n") + "[behaviors.actions]\n" + rendered + "\n";
        } else {
            int key = -1;
            for (int i = table + 1; i < lines.size() && !ANY_TABLE.matcher(lines.get(i)).matches(); i++)
                if (KEY.matcher(lines.get(i)).matches()) { key = i; break; }
            if (key < 0) lines.add(table + 1, rendered.toString());
            else {
                int end = key;
                // A list written over several lines ends at the first line holding its closing bracket.
                if (!value(lines.get(key)).contains("]")) while (end + 1 < lines.size() && !value(lines.get(end)).contains("]")) end++;
                String indent = KEY.matcher(lines.get(key)).replaceFirst("$1");
                for (int i = end; i > key; i--) lines.remove(i);
                lines.set(key, indent + rendered);
            }
            result = String.join("\n", lines);
        }
        if (!allowed(result).equals(namespaces))
            throw new IOException("config.toml could not be edited safely; set [behaviors.actions] " + rendered + " by hand");
        return result;
    }

    /** The part of a line after its {@code =}, without a trailing comment. */
    private static String value(String line) {
        int equals = line.indexOf('='), hash = line.indexOf('#', Math.max(0, equals));
        return line.substring(equals < 0 ? 0 : equals + 1, hash < 0 ? line.length() : hash);
    }
}
