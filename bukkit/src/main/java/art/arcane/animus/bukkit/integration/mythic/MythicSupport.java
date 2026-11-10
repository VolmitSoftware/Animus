package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MythicMobs support: NPCs made from Mythic mob types, their mob skills on NPC events, the {@code mythic:cast-skill}
 * behavior action, and the {@code @AnimusNPC} targeter and {@code animusnpc} condition in Mythic skill files. It loads
 * only when the MythicMobs plugin is enabled; this class names no Mythic type, so Animus starts the same without it.
 */
public final class MythicSupport {
    /** The threads that own the world. */
    public interface Owners {
        /** Runs {@code work} on the owner of {@code location}; false when refused. */
        boolean at(Location location, Runnable work);
        /** Runs {@code work} with the online player on their owner; false when they are offline or it was refused. */
        boolean player(UUID player, Consumer<Player> work);
    }

    /**
     * @param states where an active NPC stands now
     * @param ticks runs a task every tick on one owner until the handle closes
     * @param looks how a mob's disguise and Model Engine model reach a new NPC
     */
    public record Parts(JavaPlugin plugin, AnimusRuntime runtime, ExtensionRuntime extensions, Function<UUID, Optional<VirtualNpcState.Snapshot>> states,
                        Owners owners, Function<Runnable, AutoCloseable> ticks, LookHooks looks, Consumer<String> log) {}

    private MythicSupport() {}

    /** The main thread on Bukkit and Paper; the location's region and the player's entity scheduler on Folia ({@code regions} non-null). */
    public static Owners owners(JavaPlugin plugin, FoliaRegions regions) {
        return new Owners() {
            @Override public boolean at(Location location, Runnable work) {
                if (!plugin.isEnabled() || location.getWorld() == null) return false;
                if (regions != null) return regions.execute(new ChunkPosition(location.getWorld().getUID().toString(), location.getBlockX() >> 4, location.getBlockZ() >> 4), work);
                return main(plugin, work);
            }
            @Override public boolean player(UUID player, Consumer<Player> work) {
                Player online = plugin.getServer().getPlayer(player);
                if (online == null || !plugin.isEnabled()) return false;
                if (regions == null) return main(plugin, () -> work.accept(online));
                if (regions.owns(online)) { work.accept(online); return true; }
                return regions.submit(online, () -> work.accept(online), () -> {});
            }
        };
    }

    private static boolean main(JavaPlugin plugin, Runnable work) {
        if (plugin.getServer().isPrimaryThread()) { work.run(); return true; }
        try { plugin.getServer().getScheduler().runTask(plugin, work); return true; }
        catch (IllegalStateException | UnsupportedOperationException refused) { return false; }
    }

    /** Starts MythicMobs support when MythicMobs is enabled, before {@code extensions} start. The handle stops it. */
    public static Optional<AutoCloseable> start(Parts parts) {
        Plugin mythic = parts.plugin().getServer().getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled()) return Optional.empty();
        try {
            AutoCloseable started = MythicLink.start(parts);
            parts.log().accept("MythicMobs " + mythic.getDescription().getVersion() + " support enabled");
            return Optional.of(started);
        } catch (RuntimeException | LinkageError failure) {
            parts.log().accept("MythicMobs support is unavailable: " + failure);
            return Optional.empty();
        }
    }
}
