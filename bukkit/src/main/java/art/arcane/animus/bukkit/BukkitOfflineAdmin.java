package art.arcane.animus.bukkit;

import art.arcane.animus.core.editor.EditorHost;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * {@code animus.admin} for a player who is not online, for the editor's token recheck. The operator list and the node's
 * declared default answer on plain Bukkit permissions; Vault answers for the permission plugin behind it. A permission
 * plugin Vault cannot reach may grant the node where the server cannot see, so that case stays unknown. Vault lookups may
 * load the player's data, so this never runs on the server thread.
 */
final class BukkitOfflineAdmin {
    static final String NODE = "animus.admin";
    /** Permission plugins that can grant nodes to offline players without Vault. */
    private static final List<String> PERMISSION_PLUGINS = List.of("LuckPerms", "PermissionsEx", "GroupManager", "UltraPermissions", "PowerRanks", "bPermissions", "zPermissions");

    private BukkitOfflineAdmin() {}

    static EditorHost.Admin check(Server server, UUID player) {
        OfflinePlayer offline = server.getOfflinePlayer(player);
        PluginManager plugins = server.getPluginManager();
        Permission declared = plugins.getPermission(NODE);
        boolean byDefault = (declared == null ? PermissionDefault.OP : declared.getDefault()).getValue(offline.isOp());
        Optional<Boolean> vault = plugins.isPluginEnabled("Vault") ? VaultPermissions.has(server, offline, NODE) : Optional.empty();
        return decide(byDefault, vault, PERMISSION_PLUGINS.stream().anyMatch(plugins::isPluginEnabled));
    }

    /** The node's default for this player, Vault's answer when it has a permission provider, and whether another permission plugin runs. */
    static EditorHost.Admin decide(boolean byDefault, Optional<Boolean> vault, boolean permissionPlugin) {
        if (byDefault || vault.orElse(false)) return EditorHost.Admin.ADMIN;
        if (vault.isPresent() || !permissionPlugin) return EditorHost.Admin.NOT_ADMIN;
        return EditorHost.Admin.UNKNOWN;
    }
}
