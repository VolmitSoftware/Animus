package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.WriteNotices;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Every editor document's catalog entry and summary, cached by file size, modification time and file key, and the
 * {@code catalog} event for every change. Animus's own writes arrive through {@link WriteNotices} as they happen and are
 * sent by {@link #flush}; files changed outside Animus are found by {@link #scan}. Entries are announced once: the index
 * holds what streams were last told. Call {@link #flush}, {@link #scan} and {@link #current} on the file lane.
 */
final class EditorCatalog implements WriteNotices.Listener {
    /** Who changed a document, as the {@code source} of its catalog event entry. */
    enum Source {
        EDITOR("editor"), SERVER("server"), FILE("file");

        private final String wireName;

        Source(String wireName) { this.wireName = wireName; }

        String wireName() { return wireName; }
    }

    /** What the file was when the entry was read: a different size, time or file key means it changed. */
    private record Stat(long size, long modified, Object key) {}

    /** One indexed document. */
    record Entry(DocumentKind kind, String id, String path, long revision, String hash, JsonObject summary, Stat stat) {
        JsonObject json() {
            JsonObject entry = EditorChanges.catalogEntry(kind, id, path, revision, hash);
            entry.add("summary", summary.deepCopy());
            return entry;
        }

        private Entry at(Stat now) { return new Entry(kind, id, path, revision, hash, summary, now); }
    }

    /** An announced write waiting for {@link #flush}; {@code text} is null for a delete, {@link #UNKNOWN} when only the disk can tell. */
    private record Pending(EditorDocuments.Location location, String text, Source source) {}

    private static final String UNKNOWN = "\0unknown";
    private static final ThreadLocal<Source> SOURCE = ThreadLocal.withInitial(() -> Source.SERVER);

    private final EditorDocuments documents;
    private final EditorEvents events;
    private final Path root;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Object pendingLock = new Object();
    private Map<String, Pending> pending = new LinkedHashMap<>();
    private boolean primed;

    EditorCatalog(EditorDocuments documents, EditorEvents events) throws IOException {
        this.documents = Objects.requireNonNull(documents);
        this.events = Objects.requireNonNull(events);
        root = documents.store().resolve("npcs").getParent();
    }

    /** Marks writes on this thread as {@code source} until {@link #leave}; returns what to restore. */
    static Source enter(Source source) {
        Source previous = SOURCE.get();
        SOURCE.set(source);
        return previous;
    }

    static void leave(Source previous) { SOURCE.set(previous); }

    @Override public void written(Path path, String text) { note(path, text); }

    @Override public void touched(Path path) { note(path, UNKNOWN); }

    /** Quick and never throwing: it runs on the writing thread, inside the store's lock. */
    private void note(Path path, String text) {
        if (!path.startsWith(root) || path.equals(root)) return;
        String relative = root.relativize(path).toString().replace('\\', '/');
        Optional<EditorDocuments.Location> location = locate(relative);
        if (location.isEmpty()) return;
        Pending next = new Pending(location.get(), text, SOURCE.get());
        synchronized (pendingLock) { pending.put(relative, next); }
    }

    /** The document a data-folder path is, if any: behaviors inside packs before the pack manifest. */
    static Optional<EditorDocuments.Location> locate(String path) {
        for (DocumentKind kind : DocumentKind.values()) {
            boolean under = kind == DocumentKind.SERVER_BEHAVIORS || kind == DocumentKind.PRESETS || path.startsWith(kind.folder() + "/")
                    || kind == DocumentKind.BEHAVIOR && path.startsWith("packs/");
            if (!under) continue;
            Optional<String> id = EditorDocuments.idFor(kind, path);
            if (id.isPresent()) return Optional.of(new EditorDocuments.Location(kind, id.get(), path));
        }
        return Optional.empty();
    }

    private boolean pending(String path) {
        synchronized (pendingLock) { return pending.containsKey(path); }
    }

    /** Reads every document once without announcing anything, so later changes are told against it. */
    synchronized void prime() throws IOException {
        if (primed) return;
        entries.clear();
        for (EditorDocuments.Location location : documents.list(EnumSet.allOf(DocumentKind.class))) {
            Optional<Stat> stat = stat(location.path());
            // A write announced but not flushed yet stays out, so the flush tells it.
            if (stat.isEmpty() || pending(location.path())) continue;
            Optional<Entry> entry = read(location, stat.get());
            entry.ifPresent((Entry read) -> entries.put(location.path(), read));
        }
        primed = true;
    }

    /**
     * Announces the writes Animus made since the last flush, in one {@code catalog} event. The batch is taken under the
     * index lock, so {@link #current} never reads a write it no longer sees waiting and tells it as {@code file}.
     */
    void flush() throws IOException {
        synchronized (this) {
            Map<String, Pending> batch;
            synchronized (pendingLock) {
                if (pending.isEmpty()) return;
                batch = pending;
                pending = new LinkedHashMap<>();
            }
            JsonArray changed = new JsonArray(), deleted = new JsonArray();
            for (Pending write : batch.values()) {
                String path = write.location().path();
                String text = write.text() == UNKNOWN ? documents.store().read(path) : write.text();
                Entry before = entries.get(path);
                if (text == null) {
                    if (entries.remove(path) != null || !primed) deleted.add(reference(write.location(), write.source()));
                    continue;
                }
                String hash = "sha256:" + JsonDocuments.hash(text);
                Stat stat = stat(path).orElse(new Stat(-1, -1, null));
                if (before != null && before.hash().equals(hash)) { entries.put(path, before.at(stat)); continue; }
                Entry fresh = entry(write.location(), text, hash, stat);
                entries.put(path, fresh);
                changed.add(announced(fresh, write.source()));
            }
            publish(changed, deleted);
        }
    }

    /** Finds documents changed, added or removed outside Animus and announces them as {@code file}. */
    void scan() throws IOException { current(EnumSet.allOf(DocumentKind.class)); }

    /**
     * The entries of these kinds as the files are now, sorted by kind and path. A file whose size, time or key changed is
     * read again; a real change found here is announced as {@code file}, unless an Animus write of it waits for {@link #flush}.
     */
    synchronized List<Entry> current(Collection<DocumentKind> kinds) throws IOException {
        if (!primed) prime();
        List<Entry> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray changed = new JsonArray(), deleted = new JsonArray();
        for (EditorDocuments.Location location : documents.list(kinds)) {
            Optional<Stat> stat = stat(location.path());
            if (stat.isEmpty()) continue;
            seen.add(location.path());
            Entry cached = entries.get(location.path());
            if (cached != null && cached.kind() == location.kind() && cached.stat().equals(stat.get())) { found.add(cached); continue; }
            Optional<Entry> read = read(location, stat.get());
            if (read.isEmpty()) continue;
            found.add(read.get());
            // A write announced but not yet flushed is told by the flush, with its own source.
            if (pending(location.path())) continue;
            entries.put(location.path(), read.get());
            if (cached == null || !cached.hash().equals(read.get().hash())) changed.add(announced(read.get(), Source.FILE));
        }
        Set<DocumentKind> chosen = kinds.isEmpty() ? EnumSet.noneOf(DocumentKind.class) : EnumSet.copyOf(kinds);
        for (Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext(); ) {
            Entry entry = iterator.next();
            if (!chosen.contains(entry.kind()) || seen.contains(entry.path()) || pending(entry.path())) continue;
            iterator.remove();
            deleted.add(reference(new EditorDocuments.Location(entry.kind(), entry.id(), entry.path()), Source.FILE));
        }
        publish(changed, deleted);
        return found;
    }

    /** The summary of a stored document: the indexed one while its hash matches, else read from {@code source}. */
    synchronized JsonObject summary(EditorDocuments.Stored stored, JsonObject source) {
        Entry entry = entries.get(stored.location().path());
        if (entry != null && entry.hash().equals(stored.hash())) return entry.summary().deepCopy();
        return EditorSummaries.of(stored.location().kind(), stored.location().id(), source);
    }

    /** True once every document was read once. */
    synchronized boolean primed() { return primed; }

    private void publish(JsonArray changed, JsonArray deleted) {
        if (changed.isEmpty() && deleted.isEmpty()) return;
        JsonObject event = new JsonObject();
        event.addProperty("type", "catalog");
        event.add("documents", changed);
        if (!deleted.isEmpty()) event.add("deleted", deleted);
        events.publish(event);
    }

    private static JsonObject announced(Entry entry, Source source) {
        JsonObject json = entry.json();
        json.addProperty("source", source.wireName());
        return json;
    }

    private static JsonObject reference(EditorDocuments.Location location, Source source) {
        JsonObject reference = new JsonObject();
        reference.addProperty("kind", location.kind().wireName());
        reference.addProperty("id", location.id());
        reference.addProperty("source", source.wireName());
        return reference;
    }

    private Optional<Entry> read(EditorDocuments.Location location, Stat stat) throws IOException {
        String text = documents.store().read(location.path());
        if (text == null) return Optional.empty();
        return Optional.of(entry(location, text, "sha256:" + JsonDocuments.hash(text), stat));
    }

    private static Entry entry(EditorDocuments.Location location, String text, String hash, Stat stat) {
        JsonObject source = EditorDocuments.source(text);
        return new Entry(location.kind(), location.id(), location.path(), EditorDocuments.revision(source), hash,
                EditorSummaries.of(location.kind(), location.id(), source), stat);
    }

    private Optional<Stat> stat(String path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(documents.store().resolve(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return Optional.of(new Stat(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey()));
        } catch (NoSuchFileException gone) { return Optional.empty(); }
    }
}
