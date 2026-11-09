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
 * Upgrades every versioned document kind to the schema version this build reads. Each kind has an ordered chain: the
 * first entry is the identity at the kind's first version (1, or 3 for holograms, which keep Gloss's numbering), and
 * each later entry turns a document of one version into the next. Rewrites are committed in {@link DocumentStore} transactions
 * of at most {@link #BATCH} documents, so they are journaled, recover after a crash and leave rollback receipts holding the
 * original bytes. A document newer than the chain, or one whose step
 * fails, is reported and left byte-for-byte in place.
 */
public final class SchemaMigrations {
    /** Turns a document of one version into the next version. It must not modify its argument. */
    @FunctionalInterface
    public interface Step { JsonObject migrate(JsonObject previous) throws IOException; }

    /** A document kind and the data folders, or single {@code .json} files, that hold it. A {@code packs/*} prefix covers every pack. */
    public record Kind(String name, List<String> roots) {
        public Kind { roots = List.copyOf(roots); }
    }

    /** What one pass changed: one transaction per batch, none when nothing was rewritten. */
    public record Report(List<String> transactions, List<String> migrated, List<String> problems) {
        public Report { transactions = List.copyOf(transactions); migrated = List.copyOf(migrated); problems = List.copyOf(problems); }
    }

    /**
     * Documents per transaction. A journal holds at most 10000 entries, and each NPC file can bring its recovery pointer
     * along, so batches stay well under that.
     */
    private static final int BATCH = 2_000;

    public static final Kind NPC = new Kind("NPC definition", List.of("npcs"));
    public static final Kind BEHAVIOR = new Kind("behavior", List.of("behaviors", "packs/*/behaviors"));
    public static final Kind SHOP = new Kind("shop", List.of("shops"));
    public static final Kind ROUTE = new Kind("route", List.of("routes"));
    public static final Kind RECORDING = new Kind("recording", List.of("recordings"));
    public static final Kind GROUP = new Kind("group", List.of("groups"));
    public static final Kind INVENTORY = new Kind("chest GUI", List.of("gui/inventories"));
    public static final Kind HOLOGRAM = new Kind("hologram", List.of("gui/holograms"));
    public static final Kind TEXT_ANIMATION = new Kind("text animation", List.of("gui/text-animations"));
    public static final Kind RIG = new Kind("rig", List.of("rigs"));
    public static final Kind ANIMATION = new Kind("animation", List.of("animations"));
    public static final Kind SERVER_BEHAVIORS = new Kind("server behavior list", List.of("server-behaviors.json"));
    public static final Kind PACK = new Kind("pack manifest", List.of("packs/*/pack.json"));
    private static final Step IDENTITY = (JsonObject previous) -> previous;
    private static final SchemaMigrations STANDARD = builder().identity(NPC).identity(BEHAVIOR).identity(SHOP).identity(ROUTE).identity(RECORDING)
            .identity(GROUP).identity(INVENTORY).identity(HOLOGRAM, 3).identity(TEXT_ANIMATION).identity(RIG).identity(ANIMATION)
            .identity(SERVER_BEHAVIORS).identity(PACK).build();

    /** One kind's steps, the first being the identity at version {@code first}. */
    private record Chain(int first, List<Step> steps) {
        int current() { return first + steps.size() - 1; }
    }

    /** In registration order, so a pass visits kinds and reports paths in a stable order. */
    private final Map<Kind, Chain> chains;

    private SchemaMigrations(Map<Kind, Chain> chains) {
        Map<Kind, Chain> copy = new LinkedHashMap<>();
        for (Map.Entry<Kind, Chain> chain : chains.entrySet()) copy.put(chain.getKey(), new Chain(chain.getValue().first(), List.copyOf(chain.getValue().steps())));
        this.chains = Collections.unmodifiableMap(copy);
    }

    /** The chains this build ships: every kind at its first version. */
    public static SchemaMigrations standard() { return STANDARD; }
    public static Builder builder() { return new Builder(); }

    /** The version a kind is written at. */
    public int current(Kind kind) { return chain(kind).current(); }

    /**
     * The document at the current version. A missing or non-integer {@code schemaVersion}, or one below the kind's first
     * version, is returned unchanged for the document's own parser to report. A newer version is refused.
     */
    public JsonObject migrate(Kind kind, JsonObject document) throws IOException {
        Chain chain = chain(kind);
        int version = version(document);
        if (version < chain.first()) return document;
        if (version > chain.current())
            throw new IOException(kind.name() + " schemaVersion " + version + " is newer than this Animus reads (" + chain.current()
                    + "); update Animus or restore an older copy");
        JsonObject result = document;
        for (int next = version; next < chain.current(); next++) {
            result = Objects.requireNonNull(chain.steps().get(next - chain.first() + 1).migrate(result.deepCopy()), "migration step returned null");
            result.addProperty("schemaVersion", next + 1);
        }
        return result;
    }

    /** Migrates every stale document in the store, in batches. Call before the catalogs load. */
    public Report apply(DocumentStore store) throws IOException {
        List<DocumentStore.Change> changes = new ArrayList<>();
        List<String> migrated = new ArrayList<>(), problems = new ArrayList<>();
        for (Map.Entry<Kind, Chain> chain : chains.entrySet()) {
            Kind kind = chain.getKey();
            for (String path : paths(store, kind)) {
                String before;
                JsonObject document;
                // An unreadable or malformed file is the loader's to report.
                try {
                    before = store.read(path);
                    if (before == null) continue;
                    document = JsonDocuments.object(before);
                } catch (IOException | RuntimeException unreadable) { continue; }
                int version = version(document);
                if (version < chain.getValue().first() || version == chain.getValue().current()) continue;
                try {
                    JsonObject after = migrate(kind, document);
                    changes.add(new DocumentStore.Change(path, JsonDocuments.hash(before), JsonDocuments.write(after)));
                    migrated.add(path);
                } catch (IOException | RuntimeException refused) {
                    problems.add(path + ": " + refused.getMessage() + "; file left unchanged");
                }
            }
        }
        List<String> transactions = new ArrayList<>();
        for (int from = 0; from < changes.size(); from += BATCH) transactions.add(store.commit(changes.subList(from, Math.min(changes.size(), from + BATCH))));
        return new Report(transactions, migrated, problems);
    }

    private Chain chain(Kind kind) {
        Chain chain = chains.get(kind);
        if (chain == null) throw new IllegalArgumentException("No migration chain for " + kind.name());
        return chain;
    }

    /** Every document path of a kind: the files under its folders, and its single files that exist. */
    private static List<String> paths(DocumentStore store, Kind kind) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String location : locations(store, kind)) {
            Path resolved = store.resolve(location);
            if (location.endsWith(".json")) {
                if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) paths.add(location);
                continue;
            }
            for (Path file : JsonDocumentTree.files(resolved, kind.equals(NPC) ? NpcPaths.TREE_DEPTH : 8))
                paths.add(location + "/" + resolved.relativize(file).toString().replace('\\', '/'));
        }
        return paths;
    }

    private static List<String> locations(DocumentStore store, Kind kind) throws IOException {
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
        private final Map<Kind, Chain> chains = new LinkedHashMap<>();
        private Builder() {}

        /** Starts a kind's chain at version 1. */
        public Builder identity(Kind kind) { return identity(kind, 1); }

        /** Starts a kind's chain at {@code first}, for a format whose numbering began elsewhere. */
        public Builder identity(Kind kind, int first) {
            if (chains.containsKey(kind)) throw new IllegalStateException(kind.name() + " already has a chain");
            if (first < 1) throw new IllegalArgumentException("A chain starts at version 1 or later");
            List<Step> steps = new ArrayList<>();
            steps.add(IDENTITY);
            chains.put(kind, new Chain(first, steps));
            return this;
        }

        /** Appends the step from the kind's current last version to the next one. */
        public Builder step(Kind kind, Step step) {
            Chain chain = chains.get(kind);
            if (chain == null) throw new IllegalStateException(kind.name() + " needs its identity first");
            chain.steps().add(Objects.requireNonNull(step));
            return this;
        }

        public SchemaMigrations build() { return new SchemaMigrations(chains); }
    }
}
