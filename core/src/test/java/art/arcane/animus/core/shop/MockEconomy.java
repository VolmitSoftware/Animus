package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.Economy;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Balances in memory; deposits can be made to fail to prove rollback. */
final class MockEconomy implements Economy {
    final Map<UUID, BigDecimal> balances = new HashMap<>();
    boolean failDeposits;
    int withdrawals, deposits;

    MockEconomy give(UUID player, String amount) { balances.put(player, new BigDecimal(amount)); return this; }
    BigDecimal balance(UUID player) { return balances.getOrDefault(player, BigDecimal.ZERO); }

    @Override public Optional<String> unavailable() { return Optional.empty(); }
    @Override public boolean has(UUID player, BigDecimal amount) { return balance(player).compareTo(amount) >= 0; }
    @Override public boolean withdraw(UUID player, BigDecimal amount) {
        if (!has(player, amount)) return false;
        withdrawals++;
        balances.put(player, balance(player).subtract(amount));
        return true;
    }
    @Override public boolean deposit(UUID player, BigDecimal amount) {
        if (failDeposits) return false;
        deposits++;
        balances.put(player, balance(player).add(amount));
        return true;
    }
    @Override public String format(BigDecimal amount) { return "$" + amount.toPlainString(); }
}
