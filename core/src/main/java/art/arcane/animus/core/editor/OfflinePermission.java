package art.arcane.animus.core.editor;

import java.util.Optional;

/** The answer for a permission node of a player who is not online, shared by every platform's offline check. */
public final class OfflinePermission {
    private OfflinePermission() {}

    /**
     * {@code byDefault}: the server's own grant, from the operator list and the node's default. {@code plugin}: a permission
     * plugin's answer where the server can ask it offline. {@code unseenPlugin}: a permission plugin or mod runs that the server
     * cannot ask offline, which may grant the node where the server cannot see, so a no stays unknown.
     */
    public static EditorHost.Admin decide(boolean byDefault, Optional<Boolean> plugin, boolean unseenPlugin) {
        if (byDefault || plugin.orElse(false)) return EditorHost.Admin.ADMIN;
        if (plugin.isPresent() || !unseenPlugin) return EditorHost.Admin.NOT_ADMIN;
        return EditorHost.Admin.UNKNOWN;
    }
}
