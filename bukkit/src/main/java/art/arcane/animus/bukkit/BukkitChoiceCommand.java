package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.PendingChoices;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Handles the click target of a dialogue choice. Open to every player; the token decides what the click may do. */
final class BukkitChoiceCommand {
    static final String NOT_AVAILABLE = "That choice is no longer available.";
    @FunctionalInterface interface Selector { PendingChoices.Selection select(UUID player, String token, String option); }

    private BukkitChoiceCommand() {}

    /** Call on the sender's owner. Every rejection reads the same, so a failed guess reveals nothing about a prompt. */
    static void execute(CommandSender sender, String[] arguments, Selector selector) {
        boolean wellFormed = sender instanceof Player && arguments.length == 3
                && arguments[1].matches("[a-f0-9]{32}") && arguments[2].matches("[a-z0-9][a-z0-9_-]{0,31}");
        if (!wellFormed || selector.select(((Player) sender).getUniqueId(), arguments[1], arguments[2]) != PendingChoices.Selection.ACCEPTED)
            sender.sendMessage(NOT_AVAILABLE);
    }
}
