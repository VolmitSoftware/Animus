package art.arcane.animus.bukkit;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Chat text on Bukkit, Paper and Folia. Players get components built from the text's styled runs: CraftBukkit and
 * BungeeCord read any dotted word in a legacy String, such as {@code config.toml}, as a web link.
 */
public final class BukkitChat {
    private BukkitChat() {}

    /** Section-coded text: components for a player, the legacy colours for the console and other senders. */
    public static void send(CommandSender to, String coloured) {
        if (to instanceof Player player) player.spigot().sendMessage(components(coloured));
        else to.sendMessage(RichText.legacy(coloured));
    }

    /** A reply line: a player gets its chips through VolmLib's messenger, every other sender its console text. */
    public static void send(CommandSender to, ChatLine line) {
        if (to instanceof Player && line.interactive()) ComponentMessenger.send(to, ComponentText.component(line.component()));
        else send(to, line.console());
    }

    /** Section-coded text above the hotbar. */
    public static void actionBar(Player player, String coloured) { player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components(coloured)); }

    /** Section-coded text as one component per styled run, with no click on any of them. */
    public static BaseComponent[] components(String coloured) {
        List<RichText.Run> runs = RichText.sectionRuns(coloured);
        if (runs.isEmpty()) return new BaseComponent[]{new TextComponent("")};
        BaseComponent[] pieces = new BaseComponent[runs.size()];
        for (int index = 0; index < pieces.length; index++) {
            RichText.Run run = runs.get(index);
            TextComponent piece = new TextComponent(run.text());
            if (run.rgb().isPresent()) piece.setColor(colour(run.rgb().getAsInt()));
            if (run.bold()) piece.setBold(true);
            if (run.italic()) piece.setItalic(true);
            if (run.underlined()) piece.setUnderlined(true);
            if (run.strikethrough()) piece.setStrikethrough(true);
            if (run.obfuscated()) piece.setObfuscated(true);
            pieces[index] = piece;
        }
        return pieces;
    }

    /** A vanilla colour keeps its name, as a legacy code did; any other colour is sent as hex. */
    private static ChatColor colour(int rgb) {
        char nearest = RichText.nearestLegacy(rgb);
        return RichText.legacyRgb(nearest) == rgb ? ChatColor.getByChar(nearest) : ChatColor.of(String.format(Locale.ROOT, "#%06X", rgb));
    }
}
