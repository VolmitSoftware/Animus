package art.arcane.animus.bukkit;

import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.editor.EditorNotice;
import art.arcane.animus.core.editor.EditorNoticeChannel;
import java.util.Optional;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Editor notices for Bukkit, Paper and Folia: each online player is checked and reached on its own scheduler. */
final class BukkitEditorNotices implements EditorNoticeChannel {
    private final JavaPlugin plugin;
    private final ReplyScheduler replies;

    BukkitEditorNotices(JavaPlugin plugin, ReplyScheduler replies) {
        this.plugin = plugin;
        this.replies = replies;
    }

    @Override public Optional<String> name(UUID player) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) return Optional.of(online.getName());
        OfflinePlayer known = plugin.getServer().getOfflinePlayer(player);
        return Optional.ofNullable(known.getName());
    }

    @Override public void deliver(EditorNotice notice) {
        replies.eachPlayer((Player player) -> deliver(player, notice), "Editor notice could not be scheduled");
    }

    private static void deliver(Player player, EditorNotice notice) {
        if (!player.isOnline() || !notice.reaches(player.getUniqueId(), player.hasPermission(PERMISSION))) return;
        notice.sound().ifPresent((EditorNotifySettings.Sound sound) -> player.playSound(player.getLocation(), sound.id(), SoundCategory.MASTER, (float) sound.volume(), (float) sound.pitch()));
        notice.chat().ifPresent((EditorNotice.Line chat) -> {
            TextComponent line = new TextComponent(TextComponent.fromLegacyText(chat.text()));
            chat.hover().ifPresent((String hover) -> line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover)))));
            chat.suggest().ifPresent((String command) -> line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
            TextComponent whole = new TextComponent();
            whole.addExtra(line);
            chat.copy().ifPresent((EditorNotice.Copy copy) -> {
                TextComponent piece = new TextComponent(TextComponent.fromLegacyText(copy.text()));
                piece.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copy.value()));
                piece.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(copy.hover()))));
                whole.addExtra(piece);
            });
            player.spigot().sendMessage(whole);
        });
        notice.actionBar().ifPresent((String bar) -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar)));
    }
}
