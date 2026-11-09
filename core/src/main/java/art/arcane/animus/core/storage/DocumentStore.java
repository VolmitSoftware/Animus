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
    /** Where the last-valid recovery copy of each NPC file is kept, mirroring its path under {@code npcs/}. */
    public static final String DEFINITION_POINTERS = "state/npc-definitions/last-valid/";
    /** A whole replacement of generated data, where null deletes it. Generated data has no editor, so nothing can conflict. */
    public record Generated(String path, String content) {}
    private static final String GENERATED_PATH = "state/behavior-state/[a-z0-9_/-]+\\.json|state/behavior-catalog/last-valid/(?:packs/[a-z0-9_-]+/)?behaviors/[a-z0-9_/-]+\\.json"
            + "|state/builds/[a-z0-9][a-z0-9_-]{0,63}(?:/undo-[0-9]{5})?\\.json|state/editor/changes/[A-Za-z0-9_-]{16,128}\\.json"
            + "|state/rig-catalog/last-valid/(?:rigs|animations)/[a-z0-9_-]+/[a-z0-9_/-]+\\.json";
    private static final long MAX_TRANSACTION_BYTES = 256L * 1024 * 1024;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    /** Generated object kinds whose objects are named only by pointers, receipts and import plans, so unnamed ones can go. */
    private static final List<String> PRUNED_OBJECTS = List.of("npc-definitions", "behavior-catalog", "rig-catalog", "import-applications");
    private static final java.util.regex.Pattern OBJECT_HASH = java.util.regex.Pattern.compile("[a-f0-9]{64}");
    static { WriteNotices.preload(); }
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final boolean readOnly;
    private boolean closed;

    public DocumentStore(Path directory) throws IOException {
        this(directory, false);
    }

    /** Validation view: never creates files or rolls a pending journal forward. */
    public static DocumentStore readOnly(Path directory) throws IOException { return new DocumentStore(directory, true); }

    private DocumentStore(Path directory, boolean readOnly) throws IOException {
        this.readOnly = readOnly;
        if (!readOnly) createDirectoriesDurably(directory);
        root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Animus data root is not a directory");
        Path lockFile = resolve("state/store.lock");
        if (readOnly && !Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            // A never-opened export has no lock. This validates the files observed, not a concurrent external editor's snapshot.
            lockChannel = null; lock = null;
        } else {
            if (!readOnly) createDirectoriesDurably(lockFile.getParent());
            lockChannel = readOnly ? FileChannel.open(lockFile, READ, LinkOption.NOFOLLOW_LINKS)
                    : FileChannel.open(lockFile, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired;
            try {
                acquired = lockChannel.tryLock(0, Long.MAX_VALUE, readOnly);
                if (acquired == null) throw new IOException("Animus data directory is already open");
            } catch (IOException | RuntimeException e) { lockChannel.close(); throw new IOException("Cannot lock Animus data directory", e); }
            lock = acquired;
        }
        try {
            if (readOnly) verifyReadable(); else recover();
        }
        catch (IOException | RuntimeException e) { close(); throw e; }
    }

    /** Check again after a read-only scan, since an uninitialized export may have no process lock. */
    public synchronized void verifyReadable() throws IOException {
        ensureOpen();
        if (readOnly && Files.exists(resolve("state/pending.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Pending transaction requires recovery; read-only validation left all files unchanged");
    }

    public synchronized String read(String relative) throws IOException {
        ensureOpen();
        Path path = resolve(relative);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? JsonDocuments.read(path) : null;
    }
    public synchronized String hash(String relative) throws IOException { return JsonDocuments.hash(read(relative)); }

    /** The refusal of a write whose file changed after it was read: which file, and how to recover. */
    public static IOException conflict(String path) {
        return new IOException("Conflict: " + path + " changed since Animus read it; fix the file or /animus reload");
    }

    /** Content-addressed generated JSON. Unreferenced objects are harmless; references publish in journaled transactions. */
    public synchronized String putImmutableJson(String namespace, String content) throws IOException {
        ensureWritable();
        if (!namespace.matches("[a-z][a-z0-9-]{0,40}")) throw new IOException("Invalid object namespace");
        if (content.getBytes(StandardCharsets.UTF_8).length > JsonDocuments.MAX_BYTES) throw new IOException("Generated object exceeds size limit");
        JsonDocuments.object(content);
        String hash = JsonDocuments.hash(content);
        String relative = "state/objects/" + namespace + "/" + hash + ".json";
        String existing = read(relative);
        if (existing != null && !existing.equals(content)) throw new IOException("Generated object hash mismatch: " + relative);
        if (existing == null) atomic(resolve(relative), content);
        return hash;
    }

    public synchronized String immutableJson(String namespace, String hash) throws IOException {
        ensureOpen();
        if (!namespace.matches("[a-z][a-z0-9-]{0,40}") || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid generated object reference");
        String content = read("state/objects/" + namespace + "/" + hash + ".json");
        if (content == null || !JsonDocuments.hash(content).equals(hash)) throw new IOException("Generated object is missing or its hash changed");
        return content;
    }

    public synchronized String commit(List<Change> changes) throws IOException { return commit(UUID.randomUUID().toString(), changes); }

    /** A commit under a caller-chosen transaction ID, so a record written in the same journal can name its own rollback receipt. */
    public synchronized String commit(String transaction, List<Change> changes) throws IOException {
        ensureWritable();
        if (!transaction.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IOException("Invalid transaction ID");
        if (Files.exists(resolve("backups/" + transaction + ".json"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Transaction ID already used: " + transaction);
        recover();
        return publish(transaction, invalidateDefinitionCheckpoints(changes), true);
    }

    /**
     * Same journal and crash recovery as {@link #commit}, for frequently replaced generated state. It leaves no rollback
     * receipt, so repeated flushes do not accumulate under backups/, and its journal must stay inline for the same reason.
     */
    public synchronized String commitGenerated(List<Generated> generated) throws IOException {
        ensureWritable();
        recover();
        List<Change> changes = new ArrayList<>(generated.size());
        for (Generated change : generated) {
            if (!change.path().matches(GENERATED_PATH)) throw new IOException("Not a generated document path: " + change.path());
            changes.add(new Change(change.path(), hash(change.path()), change.content()));
        }
        return publish(UUID.randomUUID().toString(), changes, false);
    }

    /** Removes one invalid generated file whose bytes a journal cannot carry. A single unlink is already atomic. */
    public synchronized void deleteGenerated(String relative) throws IOException {
        ensureWritable();
        if (!relative.startsWith("state/behavior-state/") && !relative.startsWith("state/behavior-catalog/last-valid/") && !relative.startsWith("state/builds/")
                && !relative.matches("state/editor/changes/[A-Za-z0-9_-]{16,128}\\.json")
                && !relative.startsWith("state/rig-catalog/last-valid/"))
            throw new IOException("Not a generated document path: " + relative);
        Path target = resolve(relative);
        delete(target);
    }

    private String publish(String id, List<Change> changes, boolean receipt) throws IOException {
        if (changes.isEmpty() || changes.size() > 10000) throw new IOException("Transaction requires 1..10000 documents");
        JsonArray entries = new JsonArray();
        Set<Path> paths = new HashSet<>();
        long payloadBytes = 0;
        for (Change change : changes) {
            writable(change.path());
            if (!paths.add(resolve(change.path()))) throw new IOException("Duplicate transaction path: " + change.path());
            String before = read(change.path());
            if (!JsonDocuments.hash(before).equals(change.expectedHash())) throw conflict(change.path());
            if (change.content() != null && change.content().getBytes(StandardCharsets.UTF_8).length > JsonDocuments.MAX_BYTES)
                throw new IOException("Document exceeds size limit: " + change.path());
            payloadBytes += bytes(before) + bytes(change.content());
            if (payloadBytes > MAX_TRANSACTION_BYTES) throw new IOException("Transaction payload exceeds 256 MiB");
            JsonObject entry = new JsonObject();
            entry.addProperty("path", change.path());
            entry.addProperty("before", before);
            entry.addProperty("after", change.content());
            entries.add(entry);
        }
        JsonObject journal = new JsonObject();
        journal.addProperty("id", id);
        if (!receipt) journal.addProperty("receipt", false);
        journal.add("entries", entries);
        // Large values live in durable content-addressed objects; metadata stays bounded and recoverable.
        // Avoid constructing a huge escaped inline string just to discover that it does not fit.
        String encoded = payloadBytes <= JsonDocuments.MAX_BYTES / 2 ? JsonDocuments.write(journal) : null;
        if (encoded == null || bytes(encoded) > JsonDocuments.MAX_BYTES) {
            if (!receipt) throw new IOException("Generated transaction must fit an inline journal");
            JsonArray references = new JsonArray();
            for (JsonElement value : entries) {
                JsonObject inline = value.getAsJsonObject(), reference = new JsonObject();
                reference.addProperty("path", inline.get("path").getAsString());
                reference.addProperty("beforeObject", putTransactionObject(nullable(inline, "before")));
                reference.addProperty("afterObject", putTransactionObject(nullable(inline, "after")));
                references.add(reference);
            }
            journal.addProperty("schemaVersion", 2);
            journal.add("entries", references);
            encoded = JsonDocuments.write(journal);
        }
        if (bytes(encoded) > JsonDocuments.MAX_BYTES) throw new IOException("Transaction journal exceeds 16 MiB");
        atomic(resolve("state/pending.json"), encoded);
        finish(journal);
        return id;
    }

    /** Raw source changes (including converter rollback) invalidate stale recovery in the same journal. */
    private List<Change> invalidateDefinitionCheckpoints(List<Change> changes) throws IOException {
        if (changes.isEmpty() || changes.size() > 10000) throw new IOException("Transaction requires 1..10000 documents");
        Set<String> explicit = new HashSet<>();
        for (Change change : changes) explicit.add(root.relativize(resolve(change.path())).toString().replace('\\', '/'));
        List<Change> result = new ArrayList<>(changes);
        for (Change change : changes) {
            String source = root.relativize(resolve(change.path())).toString().replace('\\', '/');
            if (!NpcPaths.file(source)) continue;
            String pointer = DEFINITION_POINTERS + source;
            if (explicit.contains(pointer)) continue;
            if (change.content() != null && Objects.equals(read(change.path()), change.content())) continue;
            String before = read(pointer);
            if (before != null) result.add(new Change(pointer, JsonDocuments.hash(before), null));
        }
        return result;
    }

    /** Undoes a committed transaction, named by its id or a unique prefix of at least four characters. */
    public synchronized String rollback(String transaction) throws IOException { return commit(inverse(transaction(transaction))); }

    /** The full id of the committed transaction whose id is or starts with {@code prefix}. */
    public synchronized String transaction(String prefix) throws IOException {
        ensureOpen();
        if (!prefix.matches("[a-f0-9-]{4,36}")) throw new IOException("Invalid transaction ID");
        List<String> matches = new ArrayList<>();
        Path backups = root.resolve("backups");
        if (Files.isDirectory(backups)) try (DirectoryStream<Path> found = Files.newDirectoryStream(backups, prefix + "*.json")) {
            for (Path receipt : found) {
                String name = receipt.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                if (id.matches("[a-f0-9-]{36}")) matches.add(id);
            }
        }
        if (matches.isEmpty()) throw new IOException("Unknown transaction: " + prefix);
        if (matches.size() > 1) throw new IOException("Transaction " + prefix + " matches " + matches.size() + " changes; give more of its id");
        return matches.getFirst();
    }

    /** The changes that undo a committed transaction, from its rollback receipt. Each expects the transaction's own result, so later edits conflict. */
    public synchronized List<Change> inverse(String transaction) throws IOException {
        ensureOpen();
        if (!transaction.matches("[a-f0-9-]{36}")) throw new IOException("Invalid transaction ID");
        String archive = read("backups/" + transaction + ".json");
        if (archive == null) throw new IOException("Unknown transaction: " + transaction);
        JsonObject journal = JsonDocuments.object(archive);
        int version = validateJournal(journal);
        List<Change> inverse = new ArrayList<>();
        long payloadBytes = 0;
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            String before = transactionValue(entry, "before", version), after = transactionValue(entry, "after", version);
            payloadBytes += bytes(before) + bytes(after);
            if (payloadBytes > MAX_TRANSACTION_BYTES) throw new IOException("Transaction payload exceeds 256 MiB");
            inverse.add(new Change(entry.get("path").getAsString(), JsonDocuments.hash(after), before));
        }
        return List.copyOf(inverse);
    }

    /** The document paths a committed transaction wrote, from its rollback receipt, without loading their contents. */
    public synchronized List<String> paths(String transaction) throws IOException {
        ensureOpen();
        if (!transaction.matches("[a-f0-9-]{36}")) throw new IOException("Invalid transaction ID");
        String archive = read("backups/" + transaction + ".json");
        if (archive == null) throw new IOException("Unknown transaction: " + transaction);
        JsonObject journal = JsonDocuments.object(archive);
        validateJournal(journal);
        List<String> paths = new ArrayList<>();
        for (JsonElement element : journal.getAsJsonArray("entries")) paths.add(element.getAsJsonObject().get("path").getAsString());
        return List.copyOf(paths);
    }

    /** What one rotation removed: rollback receipts, then stored transaction objects no remaining receipt names. */
    public record Rotation(int receipts, int objects) {}

    /**
     * Removes rollback receipts that are neither among the newest {@code keep} nor younger than {@code maxAge}, unless
     * {@code pinned} names them, then the transaction objects that only removed receipts referred to. A removed
     * transaction can no longer be rolled back. Settings archives under {@code backups/} are not receipts.
     */
    public synchronized Rotation rotateBackups(int keep, java.time.Duration maxAge, Set<String> pinned, java.time.Instant now) throws IOException {
        ensureWritable();
        if (keep < 1 || maxAge.isNegative()) throw new IllegalArgumentException("Rotation keeps at least one receipt for a non-negative age");
        recover();
        Path backups = resolve("backups");
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) return new Rotation(0, 0);
        record Receipt(String id, java.time.Instant written) {}
        List<Receipt> receipts = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(backups, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString(), id = name.substring(0, name.length() - ".json".length());
                if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                receipts.add(new Receipt(id, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()));
            }
        }
        receipts.sort(Comparator.comparing(Receipt::written).reversed().thenComparing(Receipt::id));
        java.time.Instant cutoff = now.minus(maxAge);
        int removed = 0;
        for (int index = keep; index < receipts.size(); index++) {
            Receipt receipt = receipts.get(index);
            if (pinned.contains(receipt.id()) || !receipt.written().isBefore(cutoff)) continue;
            delete(resolve("backups/" + receipt.id() + ".json"));
            removed++;
        }
        return new Rotation(removed, removed == 0 ? 0 : collectTransactionObjects(backups));
    }

    /** Deletes stored transaction contents that no receipt left in {@code backups} names. */
    private int collectTransactionObjects(Path backups) throws IOException {
        Path objects = resolve("state/objects/transaction-data");
        if (!Files.isDirectory(objects, LinkOption.NOFOLLOW_LINKS)) return 0;
        Set<String> referenced = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(backups, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("[a-f0-9-]{36}\\.json") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                JsonObject journal;
                int version;
                try {
                    journal = JsonDocuments.object(JsonDocuments.read(file));
                    version = validateJournal(journal);
                } catch (IOException | RuntimeException unreadable) { return 0; } // Its references are unknown, so every object stays.
                if (version != 2) continue;
                for (JsonElement element : journal.getAsJsonArray("entries")) {
                    JsonObject entry = element.getAsJsonObject();
                    for (String side : List.of("beforeObject", "afterObject")) if (!entry.get(side).isJsonNull()) referenced.add(entry.get(side).getAsString());
                }
            }
        }
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(objects, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("[a-f0-9]{64}\\.json") || referenced.contains(name.substring(0, 64))) continue;
                delete(file);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Deletes generated objects under {@link #PRUNED_OBJECTS} that no file in {@code state/}, {@code backups/} or
     * {@code imports/} names and that are older than {@code minimumAge}, so a write still between storing an object and
     * committing its pointer keeps it. Any reference file that cannot be read keeps every object.
     */
    public synchronized int collectObjects(java.time.Duration minimumAge, java.time.Instant now) throws IOException {
        ensureWritable();
        recover();
        Set<Path> pruned = new HashSet<>();
        for (String namespace : PRUNED_OBJECTS) pruned.add(resolve("state/objects/" + namespace));
        Set<String> referenced = new HashSet<>();
        for (String folder : List.of("state", "backups", "imports")) {
            Path start = resolve(folder);
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) continue;
            try (java.util.stream.Stream<Path> files = Files.walk(start)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || pruned.contains(file.getParent()) || file.getFileName().toString().equals("store.lock")) continue;
                    // Latin-1 decodes any bytes, so malformed user JSON in a receipt still yields its hashes.
                    java.util.regex.Matcher found = OBJECT_HASH.matcher(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                    while (found.find()) referenced.add(found.group());
                }
            } catch (IOException | java.io.UncheckedIOException unreadable) { return 0; }
        }
        java.time.Instant cutoff = now.minus(minimumAge);
        int removed = 0;
        for (Path objects : pruned) {
            if (!Files.isDirectory(objects, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(objects, "*.json")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!name.matches("[a-f0-9]{64}\\.json") || referenced.contains(name.substring(0, 64)) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                    delete(file);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Deletes the {@code imports/.preview-*} folders an import left while staging when the server stopped. Only safe before any import runs. */
    public synchronized int removeStalePreviews() throws IOException {
        ensureWritable();
        Path imports = resolve("imports");
        if (!Files.isDirectory(imports, LinkOption.NOFOLLOW_LINKS)) return 0;
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(imports, ".preview-*")) {
            for (Path child : children) if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) stale.add(child);
        }
        for (Path folder : stale) {
            try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        return stale.size();
    }

    private void recover() throws IOException {
        String pending = read("state/pending.json");
        if (pending != null) finish(JsonDocuments.object(pending));
    }

    /** Reject structural/entry abuse before loading any referenced payloads. */
    private int validateJournal(JsonObject journal) throws IOException {
        int version = journalVersion(journal);
        if (!journal.has("id") || !journal.get("id").isJsonPrimitive()
                || !journal.get("id").getAsString().matches("[a-f0-9-]{36}")
                || !journal.has("entries") || !journal.get("entries").isJsonArray()
                || journal.getAsJsonArray("entries").isEmpty() || journal.getAsJsonArray("entries").size() > 10000)
            throw new IOException("Invalid transaction journal; preserve state/pending.json for recovery");
        Set<Path> paths = new HashSet<>();
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) throw new IOException("Invalid journal entry");
            JsonObject entry = element.getAsJsonObject();
            for (String key : List.of("path", version == 2 ? "beforeObject" : "before", version == 2 ? "afterObject" : "after"))
                if (!entry.has(key) || (!entry.get(key).isJsonNull() && (!entry.get(key).isJsonPrimitive() || !entry.getAsJsonPrimitive(key).isString())))
                    throw new IOException("Invalid journal field: " + key);
            if (entry.get("path").isJsonNull() || !paths.add(resolve(entry.get("path").getAsString()))) throw new IOException("Invalid or duplicate journal path");
            writable(entry.get("path").getAsString());
        }
        return version;
    }

    private void finish(JsonObject journal) throws IOException {
        int version = validateJournal(journal);
        // Preflight all entries before modifying any. Outside edits block recovery instead of being overwritten.
        long payloadBytes = 0;
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            String path = entry.get("path").getAsString();
            writable(path);
            String current = read(path);
            String before = transactionValue(entry, "before", version), after = transactionValue(entry, "after", version);
            payloadBytes += bytes(before) + bytes(after);
            if (payloadBytes > MAX_TRANSACTION_BYTES) throw new IOException("Transaction payload exceeds 256 MiB");
            if (!Objects.equals(current, before) && !Objects.equals(current, after))
                throw new IOException("Recovery conflict: " + path + "; preserve state/pending.json and resolve the external edit");
        }
        for (JsonElement element : journal.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            Path target = resolve(entry.get("path").getAsString());
            String after = transactionValue(entry, "after", version);
            if (Objects.equals(read(entry.get("path").getAsString()), after)) continue;
            if (after == null) delete(target);
            else atomic(target, after);
        }
        String id = journal.get("id").getAsString();
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid journal ID");
        JsonElement receipt = journal.get("receipt");
        if (receipt == null || !receipt.isJsonPrimitive() || !receipt.getAsJsonPrimitive().isBoolean() || receipt.getAsBoolean())
            atomic(resolve("backups/" + id + ".json"), JsonDocuments.write(journal));
        Files.delete(resolve("state/pending.json"));
        syncDirectory(resolve("state"));
    }

    private static String nullable(JsonObject object, String key) {
        return object.get(key).isJsonNull() ? null : object.get(key).getAsString();
    }

    private static long bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static int journalVersion(JsonObject journal) throws IOException {
        if (!journal.has("schemaVersion")) return 1;
        JsonElement value = journal.get("schemaVersion");
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsBigDecimal().intValueExact() != 2)
                throw new ArithmeticException();
            return 2;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IOException("Unsupported transaction journal schema", invalid); }
    }
    private String putTransactionObject(String content) throws IOException {
        if (content == null) return null;
        if (bytes(content) > JsonDocuments.MAX_BYTES) throw new IOException("Transaction object exceeds 16 MiB");
        String hash = JsonDocuments.hash(content), path = "state/objects/transaction-data/" + hash + ".json";
        String existing = read(path);
        if (existing != null && !existing.equals(content)) throw new IOException("Transaction object hash mismatch");
        // Payload can contain malformed user JSON: rollback must preserve those original bytes too.
        if (existing == null) atomic(resolve(path), content);
        return hash;
    }
    private String transactionValue(JsonObject entry, String side, int version) throws IOException {
        if (version == 1) return nullable(entry, side);
        String hash = nullable(entry, side + "Object");
        if (hash == null) return null;
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid transaction object reference");
        String content = read("state/objects/transaction-data/" + hash + ".json");
        if (content == null || !JsonDocuments.hash(content).equals(hash)) throw new IOException("Transaction object missing or changed; preserve pending journal and immutable objects");
        return content;
    }

    private void writable(String path) throws IOException {
        resolve(path);
        if (!path.matches("(?:npcs|skins|groups|behaviors|packs|imports|shops|gui|routes|recordings|rigs|animations)/[a-zA-Z0-9_./-]+\\.json")
                && !path.equals("server-behaviors.json")
                && !path.matches("state/shops/imports/[a-f0-9]{64}\\.json")
                && !(path.startsWith(DEFINITION_POINTERS) && NpcPaths.file(path.substring(DEFINITION_POINTERS.length())))
                && !path.matches(GENERATED_PATH))
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

    /** Announced to {@link WriteNotices} before and after the move, so the hotload watcher never mistakes it for a hand edit. */
    public static void atomic(Path path, String value) throws IOException {
        createDirectoriesDurably(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".animus-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            WriteNotices.written(path, value);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException | RuntimeException failure) { WriteNotices.touched(path); throw failure; }
            WriteNotices.written(path, value);
            syncDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void delete(Path target) throws IOException {
        WriteNotices.written(target, null);
        boolean deleted;
        try { deleted = Files.deleteIfExists(target); }
        catch (IOException | RuntimeException failure) { WriteNotices.touched(target); throw failure; }
        WriteNotices.written(target, null);
        if (deleted) syncDirectory(target.getParent());
    }

    /** Persist each newly created directory entry before a journal can reference files beneath it. */
    private static void createDirectoriesDurably(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        Path cursor = absolute;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(cursor); cursor = cursor.getParent();
        }
        if (cursor == null || !Files.isDirectory(cursor)) throw new IOException("Document ancestor is not a directory: " + directory);
        Collections.reverse(missing);
        for (Path path : missing) {
            try { Files.createDirectory(path); }
            catch (FileAlreadyExistsException concurrent) { if (!Files.isDirectory(path)) throw concurrent; }
            syncDirectory(path.getParent());
        }
    }

    /**
     * Best effort: the file itself was already forced before the rename. Windows cannot open a directory for reading at
     * all, and some file systems refuse a directory sync, so a refusal must not fail a commit whose rename already happened.
     */
    private static void syncDirectory(Path directory) {
        if (WINDOWS) return;
        try (FileChannel channel = FileChannel.open(directory, READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException unsupported) { /* The rename stands; only its directory entry flush is skipped. */ }
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Document store is closed"); }
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (readOnly) throw new IOException("Document store is read-only");
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { if (lock != null) lock.release(); } finally { if (lockChannel != null) lockChannel.close(); }
    }
}
