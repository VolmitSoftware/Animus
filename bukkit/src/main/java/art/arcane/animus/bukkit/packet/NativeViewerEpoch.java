package art.arcane.animus.bukkit.packet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Exact-version owner-thread teleport authority for the limited Folia preview.
 * Folia's same-region teleportAsync path calls internalTeleport without firing a
 * PlayerTeleportEvent. The connection's server-owned teleport counter changes
 * even for an identical-position teleport and is retained after the client ACK.
 * This adapter reads authority; it never changes a player or sends a packet.
 */
public final class NativeViewerEpoch {
    private static final Set<String> VERSIONS = Set.of("26.1.2", "26.2", "26.3");
    private final Server server;
    private final Method owned, handle;
    private final Field connection, counter;

    private NativeViewerEpoch(JavaPlugin plugin, String version) throws ReflectiveOperationException {
        server = Objects.requireNonNull(plugin).getServer();
        Object current = Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null);
        String actual = (String) Class.forName("net.minecraft.WorldVersion").getMethod("id").invoke(current);
        if (!version.equals(actual)) throw new IllegalStateException("Viewer epoch version differs: expected " + version + ", running " + actual);
        owned = Server.class.getMethod("isOwnedByCurrentRegion", Entity.class);
        handle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        Class<?> listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
        if (!listener.equals(connection.getType())) throw new NoSuchFieldException("ServerPlayer.connection has an unsupported type");
        counter = listener.getDeclaredField("awaitingTeleport");
        if (counter.getType() != int.class) throw new NoSuchFieldException("awaitingTeleport is not an int");
        counter.setAccessible(true);
    }

    public static NativeViewerEpoch bind(JavaPlugin plugin, String exactMinecraftVersion) throws ReflectiveOperationException {
        if (!VERSIONS.contains(exactMinecraftVersion)) throw new IllegalArgumentException("No viewer epoch adapter for " + exactMinecraftVersion);
        return new NativeViewerEpoch(plugin, exactMinecraftVersion);
    }

    /**
     * Capture before queueing the request, then compare on every owning callback
     * and active-lease validation. Callers must already own the viewer; this method
     * independently checks that condition before any native player field access.
     */
    public Stamp capture(Player viewer) {
        Objects.requireNonNull(viewer);
        try {
            if (!(boolean) owned.invoke(server, viewer)) throw new IllegalStateException("Viewer epoch capture requires the player's owner thread");
            Object listener = Objects.requireNonNull(connection.get(handle.invoke(viewer)), "Viewer has no native connection");
            return new Stamp(listener, counter.getInt(listener));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture exact viewer teleport authority", failure);
        }
    }

    /** Opaque authority value. Native objects and protocol counter values stay private. */
    public static final class Stamp {
        private final Object connection;
        private final int counter;

        Stamp(Object connection, int counter) {
            this.connection = Objects.requireNonNull(connection);
            this.counter = counter;
        }

        /** Diagnostic server sequence; wraps and must never replace full Stamp equality. */
        public int sequence() { return counter; }

        @Override public boolean equals(Object other) {
            return other instanceof Stamp stamp && connection == stamp.connection && counter == stamp.counter;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(connection) + counter; }
        @Override public String toString() { return "ViewerEpoch[opaque]"; }
    }
}
