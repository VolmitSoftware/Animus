package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.inventory.Economy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Money through Vault. This class is loaded only when the Vault plugin is installed. The provider is looked up on
 * every call, so an economy plugin that registers after Animus starts is still found, and one that goes away fails
 * closed.
 */
final class VaultEconomy implements Economy {
    private final Server server;

    private VaultEconomy(Server server) { this.server = server; }

    /** Vault when its plugin is installed, otherwise a refusal naming what is missing. */
    static Economy create(Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) return Economy.none("Vault is not installed");
        return new VaultEconomy(server);
    }

    private Optional<net.milkbowl.vault.economy.Economy> provider() {
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> registration = server.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (registration == null) return Optional.empty();
        net.milkbowl.vault.economy.Economy economy = registration.getProvider();
        return economy != null && economy.isEnabled() ? Optional.of(economy) : Optional.empty();
    }

    @Override public Optional<String> unavailable() {
        return provider().isPresent() ? Optional.empty() : Optional.of("Vault has no enabled economy provider");
    }

    @Override public boolean has(UUID player, BigDecimal amount) {
        return provider().map((net.milkbowl.vault.economy.Economy economy) -> economy.has(player(player), amount.doubleValue())).orElse(false);
    }

    @Override public boolean withdraw(UUID player, BigDecimal amount) {
        return provider().map((net.milkbowl.vault.economy.Economy economy) -> succeeded(economy.withdrawPlayer(player(player), amount.doubleValue()))).orElse(false);
    }

    @Override public boolean deposit(UUID player, BigDecimal amount) {
        return provider().map((net.milkbowl.vault.economy.Economy economy) -> succeeded(economy.depositPlayer(player(player), amount.doubleValue()))).orElse(false);
    }

    @Override public String format(BigDecimal amount) {
        return provider().map((net.milkbowl.vault.economy.Economy economy) -> economy.format(amount.doubleValue())).orElse(amount.toPlainString());
    }

    @Override public Optional<String> currency() {
        return provider().map(net.milkbowl.vault.economy.Economy::currencyNamePlural).map(String::strip).filter((String name) -> !name.isEmpty());
    }

    private OfflinePlayer player(UUID id) { return server.getOfflinePlayer(id); }
    private static boolean succeeded(EconomyResponse response) { return response != null && response.transactionSuccess(); }
}
