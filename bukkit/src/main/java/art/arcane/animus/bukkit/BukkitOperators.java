package art.arcane.animus.bukkit;

import art.arcane.animus.core.hotload.OperatorChannel;
import art.arcane.animus.core.text.ChatLine;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Hotload messages for Bukkit, Paper and Folia: each online player is checked and messaged on its own scheduler. */
final class BukkitOperators implements OperatorChannel {
    private final JavaPlugin plugin;
    private final ReplyScheduler replies;

    BukkitOperators(JavaPlugin plugin, ReplyScheduler replies) {
        this.plugin = plugin;
        this.replies = replies;
    }

    @Override public void notifyOperators(ChatLine line, boolean sound) {
        replies.eachPlayer((Player player) -> deliver(player, line, sound), "Hotload could not schedule operator messages");
    }

    private static void deliver(Player player, ChatLine line, boolean sound) {
        if (!player.isOnline() || !player.hasPermission(NOTIFY)) return;
        if (sound) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, VOLUME, PITCH);
        BukkitChat.send(player, line);
    }
}
