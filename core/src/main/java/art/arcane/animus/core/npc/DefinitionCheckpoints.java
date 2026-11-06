package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Generated last-valid definitions; never substitutes for or overwrites a hand-edited source file. */
final class DefinitionCheckpoints {
    private static final String PREFIX = DocumentStore.DEFINITION_POINTERS, NAMESPACE = "npc-definitions";
    private final DocumentStore store;
    record Saved(NpcDefinition definition, String sourceHash) {}
    DefinitionCheckpoints(DocumentStore store) { this.store = store; }

    Optional<Saved> read(String path) throws IOException {
        String content = store.read(PREFIX + path);
        if (content == null) return Optional.empty();
        try {
            JsonObject pointer = JsonDocuments.object(content);
            JsonElement version = pointer.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || version.getAsBigDecimal().intValueExact() != 1) throw new IOException("Unsupported recovery pointer schema");
            String sourceHash = string(pointer, "sourceHash"), objectHash = string(pointer, "objectHash");
            if (!sourceHash.matches("[a-f0-9]{64}") || !objectHash.matches("[a-f0-9]{64}")) throw new IOException("Invalid recovery hashes");
            NpcDefinition definition = NpcDefinition.read(path, store.immutableJson(NAMESPACE, objectHash));
            if (!path.equals(definition.path()) || !path.equals(string(pointer, "path"))) throw new IOException("Recovery definition does not match its source path");
            return Optional.of(new Saved(definition, sourceHash));
        } catch (RuntimeException error) { throw new IOException("Invalid recovery pointer: " + path, error); }
    }

    Optional<DocumentStore.Change> capture(NpcDefinition definition, String sourceHash) throws IOException {
        DocumentStore.Change change = prepare(definition, sourceHash);
        return change.expectedHash().equals(JsonDocuments.hash(change.content())) ? Optional.empty() : Optional.of(change);
    }

    /** An explicit unchanged pointer still belongs in an atomic accepted source replacement. */
    DocumentStore.Change prepare(NpcDefinition definition, String sourceHash) throws IOException {
        if (!sourceHash.matches("[a-f0-9]{64}")) throw new IOException("Invalid accepted source hash");
        String objectHash = store.putImmutableJson(NAMESPACE, definition.serialize());
        JsonObject pointer = new JsonObject();
        pointer.addProperty("schemaVersion", 1); pointer.addProperty("path", definition.path());
        pointer.addProperty("sourceHash", sourceHash); pointer.addProperty("objectHash", objectHash);
        String path = PREFIX + definition.path(), current = store.read(path), next = JsonDocuments.write(pointer);
        return new DocumentStore.Change(path, JsonDocuments.hash(current), next);
    }

    Optional<DocumentStore.Change> remove(String path) throws IOException {
        String pointer = PREFIX + path, current = store.read(pointer);
        return current == null ? Optional.empty() : Optional.of(new DocumentStore.Change(pointer, JsonDocuments.hash(current), null));
    }

    Set<String> paths() throws IOException {
        Path directory = store.resolve(PREFIX);
        Set<String> paths = new TreeSet<>();
            for (Path path : JsonDocumentTree.files(directory, NpcPaths.TREE_DEPTH + 1)) {
                    String relative = directory.relativize(path).toString().replace('\\', '/');
                    if (!NpcPaths.file(relative)) throw new IOException("Invalid recovery pointer path: " + relative);
                    paths.add(relative);
            }
        return Set.copyOf(paths);
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid recovery field: " + key);
        return value.getAsString();
    }
}
