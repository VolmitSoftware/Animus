package art.arcane.animus.core.storage;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Journaled file transactions. A committed journal is rolled forward before the runtime loads definitions. */
public final class DocumentStore implements AutoCloseable {
    public record Change(String path, String expectedHash, String content) {}
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public DocumentStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        root = directory.toRealPath();
        Path lockFile = resolve("state/store.lock");
        Files.createDirectories(lockFile.getParent());
        lockChannel = FileChannel.open(lockFile, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Animus data directory is already open");
        } catch (IOException | RuntimeException e) { lockChannel.close(); throw new IOException("Cannot lock Animus data directory", e); }
        lock = acquired;
        try { recover(); }
        catch (IOException | RuntimeException e) { close(); throw e; }
    }

    public synchronized String read(String relative) throws IOException {
        ensureOpen();
        Path path = resolve(relative);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? JsonDocuments.read(path) : null;
    }
    public synchronized String hash(String relative) throws IOException { return JsonDocuments.hash(read(relative)); }

    public synchronized String commit(List<Change> changes) throws IOException {
        ensureOpen();
        recover();
        if (changes.isEmpty() || changes.size() > 10000) throw new IOException("Transaction requires 1..10000 documents");
        JsonArray entries = new JsonArray();
        Set<Path> paths = new HashSet<>();
        for (Change change : changes) {
            writable(change.path());
            if (!paths.add(resolve(change.path()))) throw new IOException("Duplicate transaction path: " + change.path());
            String before = read(change.path());
            if (!JsonDocuments.hash(before).equals(change.expectedHash())) throw new IOException("Conflict: " + change.path());
            if (change.content() != null && change.content().getBytes(StandardCharsets.UTF_8).length > JsonDocuments.MAX_BYTES)
                throw new IOException("Document exceeds size limit: " + change.path());
            JsonObject entry = new JsonObject();
            entry.addProperty("path", change.path());
            entry.addProperty("before", before);
            entry.addProperty("after", change.content());
            entries.add(entry);
        }
        String id = UUID.randomUUID().toString();
        JsonObject journal = new JsonObject();
        journal.addProperty("id", id);
        journal.add("entries", entries);
        String encoded = JsonDocuments.write(journal);
        // A bound also applies to the journal so recovery never requires unbounded allocation.
        if (encoded.getBytes(StandardCharsets.UTF_8).length > JsonDocuments.MAX_BYTES) throw new IOException("Transaction journal exceeds 16 MiB");
        atomic(resolve("state/pending.json"), encoded);
        finish(journal);
        return id;
    }

    public synchronized String rollback(String transaction) throws IOException {
        if (!transaction.matches("[a-f0-9-]{36}")) throw new IOException("Invalid transaction ID");
        String archive = read("backups/" + transaction + ".json");
        if (archive == null) throw new IOException("Unknown transaction: " + transaction);
        JsonObject journal = JsonDocuments.object(archive);
        List<Change> inverse = new ArrayList<>();
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            inverse.add(new Change(entry.get("path").getAsString(), JsonDocuments.hash(nullable(entry, "after")), nullable(entry, "before")));
        }
        return commit(inverse);
    }

    private void recover() throws IOException {
        String pending = read("state/pending.json");
        if (pending != null) finish(JsonDocuments.object(pending));
    }

    private void finish(JsonObject journal) throws IOException {
        if (!journal.has("id") || !journal.get("id").isJsonPrimitive()
                || !journal.get("id").getAsString().matches("[a-f0-9-]{36}")
                || !journal.has("entries") || !journal.get("entries").isJsonArray()
                || journal.getAsJsonArray("entries").isEmpty() || journal.getAsJsonArray("entries").size() > 10000)
            throw new IOException("Invalid transaction journal; preserve state/pending.json for recovery");
        Set<Path> paths = new HashSet<>();
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) throw new IOException("Invalid journal entry");
            JsonObject entry = element.getAsJsonObject();
            for (String key : List.of("path", "before", "after"))
                if (!entry.has(key) || (!entry.get(key).isJsonNull() && (!entry.get(key).isJsonPrimitive() || !entry.getAsJsonPrimitive(key).isString())))
                    throw new IOException("Invalid journal field: " + key);
            if (entry.get("path").isJsonNull() || !paths.add(resolve(entry.get("path").getAsString()))) throw new IOException("Invalid or duplicate journal path");
        }
        // Preflight all entries before modifying any. Outside edits block recovery instead of being overwritten.
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            String path = entry.get("path").getAsString();
            writable(path);
            String current = read(path);
            if (!Objects.equals(current, nullable(entry, "before")) && !Objects.equals(current, nullable(entry, "after")))
                throw new IOException("Recovery conflict: " + path + "; preserve state/pending.json and resolve the external edit");
        }
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            Path target = resolve(entry.get("path").getAsString());
            String after = nullable(entry, "after");
            if (Objects.equals(read(entry.get("path").getAsString()), after)) continue;
            if (after == null) { Files.deleteIfExists(target); syncDirectory(target.getParent()); }
            else atomic(target, after);
        }
        String id = journal.get("id").getAsString();
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid journal ID");
        atomic(resolve("backups/" + id + ".json"), JsonDocuments.write(journal));
        Files.delete(resolve("state/pending.json"));
        syncDirectory(resolve("state"));
    }

    private static String nullable(JsonObject object, String key) {
        return object.get(key).isJsonNull() ? null : object.get(key).getAsString();
    }

    private void writable(String path) throws IOException {
        resolve(path);
        if (!path.matches("(?:npcs|skins|behaviors|packs|imports)/[a-zA-Z0-9_./-]+\\.json"))
            throw new IOException("Not a writable document path: " + path);
    }

    public Path resolve(String relative) throws IOException {
        Path part = Path.of(relative);
        Path resolved = root.resolve(part).normalize();
        if (part.isAbsolute() || !resolved.startsWith(root) || resolved.equals(root) || !part.normalize().equals(part))
            throw new IOException("Invalid relative path: " + relative);
        for (Path cursor = resolved; !cursor.equals(root); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink is not allowed: " + relative);
        }
        return resolved;
    }

    public static void atomic(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".animus-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ)) { channel.force(true); }
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Document store is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
