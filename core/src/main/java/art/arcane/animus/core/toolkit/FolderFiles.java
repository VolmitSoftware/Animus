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

/** Folder renames for the menu, on the file lane. A folder is only the NPC files in it, so a rename moves them all. */
final class FolderFiles {
    private final NpcRepository npcs;
    private final DocumentStore store;

    FolderFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** A folder as people read it: {@code town / market}, and the top level for {@code npcs/} itself. */
    static String shown(String folder) { return folder.isEmpty() ? "Top level" : folder.replace("/", " / "); }

    /** A typed folder name with the spaces around its slashes dropped, so {@code town / market} reads {@code town/market}. */
    static String typed(String text) { return text.strip().replaceAll("\\s*/\\s*", "/"); }

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
        if (from.isEmpty()) throw new IOException("The top level cannot be renamed");
        if (to.isEmpty() || !NpcPaths.folder(to)) throw new IOException("Folder " + to + " is not lowercase words split by /");
        if (to.equals(from)) return "Folder " + shown(from) + " already has that name";
        List<NpcDefinition> moved = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<DocumentStore.Change> removals = new ArrayList<>();
        for (String id : members(npcs, from)) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            String next = to + document.get("group").getAsString().substring(from.length());
            if (!NpcPaths.folder(next)) throw new IOException("Folder " + shown(next) + " would be too deep");
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
        if (moved.isEmpty()) throw new IOException("No NPCs in folder " + shown(from));
        String tx = DefinitionBatch.commit(store, moved, expected, removals);
        npcs.reload();
        return "Renamed folder " + shown(from) + " to " + shown(to) + ", moved " + ToolkitFiles.count(moved.size()) + "; tx " + tx;
    }
}
