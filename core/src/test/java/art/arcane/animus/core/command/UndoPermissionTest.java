package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The node {@code /animus undo} needs for a change: the one its command needed, else the one for what its files are,
 * so a change made in the Animus menu needs the node of the screen that made it.
 */
class UndoPermissionTest {
    private static final String NPC = "{\"id\":\"guard\"}", NPC_EDITED = "{\"id\":\"guard\",\"name\":\"Guard\"}";
    @TempDir Path root;

    private List<String> needed(List<DocumentStore.Change> changes) throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            String tx = documents.commit(changes);
            return UndoPermission.needed(new RecentChanges.Change(tx.substring(0, 8), "", ""), documents);
        }
    }

    private static DocumentStore.Change made(String path, String content) { return new DocumentStore.Change(path, "missing", content); }

    @Test void aCommandsChangeNeedsTheNodeTheCommandNeeded() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            String tx = documents.commit(List.of(made("npcs/guard.json", NPC)));
            assertEquals(List.of(PermissionNodes.IMPORT), UndoPermission.needed(new RecentChanges.Change(tx, "Imported guard", PermissionNodes.IMPORT), documents));
        }
    }

    @Test void makingAnNpcNeedsCreateAndDeletingOneNeedsDelete() throws Exception {
        assertEquals(List.of(PermissionNodes.NPC_CREATE), needed(List.of(made("npcs/town/guard.json", NPC), made("skins/abc.json", "{}"))));
        try (DocumentStore documents = new DocumentStore(root)) {
            String tx = documents.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", documents.hash("npcs/town/guard.json"), null)));
            assertEquals(List.of(PermissionNodes.NPC_DELETE), UndoPermission.needed(new RecentChanges.Change(tx, "", ""), documents));
        }
    }

    @Test void aShopRouteOrSkinNeedsItsOwnNodeEvenWhenItAlsoChangedNpcs() throws Exception {
        assertEquals(List.of(PermissionNodes.SHOPS), needed(List.of(made("shops/town/bakery.json", "{}"))));
        try (DocumentStore documents = new DocumentStore(root)) {
            documents.commit(List.of(made("npcs/guard.json", NPC)));
            String tx = documents.commit(List.of(made("routes/market.json", "{}"), new DocumentStore.Change("npcs/guard.json", documents.hash("npcs/guard.json"), NPC_EDITED)));
            assertEquals(List.of(PermissionNodes.NPC_MOVE), UndoPermission.needed(new RecentChanges.Change(tx, "", ""), documents));
        }
        assertEquals(List.of(PermissionNodes.GUI), needed(List.of(made("gui/holograms/sign.json", "{}"))));
    }

    @Test void anEditToNpcsOnlyNeedsAnyNodeThatEditsNpcsAndAMoveCountsAsAnEdit() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            documents.commit(List.of(made("npcs/guard.json", NPC)));
            String edited = documents.commit(List.of(new DocumentStore.Change("npcs/guard.json", documents.hash("npcs/guard.json"), NPC_EDITED)));
            assertEquals(UndoPermission.NPC_CHANGES, UndoPermission.needed(new RecentChanges.Change(edited, "", ""), documents));
            String moved = documents.commit(List.of(new DocumentStore.Change("npcs/guard.json", documents.hash("npcs/guard.json"), null), made("npcs/town/guard.json", NPC_EDITED)));
            assertEquals(UndoPermission.NPC_CHANGES, UndoPermission.needed(new RecentChanges.Change(moved, "", ""), documents), "a folder move is an edit");
        }
    }

    @Test void anImportFileNeedsTheImportNode() throws Exception {
        assertEquals(List.of(PermissionNodes.IMPORT), needed(List.of(made("imports/applications/a1.json", "{}"), made("npcs/guard.json", NPC))));
    }
}
