package art.arcane.animus.core.hotload;

import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.editor.DocumentKind;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The data files a hotload watches: {@code config.toml}, the JSON documents of every editor kind (NPCs, behaviors,
 * packs, routes, recordings, skins, shops, chest GUIs, menus, holograms, text animations, presets, rigs, animations) and the
 * schematics. {@code state/}, {@code backups/}, {@code logs/}, {@code imports/}, generated output and editor temporary
 * files are never watched.
 */
public final class HotloadTargets {
    public static final String CONFIG = "config.toml";
    /** Top-level folders that hold watched files. */
    public static final List<String> FOLDERS = folders();

    public enum Area { CONFIG, DOCUMENT, SCHEMATIC }

    /** A watched file: what it is and, for documents, its kind. */
    public record Target(String path, Area area, Optional<DocumentKind> kind) {}

    private HotloadTargets() {}

    /** The target a data-folder relative path names, or empty when it is not watched. */
    public static Optional<Target> of(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) return Optional.empty();
        for (String segment : path.split("/")) if (temporary(segment) || segment.equals("..")) return Optional.empty();
        if (path.equals(CONFIG)) return Optional.of(new Target(path, Area.CONFIG, Optional.empty()));
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith(BuildService.SCHEMATICS + "/")) {
            boolean schematic = lower.endsWith(".schem") || lower.endsWith(".schematic") || lower.endsWith(".nbt");
            return schematic ? Optional.of(new Target(path, Area.SCHEMATIC, Optional.empty())) : Optional.empty();
        }
        if (!lower.endsWith(".json")) return Optional.empty();
        return DocumentKind.forPath(path).map((DocumentKind kind) -> new Target(path, Area.DOCUMENT, Optional.of(kind)));
    }

    /** Editor and operating system scratch names: dot files, backups, swap and partial downloads. */
    static boolean temporary(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.startsWith(".") || lower.startsWith("~") || lower.startsWith("#") || lower.endsWith("~")
                || lower.endsWith(".tmp") || lower.endsWith(".swp") || lower.endsWith(".swx") || lower.endsWith(".bak") || lower.endsWith(".part")
                || lower.contains(".tmp.") || lower.contains(".temp.") || lower.contains(".part.") || lower.contains(".bak.");
    }

    private static List<String> folders() {
        TreeSet<String> folders = new TreeSet<>();
        for (DocumentKind kind : DocumentKind.values()) folders.add(kind.folder().split("/")[0]);
        folders.add(BuildService.SCHEMATICS);
        return List.copyOf(folders);
    }
}
