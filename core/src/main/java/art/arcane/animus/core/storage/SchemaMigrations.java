package art.arcane.animus.core.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Upgrades stored NPC definitions, behavior documents and shops to the schema version this build reads. Each document
 * kind has an ordered chain: the first entry is the version1 identity, and entry N turns a version N document into
 * version N+1. Rewrites are committed in one {@link DocumentStore} transaction, so they are journaled, recover after a
 * crash and leave a rollback receipt holding the original bytes. A document newer than the chain, or one whose step
 * fails, is reported and left byte-for-byte in place.
 */
public final class SchemaMigrations {
    /** Turns a document of one version into the next version. It must not modify its argument. */
    @FunctionalInterface
    public interface Step { JsonObject migrate(JsonObject previous) throws IOException; }

    /** A document kind and the data folders that hold it. A {@code packs/*} prefix covers every pack. */
    public record Kind(String name, List<String> roots) {
        public Kind { roots = List.copyOf(roots); }
    }

    /** What one pass changed: the transaction is null when nothing was rewritten. */
    public record Report(String transaction, List<String> migrated, List<String> problems) {
        public Report { migrated = List.copyOf(migrated); problems = List.copyOf(problems); }
    }

    public static final Kind NPC = new Kind("NPC definition", List.of("npcs"));
    public static final Kind BEHAVIOR = new Kind("behavior", List.of("behaviors", "packs/*/behaviors"));
    public static final Kind SHOP = new Kind("shop", List.of("shops"));
    private static final Step IDENTITY = (JsonObject previous) -> previous;
    private static final SchemaMigrations STANDARD = builder().identity(NPC).identity(BEHAVIOR).identity(SHOP).build();

    /** In registration order, so a pass visits kinds and reports paths in a stable order. */
    private final Map<Kind, List<Step>> chains;

    private SchemaMigrations(Map<Kind, List<Step>> chains) {
        Map<Kind, List<Step>> copy = new LinkedHashMap<>();
        for (Map.Entry<Kind, List<Step>> chain : chains.entrySet()) copy.put(chain.getKey(), List.copyOf(chain.getValue()));
        this.chains = Collections.unmodifiableMap(copy);
    }

    /** The chains this build ships: every kind is at version 1. */
    public static SchemaMigrations standard() { return STANDARD; }
    public static Builder builder() { return new Builder(); }

    /** The version a kind is written at. */
    public int current(Kind kind) { return chain(kind).size(); }

    /**
     * The document at the current version. A missing or non-integer {@code schemaVersion}, or one below 1, is returned
     * unchanged for the document's own parser to report. A newer version is refused.
     */
    public JsonObject migrate(Kind kind, JsonObject document) throws IOException {
        List<Step> chain = chain(kind);
        int version = version(document);
        if (version < 1) return document;
        if (version > chain.size())
            throw new IOException(kind.name() + " schemaVersion " + version + " is newer than this Animus reads (" + chain.size()
                    + "); update Animus or restore an older copy");
        JsonObject result = document;
        for (int next = version; next < chain.size(); next++) {
            result = Objects.requireNonNull(chain.get(next).migrate(result.deepCopy()), "migration step returned null");
            result.addProperty("schemaVersion", next + 1);
        }
        return result;
    }

    /** Migrates every stale document in the store in one transaction. Call before the catalogs load. */
    public Report apply(DocumentStore store) throws IOException {
        List<DocumentStore.Change> changes = new ArrayList<>();
        List<String> migrated = new ArrayList<>(), problems = new ArrayList<>();
        for (Map.Entry<Kind, List<Step>> chain : chains.entrySet()) {
            Kind kind = chain.getKey();
            for (String folder : folders(store, kind)) {
                Path root = store.resolve(folder);
                for (Path file : JsonDocumentTree.files(root)) {
                    String path = folder + "/" + root.relativize(file).toString().replace('\\', '/');
                    String before = store.read(path);
                    JsonObject document;
                    try { document = JsonDocuments.object(before); }
                    catch (IOException | RuntimeException malformed) { continue; }
                    int version = version(document);
                    if (version < 1 || version == chain.getValue().size()) continue;
                    try {
                        JsonObject after = migrate(kind, document);
                        changes.add(new DocumentStore.Change(path, JsonDocuments.hash(before), JsonDocuments.write(after)));
                        migrated.add(path);
                    } catch (IOException | RuntimeException refused) {
                        problems.add(path + ": " + refused.getMessage() + "; file left unchanged");
                    }
                }
            }
        }
        String transaction = changes.isEmpty() ? null : store.commit(changes);
        return new Report(transaction, migrated, problems);
    }

    private List<Step> chain(Kind kind) {
        List<Step> chain = chains.get(kind);
        if (chain == null) throw new IllegalArgumentException("No migration chain for " + kind.name());
        return chain;
    }

    private static List<String> folders(DocumentStore store, Kind kind) throws IOException {
        List<String> folders = new ArrayList<>();
        for (String root : kind.roots()) {
            if (!root.startsWith("packs/*/")) { folders.add(root); continue; }
            Path packs = store.resolve("packs");
            if (!Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) continue;
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(packs)) {
                for (Path child : children) if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) names.add(child.getFileName().toString());
            }
            names.sort(null);
            for (String name : names) folders.add("packs/" + name + "/" + root.substring("packs/*/".length()));
        }
        return folders;
    }

    private static int version(JsonObject document) {
        JsonElement value = document.get("schemaVersion");
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) return -1;
        try {
            BigDecimal number = primitive.getAsBigDecimal();
            return number.signum() < 0 ? -1 : number.intValueExact();
        } catch (ArithmeticException | NumberFormatException notInteger) { return -1; }
    }

    public static final class Builder {
        private final Map<Kind, List<Step>> chains = new LinkedHashMap<>();
        private Builder() {}

        /** Starts a kind's chain at version 1. */
        public Builder identity(Kind kind) {
            if (chains.containsKey(kind)) throw new IllegalStateException(kind.name() + " already has a chain");
            List<Step> chain = new ArrayList<>();
            chain.add(IDENTITY);
            chains.put(kind, chain);
            return this;
        }

        /** Appends the step from the kind's current last version to the next one. */
        public Builder step(Kind kind, Step step) {
            List<Step> chain = chains.get(kind);
            if (chain == null) throw new IllegalStateException(kind.name() + " needs its version 1 identity first");
            chain.add(Objects.requireNonNull(step));
            return this;
        }

        public SchemaMigrations build() { return new SchemaMigrations(chains); }
    }
}
