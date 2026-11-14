package art.arcane.animus.core.inventory;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's money, reached through an explicit adapter. Every call runs on the player's owner. Amounts are
 * positive. A provider that is absent answers {@link #unavailable()} and every money price is refused.
 */
public interface Economy {
    /** Present when no money provider can be used here; money prices fail closed with this reason. */
    Optional<String> unavailable();
    boolean has(UUID player, BigDecimal amount);
    /** True when the whole amount was taken. */
    boolean withdraw(UUID player, BigDecimal amount);
    /** True when the whole amount was paid. */
    boolean deposit(UUID player, BigDecimal amount);
    /** The provider's display text for an amount, or the plain number. */
    String format(BigDecimal amount);
    /** The money's plural name, such as Coins; empty when the provider names none. */
    default Optional<String> currency() { return Optional.empty(); }

    static Economy none(String reason) {
        return new Economy() {
            @Override public Optional<String> unavailable() { return Optional.of(reason); }
            @Override public boolean has(UUID player, BigDecimal amount) { return false; }
            @Override public boolean withdraw(UUID player, BigDecimal amount) { return false; }
            @Override public boolean deposit(UUID player, BigDecimal amount) { return false; }
            @Override public String format(BigDecimal amount) { return amount.toPlainString(); }
        };
    }
}
