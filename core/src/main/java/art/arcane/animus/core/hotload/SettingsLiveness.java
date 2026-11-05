package art.arcane.animus.core.hotload;

import java.util.List;

/**
 * Which {@code config.toml} keys take effect the moment a hotload applies them. Every other key is read once when a
 * service starts (budgets, thread pools, listeners, packet transports) and its hotload message says a restart is needed.
 */
public final class SettingsLiveness {
    /** Exact keys, or prefixes ending in a dot that cover a whole table. */
    private static final List<String> LIVE = List.of(
            "statusMessage",
            "hotload.",
            "shops.enabled", "shops.economy", "shops.priceFormat", "shops.messages.",
            "behaviors.actions.commandBehaviors", "behaviors.actions.commandNamespaces", "behaviors.actions.choiceLifetimeSeconds");

    private SettingsLiveness() {}

    public static boolean live(String key) {
        for (String entry : LIVE) {
            if (entry.endsWith(".") ? key.startsWith(entry) || key.equals(entry.substring(0, entry.length() - 1)) : key.equals(entry) || key.startsWith(entry + "["))
                return true;
        }
        return false;
    }

    public static boolean restartRequired(String key) { return !live(key); }
}
