package art.arcane.animus.core.editor;

import java.nio.file.Path;

/** The name the editor shows for a server: {@code [editor] serverName}, else the platform's {@link #fallback}. */
public final class EditorServerName {
    static final String VANILLA_MOTD = "A Minecraft Server", UNNAMED = "Minecraft server";

    private EditorServerName() {}

    /** The MOTD's first line without colour codes, unless it is empty or the vanilla default; then the server folder's name. */
    public static String fallback(String motd, Path folder) {
        String line = bounded(motd == null ? "" : motd.lines().findFirst().orElse(""));
        if (!line.equals(UNNAMED) && !line.equalsIgnoreCase(VANILLA_MOTD)) return line;
        Path name = folder.toAbsolutePath().normalize().getFileName();
        return name == null ? UNNAMED : bounded(name.toString());
    }

    /** Plain text of at most 64 characters, never empty. */
    static String bounded(String name) {
        String plain = EditorSummaries.plain(name == null ? "" : name);
        return plain.isEmpty() ? UNNAMED : plain;
    }
}
