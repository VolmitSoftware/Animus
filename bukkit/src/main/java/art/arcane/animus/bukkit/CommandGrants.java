package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.TemporaryOperators;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * The permissions an {@code animus:command} lends its player: an attachment added on the player's owner just before the
 * command and removed right after it, or {@code ticks} later on the player's owner. What is still lent goes when the
 * player quits or Animus stops. An {@code asOperator} command makes the player an operator for that one command through
 * {@link TemporaryOperators}, which takes it back the same way and, after a crash, at the next start.
 */
final class CommandGrants implements Listener {
    /** Runs {@code work} on the player's owner after {@code ticks}; false when it was not accepted. */
    @FunctionalInterface interface Later { boolean run(Player player, Runnable work, long ticks); }

    private final Plugin plugin;
    private final Later later;
    private final TemporaryOperators operators;
    private final Set<PermissionAttachment> lent = ConcurrentHashMap.newKeySet();

    CommandGrants(Plugin plugin, Later later, TemporaryOperators operators) { this.plugin = plugin; this.later = later; this.operators = operators; }

    /** On the player's owner: runs the command as an operator when {@code operator} is set, else as {@link #perform(Player, List, int, Predicate)}. */
    boolean perform(Player player, List<String> nodes, int ticks, boolean operator, Predicate<Player> command) {
        if (!operator) return perform(player, nodes, ticks, command);
        try { return operators.perform(player.getUniqueId(), () -> perform(player, nodes, ticks, command)); }
        catch (java.io.IOException unwritten) {
            throw new art.arcane.animus.core.extension.CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "operator-unrecorded",
                    "The temporary operator list could not be written, so the player was not made an operator: " + unwritten.getMessage());
        }
    }

    /** On the player's owner: runs the command holding the nodes and returns what it returned. */
    boolean perform(Player player, List<String> nodes, int ticks, Predicate<Player> command) {
        if (nodes.isEmpty()) return command.test(player);
        PermissionAttachment attachment = player.addAttachment(plugin);
        lent.add(attachment);
        try {
            for (String node : nodes) attachment.setPermission(node, true);
            return command.test(player);
        } finally {
            if (ticks <= 0 || !later.run(player, () -> revoke(attachment), ticks)) revoke(attachment);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { quit(event.getPlayer()); }

    void quit(Player player) {
        for (PermissionAttachment attachment : List.copyOf(lent)) if (attachment.getPermissible() == player) revoke(attachment);
        operators.quit(player.getUniqueId());
    }

    void close() {
        List.copyOf(lent).forEach(this::revoke);
        operators.close();
    }

    private void revoke(PermissionAttachment attachment) {
        if (lent.remove(attachment)) attachment.remove();
    }
}
