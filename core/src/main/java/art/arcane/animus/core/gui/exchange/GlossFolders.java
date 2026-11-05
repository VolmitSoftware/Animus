package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.WriteNotices;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Gloss's own folders and the {@code /gloss export documents} layout, read and written atomically. */
public final class GlossFolders {
    private GlossFolders() {}

    /** Documents in Gloss's live folders under its data folder, optionally one kind and one Gloss id. */
    public static List<GlossDocument> readLive(Path glossData, Optional<GuiKind> kind, Optional<String> glossId) throws IOException {
        List<GlossDocument> documents = new ArrayList<>();
        for (GuiKind each : GuiKind.values()) {
            if (kind.isPresent() && kind.get() != each) continue;
            for (GlossDocument document : GlossSources.kindFolder(glossData.resolve(each.glossFolder()), each))
                if (glossId.isEmpty() || glossId.get().equals(document.id())) documents.add(document);
        }
        return documents;
    }

    /** Writes into Gloss's live folders, so its watcher loads them. A Gloss file with other content is kept unless {@code replace}. */
    public static GlossExchange.Report writeLive(Path glossData, List<GlossExchange.Exported> documents, boolean replace) throws IOException {
        GlossExchange.Report.Builder report = new GlossExchange.Report.Builder();
        for (GlossExchange.Exported document : documents) {
            Path file = glossData.resolve(document.kind().glossFolder()).resolve(document.glossId() + ".json").normalize();
            String label = document.kind().word() + " " + document.glossId();
            if (!file.startsWith(glossData.normalize())) { report.refused(label + ": id leaves the Gloss folder"); continue; }
            switch (compare(file, document.json())) {
                case SAME -> report.unchanged(label);
                case DIFFERENT -> { if (replace) { write(file, document.json()); report.written(label); } else report.refused(label + ": Gloss has a different file; add replace=true to overwrite it"); }
                case MISSING -> { write(file, document.json()); report.written(label); }
            }
        }
        return report.build();
    }

    /** Writes the {@code /gloss export documents} layout: {@code <kind>/<id>.json} plus {@code workspace.json}. */
    public static GlossExchange.Report writeExport(Path root, List<GlossExchange.Exported> documents) throws IOException {
        GlossExchange.Report.Builder report = new GlossExchange.Report.Builder();
        JsonArray listed = new JsonArray();
        for (GlossExchange.Exported document : documents) {
            String path = document.kind().glossFolder() + "/" + document.glossId() + ".json";
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root.normalize())) { report.refused(document.animusId() + ": id leaves the export folder"); continue; }
            write(file, document.json());
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", document.kind().glossFolder());
            entry.addProperty("id", document.glossId());
            entry.addProperty("path", path);
            listed.add(entry);
            report.written(document.kind().word() + " " + document.animusId() + " as " + document.glossId());
        }
        JsonObject workspace = new JsonObject();
        workspace.addProperty("format", GlossSources.EXPORT_FORMAT);
        workspace.addProperty("version", GlossSources.EXPORT_VERSION);
        workspace.addProperty("exportedAt", Instant.now().toString());
        workspace.add("documents", listed);
        write(root.resolve("workspace.json"), JsonDocuments.write(workspace));
        return report.build();
    }

    enum Comparison { SAME, DIFFERENT, MISSING }

    static Comparison compare(Path file, String json) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Comparison.MISSING;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(file.getFileName() + " is not a regular file");
        try { return JsonDocuments.object(JsonDocuments.read(file)).equals(JsonDocuments.object(json)) ? Comparison.SAME : Comparison.DIFFERENT; }
        catch (IOException unreadable) { return Comparison.DIFFERENT; }
    }

    /** Writes beside the target and moves it into place, so a reader never sees half a file. */
    static void write(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".animus-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            WriteNotices.written(file, text);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException | RuntimeException failure) { WriteNotices.touched(file); throw failure; }
            WriteNotices.written(file, text);
        } finally { Files.deleteIfExists(temporary); }
    }
}
