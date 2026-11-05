package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Writes a gloss-web workspace bundle ({@code gloss-editor-workspace} version 1, workspace schemaVersion 2) that
 * Gloss Web opens. Document and workspace UUIDs are name-based, so exporting the same files twice gives the same
 * bundle.
 */
public final class GlossBundle {
    private GlossBundle() {}

    public static void write(Path file, List<GlossExchange.Exported> documents) throws IOException {
        JsonArray entries = new JsonArray();
        for (GlossExchange.Exported document : documents) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", uuid("animus:" + document.kind().word() + ":" + document.animusId()).toString());
            entry.addProperty("title", document.glossId());
            entry.addProperty("runtimeId", document.glossId());
            entry.addProperty("json", document.json());
            entry.addProperty("updatedAt", document.updatedAt());
            entry.addProperty("kind", document.kind().editorKind());
            entry.add("folderId", JsonNull.INSTANCE);
            entries.add(entry);
        }
        JsonObject workspace = new JsonObject();
        workspace.addProperty("schemaVersion", GlossSources.WORKSPACE_SCHEMA);
        workspace.addProperty("workspaceId", uuid("animus-gui-export").toString());
        workspace.add("folders", new JsonArray());
        workspace.add("documents", entries);
        workspace.add("activeDocumentId", JsonNull.INSTANCE);
        JsonObject root = new JsonObject();
        root.addProperty("format", GlossSources.BUNDLE_FORMAT);
        root.addProperty("version", GlossSources.BUNDLE_VERSION);
        root.add("workspace", workspace);
        root.add("images", new JsonArray());
        GlossFolders.write(file, JsonDocuments.write(root));
    }

    private static UUID uuid(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }
}
