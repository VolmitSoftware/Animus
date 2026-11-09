package art.arcane.animus.bukkit;

import java.util.Optional;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Offline permission checks through Vault. Loaded only when the Vault plugin is enabled. */
final class VaultPermissions {
    private VaultPermissions() {}

    /** Vault's answer, or empty when no permission provider is registered or enabled. */
    static Optional<Boolean> has(Server server, OfflinePlayer player, String node) {
        RegisteredServiceProvider<Permission> registration = server.getServicesManager().getRegistration(Permission.class);
        if (registration == null || registration.getProvider() == null || !registration.getProvider().isEnabled()) return Optional.empty();
        return Optional.of(registration.getProvider().playerHas(null, player, node));
    }
}
