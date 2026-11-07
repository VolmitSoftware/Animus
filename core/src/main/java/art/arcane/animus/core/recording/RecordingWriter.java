package art.arcane.animus.core.recording;

import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.CatalogReloads;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Saves a capture as {@code recordings/<id>.json} in one journaled transaction with a rollback receipt, at the stored
 * revision plus one, then reloads what depends on recordings. Call on the file lane.
 */
public final class RecordingWriter {
    /** What a save did. */
    public sealed interface Outcome permits Saved, TooLarge {}
    /** Written; {@code transaction} is the rollback receipt. */
    public record Saved(long revision, int bytes, String transaction, int frames, int events) implements Outcome {}
    /** Refused: the serialized recording is {@code bytes} long, above {@code maximum}. Nothing was written. */
    public record TooLarge(int bytes, int maximum) implements Outcome {}

    private RecordingWriter() {}

    public static Outcome save(DocumentStore store, CatalogReloads reloads, RecordingCapture.Take take, int maximumBytes) throws IOException {
        String path = DocumentKind.RECORDING.folder() + "/" + take.id() + ".json";
        String current = store.read(path);
        RecordingDocument document = take.document(EditorDocuments.revision(current) + 1, kept(current, maximumBytes));
        String content = document.serialize();
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumBytes) return new TooLarge(bytes, maximumBytes);
        String transaction = store.commit(List.of(new DocumentStore.Change(path, JsonDocuments.hash(current), content)));
        reloads.reload(new CatalogChange(Set.of(DocumentKind.RECORDING), Set.of(), false));
        return new Saved(document.revision(), bytes, transaction, document.frames().size(), document.events().size());
    }

    /** The details an owner set on the recording a new take replaces; none when there is none or it does not parse. */
    private static RecordingDocument.Details kept(String current, int maximumBytes) {
        if (current == null) return RecordingDocument.Details.NONE;
        try { return RecordingDocument.parse(current, maximumBytes).details(); }
        catch (IOException unreadable) { return RecordingDocument.Details.NONE; }
    }
}
