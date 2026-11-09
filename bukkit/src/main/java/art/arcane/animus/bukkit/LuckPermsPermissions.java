package art.arcane.animus.bukkit;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Offline permission checks through the LuckPerms API. Loaded only when the LuckPerms plugin is enabled. */
final class LuckPermsPermissions {
    private static final long LOAD_SECONDS = 10;

    private LuckPermsPermissions() {}

    /**
     * Whether LuckPerms grants the node, false when it does not set it (the node's default then decides), or empty when
     * LuckPerms is not registered or cannot load the player in time.
     */
    static Optional<Boolean> has(Server server, UUID player, String node) {
        RegisteredServiceProvider<LuckPerms> registration = server.getServicesManager().getRegistration(LuckPerms.class);
        if (registration == null || registration.getProvider() == null) return Optional.empty();
        User user;
        try { user = registration.getProvider().getUserManager().loadUser(player).get(LOAD_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
        catch (ExecutionException | TimeoutException failed) { return Optional.empty(); }
        if (user == null) return Optional.empty();
        return Optional.of(user.getCachedData().getPermissionData().checkPermission(node) == Tristate.TRUE);
    }
}
