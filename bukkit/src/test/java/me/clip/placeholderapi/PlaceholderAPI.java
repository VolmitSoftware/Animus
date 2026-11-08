package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;

/** A stand-in for PlaceholderAPI's static entry point, with the one method Animus calls. */
public final class PlaceholderAPI {
    private PlaceholderAPI() {}

    public static String setPlaceholders(OfflinePlayer player, String text) {
        return text.replace("%player_name%", player.getName()).replace("%server_online%", "3");
    }
}
