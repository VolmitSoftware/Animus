package art.arcane.animus.bukkit;

import art.arcane.animus.core.hotload.OperatorChannel;
import java.util.List;
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

    @Override public void notifyOperators(List<String> lines, boolean sound) {
        List<String> sent = List.copyOf(lines);
        replies.eachPlayer((Player player) -> deliver(player, sent, sound), "Hotload could not schedule operator messages");
    }

    private static void deliver(Player player, List<String> lines, boolean sound) {
        if (!player.isOnline() || !player.hasPermission(NOTIFY)) return;
        if (sound) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, VOLUME, PITCH);
        for (String line : lines) player.sendMessage(line);
    }
}
