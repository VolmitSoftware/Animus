package art.arcane.animus.core.shop;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.inventory.Economy;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * How a shop price reads in the shop window and the Animus menu: the economy's own text, such as {@code $2.00} or
 * {@code 2 Coins}, while an economy is ready, else {@code [shops] priceFormat}. Call on the player's owner, as every
 * {@link Economy} call is.
 */
public record Prices(Economy economy, ShopSettings settings) {
    public Prices {
        Objects.requireNonNull(economy);
        Objects.requireNonNull(settings);
    }

    public String format(BigDecimal amount) {
        if (economy.unavailable().isEmpty()) return economy.format(amount);
        return new DecimalFormat(settings.priceFormat(), DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }

    /** The money's name, such as Coins, while an economy is ready and names it. */
    public Optional<String> currency() {
        if (economy.unavailable().isPresent()) return Optional.empty();
        return economy.currency().map(String::strip).filter((String name) -> !name.isEmpty());
    }

    /** Why players cannot pay a price now, such as Vault is not installed; empty while an economy is ready. */
    public Optional<String> unavailable() { return economy.unavailable(); }
}
