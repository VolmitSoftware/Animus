package art.arcane.animus.core.gui;

import java.util.Optional;

/**
 * The Gloss document kinds Animus adopts, with Gloss's folder name, the gloss-web editor kind and Animus's folder
 * under its data folder. Animus ids are {@code namespace:path}, from {@code <folder>/<namespace>/<path>.json}.
 */
public enum GuiKind {
    INVENTORY("inventories", "inventory", "gui/inventories"),
    MENU("menus", "menu", "gui/menus"),
    HOLOGRAM("holograms", "hologram", "gui/holograms"),
    ANIMATION("animations", "animation", "gui/text-animations");

    private final String glossFolder, editorKind, folder;

    GuiKind(String glossFolder, String editorKind, String folder) {
        this.glossFolder = glossFolder; this.editorKind = editorKind; this.folder = folder;
    }

    /** Gloss's folder under {@code plugins/Gloss/}, also the kind name of {@code /gloss export}. */
    public String glossFolder() { return glossFolder; }
    /** The {@code kind} of a document in a gloss-web workspace bundle. */
    public String editorKind() { return editorKind; }
    /** Animus's folder under its data folder. */
    public String folder() { return folder; }
    /** The command word: inventory, menu, hologram or animation. */
    public String word() { return editorKind; }

    public static Optional<GuiKind> word(String word) {
        for (GuiKind kind : values()) if (kind.editorKind.equals(word) || kind.glossFolder.equals(word)) return Optional.of(kind);
        return Optional.empty();
    }
}
