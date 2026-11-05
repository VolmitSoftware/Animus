package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.CommandActor;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** A Bukkit command sender as the shared command set sees it. Replies go through the sender's own scheduler. */
record BukkitActor(CommandSender sender, ReplyScheduler replies) implements CommandActor {
    BukkitActor {
        Objects.requireNonNull(sender); Objects.requireNonNull(replies);
    }
    @Override public boolean permitted(String node) { return sender.hasPermission(node); }
    @Override public void tell(String message) { sender.sendMessage(message); }
    @Override public Optional<UUID> player() { return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }
    @Override public boolean reply(Runnable action) { return replies.reply(sender, action); }
}
