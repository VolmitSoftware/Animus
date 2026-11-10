package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * One atomic registry snapshot. A live reload publishes every valid file; an invalid file keeps only its own last valid
 * version. Stored files stay the drafts that edits patch; {@link #snapshot()} holds effective definitions, each merged
 * over the trait groups it lists ({@link GroupMerge}).
 */
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
            if (hash == null) throw DocumentStore.conflict(definition.path());
            return hash;
        }
    }
    private record ScanResult(List<String> diagnostics, int definitions) {}
    /** A stored definition resolved over its groups: the effective definition, or the error that refused the combination. */
    private record Resolution(NpcDefinition effective, Map<String, String> provenance, String error) {}
    private static final System.Logger LOG = System.getLogger("Animus");
    private final DocumentStore store;
    private final DefinitionCheckpoints checkpoints;
    private volatile Map<String, NpcDefinition> active = Map.of(), stored = Map.of();
    private volatile Map<String, Map<String, String>> provenance = Map.of();
    private volatile Map<String, String> problems = Map.of(), groupProblems = Map.of();
    private volatile GroupCatalog groups = GroupCatalog.EMPTY;
    private volatile Map<String, List<String>> disguiseNotes = Map.of();
    private Map<String, String> hashes = Map.of();
    /** The on-disk hash of each file whose hand edit a reload or hotload refused, while its last valid version stays published. */
    private Map<String, String> refused = Map.of();
    private boolean loaded;
    public NpcRepository(DocumentStore store) { this.store = store; checkpoints = new DefinitionCheckpoints(store); }
    public Collection<NpcDefinition> all() { return active.values(); }
    /** Immutable published registry of effective definitions; identity changes only when a valid edit/reload is published. */
    public Map<String, NpcDefinition> snapshot() { return active; }
    public Optional<NpcDefinition> find(String id) { return Optional.ofNullable(active.get(id)); }
    /** The definitions as their files store them, without group values. Writers that patch a definition start from these. */
    public Collection<NpcDefinition> stored() { return stored.values(); }
    /** The trait groups of the last reload. */
    public GroupCatalog groups() { return groups; }
    /** Where each effective field of an NPC with groups came from; empty for an NPC without groups. */
    public Map<String, String> provenance(String id) { return provenance.getOrDefault(id, Map.of()); }
    /** NPC paths whose groups went missing or stopped merging into a valid definition, with the problem. They keep their last valid effective definition. */
    public Map<String, String> groupProblems() { return groupProblems; }
    /** The stored definitions, without group values, and the file hash each was read from, taken together. Batch writers start from these. */
    public synchronized Installed installed() { return new Installed(stored.values(), hashes); }
    /**
     * Records that hotload refused the content of {@code path} hashed {@code hash}. An explicit edit or delete of that NPC
     * then replaces the refused file instead of answering a conflict, and a batch skips it.
     */
    public synchronized void refused(String path, String hash) {
        Map<String, String> next = new HashMap<>(refused);
        next.put(path, hash);
        refused = Map.copyOf(next);
    }
    public List<String> diagnostics() { return problems.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList(); }

    /**
     * Restores only known invalid existing files, never deleted definitions or unknown malformed records. Any other file
     * that cannot load is skipped and logged, and stays on disk unchanged, so one bad file never stops the rest.
     */
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
     * Reads the group files, then every definition file. At startup a file restores from durable recovery when it can;
     * otherwise it is skipped with a warning, and of two files with one identity the recovery-backed one, then the first
     * by path, is kept. A live reload applies each file on its own: a file that cannot be read, would take another
     * definition's identity, or was edited to name a missing group or an invalid combination keeps its own last valid
     * version, a new file that fails is skipped, and only that file's error is reported. An unchanged file whose groups
     * changed under it keeps its last valid effective definition and is listed in {@link #groupProblems()}.
     */
    private ScanResult scan(boolean apply, boolean allowOlderRevision, boolean startup) throws IOException {
        GroupCatalog catalog = GroupCatalog.load(store, apply ? groups : GroupCatalog.EMPTY);
        Map<String, Read> reads = new TreeMap<>();
        Map<String, String> recovered = new TreeMap<>();
        // At startup the identity each file's recovery copy holds stands in for the running definition when claiming.
        Map<String, NpcDefinition> checkpointed = new HashMap<>();
        Path directory = store.resolve("npcs");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            for (Path file : JsonDocumentTree.files(directory, NpcPaths.TREE_DEPTH)) {
                String path = "npcs/" + directory.relativize(file).toString().replace('\\', '/');
                if (reads.size() >= 100_000) throw new IOException("NPC definition limit exceeded: 100000");
                try {
                    DefinitionCheckpoints.Saved saved = startup && NpcPaths.file(path) ? checkpoints.read(path).orElse(null) : null;
                    if (saved != null) checkpointed.put(path, saved.definition());
                    try {
                        String json = JsonDocuments.read(file);
                        NpcDefinition definition = NpcDefinition.read(path, json);
                        NpcDefinition previous = startup && saved != null ? saved.definition() : stored.get(definition.id());
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
        for (NpcDefinition definition : stored.values()) previousByPath.put(definition.path(), definition);
        Map<String, NpcDefinition> claimants = startup ? checkpointed : previousByPath;
        Map<String, NpcDefinition> candidates = new TreeMap<>(), effective = new TreeMap<>();
        Map<String, Map<String, String>> sources = new TreeMap<>();
        Map<String, String> nextHashes = new HashMap<>(), errors = new TreeMap<>(), nextGroupProblems = new TreeMap<>();
        Set<UUID> uuids = new HashSet<>();
        // Unchanged identities claim first, then edited files, then new ones: a newcomer never displaces a running definition.
        List<Read> order = new ArrayList<>(reads.values());
        order.sort(Comparator.comparingInt((Read read) -> claimOrder(read, claimants.get(read.path()))));
        for (Read read : order) {
            String problem = read.error();
            if (problem == null) {
                if (candidates.containsKey(read.definition().id())) problem = "$.id: duplicate " + read.definition().id();
                else if (uuids.contains(read.definition().uuid())) problem = "$.uuid: duplicate " + read.definition().uuid();
                else {
                    Resolution resolution = resolve(read.definition(), catalog, apply);
                    // An edited file that names a missing group or merges into an invalid definition is refused like any
                    // invalid edit. When only its groups changed, the NPC keeps its last valid effective definition.
                    boolean edited = !startup && (!apply || !read.sourceHash().equals(hashes.get(read.path())));
                    if (resolution.error() == null || !edited) {
                        Resolution published = resolution;
                        if (resolution.error() != null) {
                            nextGroupProblems.put(read.path(), resolution.error());
                            NpcDefinition previous = previousByPath.get(read.path());
                            published = previous != null && previous.uuid().equals(read.definition().uuid()) && active.containsKey(previous.id())
                                    ? new Resolution(active.get(previous.id()), provenance(previous.id()), null) : fallback(read.definition(), catalog, apply);
                        }
                        claim(read.definition(), published, read.sourceHash(), candidates, effective, sources, nextHashes, uuids);
                        continue;
                    }
                    problem = resolution.error();
                }
            }
            NpcDefinition kept = startup ? null : previousByPath.get(read.path());
            if (kept != null && !candidates.containsKey(kept.id()) && !uuids.contains(kept.uuid())) {
                claim(kept, new Resolution(active.getOrDefault(kept.id(), kept), provenance(kept.id()), null), hashes.get(kept.path()),
                        candidates, effective, sources, nextHashes, uuids);
                if (groupProblems.containsKey(kept.path())) nextGroupProblems.put(kept.path(), groupProblems.get(kept.path()));
                errors.put(read.path(), problem + "; kept the last valid revision " + kept.revision());
            } else errors.put(read.path(), problem);
        }
        if (startup) {
            for (Map.Entry<String, String> error : errors.entrySet())
                LOG.log(System.Logger.Level.WARNING, "Skipped NPC file " + error.getKey() + ": " + error.getValue() + "; the file is unchanged, fix it then /animus reload");
            for (Map.Entry<String, String> restored : recovered.entrySet()) LOG.log(System.Logger.Level.WARNING, "NPC file " + restored.getKey() + ": " + restored.getValue());
        }
        Map<String, String> reported = new TreeMap<>(recovered);
        reported.putAll(errors);
        for (String problem : catalog.problems()) {
            int separator = problem.indexOf(": ");
            reported.put(problem.substring(0, separator), problem.substring(separator + 2));
        }
        if (apply) {
            List<DocumentStore.Change> changes = new ArrayList<>();
            for (NpcDefinition definition : candidates.values()) checkpoints.capture(definition, nextHashes.get(definition.path())).ifPresent(changes::add);
            // Acknowledged deletions must not leave a pointer that could resurrect an old identity if this path is reused.
            for (String path : checkpoints.paths()) if (!reads.containsKey(path)) checkpoints.remove(path).ifPresent(changes::add);
            for (int from = 0; from < changes.size(); from += 200) store.commit(changes.subList(from, Math.min(changes.size(), from + 200)));
            for (Map.Entry<String, String> problem : nextGroupProblems.entrySet())
                if (!problem.getValue().equals(groupProblems.get(problem.getKey())))
                    LOG.log(System.Logger.Level.WARNING, problem.getKey() + ": group-problem=" + problem.getValue() + "; kept the last valid effective definition");
            stored = Collections.unmodifiableMap(candidates); active = Collections.unmodifiableMap(effective); provenance = Collections.unmodifiableMap(sources);
            Map<String, String> nextRefused = new HashMap<>();
            for (Read read : reads.values()) {
                String published = nextHashes.get(read.path());
                if (published == null || (read.error() == null && !recovered.containsKey(read.path()) && published.equals(read.sourceHash()))) continue;
                try {
                    String onDisk = store.hash(read.path());
                    if (!onDisk.equals(published)) nextRefused.put(read.path(), onDisk);
                } catch (IOException unreadable) { /* A file that cannot be read stays a conflict until it is fixed. */ }
            }
            hashes = Map.copyOf(nextHashes); refused = Map.copyOf(nextRefused); loaded = true; groups = catalog;
            problems = Collections.unmodifiableMap(reported); groupProblems = Collections.unmodifiableMap(nextGroupProblems);
        }
        return new ScanResult(reported.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList(), candidates.size());
    }

    private static int claimOrder(Read read, NpcDefinition previous) {
        if (previous == null) return 2;
        if (read.definition() == null) return 1;
        return read.definition().id().equals(previous.id()) && read.definition().uuid().equals(previous.uuid()) ? 0 : 1;
    }

    private static void claim(NpcDefinition definition, Resolution resolution, String sourceHash, Map<String, NpcDefinition> candidates, Map<String, NpcDefinition> effective,
                              Map<String, Map<String, String>> sources, Map<String, String> hashes, Set<UUID> uuids) {
        candidates.put(definition.id(), definition);
        effective.put(definition.id(), resolution.effective());
        if (!resolution.provenance().isEmpty()) sources.put(definition.id(), resolution.provenance());
        uuids.add(definition.uuid());
        hashes.put(definition.path(), sourceHash);
    }

    /** The effective definition of a stored one, or the first error: a missing group or an invalid combination. */
    private Resolution resolve(NpcDefinition definition, GroupCatalog catalog, boolean write) {
        if (definition.groups().isEmpty()) return new Resolution(disguised(definition, write), Map.of(), null);
        List<GroupDocument> listed = new ArrayList<>();
        for (int i = 0; i < definition.groups().size(); i++) {
            Optional<GroupDocument> group = catalog.find(definition.groups().get(i));
            if (group.isEmpty()) return new Resolution(null, Map.of(), "$.groups[" + i + "]: unknown group " + definition.groups().get(i));
            listed.add(group.get());
        }
        GroupMerge.Result merged = GroupMerge.merge(definition.json(), listed);
        try { return new Resolution(disguised(NpcDefinition.validated(merged.effective()), write), merged.provenance(), null); }
        catch (IOException invalid) { return new Resolution(null, Map.of(), "with groups " + String.join(", ", definition.groups()) + ": " + invalid.getMessage()); }
    }

    /** Without a last valid effective definition (at startup): the groups that still exist if they merge validly, else the stored definition alone. */
    private Resolution fallback(NpcDefinition definition, GroupCatalog catalog, boolean write) {
        List<GroupDocument> available = new ArrayList<>();
        for (String id : definition.groups()) catalog.find(id).ifPresent(available::add);
        GroupMerge.Result merged = GroupMerge.merge(definition.json(), available);
        try { return new Resolution(disguised(NpcDefinition.validated(merged.effective()), write), merged.provenance(), null); }
        catch (IOException invalid) { return new Resolution(disguised(definition, write), Map.of(), null); }
    }

    /**
     * The effective definition wearing its {@link NpcDisguise}, with the skin document the disguise needs saved first when
     * {@code write} is set. What the disguise left out is kept for {@link #disguiseNotes} and logged when it changes.
     */
    private NpcDefinition disguised(NpcDefinition effective, boolean write) {
        if (NpcDisguise.declared(effective.json()).isEmpty()) { notes(effective.id(), List.of()); return effective; }
        NpcDisguise.Applied applied = NpcDisguise.apply(effective.json());
        try {
            if (write && applied.skin().isPresent() && store.read(applied.skin().get().getKey()) == null)
                store.commit(List.of(new DocumentStore.Change(applied.skin().get().getKey(), "missing", applied.skin().get().getValue())));
            NpcDefinition disguised = NpcDefinition.validated(applied.definition());
            notes(effective.id(), applied.notes());
            return disguised;
        } catch (IOException unsaved) {
            notes(effective.id(), List.of("disguise not shown: " + unsaved.getMessage()));
            return effective;
        }
    }

    private void notes(String id, List<String> next) {
        if (next.equals(disguiseNotes.getOrDefault(id, List.of()))) return;
        Map<String, List<String>> updated = new TreeMap<>(disguiseNotes);
        if (next.isEmpty()) updated.remove(id); else updated.put(id, next);
        disguiseNotes = Collections.unmodifiableMap(updated);
        for (String note : next) LOG.log(System.Logger.Level.INFO, "NPC " + id + ": " + note);
    }

    /** What an NPC's disguise could not show, or why it did not apply; empty when it applied in full or there is none. */
    public List<String> disguiseNotes(String id) { return disguiseNotes.getOrDefault(id, List.of()); }

    public synchronized Draft draft(String id) throws IOException {
        NpcDefinition definition = stored.get(id);
        if (definition == null) throw new IOException("No NPC named " + id + "; /animus list shows ids");
        return new Draft(definition.path(), hashes.get(definition.path()), definition.revision(), definition.json());
    }

    public synchronized String apply(Draft draft) throws IOException {
        JsonObject json = draft.document();
        if (draft.expectedRevision() == Long.MAX_VALUE) throw new IOException("Revision exhausted");
        json.addProperty("revision", draft.expectedRevision() + 1);
        NpcDefinition updated = NpcDefinition.parse(JsonDocuments.write(json));
        if (!updated.path().equals(draft.path())) throw new IOException("Rename/move requires an explicit transaction");
        Resolution resolution = resolve(updated, groups, true);
        if (resolution.error() != null) throw new IOException(resolution.error());
        for (NpcDefinition definition : stored.values()) {
            if (!definition.path().equals(updated.path()) && (definition.id().equals(updated.id()) || definition.uuid().equals(updated.uuid())))
                throw new IOException("Identity collision: " + updated.id());
        }
        NpcDefinition previous = stored.get(updated.id());
        if ((previous == null && draft.expectedRevision() != 0) || (previous != null && (previous.revision() != draft.expectedRevision() || !previous.uuid().equals(updated.uuid()))))
            throw DocumentStore.conflict(draft.path());
        if (previous == null && stored.size() >= 100_000) throw new IOException("NPC definition limit exceeded: 100000");
        String serialized = updated.serialize(), sourceHash = JsonDocuments.hash(serialized);
        List<DocumentStore.Change> changes = new ArrayList<>();
        Optional<String> replaced = refusedBase(draft.path(), draft.expectedHash());
        changes.add(new DocumentStore.Change(updated.path(), replaced.orElse(draft.expectedHash()), serialized));
        checkpoints.capture(updated, sourceHash).ifPresent(changes::add);
        String transaction = store.commit(changes);
        publish(updated.id(), updated, resolution, updated.path(), sourceHash);
        return replaced.isPresent() ? transaction + "; replaced the refused hand edits in " + updated.path() : transaction;
    }

    /**
     * The hash a write to {@code path} must find on disk when that file holds a refused hand edit and the caller started
     * from the published version: the write is the user's explicit intent, so it replaces the refused content. Empty
     * otherwise, so any other change since the last read stays a conflict.
     */
    private Optional<String> refusedBase(String path, String expectedHash) throws IOException {
        String hash = refused.get(path);
        if (hash == null || !Objects.equals(expectedHash, hashes.get(path)) || !store.hash(path).equals(hash)) return Optional.empty();
        return Optional.of(hash);
    }

    /**
     * Applies several drafts as one transaction with one rollback receipt. Each draft is checked as {@link #apply} checks
     * it, and any refusal refuses the whole batch with every error, before anything is written. A member whose file holds
     * a refused hand edit is skipped instead: the result is the transaction followed by a note naming the skipped members.
     */
    public synchronized String applyAll(List<Draft> drafts) throws IOException {
        if (drafts.isEmpty()) throw new IOException("Nothing to apply");
        List<String> errors = new ArrayList<>();
        List<NpcDefinition> updated = new ArrayList<>();
        List<Resolution> resolutions = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Draft draft : drafts) {
            NpcDefinition previous = stored.values().stream().filter((NpcDefinition definition) -> definition.path().equals(draft.path())).findFirst().orElse(null);
            String label = previous == null ? draft.path() : previous.id();
            try {
                if (previous == null || previous.revision() != draft.expectedRevision() || !Objects.equals(hashes.get(draft.path()), draft.expectedHash()))
                    throw DocumentStore.conflict(draft.path());
                if (refusedBase(draft.path(), draft.expectedHash()).isPresent()) { skipped.add(label + ": " + draft.path() + " has refused hand edits"); continue; }
                if (draft.expectedRevision() == Long.MAX_VALUE) throw new IOException("Revision exhausted");
                JsonObject json = draft.document();
                json.addProperty("revision", draft.expectedRevision() + 1);
                NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(json));
                if (!definition.path().equals(draft.path()) || !definition.uuid().equals(previous.uuid())) throw new IOException("Rename/move requires an explicit transaction");
                Resolution resolution = resolve(definition, groups, true);
                if (resolution.error() != null) throw new IOException(resolution.error());
                updated.add(definition);
                resolutions.add(resolution);
                expected.put(draft.path(), draft.expectedHash());
            } catch (IOException refused) { errors.add(label + ": " + refused.getMessage()); }
        }
        if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
        String skip = String.join(", ", skipped) + (skipped.size() == 1 ? "; fix the file" : "; fix the files") + " or /animus reload";
        if (updated.isEmpty()) throw new IOException("Skipped " + skip);
        String transaction = DefinitionBatch.commit(store, updated, expected, List.of());
        for (int i = 0; i < updated.size(); i++) {
            NpcDefinition definition = updated.get(i);
            publish(definition.id(), definition, resolutions.get(i), definition.path(), JsonDocuments.hash(definition.serialize()));
        }
        return skipped.isEmpty() ? transaction : transaction + "; skipped " + skip;
    }

    public synchronized String delete(String id) throws IOException {
        Draft draft = draft(id);
        Optional<String> replaced = refusedBase(draft.path(), draft.expectedHash());
        List<DocumentStore.Change> changes = new ArrayList<>();
        changes.add(new DocumentStore.Change(draft.path(), replaced.orElse(draft.expectedHash()), null));
        checkpoints.remove(draft.path()).ifPresent(changes::add);
        String transaction = store.commit(changes);
        publish(id, null, null, draft.path(), null);
        return replaced.isPresent() ? transaction + "; removed the refused hand edits in " + draft.path() : transaction;
    }

    /** Publishes one NPC's stored and effective definitions after a committed write, or removes them when {@code definition} is null. */
    private void publish(String id, NpcDefinition definition, Resolution resolution, String path, String sourceHash) {
        Map<String, NpcDefinition> nextStored = new TreeMap<>(stored), nextActive = new TreeMap<>(active);
        Map<String, Map<String, String>> nextSources = new TreeMap<>(provenance);
        Map<String, String> nextHashes = new HashMap<>(hashes);
        nextSources.remove(id);
        if (definition == null) { nextStored.remove(id); nextActive.remove(id); nextHashes.remove(path); }
        else {
            nextStored.put(id, definition); nextActive.put(id, resolution.effective()); nextHashes.put(path, sourceHash);
            if (!resolution.provenance().isEmpty()) nextSources.put(id, resolution.provenance());
        }
        stored = Collections.unmodifiableMap(nextStored); active = Collections.unmodifiableMap(nextActive);
        provenance = Collections.unmodifiableMap(nextSources); hashes = Map.copyOf(nextHashes);
        if (refused.containsKey(path)) { Map<String, String> nextRefused = new HashMap<>(refused); nextRefused.remove(path); refused = Map.copyOf(nextRefused); }
        Map<String, String> nextProblems = new TreeMap<>(problems); nextProblems.remove(path); problems = Collections.unmodifiableMap(nextProblems);
        Map<String, String> nextGroupProblems = new TreeMap<>(groupProblems); nextGroupProblems.remove(path); groupProblems = Collections.unmodifiableMap(nextGroupProblems);
    }
}
