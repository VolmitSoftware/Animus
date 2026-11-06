package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
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
    /**
     * Players see the brand pinks in hex, with transaction ids and links as click chips; the console gets the legacy
     * fallbacks, which every Bukkit console renders.
     */
    @Override public void tell(String message) { rich(ChatLine.parse(Palette.reply(message))); }
    @Override public void plain(String line) { sender.sendMessage(sender instanceof Player ? line : RichText.legacy(line)); }
    @Override public void rich(ChatLine line) {
        if (sender instanceof Player && line.interactive()) ComponentMessenger.send(sender, ComponentText.component(line.component()));
        else plain(line.console());
    }
    @Override public Optional<UUID> player() { return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }
    @Override public boolean reply(Runnable action) { return replies.reply(sender, action); }
    /** Lenient MiniMessage through VolmLib, the fleet's help delivery: hover chips and click paging for players. */
    @Override public void menu(DirectorMiniMenu.DirectorHelpPage page) { DirectorMiniMenu.deliver(sender, page, AnimusDirector.THEME, AnimusDirector.TEXT); }
    @Override public void menu(DirectorMiniMenu.ContentMenu menu) { DirectorMiniMenu.deliverContent(sender, menu, AnimusDirector.THEME, AnimusDirector.TEXT); }
}
