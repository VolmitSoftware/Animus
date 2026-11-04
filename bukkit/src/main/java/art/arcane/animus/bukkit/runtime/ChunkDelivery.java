package art.arcane.animus.bukkit.runtime;

import java.lang.reflect.*;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Bound once. Tests server delivery state, never desired distance or server-loaded state alone. */
final class ChunkDelivery {
    private final Method paperSent, handle, trackingView, contains, pending;
    private final Field connection, sender;
    private ChunkDelivery(Method paperSent, Method handle, Method trackingView, Method contains,
                          Field connection, Field sender, Method pending) {
        this.paperSent = paperSent; this.handle = handle; this.trackingView = trackingView;
        this.contains = contains; this.connection = connection; this.sender = sender; this.pending = pending;
    }
    static ChunkDelivery bind(JavaPlugin plugin, String version) throws ReflectiveOperationException {
        if (!Set.of("26.1.2", "26.2", "26.3").contains(version)) throw new IllegalArgumentException("Unvalidated chunk delivery adapter");
        try {
            return new ChunkDelivery(Player.class.getMethod("isChunkSent", long.class), null, null, null, null, null, null);
        } catch (NoSuchMethodException absent) {
            ClassLoader loader = plugin.getServer().getClass().getClassLoader();
            Class<?> craft = Class.forName(plugin.getServer().getClass().getPackageName() + ".entity.CraftPlayer", false, loader);
            Method handle = craft.getMethod("getHandle");
            Method tracking = handle.getReturnType().getMethod("getChunkTrackingView");
            Field connection = handle.getReturnType().getField("connection");
            Field sender = connection.getType().getField("chunkSender");
            return new ChunkDelivery(null, handle, tracking, tracking.getReturnType().getMethod("contains", int.class, int.class),
                    connection, sender, sender.getType().getMethod("isPending", long.class));
        }
    }
    boolean sent(Player player, int x, int z) {
        try {
            if (paperSent != null) return (boolean) paperSent.invoke(player, key(x, z));
            Object nativePlayer = handle.invoke(player);
            return (boolean) contains.invoke(trackingView.invoke(nativePlayer), x, z)
                    && !(boolean) pending.invoke(sender.get(connection.get(nativePlayer)), key(x, z));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Chunk delivery capability failed", failure); }
    }
    String implementation() { return paperSent != null ? "Paper isChunkSent" : "native tracked-and-not-pending"; }
    static long key(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
