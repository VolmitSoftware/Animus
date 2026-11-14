package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * {@code GET /v1/catalog}, {@code GET /v1/documents/{kind}/{id}} and {@code POST /v1/documents/read}. Catalog entries come
 * from {@link EditorCatalog}; a page and a batch answer stay within {@link #ANSWER_BYTES}, and on the relay within its frame.
 */
final class EditorDocumentRoutes {
    static final int PAGE_ENTRIES = 1000, ANSWER_BYTES = 512 * 1024, RELAY_MARGIN = 16 * 1024, MAXIMUM_REFS = 200;

    /** One requested document; {@code kind} is empty when the name is not a kind. */
    private record Ref(String kindName, Optional<DocumentKind> kind, String id) {
        JsonObject json() {
            JsonObject ref = new JsonObject();
            ref.addProperty("kind", kindName);
            ref.addProperty("id", id);
            return ref;
        }
    }

    private final EditorApi api;

    EditorDocumentRoutes(EditorApi api) { this.api = api; }

    /** The most entry bytes one answer carries on this request's path. */
    private int budget(EditorRequest request) {
        if (request.transport() != EditorRequest.Transport.RELAY) return ANSWER_BYTES;
        return Math.max(RELAY_MARGIN, Math.min(ANSWER_BYTES, api.parts().relay().frameBytes() - RELAY_MARGIN));
    }

    CompletionStage<EditorResponse> catalog(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        Map<String, String> query = EditorReplies.query(request);
        EditorMessages.fields(EditorMessages.queryObject(query), "$query", Set.of("kind", "after"));
        Set<DocumentKind> kinds = EnumSet.allOf(DocumentKind.class);
        String kindName = query.get("kind");
        if (kindName != null && !kindName.isEmpty())
            kinds = EnumSet.of(DocumentKind.parse(kindName).orElseThrow(() -> EditorMessages.invalid("kind: unknown kind " + kindName)));
        String rawAfter = query.get("after");
        Optional<String> after = rawAfter == null || rawAfter.isEmpty() ? Optional.empty() : Optional.of(EditorMessages.cursor(rawAfter));
        Set<DocumentKind> chosen = kinds;
        int budget = budget(request);
        return api.files(token, () -> {
            JsonArray documents = new JsonArray();
            String next = null;
            int bytes = 0;
            for (EditorCatalog.Entry entry : api.parts().catalog().current(chosen)) {
                String key = entry.kind().ordinal() + "\n" + entry.path();
                if (after.isPresent() && EditorMessages.compareCursor(key, after.get()) <= 0) continue;
                if (!EditorApi.visible(token, entry.kind(), entry.id())) continue;
                JsonObject json = entry.json();
                int size = size(json);
                if (documents.size() == PAGE_ENTRIES || !documents.isEmpty() && bytes + size > budget) {
                    next = EditorMessages.encodeCursor(EditorMessages.cursorKey(documents.get(documents.size() - 1).getAsJsonObject()));
                    break;
                }
                documents.add(json);
                bytes += size;
            }
            JsonObject body = new JsonObject();
            body.add("documents", documents);
            if (next == null) body.add("next", JsonNull.INSTANCE); else body.addProperty("next", next);
            return EditorResponse.json(200, body);
        });
    }

    CompletionStage<EditorResponse> document(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        DocumentKind kind = DocumentKind.parse(segments[1]).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "Unknown kind " + segments[1] + "."));
        List<String> pieces = new ArrayList<>();
        for (int index = 2; index < segments.length; index++) pieces.add(EditorApi.decode(segments[index]));
        String id = String.join("/", pieces);
        if (!id.matches(EditorMessages.DOCUMENT_ID)) throw EditorMessages.invalid("'" + id + "' is not a document id.");
        EditorApi.group(token, kind, id);
        return api.files(token, () -> EditorResponse.json(200, body(kind, id).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No " + kind.wireName() + " " + id + "."))));
    }

    /**
     * Up to {@link #MAXIMUM_REFS} documents in request order, within the answer budget. A ref that cannot be read is
     * reported in {@code errors}; refs past the budget come back in {@code remaining}. A single document larger than the
     * budget comes alone, unless it is larger than the relay frame.
     */
    CompletionStage<EditorResponse> read(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("refs"));
        List<Ref> refs = refs(body);
        int budget = budget(request);
        long alone = request.transport() == EditorRequest.Transport.RELAY ? Math.max(RELAY_MARGIN, api.parts().relay().frameBytes() - RELAY_MARGIN) : Long.MAX_VALUE;
        return api.files(token, () -> {
            JsonArray documents = new JsonArray(), errors = new JsonArray(), remaining = new JsonArray();
            int bytes = 0;
            for (Ref ref : refs) {
                if (!remaining.isEmpty() || bytes >= budget) { remaining.add(ref.json()); continue; }
                if (ref.kind().isEmpty()) { errors.add(error(ref, EditorError.Code.INVALID_REQUEST, "Unknown kind " + ref.kindName() + ".")); continue; }
                DocumentKind kind = ref.kind().get();
                if (!ref.id().matches(EditorMessages.DOCUMENT_ID)) { errors.add(error(ref, EditorError.Code.INVALID_REQUEST, "'" + ref.id() + "' is not a document id.")); continue; }
                if (!EditorApi.visible(token, kind, ref.id())) { errors.add(error(ref, EditorError.Code.GROUP_DENIED, "This token cannot reach NPC folder npcs/" + EditorApi.folder(ref.id()) + ".")); continue; }
                Optional<JsonObject> answer;
                try { answer = body(kind, ref.id()); }
                catch (EditorError refused) { errors.add(error(ref, refused.code(), refused.getMessage())); continue; }
                if (answer.isEmpty()) { errors.add(error(ref, EditorError.Code.NOT_FOUND, "No " + kind.wireName() + " " + ref.id() + ".")); continue; }
                int size = size(answer.get());
                if (bytes + size > budget && !documents.isEmpty()) { remaining.add(ref.json()); continue; }
                if (size > alone) { errors.add(error(ref, EditorError.Code.TOO_LARGE, "Over the relay frame limit; read it on the direct path.")); continue; }
                documents.add(answer.get());
                bytes += size;
            }
            JsonObject answer = new JsonObject();
            answer.add("documents", documents);
            answer.add("errors", errors);
            answer.add("remaining", remaining);
            return EditorResponse.json(200, answer);
        });
    }

    /** {@code refs}: 1 to {@link #MAXIMUM_REFS} {@code {kind, id}} objects, each named once. */
    private static List<Ref> refs(JsonObject body) throws EditorError {
        JsonElement value = body.get("refs");
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAXIMUM_REFS)
            throw EditorMessages.invalid("refs: 1 to " + MAXIMUM_REFS + " {kind, id} references.");
        List<Ref> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            String at = "$.refs[" + index++ + "]";
            if (!element.isJsonObject()) throw EditorMessages.invalid(at + ": expected {kind, id}.");
            JsonObject ref = element.getAsJsonObject();
            EditorMessages.fields(ref, at, Set.of("kind", "id"));
            String kind = text(ref, "kind", at), id = text(ref, "id", at);
            if (!seen.add(kind + "\n" + id)) throw EditorMessages.invalid(at + ": " + kind + " " + id + " is named twice.");
            refs.add(new Ref(kind, DocumentKind.parse(kind), id));
        }
        return refs;
    }

    private static String text(JsonObject ref, String key, String at) throws EditorError {
        JsonElement value = ref.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty() || value.getAsString().length() > 512)
            throw EditorMessages.invalid(at + "." + key + ": expected a string.");
        return value.getAsString();
    }

    private static JsonObject error(Ref ref, EditorError.Code code, String message) {
        JsonObject error = ref.json();
        error.addProperty("code", code.wireName());
        error.addProperty("message", message.length() > 2048 ? message.substring(0, 2048) : message);
        return error;
    }

    /** What {@code GET /v1/documents/{kind}/{id}} answers, or empty when there is no such document. Call on the file lane. */
    private Optional<JsonObject> body(DocumentKind kind, String id) throws EditorError, IOException {
        EditorDocuments.Stored stored = api.parts().documents().read(api.parts().documents().locate(kind, id));
        if (!stored.exists()) return Optional.empty();
        JsonObject body = EditorChanges.catalogEntry(kind, id, stored.location().path(), stored.revision(), stored.hash());
        JsonObject source;
        JsonArray diagnostics;
        try {
            source = JsonDocuments.object(stored.content());
            diagnostics = api.parts().documents().validate(kind, id, source);
        } catch (IOException unreadable) {
            source = new JsonObject();
            diagnostics = new JsonArray();
            diagnostics.add(EditorDocuments.diagnostic("error", "$", "The stored file is not one JSON object: " + unreadable.getMessage(), "json"));
        }
        body.add("source", source);
        body.add("summary", api.parts().catalog().summary(stored, source));
        if (!diagnostics.isEmpty()) body.add("diagnostics", diagnostics);
        return Optional.of(body);
    }

    private static int size(JsonObject json) { return json.toString().getBytes(StandardCharsets.UTF_8).length; }
}
