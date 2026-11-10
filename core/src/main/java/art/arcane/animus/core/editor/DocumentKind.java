package art.arcane.animus.core.editor;

import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import java.util.Optional;

/** Document kinds the editor API names, with their folder under the data folder and the scope that applies them. */
public enum DocumentKind {
    NPC("npc", "npcs", EditorScope.APPLY_NPCS),
    ROUTE("route", "routes", EditorScope.APPLY_NPCS),
    RECORDING("recording", "recordings", EditorScope.APPLY_NPCS),
    SKIN("skin", "skins", EditorScope.APPLY_NPCS),
    GROUP("group", "groups", EditorScope.APPLY_NPCS),
    BEHAVIOR("behavior", "behaviors", EditorScope.APPLY_BEHAVIORS),
    PACK("pack", "packs", EditorScope.APPLY_BEHAVIORS),
    SHOP("shop", "shops", EditorScope.APPLY_GUI),
    QUEST("quest", "quests", EditorScope.APPLY_BEHAVIORS),
    INVENTORY("inventory", "gui/inventories", EditorScope.APPLY_GUI),
    MENU("menu", "gui/menus", EditorScope.APPLY_GUI),
    HOLOGRAM("hologram", "gui/holograms", EditorScope.APPLY_GUI),
    TEXT_ANIMATION("text-animation", "gui/text-animations", EditorScope.APPLY_GUI),
    PRESETS("presets", "gui", EditorScope.APPLY_GUI),
    RIG("rig", "rigs", EditorScope.APPLY_RIGS),
    ANIMATION("animation", "animations", EditorScope.APPLY_RIGS),
    /** The single {@code server-behaviors.json} at the data folder root; its folder is empty. */
    SERVER_BEHAVIORS("server-behaviors", "", EditorScope.APPLY_BEHAVIORS);

    private final String wireName;
    private final String folder;
    private final EditorScope applyScope;

    DocumentKind(String wireName, String folder, EditorScope applyScope) {
        this.wireName = wireName;
        this.folder = folder;
        this.applyScope = applyScope;
    }

    public String wireName() { return wireName; }

    public String folder() { return folder; }

    public EditorScope applyScope() { return applyScope; }

    /** The kind whose folder holds a data-folder relative path, the deepest folder first; empty for anything else. */
    public static Optional<DocumentKind> forPath(String path) {
        if (path.equals(ServerBehaviorList.PATH)) return Optional.of(SERVER_BEHAVIORS);
        DocumentKind found = null;
        for (DocumentKind kind : values())
            if (!kind.folder.isEmpty() && path.startsWith(kind.folder + "/") && (found == null || kind.folder.length() > found.folder.length())) found = kind;
        return Optional.ofNullable(found);
    }

    public static Optional<DocumentKind> parse(String wireName) {
        for (DocumentKind kind : values()) if (kind.wireName.equals(wireName)) return Optional.of(kind);
        return Optional.empty();
    }
}
