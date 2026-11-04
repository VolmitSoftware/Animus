package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One atomic registry snapshot. Invalid reloads retain the entire last valid snapshot. */
public final class NpcRepository {
    public record Draft(String path, String expectedHash, long expectedRevision, JsonObject document) {
        public Draft { document = document.deepCopy(); }
        @Override public JsonObject document() { return document.deepCopy(); }
    }
    private final DocumentStore store;
    private volatile Map<String, NpcDefinition> active = Map.of();
    private Map<String, String> hashes = Map.of();
    public NpcRepository(DocumentStore store) { this.store = store; }
    public Collection<NpcDefinition> all() { return active.values(); }
    public Optional<NpcDefinition> find(String id) { return Optional.ofNullable(active.get(id)); }

    public synchronized List<String> reload() throws IOException { return scan(true); }
    public synchronized List<String> reloadAfterRollback() throws IOException { return scan(true, true); }
    public synchronized List<String> validate() throws IOException { return scan(false); }

    private List<String> scan(boolean apply) throws IOException { return scan(apply, false); }

    private List<String> scan(boolean apply, boolean allowOlderRevision) throws IOException {
        Map<String, NpcDefinition> candidates = new TreeMap<>();
        Map<String, String> nextHashes = new HashMap<>();
        Set<UUID> uuids = new HashSet<>();
        List<String> errors = new ArrayList<>();
        Path directory = store.resolve("npcs");
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted().toList()) {
                    if (Files.isSymbolicLink(file)) { errors.add(file + ": symlinks are not allowed"); continue; }
                    if (!Files.isRegularFile(file) || !file.toString().endsWith(".json")) continue;
                    try {
                        String json = JsonDocuments.read(file);
                        NpcDefinition definition = NpcDefinition.parse(json);
                        if (!store.resolve(definition.path()).equals(file)) throw new IOException("$.id/group: must match file path");
                        if (candidates.putIfAbsent(definition.id(), definition) != null) throw new IOException("$.id: duplicate " + definition.id());
                        if (!uuids.add(definition.uuid())) throw new IOException("$.uuid: duplicate " + definition.uuid());
                        NpcDefinition previous = active.get(definition.id());
                        if (!allowOlderRevision && previous != null && definition.revision() < previous.revision()) throw new IOException("$.revision: cannot decrease");
                        nextHashes.put(definition.path(), JsonDocuments.hash(json));
                    } catch (IOException | RuntimeException e) { errors.add(file + ": " + e.getMessage()); }
                }
            }
        }
        if (errors.isEmpty() && apply) { active = Collections.unmodifiableMap(candidates); hashes = Map.copyOf(nextHashes); }
        return List.copyOf(errors);
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
        String transaction = store.commit(List.of(new DocumentStore.Change(updated.path(), draft.expectedHash(), updated.serialize())));
        Map<String, NpcDefinition> next = new TreeMap<>(active);
        Map<String, String> nextHashes = new HashMap<>(hashes);
        next.put(updated.id(), updated);
        nextHashes.put(updated.path(), JsonDocuments.hash(updated.serialize()));
        active = Collections.unmodifiableMap(next);
        hashes = Map.copyOf(nextHashes);
        return transaction;
    }

    public synchronized String delete(String id) throws IOException {
        Draft draft = draft(id);
        String transaction = store.commit(List.of(new DocumentStore.Change(draft.path(), draft.expectedHash(), null)));
        Map<String, NpcDefinition> next = new TreeMap<>(active);
        next.remove(id);
        active = Collections.unmodifiableMap(next);
        return transaction;
    }
}
