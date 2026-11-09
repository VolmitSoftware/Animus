package art.arcane.animus.core.gui;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;

/**
 * The smallest chest GUI and hologram files the in-game commands write: an empty chest window with a title, and a
 * hologram of one text line where the player stands. Both are edited afterwards in the toolkit or the web editor.
 */
public final class GuiDrafts {
    /** Holograms sit this far above the feet, so the text starts at head height. */
    static final double HEAD = 1.5;

    private GuiDrafts() {}

    /** An empty chest GUI {@code rows} high, 1 to 6, titled {@code title}. */
    public static JsonObject inventory(String title, int rows) throws IOException {
        if (rows < 1 || rows > 6) throw new IOException("rows= is 1 to 6");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", GuiDocument.SCHEMA_VERSION);
        document.addProperty("revision", 1);
        document.addProperty("title", title);
        document.addProperty("resolution", "9x" + rows);
        return document;
    }

    /** A hologram of one text line at head height above {@code at}, facing {@code yaw}. */
    public static JsonObject hologram(WorldPosition at, float yaw, String text) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", HologramDocument.SCHEMA_VERSION);
        document.addProperty("revision", 1);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("world", at.world());
        JsonArray position = new JsonArray();
        position.add(round(at.point().x())); position.add(round(at.point().y() + HEAD)); position.add(round(at.point().z()));
        anchor.add("position", position);
        document.add("anchor", anchor);
        document.addProperty("yaw", Math.round(yaw * 100) / 100.0);
        JsonArray lines = new JsonArray();
        lines.add(text);
        document.add("lines", lines);
        return document;
    }

    /**
     * Writes a new chest GUI or hologram file after checking it loads, refusing an id whose file exists. Returns the line to
     * answer, with its transaction.
     */
    public static String create(DocumentStore store, DocumentKind kind, String id, JsonObject document) throws IOException {
        String path;
        try { path = EditorDocuments.pathFor(kind, id); }
        catch (EditorError invalid) { throw new IOException("An id is namespace:path in lowercase, such as town:welcome"); }
        if (store.read(path) != null) throw new IOException(path + " already exists; pick another id");
        String text = JsonDocuments.write(document);
        List<String> problems = kind == DocumentKind.HOLOGRAM ? HologramDocument.parse(id, text).problems() : GuiDocument.parse(id, text).problems();
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
        return "Created " + path + "; tx " + store.commit(List.of(new DocumentStore.Change(path, "missing", text)));
    }

    private static double round(double value) { return Math.round(value * 100) / 100.0; }
}
