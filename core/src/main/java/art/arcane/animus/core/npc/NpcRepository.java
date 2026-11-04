package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One atomic registry snapshot. Invalid live reloads retain the previous published registry. */
public final class NpcRepository {
    public record Draft(String path, String expectedHash, long expectedRevision, JsonObject document) {
        public Draft { document = document.deepCopy(); }
        @Override public JsonObject document() { return document.deepCopy(); }
    }
    public record Validation(List<String> errors, int definitions) {
        public Validation { errors = List.copyOf(errors); }
    }
    private record ScanResult(List<String> diagnostics, int definitions) {}
    private final DocumentStore store;
    private final DefinitionCheckpoints checkpoints;
    private volatile Map<String, NpcDefinition> active = Map.of();
    private volatile Map<String, String> problems = Map.of();
    private Map<String, String> hashes = Map.of();
    private boolean loaded;
    public NpcRepository(DocumentStore store) { this.store = store; checkpoints = new DefinitionCheckpoints(store); }
    public Collection<NpcDefinition> all() { return active.values(); }
    /** Immutable published registry; identity changes only when a valid edit/reload is published. */
    public Map<String, NpcDefinition> snapshot() { return active; }
    public Optional<NpcDefinition> find(String id) { return Optional.ofNullable(active.get(id)); }
    public List<String> diagnostics() { return problems.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList(); }

    /** Restores only known invalid existing files, never deleted definitions or unknown malformed records. */
    public synchronized List<String> loadForStartup() throws IOException {
        if (loaded) throw new IllegalStateException("NPC repository is already loaded");
        return scan(true, false, true).diagnostics();
    }
    public synchronized List<String> reload() throws IOException { return scan(true, false, false).diagnostics(); }
    public synchronized List<String> reloadAfterRollback() throws IOException { return scan(true, true, false).diagnostics(); }
    public synchronized List<String> validate() throws IOException { return validateStored().errors(); }
    /** Validates persisted files without activation or checkpoint writes. */
    public synchronized Validation validateStored() throws IOException {
        ScanResult result = scan(false, false, false);
        return new Validation(result.diagnostics(), result.definitions());
    }

    private ScanResult scan(boolean apply, boolean allowOlderRevision, boolean startup) throws IOException {
        Map<String, NpcDefinition> candidates = new TreeMap<>();
        Map<String, String> nextHashes = new HashMap<>(), errors = new TreeMap<>(), recovered = new TreeMap<>();
        Set<UUID> uuids = new HashSet<>();
        Set<String> presentPaths = new HashSet<>();
        Path directory = store.resolve("npcs");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                for (Path file : JsonDocumentTree.files(directory)) {
                    String path = "npcs/" + directory.relativize(file).toString().replace('\\', '/');
                    if (presentPaths.size() >= 100_000) throw new IOException("NPC definition limit exceeded: 100000");
                    presentPaths.add(path);
                    try {
                        DefinitionCheckpoints.Saved saved = startup ? checkpoints.read(path).orElse(null) : null;
                        NpcDefinition definition;
                        String sourceHash;
                        try {
                            String json = JsonDocuments.read(file);
                            definition = NpcDefinition.parse(json);
                            if (!store.resolve(definition.path()).equals(file)) throw new IOException("$.id/group: must match file path");
                            NpcDefinition previous = startup && saved != null ? saved.definition() : active.get(definition.id());
                            if (!allowOlderRevision && previous != null && definition.revision() < previous.revision()) throw new IOException("$.revision: cannot decrease");
                            sourceHash = JsonDocuments.hash(json);
                        } catch (IOException | RuntimeException invalid) {
                            if (saved == null) throw invalid;
                            definition = saved.definition(); sourceHash = saved.sourceHash();
                            recovered.put(path, invalid.getMessage() + "; retained last valid revision " + definition.revision()
                                    + " from durable recovery; original file unchanged, repair it then reload");
                        }
                        if (candidates.containsKey(definition.id())) throw new IOException("$.id: duplicate " + definition.id());
                        if (uuids.contains(definition.uuid())) throw new IOException("$.uuid: duplicate " + definition.uuid());
                        candidates.put(definition.id(), definition); uuids.add(definition.uuid());
                        nextHashes.put(definition.path(), sourceHash);
                    } catch (IOException | RuntimeException e) { errors.put(path, e.getMessage()); }
                }
        }
        store.verifyReadable();
        if (!errors.isEmpty()) {
            problems = Collections.unmodifiableMap(new TreeMap<>(errors));
            if (startup) throw new IOException(String.join("; ", diagnostics()));
            return new ScanResult(diagnostics(), candidates.size());
        }
        if (apply) {
            List<DocumentStore.Change> changes = new ArrayList<>();
            for (NpcDefinition definition : candidates.values()) checkpoints.capture(definition, nextHashes.get(definition.path())).ifPresent(changes::add);
            // Acknowledged deletions must not leave a pointer that could resurrect an old identity if this path is reused.
            for (String path : checkpoints.paths()) if (!presentPaths.contains(path)) checkpoints.remove(path).ifPresent(changes::add);
            for (int from = 0; from < changes.size(); from += 200) store.commit(changes.subList(from, Math.min(changes.size(), from + 200)));
            active = Collections.unmodifiableMap(candidates); hashes = Map.copyOf(nextHashes); loaded = true;
            problems = Collections.unmodifiableMap(new TreeMap<>(recovered));
        }
        return new ScanResult(recovered.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList(), candidates.size());
    }

    public synchronized Draft draft(String id) throws IOException {
        NpcDefinition definition = active.get(id);
        if (definition == null) throw new IOException("Unknown NPC: " + id);
        return new Draft(definition.path(), hashes.get(definition.path()), definition.revision(), definition.json());
    }

    public synchronized String apply(Draft draft) throws IOException {
        JsonObject json = draft.document();
        if (draft.expectedRevision() == Long.MAX_VALUE) throw new IOException("Revision exhausted");
        json.addProperty("revision", draft.expectedRevision() + 1);
        NpcDefinition updated = NpcDefinition.parse(JsonDocuments.write(json));
        if (!updated.path().equals(draft.path())) throw new IOException("Rename/move requires an explicit transaction");
        for (NpcDefinition definition : active.values()) {
            if (!definition.path().equals(updated.path()) && (definition.id().equals(updated.id()) || definition.uuid().equals(updated.uuid())))
                throw new IOException("Identity collision: " + updated.id());
        }
        NpcDefinition previous = active.get(updated.id());
        if ((previous == null && draft.expectedRevision() != 0) || (previous != null && (previous.revision() != draft.expectedRevision() || !previous.uuid().equals(updated.uuid()))))
            throw new IOException("Conflict: identity or revision changed");
        if (previous == null && active.size() >= 100_000) throw new IOException("NPC definition limit exceeded: 100000");
        String serialized = updated.serialize(), sourceHash = JsonDocuments.hash(serialized);
        List<DocumentStore.Change> changes = new ArrayList<>();
        changes.add(new DocumentStore.Change(updated.path(), draft.expectedHash(), serialized));
        checkpoints.capture(updated, sourceHash).ifPresent(changes::add);
        String transaction = store.commit(changes);
        Map<String, NpcDefinition> next = new TreeMap<>(active);
        Map<String, String> nextHashes = new HashMap<>(hashes);
        next.put(updated.id(), updated); nextHashes.put(updated.path(), sourceHash);
        active = Collections.unmodifiableMap(next); hashes = Map.copyOf(nextHashes);
        clearProblem(updated.path());
        return transaction;
    }

    public synchronized String delete(String id) throws IOException {
        Draft draft = draft(id);
        List<DocumentStore.Change> changes = new ArrayList<>();
        changes.add(new DocumentStore.Change(draft.path(), draft.expectedHash(), null));
        checkpoints.remove(draft.path()).ifPresent(changes::add);
        String transaction = store.commit(changes);
        Map<String, NpcDefinition> next = new TreeMap<>(active); next.remove(id);
        Map<String, String> nextHashes = new HashMap<>(hashes); nextHashes.remove(draft.path());
        active = Collections.unmodifiableMap(next); hashes = Map.copyOf(nextHashes);
        clearProblem(draft.path());
        return transaction;
    }
    private void clearProblem(String path) {
        Map<String, String> next = new TreeMap<>(problems); next.remove(path); problems = Collections.unmodifiableMap(next);
    }
}
