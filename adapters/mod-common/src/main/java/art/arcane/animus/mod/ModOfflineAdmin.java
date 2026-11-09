package art.arcane.animus.mod;

import art.arcane.animus.core.editor.EditorHost;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code animus.admin} for a player who is not online, for the editor's token recheck. An operator is an admin; anyone else
 * may still hold the permission atom through a permission mod the server cannot ask offline, so that stays unknown.
 */
final class ModOfflineAdmin {
    private ModOfflineAdmin() {}

    static EditorHost.Admin check(MinecraftServer server, UUID player) {
        return server.getPlayerList().isOp(new NameAndId(player, "")) ? EditorHost.Admin.ADMIN : EditorHost.Admin.UNKNOWN;
    }
}
