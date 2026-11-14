package art.arcane.animus.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.inventory.Economy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class PricesTest {
    /** An economy that writes amounts as {@code 2 Coins} and calls its money {@code name}. */
    public static Economy coins(String name) {
        return new Economy() {
            @Override public Optional<String> unavailable() { return Optional.empty(); }
            @Override public boolean has(UUID player, BigDecimal amount) { return true; }
            @Override public boolean withdraw(UUID player, BigDecimal amount) { return true; }
            @Override public boolean deposit(UUID player, BigDecimal amount) { return true; }
            @Override public String format(BigDecimal amount) { return amount.stripTrailingZeros().toPlainString() + " Coins"; }
            @Override public Optional<String> currency() { return Optional.of(name); }
        };
    }

    @Test void aReadyEconomyWritesThePriceInItsOwnWordsAndNamesItsMoney() {
        Prices prices = new Prices(coins("Coins"), ShopSettings.DEFAULT);
        assertEquals("2 Coins", prices.format(new BigDecimal("2")));
        assertEquals(Optional.of("Coins"), prices.currency());
        assertEquals(Optional.empty(), prices.unavailable());
    }

    @Test void withoutAnEconomyPriceFormatWritesItAndNoMoneyIsNamed() {
        Prices prices = new Prices(Economy.none("Vault is not installed"), ShopSettings.DEFAULT);
        assertEquals("1,234.5", prices.format(new BigDecimal("1234.50")), "[shops] priceFormat, #,##0.## by default");
        assertEquals(Optional.empty(), prices.currency());
        assertEquals(Optional.of("Vault is not installed"), prices.unavailable());
    }

    @Test void aBlankMoneyNameIsNoName() {
        assertEquals(Optional.empty(), new Prices(coins("  "), ShopSettings.DEFAULT).currency());
        assertEquals(Optional.of("Dollars"), new Prices(coins(" Dollars "), ShopSettings.DEFAULT).currency());
    }
}
