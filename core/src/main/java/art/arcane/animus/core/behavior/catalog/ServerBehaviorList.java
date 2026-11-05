package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The server behaviors enabled in {@code server-behaviors.json} at the data folder root:
 * {@code {"schemaVersion":1,"behaviors":["denizen:world/x"]}}. Only listed server behaviors run. Read on the engine file lane.
 */
public record ServerBehaviorList(long generation, List<String> ids, List<Diagnostic> problems) {
    public static final String PATH = "server-behaviors.json";
    public static final int MAXIMUM = 256;
    /** Before the first read: nothing is enabled and nothing has started. */
    public static final ServerBehaviorList UNLOADED = new ServerBehaviorList(0, List.of(), List.of());

    public ServerBehaviorList { Objects.requireNonNull(ids); ids = List.copyOf(ids); problems = List.copyOf(problems); }

    public boolean loaded() { return generation > 0; }

    /** A missing file enables nothing. An invalid file enables nothing and reports why. */
    public static ServerBehaviorList read(Path dataDirectory, long generation) {
        Path file = dataDirectory.resolve(PATH);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new ServerBehaviorList(generation, List.of(), List.of());
        try { return new ServerBehaviorList(generation, parse(JsonDocuments.read(file)), List.of()); }
        catch (NoSuchFileException missing) { return new ServerBehaviorList(generation, List.of(), List.of()); }
        catch (IOException | RuntimeException invalid) {
            return new ServerBehaviorList(generation, List.of(), List.of(new Diagnostic(Diagnostic.Severity.ERROR, "server-behaviors-invalid", PATH,
                    "No server behavior is enabled until this file is fixed: " + Objects.toString(invalid.getMessage(), invalid.getClass().getSimpleName()))));
        }
    }

    /** Strict: schemaVersion 1 and at most 256 distinct behavior ids. */
    public static List<String> parse(String json) throws IOException {
        JsonObject document = JsonDocuments.object(json);
        for (String key : document.keySet()) if (!Set.of("schemaVersion", "behaviors").contains(key)) throw new IOException("unsupported field " + key + "; allowed: schemaVersion, behaviors");
        JsonElement version = document.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsDouble() != 1) throw new IOException("schemaVersion must be 1");
        JsonElement behaviors = document.get("behaviors");
        if (behaviors == null || !behaviors.isJsonArray()) throw new IOException("behaviors must be an array of behavior ids");
        JsonArray array = behaviors.getAsJsonArray();
        if (array.size() > MAXIMUM) throw new IOException("at most " + MAXIMUM + " server behaviors");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement id : array) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IOException("behaviors must hold behavior id strings");
            try { BehaviorPath.forId(id.getAsString()); }
            catch (IllegalArgumentException invalid) { throw new IOException(id.getAsString() + ": " + invalid.getMessage()); }
            ids.add(id.getAsString());
        }
        return List.copyOf(ids);
    }

    public static String write(Collection<String> ids) {
        if (ids.size() > MAXIMUM) throw new IllegalArgumentException("at most " + MAXIMUM + " server behaviors");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        JsonArray array = new JsonArray();
        for (String id : new LinkedHashSet<>(ids)) { BehaviorPath.forId(id); array.add(id); }
        document.add("behaviors", array);
        return JsonDocuments.write(document);
    }

    /** The file content with {@code added} appended once each, or the same content when every id is already listed. {@code existing} may be null. */
    public static String append(String existing, Collection<String> added) throws IOException {
        List<String> ids = new ArrayList<>(existing == null ? List.of() : parse(existing));
        boolean changed = false;
        for (String id : added) if (!ids.contains(id)) { ids.add(id); changed = true; }
        if (ids.size() > MAXIMUM) throw new IOException("server-behaviors.json would list more than " + MAXIMUM + " behaviors");
        return changed || existing == null ? write(ids) : existing;
    }
}
