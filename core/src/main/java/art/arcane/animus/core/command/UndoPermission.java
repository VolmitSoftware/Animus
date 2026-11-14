package art.arcane.animus.core.command;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The permission node {@code /animus undo} needs for one change, so a player cannot revert what they could no longer
 * make. A command's change needs the node its command needed. A change made in the Animus menu does not say which screen
 * made it, so it needs the node for what it wrote, read from its rollback receipt.
 */
final class UndoPermission {
    /** Every node whose commands and screens change only NPC files; any one of them undoes such a change. */
    static final List<String> NPC_CHANGES = List.of(PermissionNodes.NPC_EDIT, PermissionNodes.NPC_SKIN, PermissionNodes.NPC_MOVE,
            PermissionNodes.COMBAT, PermissionNodes.BEHAVIORS, PermissionNodes.RECORD);

    private UndoPermission() {}

    /**
     * The nodes, any one of which undoes {@code change}. File lane: a menu change reads its receipt. An import file needs
     * {@code animus.import} and a file no node covers {@code animus.admin}. Making an NPC needs {@code animus.npc.create}
     * and deleting one {@code animus.npc.delete}, while one NPC file going and another coming is a move, an edit. A route,
     * skin, shop or other file needs its own node even when the change also put it on NPCs; a change to NPC files alone
     * needs any node that edits NPCs.
     */
    static List<String> needed(RecentChanges.Change change, DocumentStore documents) throws IOException {
        if (!change.node().isEmpty()) return List.of(change.node());
        boolean made = false, deleted = false;
        Optional<String> own = Optional.empty();
        for (DocumentStore.Change undo : documents.inverse(documents.transaction(change.transaction()))) {
            // state/ holds bookkeeping that follows the documents, such as an NPC file's last-valid pointer.
            if (undo.path().startsWith("state/")) continue;
            if (undo.path().startsWith("imports/")) return List.of(PermissionNodes.IMPORT);
            Optional<DocumentKind> kind = DocumentKind.forPath(undo.path());
            if (kind.isEmpty()) return List.of(PermissionNodes.ADMIN);
            if (kind.get() != DocumentKind.NPC) { if (own.isEmpty()) own = Optional.of(node(kind.get())); continue; }
            if (undo.content() == null) made = true;
            else if (undo.expectedHash().equals(JsonDocuments.hash(null))) deleted = true;
        }
        if (made && !deleted) return List.of(PermissionNodes.NPC_CREATE);
        if (deleted && !made) return List.of(PermissionNodes.NPC_DELETE);
        return own.map(List::of).orElse(NPC_CHANGES);
    }

    /** The node the commands and screens for a kind of file need. */
    private static String node(DocumentKind kind) {
        return switch (kind) {
            case NPC, GROUP -> PermissionNodes.NPC_EDIT;
            case ROUTE -> PermissionNodes.NPC_MOVE;
            case RECORDING -> PermissionNodes.RECORD;
            case SKIN -> PermissionNodes.NPC_SKIN;
            case BEHAVIOR, PACK, SERVER_BEHAVIORS -> PermissionNodes.BEHAVIORS;
            case SHOP -> PermissionNodes.SHOPS;
            case QUEST -> PermissionNodes.QUESTS;
            case INVENTORY, MENU, HOLOGRAM, TEXT_ANIMATION, PRESETS -> PermissionNodes.GUI;
            case RIG, ANIMATION -> PermissionNodes.RIGS;
        };
    }
}
