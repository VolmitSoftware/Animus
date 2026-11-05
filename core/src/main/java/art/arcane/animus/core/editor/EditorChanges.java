package art.arcane.animus.core.editor;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Change sets through the existing journal. An apply writes every document and its idempotency record
 * ({@code state/editor/changes/<key>.json}) in one transaction, so an {@code applied} answer and its record are durable
 * together; the change id is the transaction id, whose rollback receipt drives undo. Conflicts and refusals write only their
 * record. Call {@link #apply}, {@link #undo} and {@link #status} on the file lane.
 */
public final class EditorChanges {
    public static final String DIRECTORY = "state/editor/changes";

    /** What a change did. {@code status} is the HTTP status; {@code committed} is true when documents were written. */
    public record Outcome(int status, JsonObject body, Set<DocumentKind> kinds, boolean committed, boolean rollback, JsonArray catalog, JsonArray deleted) {}

    private final EditorDocuments documents;
    private final DocumentStore store;
    private final Clock clock;
    private final Duration retention;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private Map<String, String> changeIds;

    public EditorChanges(EditorDocuments documents, Clock clock, Duration retention) {
        this.documents = Objects.requireNonNull(documents);
        this.store = documents.store();
        this.clock = Objects.requireNonNull(clock);
        this.retention = Objects.requireNonNull(retention);
    }

    /** Marks a key as queued so a status request during the apply answers {@code pending}. */
    public void queued(String key) { pending.add(key); }

    public void finished(String key) { pending.remove(key); }

    public boolean pending(String key) { return pending.contains(key); }

    public synchronized Outcome apply(EditorMessages.ChangeSet set, String requestHash, String tokenId) throws IOException, EditorError {
        Optional<Outcome> replay = replay(set.idempotencyKey(), requestHash);
        if (replay.isPresent()) return replay.get();
        JsonArray conflicts = new JsonArray(), invalid = new JsonArray(), applied = new JsonArray(), catalog = new JsonArray(), deleted = new JsonArray();
        List<DocumentStore.Change> writes = new ArrayList<>();
        JsonArray touched = new JsonArray();
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        for (EditorMessages.Change change : set.documents()) {
            EditorDocuments.Location location = documents.locate(change.kind(), change.id());
            EditorDocuments.Stored current = documents.read(location);
            boolean matches = change.baseRevision().isEmpty() ? !current.exists()
                    : current.exists() && current.revision() == change.baseRevision().get() && current.hash().equals(change.baseHash().orElseThrow());
            if (!matches) { conflicts.add(conflict(change.kind(), change.id(), change.baseRevision(), change.baseHash(), current)); continue; }
            kinds.add(change.kind());
            touched.add(reference(location));
            if (change.delete()) {
                writes.add(new DocumentStore.Change(location.path(), plain(current.content()), null));
                applied.add(version(change.kind(), change.id(), current.revision(), null));
                deleted.add(ref(change.kind(), change.id()));
                continue;
            }
            JsonObject source = change.source().orElseThrow().deepCopy();
            long revision = EditorDocuments.REVISIONED.contains(change.kind()) ? current.revision() + 1 : 0;
            if (EditorDocuments.REVISIONED.contains(change.kind())) source.addProperty("revision", revision);
            JsonArray diagnostics = EditorDocuments.validate(change.kind(), change.id(), source);
            if (change.kind() == DocumentKind.NPC && current.exists()) identity(current.content(), source, diagnostics);
            if (change.kind() == DocumentKind.NPC && !current.exists() && !EditorDocuments.hasErrors(diagnostics)) collisions(location, source, diagnostics);
            if (EditorDocuments.hasErrors(diagnostics)) invalid.add(documentDiagnostics(change.kind(), change.id(), diagnostics));
            String content = JsonDocuments.write(source);
            String hash = "sha256:" + JsonDocuments.hash(content);
            writes.add(new DocumentStore.Change(location.path(), plain(current.content()), content));
            applied.add(version(change.kind(), change.id(), revision, hash));
            catalog.add(catalogEntry(change.kind(), change.id(), location.path(), revision, hash));
        }
        if (!conflicts.isEmpty()) {
            JsonObject body = new JsonObject();
            body.addProperty("status", "conflict");
            body.addProperty("idempotencyKey", set.idempotencyKey());
            body.add("conflicts", conflicts);
            remember(set.idempotencyKey(), requestHash, tokenId, "conflict", null, new JsonArray(), body);
            return new Outcome(409, body, Set.of(), false, false, new JsonArray(), new JsonArray());
        }
        if (!invalid.isEmpty()) {
            JsonObject body = new JsonObject();
            body.addProperty("status", "invalid");
            body.addProperty("idempotencyKey", set.idempotencyKey());
            body.add("documents", invalid);
            remember(set.idempotencyKey(), requestHash, tokenId, "invalid", null, new JsonArray(), body);
            return new Outcome(422, body, Set.of(), false, false, new JsonArray(), new JsonArray());
        }
        String transaction = UUID.randomUUID().toString();
        JsonObject body = appliedBody(transaction, set.idempotencyKey(), applied);
        writes.add(record(set.idempotencyKey(), requestHash, tokenId, "applied", transaction, touched, body));
        store.commit(transaction, writes);
        index().put(transaction, set.idempotencyKey());
        return new Outcome(200, body, kinds, true, false, catalog, deleted);
    }

    /** Undoes an applied change through its rollback receipt. Later edits to the same documents come back as a conflict. */
    public synchronized Outcome undo(String changeId, String key, String tokenId, ScopeCheck scopes) throws IOException, EditorError {
        String original = index().get(changeId);
        JsonObject record = original == null ? null : load(original).orElse(null);
        if (record == null || !record.get("status").getAsString().equals("applied"))
            throw new EditorError(EditorError.Code.NOT_FOUND, "No applied change " + changeId + " is on record; records are kept " + retention.toHours() + " hours.");
        Map<String, EditorDocuments.Location> byPath = new LinkedHashMap<>();
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        for (JsonElement element : record.getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            DocumentKind kind = DocumentKind.parse(entry.get("kind").getAsString()).orElseThrow();
            byPath.put(entry.get("path").getAsString(), new EditorDocuments.Location(kind, entry.get("id").getAsString(), entry.get("path").getAsString()));
            kinds.add(kind);
        }
        scopes.check(kinds, byPath.values().stream().map(EditorDocuments.Location::id).toList());
        String requestHash = "undo:" + changeId;
        Optional<Outcome> replay = replay(key, requestHash);
        if (replay.isPresent()) return replay.get();
        Map<String, JsonObject> applied = new HashMap<>();
        for (JsonElement element : record.getAsJsonObject("result").getAsJsonArray("documents"))
            applied.put(element.getAsJsonObject().get("kind").getAsString() + "\n" + element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject());
        List<DocumentStore.Change> writes = new ArrayList<>();
        JsonArray conflicts = new JsonArray(), result = new JsonArray(), catalog = new JsonArray(), deleted = new JsonArray(), touched = new JsonArray();
        for (DocumentStore.Change inverse : store.inverse(changeId)) {
            EditorDocuments.Location location = byPath.get(inverse.path());
            // The editor record and NPC checkpoint pointers are engine state; the commit invalidates stale checkpoints itself.
            if (location == null) continue;
            EditorDocuments.Stored current = documents.read(location);
            JsonObject after = applied.get(location.kind().wireName() + "\n" + location.id());
            if (!plain(current.content()).equals(inverse.expectedHash())) {
                Optional<Long> revision = Optional.of(after.get("revision").getAsLong());
                Optional<String> hash = after.get("hash").isJsonNull() ? Optional.empty() : Optional.of(after.get("hash").getAsString());
                conflicts.add(conflict(location.kind(), location.id(), hash.isPresent() ? revision : Optional.empty(), hash, current));
                continue;
            }
            writes.add(inverse);
            touched.add(reference(location));
            long revision = inverse.content() == null ? after.get("revision").getAsLong() : EditorDocuments.revision(inverse.content());
            String hash = inverse.content() == null ? null : "sha256:" + JsonDocuments.hash(inverse.content());
            result.add(version(location.kind(), location.id(), revision, hash));
            if (hash == null) deleted.add(ref(location.kind(), location.id()));
            else catalog.add(catalogEntry(location.kind(), location.id(), location.path(), revision, hash));
        }
        if (!conflicts.isEmpty()) {
            JsonObject body = new JsonObject();
            body.addProperty("status", "conflict");
            body.addProperty("idempotencyKey", key);
            body.add("conflicts", conflicts);
            remember(key, requestHash, tokenId, "conflict", null, new JsonArray(), body);
            return new Outcome(409, body, Set.of(), false, false, new JsonArray(), new JsonArray());
        }
        if (writes.isEmpty()) throw new EditorError(EditorError.Code.NOT_FOUND, "Change " + changeId + " wrote no editor documents to undo.");
        String transaction = UUID.randomUUID().toString();
        JsonObject body = appliedBody(transaction, key, result);
        writes.add(record(key, requestHash, tokenId, "applied", transaction, touched, body));
        store.commit(transaction, writes);
        index().put(transaction, key);
        return new Outcome(200, body, kinds, true, true, catalog, deleted);
    }

    /** Checks that the token may apply every kind and NPC id of a change before an undo runs. */
    @FunctionalInterface public interface ScopeCheck { void check(Set<DocumentKind> kinds, List<String> ids) throws EditorError; }

    /** {@code GET /v1/changes/{key}}. */
    public synchronized JsonObject status(String key) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("idempotencyKey", key);
        Optional<JsonObject> record = load(key);
        if (record.isPresent()) {
            body.addProperty("status", record.get().get("status").getAsString());
            body.add("result", record.get().getAsJsonObject("result"));
        } else body.addProperty("status", pending(key) ? "pending" : "unknown");
        return body;
    }

    /** Removes records older than the retention. */
    public synchronized int prune() throws IOException {
        int removed = 0;
        Path directory = store.resolve(DIRECTORY);
        for (Path file : JsonDocumentTree.files(directory, 1)) {
            String name = file.getFileName().toString();
            String key = name.substring(0, name.length() - 5);
            if (!key.matches(EditorMessages.KEY)) continue;
            Optional<JsonObject> record = read(key);
            if (record.isPresent() && !expired(record.get())) continue;
            store.deleteGenerated(DIRECTORY + "/" + name);
            if (record.isPresent() && record.get().has("changeId")) index().remove(record.get().get("changeId").getAsString());
            removed++;
        }
        return removed;
    }

    private Optional<Outcome> replay(String key, String requestHash) throws IOException, EditorError {
        Optional<JsonObject> record = load(key);
        if (record.isEmpty()) return Optional.empty();
        if (!record.get().get("requestHash").getAsString().equals(requestHash))
            throw new EditorError(EditorError.Code.INVALID_REQUEST, "idempotencyKey " + key + " was already used for a different request; use a new key.");
        JsonObject body = record.get().getAsJsonObject("result");
        int status = switch (record.get().get("status").getAsString()) { case "applied" -> 200; case "conflict" -> 409; default -> 422; };
        return Optional.of(new Outcome(status, body, Set.of(), false, false, new JsonArray(), new JsonArray()));
    }

    private Optional<JsonObject> load(String key) throws IOException {
        Optional<JsonObject> record = read(key);
        return record.isPresent() && expired(record.get()) ? Optional.empty() : record;
    }

    private Optional<JsonObject> read(String key) throws IOException {
        String text = store.read(DIRECTORY + "/" + key + ".json");
        if (text == null) return Optional.empty();
        try { return Optional.of(JsonDocuments.object(text)); }
        catch (IOException unreadable) { return Optional.empty(); }
    }

    private boolean expired(JsonObject record) {
        try { return Instant.parse(record.get("createdAt").getAsString()).plus(retention).isBefore(clock.instant()); }
        catch (RuntimeException unreadable) { return true; }
    }

    private Map<String, String> index() throws IOException {
        if (changeIds != null) return changeIds;
        Map<String, String> found = new HashMap<>();
        for (Path file : JsonDocumentTree.files(store.resolve(DIRECTORY), 1)) {
            String name = file.getFileName().toString();
            String key = name.substring(0, name.length() - 5);
            if (!key.matches(EditorMessages.KEY)) continue;
            read(key).ifPresent((JsonObject record) -> { if (record.has("changeId")) found.put(record.get("changeId").getAsString(), key); });
        }
        changeIds = found;
        return changeIds;
    }

    private void remember(String key, String requestHash, String tokenId, String status, String changeId, JsonArray touched, JsonObject result) throws IOException {
        DocumentStore.Change change = record(key, requestHash, tokenId, status, changeId, touched, result);
        store.commitGenerated(List.of(new DocumentStore.Generated(change.path(), change.content())));
    }

    private DocumentStore.Change record(String key, String requestHash, String tokenId, String status, String changeId, JsonArray touched, JsonObject result) throws IOException {
        JsonObject record = new JsonObject();
        record.addProperty("schemaVersion", 1);
        record.addProperty("idempotencyKey", key);
        record.addProperty("requestHash", requestHash);
        record.addProperty("tokenId", tokenId);
        record.addProperty("createdAt", clock.instant().toString());
        record.addProperty("status", status);
        if (changeId != null) record.addProperty("changeId", changeId);
        record.add("documents", touched);
        record.add("result", result);
        String path = DIRECTORY + "/" + key + ".json";
        return new DocumentStore.Change(path, store.hash(path), JsonDocuments.write(record));
    }

    private static void identity(String stored, JsonObject source, JsonArray diagnostics) {
        try {
            NpcDefinition before = NpcDefinition.parse(stored);
            JsonElement uuid = source.get("uuid");
            if (uuid != null && uuid.isJsonPrimitive() && !before.uuid().toString().equals(uuid.getAsString()))
                diagnostics.add(EditorDocuments.diagnostic("error", "$.uuid", "an NPC keeps its uuid; clone it to make a new one", "identity"));
        } catch (IOException unreadable) { /* A broken stored file may be replaced by any valid definition. */ }
    }

    /** NPC ids and uuids are unique across groups, as the registry requires. */
    private void collisions(EditorDocuments.Location location, JsonObject source, JsonArray diagnostics) throws IOException {
        String shortId = location.id().substring(location.id().indexOf('/') + 1);
        String uuid = source.has("uuid") && source.get("uuid").isJsonPrimitive() ? source.get("uuid").getAsString() : "";
        for (EditorDocuments.Location other : documents.list(List.of(DocumentKind.NPC))) {
            if (other.path().equals(location.path())) continue;
            if (other.id().substring(other.id().indexOf('/') + 1).equals(shortId)) {
                diagnostics.add(EditorDocuments.diagnostic("error", "$.id", "NPC id " + shortId + " is already used by " + other.id(), "identity"));
                return;
            }
            String text = store.read(other.path());
            if (text != null && !uuid.isEmpty() && EditorDocuments.source(text).has("uuid") && EditorDocuments.source(text).get("uuid").getAsString().equals(uuid)) {
                diagnostics.add(EditorDocuments.diagnostic("error", "$.uuid", "uuid " + uuid + " is already used by " + other.id(), "identity"));
                return;
            }
        }
    }

    static String plain(String content) { return JsonDocuments.hash(content); }

    private static JsonObject appliedBody(String changeId, String key, JsonArray documents) {
        JsonObject body = new JsonObject();
        body.addProperty("status", "applied");
        body.addProperty("changeId", changeId);
        body.addProperty("idempotencyKey", key);
        body.add("documents", documents);
        return body;
    }

    private static JsonObject conflict(DocumentKind kind, String id, Optional<Long> baseRevision, Optional<String> baseHash, EditorDocuments.Stored current) {
        JsonObject conflict = new JsonObject();
        conflict.addProperty("kind", kind.wireName());
        conflict.addProperty("id", id);
        if (baseRevision.isPresent()) conflict.addProperty("baseRevision", baseRevision.get()); else conflict.add("baseRevision", com.google.gson.JsonNull.INSTANCE);
        if (baseHash.isPresent()) conflict.addProperty("baseHash", baseHash.get()); else conflict.add("baseHash", com.google.gson.JsonNull.INSTANCE);
        if (current.exists()) {
            JsonObject now = new JsonObject();
            now.addProperty("revision", current.revision());
            now.addProperty("hash", current.hash());
            now.add("source", EditorDocuments.source(current.content()));
            conflict.add("current", now);
        } else conflict.add("current", com.google.gson.JsonNull.INSTANCE);
        return conflict;
    }

    private static JsonObject version(DocumentKind kind, String id, long revision, String hash) {
        JsonObject version = new JsonObject();
        version.addProperty("kind", kind.wireName());
        version.addProperty("id", id);
        version.addProperty("revision", revision);
        if (hash == null) version.add("hash", com.google.gson.JsonNull.INSTANCE); else version.addProperty("hash", hash);
        return version;
    }

    static JsonObject catalogEntry(DocumentKind kind, String id, String path, long revision, String hash) {
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", kind.wireName());
        entry.addProperty("id", id);
        entry.addProperty("path", path);
        entry.addProperty("revision", revision);
        entry.addProperty("hash", hash);
        return entry;
    }

    private static JsonObject ref(DocumentKind kind, String id) {
        JsonObject reference = new JsonObject();
        reference.addProperty("kind", kind.wireName());
        reference.addProperty("id", id);
        return reference;
    }

    private static JsonObject reference(EditorDocuments.Location location) {
        JsonObject reference = ref(location.kind(), location.id());
        reference.addProperty("path", location.path());
        return reference;
    }

    static JsonObject documentDiagnostics(DocumentKind kind, String id, JsonArray diagnostics) {
        JsonObject entry = ref(kind, id);
        entry.add("diagnostics", diagnostics);
        return entry;
    }
}
