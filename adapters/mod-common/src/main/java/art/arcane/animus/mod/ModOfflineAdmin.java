package art.arcane.animus.mod;

import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.OfflinePermission;
import art.arcane.animus.core.platform.ModPermissions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;

/**
 * A permission node for a player who is not online, for the editor's token recheck. The server's operator list answers the
 * same way the online check does: an operator at the gamemaster level (2) or above holds every node, so a level 1 operator
 * does not. A permission mod can grant the node's permission atom where the server cannot see while the player is offline,
 * so with one installed a no stays unknown.
 */
final class ModOfflineAdmin {
    /** API classes of permission mods that grant permission atoms. */
    private static final List<String> PERMISSION_MODS = List.of("net.luckperms.api.LuckPerms", "me.lucko.fabric.api.permissions.v0.Permissions");

    private ModOfflineAdmin() {}

    static EditorHost.Admin check(MinecraftServer server, UUID player, String node) {
        boolean byDefault = ModPermissions.everyone(node)
                || server.getProfilePermissions(new NameAndId(player, "")).hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return OfflinePermission.decide(byDefault, Optional.empty(), PERMISSION_MODS.stream().anyMatch(ModOfflineAdmin::present));
    }

    private static boolean present(String type) {
        try { Class.forName(type, false, ModOfflineAdmin.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError missing) { return false; }
    }
}
