package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
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
    /** Players see the brand pinks in hex; the console gets their legacy fallbacks, which every Bukkit console renders. */
    @Override public void tell(String message) {
        String branded = Palette.reply(message);
        sender.sendMessage(sender instanceof Player ? branded : RichText.legacy(branded));
    }
    @Override public Optional<UUID> player() { return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }
    @Override public boolean reply(Runnable action) { return replies.reply(sender, action); }
}
