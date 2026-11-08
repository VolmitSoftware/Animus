package art.arcane.animus.bukkit;

import art.arcane.animus.core.text.CachedPlaceholders;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI, reached by reflection so Animus neither depends on it nor loads its classes when it is absent. It is
 * found when Animus starts and again whenever PlaceholderAPI enables, and dropped when it disables. Each fill asks
 * PlaceholderAPI for the online viewer; a viewer who is offline keeps the text as written.
 * <p>
 * PlaceholderAPI runs an expansion on the calling thread. Animus fills on the viewer's owner: the main thread on Paper
 * and Spigot, and the player's own region on Folia, where expansions that read the player are safe and expansions that
 * read another region's world or entities are not.
 */
final class BukkitPlaceholders implements CachedPlaceholders.Source {
    static final String PLUGIN = "PlaceholderAPI";
    private static final String API = "me.clip.placeholderapi.PlaceholderAPI";

    private final Function<UUID, ? extends OfflinePlayer> players;
    private volatile MethodHandle setPlaceholders;

    BukkitPlaceholders(Function<UUID, ? extends OfflinePlayer> players) { this.players = players; }

    /** Binds to the installed PlaceholderAPI and follows it being enabled, disabled and reloaded. */
    static CachedPlaceholders install(Plugin owner) {
        Server server = owner.getServer();
        BukkitPlaceholders source = new BukkitPlaceholders(server::getPlayer);
        CachedPlaceholders cached = new CachedPlaceholders(source);
        source.bind(server.getPluginManager().getPlugin(PLUGIN));
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void enabled(PluginEnableEvent event) { if (event.getPlugin().getName().equals(PLUGIN)) source.bind(event.getPlugin()); }
            @EventHandler public void disabled(PluginDisableEvent event) { if (event.getPlugin().getName().equals(PLUGIN)) source.setPlaceholders = null; }
            @EventHandler public void quit(PlayerQuitEvent event) { cached.forget(event.getPlayer().getUniqueId()); }
        }, owner);
        return cached;
    }

    /** Finds {@code PlaceholderAPI.setPlaceholders(OfflinePlayer, String)} through the plugin's own class loader. */
    void bind(Plugin placeholderApi) {
        setPlaceholders = placeholderApi == null || !placeholderApi.isEnabled() ? null : resolve(placeholderApi.getClass().getClassLoader());
    }

    /** The fill method, or null when this class loader holds no compatible PlaceholderAPI. */
    private static MethodHandle resolve(ClassLoader loader) {
        try {
            Class<?> api = Class.forName(API, true, loader);
            return MethodHandles.publicLookup().findStatic(api, "setPlaceholders", MethodType.methodType(String.class, OfflinePlayer.class, String.class));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return null;
        }
    }

    @Override public boolean available() { return setPlaceholders != null; }

    @Override public String fill(UUID viewer, String text) {
        MethodHandle fill = setPlaceholders;
        OfflinePlayer player = fill == null ? null : players.apply(viewer);
        if (player == null) return null;
        try { return (String) fill.invokeExact(player, text); }
        catch (VirtualMachineError fatal) { throw fatal; }
        catch (Throwable failure) { throw new IllegalStateException("PlaceholderAPI failed: " + failure, failure); }
    }
}
