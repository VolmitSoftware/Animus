package art.arcane.animus.core.rig;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Last-valid rig and animation sources, stored like behavior checkpoints: a pointer under
 * {@code state/rig-catalog/last-valid/<rigs|animations>/...} names a hashed copy under {@code state/objects/rig-catalog/}.
 * Hand-edited files are never rewritten. Call on the file lane.
 */
public final class RigCheckpoints {
    private static final String PREFIX = "state/rig-catalog/last-valid/", NAMESPACE = "rig-catalog";
    private static final RigCheckpoints DISABLED = new RigCheckpoints();
    record Saved(long revision, String sourceHash, String source) {}

    private final DocumentStore store;
    private final Map<String, String> current = new HashMap<>();

    public RigCheckpoints(DocumentStore store) { this.store = Objects.requireNonNull(store); }
    private RigCheckpoints() { store = null; }
    /** Validation and tests that must not write anything. */
    public static RigCheckpoints disabled() { return DISABLED; }

    /** Pointers whose source file still exists, by source path such as {@code rigs/town/golem.json}. Unusable pointers are skipped. */
    synchronized Map<String, Saved> load(Path data) throws IOException {
        if (store == null) return Map.of();
        Map<String, Saved> saved = new TreeMap<>();
        Path directory = store.resolve(PREFIX);
        for (Path file : JsonDocumentTree.files(directory, 20)) {
            String path = directory.relativize(file).toString().replace('\\', '/');
            current.put(path, "");
            if (!Files.exists(data.resolve(path), LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                JsonObject pointer = JsonDocuments.object(store.read(PREFIX + path));
                if (!path.equals(text(pointer, "path"))) continue;
                String hash = text(pointer, "sourceHash");
                if (!hash.matches("[a-f0-9]{64}")) continue;
                JsonElement revision = pointer.get("revision");
                if (!RigFields.number(revision)) continue;
                Saved value = new Saved(revision.getAsBigDecimal().longValueExact(), hash, store.immutableJson(NAMESPACE, hash));
                saved.put(path, value);
                current.put(path, hash);
            } catch (IOException | RuntimeException unusable) { /* An unusable pointer is replaced on the next accepted source. */ }
        }
        return saved;
    }

    /** Points every accepted path at its source and drops pointers whose source file is gone, in one journal. */
    synchronized void update(Map<String, String> accepted, Map<String, Long> revisions, Set<String> present) throws IOException {
        if (store == null) return;
        List<DocumentStore.Generated> changes = new ArrayList<>();
        Map<String, String> next = new HashMap<>(current);
        for (Map.Entry<String, String> item : accepted.entrySet()) {
            String hash = JsonDocuments.hash(item.getValue());
            if (hash.equals(current.get(item.getKey()))) continue;
            String stored = store.putImmutableJson(NAMESPACE, item.getValue());
            JsonObject pointer = new JsonObject();
            pointer.addProperty("schemaVersion", 1);
            pointer.addProperty("path", item.getKey());
            pointer.addProperty("revision", revisions.get(item.getKey()));
            pointer.addProperty("sourceHash", stored);
            clearUnreadable(PREFIX + item.getKey());
            changes.add(new DocumentStore.Generated(PREFIX + item.getKey(), JsonDocuments.write(pointer)));
            next.put(item.getKey(), stored);
        }
        for (String path : current.keySet()) {
            if (present.contains(path)) continue;
            clearUnreadable(PREFIX + path);
            changes.add(new DocumentStore.Generated(PREFIX + path, null));
            next.remove(path);
        }
        if (changes.isEmpty()) return;
        store.commitGenerated(changes);
        current.clear();
        current.putAll(next);
    }

    private void clearUnreadable(String path) throws IOException {
        try { store.read(path); }
        catch (CharacterCodingException undecodable) { store.deleteGenerated(path); }
    }
    private static String text(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (!RigFields.string(value)) throw new IOException("Invalid checkpoint field: " + key);
        return value.getAsString();
    }
}
