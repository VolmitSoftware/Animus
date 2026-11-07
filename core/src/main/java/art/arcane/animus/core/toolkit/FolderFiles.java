package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Folder renames for the toolkit, on the file lane. A folder is only the NPC files in it, so a rename moves them all. */
final class FolderFiles {
    private final NpcRepository npcs;
    private final DocumentStore store;

    FolderFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** Whether the NPC sits in the folder or one below it. */
    static boolean inside(String folder, String npcFolder) { return npcFolder.equals(folder) || npcFolder.startsWith(folder + "/"); }

    /** The stored NPCs in the folder and the folders below it. */
    static List<String> members(NpcRepository npcs, String folder) {
        List<String> ids = new ArrayList<>();
        for (NpcDefinition definition : npcs.stored()) if (inside(folder, definition.group())) ids.add(definition.id());
        ids.sort(String::compareTo);
        return ids;
    }

    /** Moves every NPC in {@code from}, and in the folders below it, to {@code to} in one transaction. */
    String rename(String from, String to) throws IOException {
        if (from.isEmpty()) throw new IOException("npcs/ itself cannot be renamed");
        if (to.isEmpty() || !NpcPaths.folder(to)) throw new IOException("Folder " + to + " is not lowercase words split by /");
        if (to.equals(from)) return "Folder npcs/" + from + "/ already has that name";
        List<NpcDefinition> moved = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<DocumentStore.Change> removals = new ArrayList<>();
        for (String id : members(npcs, from)) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            String next = to + document.get("group").getAsString().substring(from.length());
            if (!NpcPaths.folder(next)) throw new IOException("Folder " + next + " would be too deep");
            document.addProperty("group", next);
            document.addProperty("revision", draft.expectedRevision() + 1);
            NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(document));
            if (store.read(definition.path()) != null) throw new IOException(definition.path() + " already exists");
            moved.add(definition);
            expected.put(definition.path(), "missing");
            removals.add(new DocumentStore.Change(draft.path(), draft.expectedHash(), null));
            String pointer = DocumentStore.DEFINITION_POINTERS + draft.path(), current = store.read(pointer);
            if (current != null) removals.add(new DocumentStore.Change(pointer, JsonDocuments.hash(current), null));
        }
        if (moved.isEmpty()) throw new IOException("No NPCs in npcs/" + from + "/");
        String tx = DefinitionBatch.commit(store, moved, expected, removals);
        npcs.reload();
        return "Renamed npcs/" + from + "/ to npcs/" + to + "/, moved " + ToolkitFiles.count(moved.size()) + "; tx " + tx;
    }
}
