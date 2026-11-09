package art.arcane.animus.bukkit;

import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.OfflinePermission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * A permission node for a player who is not online, for the editor's token recheck. The operator list and the node's
 * declared default answer on plain Bukkit permissions; Vault answers for the permission plugin behind it, and LuckPerms
 * answers through its own API when Vault is missing. Another permission plugin may grant the node where the server cannot
 * see, so that case stays unknown. Vault and LuckPerms lookups may load the player's data, so this never runs on the server
 * thread.
 */
final class BukkitOfflinePermission {
    /** Permission plugins that can grant nodes to offline players. */
    private static final List<String> PERMISSION_PLUGINS = List.of("LuckPerms", "PermissionsEx", "GroupManager", "UltraPermissions", "PowerRanks", "bPermissions", "zPermissions");

    private BukkitOfflinePermission() {}

    static EditorHost.Admin check(Server server, UUID player, String node) {
        OfflinePlayer offline = server.getOfflinePlayer(player);
        PluginManager plugins = server.getPluginManager();
        Permission declared = plugins.getPermission(node);
        boolean byDefault = (declared == null ? PermissionDefault.OP : declared.getDefault()).getValue(offline.isOp());
        Optional<Boolean> answer = plugins.isPluginEnabled("Vault") ? VaultPermissions.has(server, offline, node) : Optional.empty();
        if (answer.isEmpty() && plugins.isPluginEnabled("LuckPerms")) answer = LuckPermsPermissions.has(server, player, node);
        return OfflinePermission.decide(byDefault, answer, PERMISSION_PLUGINS.stream().anyMatch(plugins::isPluginEnabled));
    }
}
