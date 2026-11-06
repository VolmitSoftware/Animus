package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One atomic registry snapshot. A live reload publishes every valid file; an invalid file keeps only its own last valid version. */
public final class NpcRepository {
    public record Draft(String path, String expectedHash, long expectedRevision, JsonObject document) {
        public Draft { document = document.deepCopy(); }
        @Override public JsonObject document() { return document.deepCopy(); }
    }
    public record Validation(List<String> errors, int definitions) {
        public Validation { errors = List.copyOf(errors); }
    }
    /**
     * The published definitions together with the hash of the file each one was read from. A batch edit made from these
     * expects those hashes, so a file that changed since is refused instead of overwritten.
     */
    public record Installed(Collection<NpcDefinition> definitions, Map<String, String> sourceHashes) {
        public static final Installed NONE = new Installed(List.of(), Map.of());
        public Installed { definitions = List.copyOf(definitions); sourceHashes = Map.copyOf(sourceHashes); }
        /** The hash of the file {@code definition} was read from. */
        public String expectedHash(NpcDefinition definition) throws IOException {
            String hash = sourceHashes.get(definition.path());
            if (hash == null) throw new IOException("Conflict: " + definition.path() + " was not read from the store");
            return hash;
        }
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
    /** The published definitions and the file hash each was read from, taken together. */
    public synchronized Installed installed() { return new Installed(active.values(), hashes); }
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

    /** One file of a scan: its parsed definition and source hash, or the error that refused it. */
    private record Read(String path, NpcDefinition definition, String sourceHash, String error) {}

    /**
     * Reads every definition file. At startup any error refuses the whole set (after durable recovery). A live reload
     * applies each file on its own: a file that cannot be read or would take another definition's identity keeps its own
     * last valid version, a new file that fails is skipped, and only that file's error is reported.
     */
    private ScanResult scan(boolean apply, boolean allowOlderRevision, boolean startup) throws IOException {
        Map<String, Read> reads = new TreeMap<>();
        Map<String, String> recovered = new TreeMap<>();
        Path directory = store.resolve("npcs");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            for (Path file : JsonDocumentTree.files(directory)) {
                String path = "npcs/" + directory.relativize(file).toString().replace('\\', '/');
                if (reads.size() >= 100_000) throw new IOException("NPC definition limit exceeded: 100000");
                try {
                    DefinitionCheckpoints.Saved saved = startup ? checkpoints.read(path).orElse(null) : null;
                    try {
                        String json = JsonDocuments.read(file);
                        NpcDefinition definition = NpcDefinition.parse(json);
                        if (!store.resolve(definition.path()).equals(file)) throw new IOException("$.id/group: must match file path");
                        NpcDefinition previous = startup && saved != null ? saved.definition() : active.get(definition.id());
                        if (!allowOlderRevision && previous != null && definition.revision() < previous.revision()) throw new IOException("$.revision: cannot decrease");
                        reads.put(path, new Read(path, definition, JsonDocuments.hash(json), null));
                    } catch (IOException | RuntimeException invalid) {
                        if (saved == null) throw invalid;
                        reads.put(path, new Read(path, saved.definition(), saved.sourceHash(), null));
                        recovered.put(path, invalid.getMessage() + "; retained last valid revision " + saved.definition().revision()
                                + " from durable recovery; original file unchanged, repair it then reload");
                    }
                } catch (IOException | RuntimeException e) { reads.put(path, new Read(path, null, null, e.getMessage())); }
            }
        }
        store.verifyReadable();
        Map<String, NpcDefinition> previousByPath = new HashMap<>();
        for (NpcDefinition definition : active.values()) previousByPath.put(definition.path(), definition);
        Map<String, NpcDefinition> candidates = new TreeMap<>();
        Map<String, String> nextHashes = new HashMap<>(), errors = new TreeMap<>();
        Set<UUID> uuids = new HashSet<>();
        // Unchanged identities claim first, then edited files, then new ones: a newcomer never displaces a running definition.
        List<Read> order = new ArrayList<>(reads.values());
        order.sort(Comparator.comparingInt((Read read) -> claimOrder(read, previousByPath.get(read.path()))));
        for (Read read : order) {
            String problem = read.error();
            if (problem == null) {
                if (candidates.containsKey(read.definition().id())) problem = "$.id: duplicate " + read.definition().id();
                else if (uuids.contains(read.definition().uuid())) problem = "$.uuid: duplicate " + read.definition().uuid();
                else { claim(read.definition(), read.sourceHash(), candidates, nextHashes, uuids); continue; }
            }
            NpcDefinition kept = startup ? null : previousByPath.get(read.path());
            if (kept != null && !candidates.containsKey(kept.id()) && !uuids.contains(kept.uuid())) {
                claim(kept, hashes.get(kept.path()), candidates, nextHashes, uuids);
                errors.put(read.path(), problem + "; kept the last valid revision " + kept.revision());
            } else errors.put(read.path(), problem);
        }
        if (startup && !errors.isEmpty()) {
            problems = Collections.unmodifiableMap(new TreeMap<>(errors));
            throw new IOException(String.join("; ", diagnostics()));
        }
        Map<String, String> reported = new TreeMap<>(recovered);
        reported.putAll(errors);
        if (apply) {
            List<DocumentStore.Change> changes = new ArrayList<>();
            for (NpcDefinition definition : candidates.values()) checkpoints.capture(definition, nextHashes.get(definition.path())).ifPresent(changes::add);
            // Acknowledged deletions must not leave a pointer that could resurrect an old identity if this path is reused.
            for (String path : checkpoints.paths()) if (!reads.containsKey(path)) checkpoints.remove(path).ifPresent(changes::add);
            for (int from = 0; from < changes.size(); from += 200) store.commit(changes.subList(from, Math.min(changes.size(), from + 200)));
            active = Collections.unmodifiableMap(candidates); hashes = Map.copyOf(nextHashes); loaded = true;
            problems = Collections.unmodifiableMap(reported);
        }
        return new ScanResult(reported.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList(), candidates.size());
    }

    private static int claimOrder(Read read, NpcDefinition previous) {
        if (previous == null) return 2;
        if (read.definition() == null) return 1;
        return read.definition().id().equals(previous.id()) && read.definition().uuid().equals(previous.uuid()) ? 0 : 1;
    }

    private static void claim(NpcDefinition definition, String sourceHash, Map<String, NpcDefinition> candidates, Map<String, String> hashes, Set<UUID> uuids) {
        candidates.put(definition.id(), definition);
        uuids.add(definition.uuid());
        hashes.put(definition.path(), sourceHash);
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
