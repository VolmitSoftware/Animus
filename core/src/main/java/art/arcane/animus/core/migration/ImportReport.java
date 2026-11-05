package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;

/** Per-field classification for one converted source, as JSON-pointer paths. A later classification of a path wins. */
final class ImportReport {
    enum Status { CONVERTED, CONVERTED_WITH_CHANGES, REQUIRES_REVIEW, UNSUPPORTED_PRESERVED }
    private final Map<String, String> fields = new TreeMap<>();
    private final JsonArray notes = new JsonArray();

    void field(String path, Status status, String note) {
        fields.put(path, status.name());
        if (note != null && !note.isEmpty()) notes.add(path + ": " + note);
    }
    void field(String path, Status status) { field(path, status, null); }
    void note(String note) { notes.add(note); }
    int notes() { return notes.size(); }

    JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("fieldPathFormat", "json-pointer");
        JsonObject byPath = new JsonObject();
        fields.forEach(byPath::addProperty);
        json.add("fields", byPath);
        json.add("notes", notes.deepCopy());
        JsonObject counts = new JsonObject();
        for (String status : fields.values()) counts.addProperty(status, counts.has(status) ? counts.get(status).getAsInt() + 1 : 1);
        json.add("counts", counts);
        return json;
    }
}
