package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads Gloss documents from a {@code /gloss export documents} folder (kind folders plus {@code workspace.json}) or
 * a gloss-web workspace bundle ({@code gloss-editor-workspace} version 1, workspace schemaVersion 2). Kinds Animus
 * does not adopt are listed in {@link Batch#skipped}, never dropped silently.
 */
public final class GlossSources {
    public static final String EXPORT_FORMAT = "gloss-workspace-export", BUNDLE_FORMAT = "gloss-editor-workspace";
    public static final int EXPORT_VERSION = 1, BUNDLE_VERSION = 1, WORKSPACE_SCHEMA = 2;

    private GlossSources() {}

    public record Batch(List<GlossDocument> documents, List<String> skipped) {
        public Batch { documents = List.copyOf(documents); skipped = List.copyOf(skipped); }
    }

    /** A bundle file or an export folder. */
    public static Batch read(Path source) throws IOException {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return folder(source);
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return bundle(source);
        throw new IOException(source.getFileName() + ": no such folder or bundle file");
    }

    public static Batch folder(Path root) throws IOException {
        List<GlossDocument> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Path manifest = root.resolve("workspace.json");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject workspace = JsonDocuments.object(JsonDocuments.read(manifest));
            String format = string(workspace, "format");
            if (!EXPORT_FORMAT.equals(format)) throw new IOException("workspace.json: format " + format + " is not " + EXPORT_FORMAT);
            int version = integer(workspace, "version");
            if (version != EXPORT_VERSION) throw new IOException("workspace.json: version " + version + " is not supported; Animus reads version " + EXPORT_VERSION);
            JsonElement list = workspace.get("documents");
            if (list == null || !list.isJsonArray()) throw new IOException("workspace.json: documents must be an array");
            for (JsonElement entry : list.getAsJsonArray()) {
                if (!entry.isJsonObject()) throw new IOException("workspace.json: every document must be an object");
                JsonObject document = entry.getAsJsonObject();
                String folder = string(document, "kind"), id = string(document, "id"), path = string(document, "path");
                Optional<GuiKind> kind = folderKind(folder);
                if (kind.isEmpty()) { skipped.add(folder + " " + id + ": kind not adopted by Animus"); continue; }
                Path file = root.resolve(path).normalize();
                if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("workspace.json: " + path + " is missing or outside the export folder");
                documents.add(new GlossDocument(kind.get(), id, JsonDocuments.read(file)));
            }
            return new Batch(documents, skipped);
        }
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            for (Path child : children.sorted().toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = child.getFileName().toString();
                Optional<GuiKind> kind = folderKind(name);
                if (kind.isEmpty()) { skipped.add(name + ": kind not adopted by Animus"); continue; }
                documents.addAll(kindFolder(child, kind.get()));
            }
        }
        return new Batch(documents, skipped);
    }

    /** Every document in one Gloss kind folder; flat kinds read direct children only. */
    static List<GlossDocument> kindFolder(Path folder, GuiKind kind) throws IOException {
        List<GlossDocument> documents = new ArrayList<>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return documents;
        List<Path> files;
        if (kind == GuiKind.MENU) files = JsonDocumentTree.files(folder);
        else try (java.util.stream.Stream<Path> children = Files.list(folder)) {
            files = children.filter((Path child) -> Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) && child.getFileName().toString().endsWith(".json"))
                    .sorted().limit(100_000).toList();
        }
        for (Path file : files) {
            String id = folder.relativize(file).toString().replace('\\', '/').replaceFirst("\\.json$", "");
            documents.add(new GlossDocument(kind, id, JsonDocuments.read(file)));
        }
        return documents;
    }

    public static Batch bundle(Path file) throws IOException {
        JsonObject root = JsonDocuments.object(JsonDocuments.read(file));
        String format = string(root, "format");
        if (!BUNDLE_FORMAT.equals(format)) throw new IOException("bundle: format " + format + " is not " + BUNDLE_FORMAT);
        int version = integer(root, "version");
        if (version != BUNDLE_VERSION) throw new IOException("bundle: version " + version + " is not supported; Animus reads version " + BUNDLE_VERSION);
        JsonElement value = root.get("workspace");
        if (value == null || !value.isJsonObject()) throw new IOException("bundle: workspace must be an object");
        JsonObject workspace = value.getAsJsonObject();
        int schema = integer(workspace, "schemaVersion");
        if (schema != WORKSPACE_SCHEMA) throw new IOException("bundle: workspace schemaVersion " + schema + " is not supported; Animus reads schemaVersion " + WORKSPACE_SCHEMA);
        JsonElement list = workspace.get("documents");
        if (list == null || !list.isJsonArray()) throw new IOException("bundle: workspace.documents must be an array");
        List<GlossDocument> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        JsonArray entries = list.getAsJsonArray();
        for (int index = 0; index < entries.size(); index++) {
            String path = "bundle: workspace.documents[" + index + "]";
            if (!entries.get(index).isJsonObject()) throw new IOException(path + " must be an object");
            JsonObject document = entries.get(index).getAsJsonObject();
            String kindName = string(document, "kind");
            Optional<GuiKind> kind = editorKind(kindName);
            String id = document.has("runtimeId") && document.get("runtimeId").isJsonPrimitive() ? document.get("runtimeId").getAsString()
                    : document.has("title") && document.get("title").isJsonPrimitive() ? document.get("title").getAsString() : "#" + index;
            if (kind.isEmpty()) { skipped.add(kindName + " " + id + ": kind not adopted by Animus"); continue; }
            if (!document.has("runtimeId")) { skipped.add(kindName + " " + id + ": no runtimeId"); continue; }
            JsonElement json = document.get("json");
            if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) { skipped.add(kindName + " " + id + ": json must be a string"); continue; }
            documents.add(new GlossDocument(kind.get(), id, json.getAsString()));
        }
        return new Batch(documents, skipped);
    }

    static Optional<GuiKind> folderKind(String folder) {
        for (GuiKind kind : GuiKind.values()) if (kind.glossFolder().equals(folder)) return Optional.of(kind);
        return Optional.empty();
    }

    static Optional<GuiKind> editorKind(String name) {
        for (GuiKind kind : GuiKind.values()) if (kind.editorKind().equals(name)) return Optional.of(kind);
        return Optional.empty();
    }

    private static String string(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException(field + " must be a string");
        return value.getAsString();
    }

    private static int integer(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException(field + " must be a number");
        return value.getAsInt();
    }
}
