package art.arcane.animus.core.storage;

/**
 * Where NPC definition files may live: {@code npcs/<id>.json} directly, or under up to {@value #MAX_FOLDERS} folders
 * of the owner's choosing, such as {@code npcs/world/town/market/blacksmith.json}. Every folder and the file name are
 * one segment of 1-64 lowercase letters, digits, {@code _} or {@code -}; the file name is the NPC id.
 */
public final class NpcPaths {
    public static final String SEGMENT = "[a-z0-9][a-z0-9_-]{0,63}";
    public static final int MAX_FOLDERS = 8;
    /** A folder below {@code npcs/}: empty for {@code npcs/} itself, otherwise 1-8 segments joined by {@code /}. */
    public static final String FOLDER = "(?:" + SEGMENT + "(?:/" + SEGMENT + "){0," + (MAX_FOLDERS - 1) + "})?";
    public static final String FILE = "npcs/(?:" + SEGMENT + "/){0," + MAX_FOLDERS + "}" + SEGMENT + "\\.json";
    /** Path names below {@code npcs/} a walk must allow: the folders and the file. */
    public static final int TREE_DEPTH = MAX_FOLDERS + 1;

    private NpcPaths() {}

    public static boolean file(String path) { return path.matches(FILE); }

    public static boolean folder(String folder) { return folder.matches(FOLDER); }

    public static String path(String folder, String id) { return "npcs/" + (folder.isEmpty() ? "" : folder + "/") + id + ".json"; }

    /** The folder of a valid {@link #FILE} path. */
    public static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= "npcs".length() ? "" : path.substring("npcs/".length(), slash);
    }

    /** The id a valid {@link #FILE} path names. */
    public static String idOf(String path) { return path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length()); }
}
