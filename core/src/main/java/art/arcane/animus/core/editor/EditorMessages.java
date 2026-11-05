package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Strict readers for the request messages of schema/editor-v1.schema.json. Unknown fields are refused, as the schema says. */
public final class EditorMessages {
    public static final String DOCUMENT_ID = "[a-z0-9_.:/-]{1,512}", HASH = "sha256:[0-9a-f]{64}", KEY = "[A-Za-z0-9_-]{16,128}",
            TOKEN_ID = "[A-Za-z0-9_-]{8,64}", SECRET = "[A-Za-z0-9_-]{43}", OBJECT_ID = "[A-Za-z0-9_-]{8,64}";

    private EditorMessages() {}

    /** One entry of a change set. {@code baseRevision} empty means the document must not exist yet. */
    public record Change(DocumentKind kind, String id, Optional<Long> baseRevision, Optional<String> baseHash, Optional<JsonObject> source) {
        public boolean delete() { return source.isEmpty(); }

        public String name() { return kind.wireName() + ":" + id; }
    }

    public record ChangeSet(String idempotencyKey, List<Change> documents, Optional<String> message) {
        public ChangeSet { documents = List.copyOf(documents); }
    }

    public static JsonObject body(EditorRequest request) throws EditorError {
        JsonElement body = request.body().orElseThrow(() -> new EditorError(EditorError.Code.INVALID_REQUEST, "A JSON body is required."));
        if (!body.isJsonObject()) throw new EditorError(EditorError.Code.INVALID_REQUEST, "The body must be one JSON object.");
        return body.getAsJsonObject();
    }

    public static ChangeSet changeSet(JsonObject body, int maximumDocuments) throws EditorError {
        fields(body, "$", Set.of("idempotencyKey", "documents", "message"));
        String key = string(body, "idempotencyKey", KEY, "$.idempotencyKey");
        Optional<String> message = optionalString(body, "message", 512);
        return new ChangeSet(key, changes(body, maximumDocuments), message);
    }

    /** The {@code documents} array of a change set, validate request or preview request. */
    public static List<Change> changes(JsonObject body, int maximumDocuments) throws EditorError {
        JsonElement documents = body.get("documents");
        if (documents == null || !documents.isJsonArray()) throw invalid("$.documents: expected an array");
        if (documents.getAsJsonArray().isEmpty()) throw invalid("$.documents: at least one document");
        if (documents.getAsJsonArray().size() > maximumDocuments) throw new EditorError(EditorError.Code.TOO_LARGE, "$.documents: at most " + maximumDocuments + " documents");
        List<Change> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonElement element : documents.getAsJsonArray()) {
            String at = "$.documents[" + index++ + "]";
            if (!element.isJsonObject()) throw invalid(at + ": expected an object");
            JsonObject entry = element.getAsJsonObject();
            fields(entry, at, Set.of("kind", "id", "baseRevision", "baseHash", "source", "delete"));
            String kindName = string(entry, "kind", "[a-z-]{1,32}", at + ".kind");
            DocumentKind kind = DocumentKind.parse(kindName).orElseThrow(() -> invalid(at + ".kind: unknown kind " + kindName));
            String id = string(entry, "id", DOCUMENT_ID, at + ".id");
            if (!entry.has("baseRevision") || !entry.has("baseHash")) throw invalid(at + ": baseRevision and baseHash are required (null for a new document)");
            Optional<Long> revision;
            JsonElement rawRevision = entry.get("baseRevision");
            if (rawRevision.isJsonNull()) revision = Optional.empty();
            else if (rawRevision.isJsonPrimitive() && rawRevision.getAsJsonPrimitive().isNumber()) {
                try {
                    long value = rawRevision.getAsBigDecimal().longValueExact();
                    if (value < 0) throw new ArithmeticException();
                    revision = Optional.of(value);
                } catch (ArithmeticException notInteger) { throw invalid(at + ".baseRevision: expected a non-negative integer or null"); }
            } else throw invalid(at + ".baseRevision: expected a non-negative integer or null");
            Optional<String> hash;
            JsonElement rawHash = entry.get("baseHash");
            if (rawHash.isJsonNull()) hash = Optional.empty();
            else hash = Optional.of(string(entry, "baseHash", HASH, at + ".baseHash"));
            if (revision.isPresent() != hash.isPresent()) throw invalid(at + ": baseRevision and baseHash are both null for a new document or both set");
            boolean delete = entry.has("delete");
            if (delete && (!entry.get("delete").isJsonPrimitive() || !entry.getAsJsonPrimitive("delete").isBoolean() || !entry.get("delete").getAsBoolean()))
                throw invalid(at + ".delete: expected true");
            if (delete == entry.has("source")) throw invalid(at + ": give exactly one of source and delete");
            if (delete && revision.isEmpty()) throw invalid(at + ": a delete needs the baseRevision and baseHash of the document it removes");
            Optional<JsonObject> source = Optional.empty();
            if (!delete) {
                if (!entry.get("source").isJsonObject()) throw invalid(at + ".source: expected an object");
                source = Optional.of(entry.getAsJsonObject("source"));
            }
            if (!seen.add(kind.wireName() + "\n" + id)) throw invalid(at + ": " + kind.wireName() + " " + id + " appears twice");
            changes.add(new Change(kind, id, revision, hash, source));
        }
        return changes;
    }

    public static void fields(JsonObject object, String at, Set<String> allowed) throws EditorError {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw invalid(at + "." + key + ": unknown field");
    }

    public static String string(JsonObject object, String key, String pattern, String at) throws EditorError {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches(pattern))
            throw invalid(at + ": expected a string matching " + pattern);
        return value.getAsString();
    }

    public static Optional<String> optionalString(JsonObject object, String key, int maximumLength) throws EditorError {
        JsonElement value = object.get(key);
        if (value == null) return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() > maximumLength)
            throw invalid("$." + key + ": expected a string of at most " + maximumLength + " characters");
        return Optional.of(value.getAsString());
    }

    public static EditorError invalid(String message) { return new EditorError(EditorError.Code.INVALID_REQUEST, message); }

    /** Instants as the schema writes them: UTC, whole seconds. */
    public static String instant(Instant instant) { return instant.truncatedTo(ChronoUnit.SECONDS).toString(); }
}
