package art.arcane.animus.bukkit.recording;

import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.HologramStyle;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.recording.RecordingDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Viewer-private ghost bodies: packets only, never a registered entity, shown to one player. Binds its own packet
 * adapter, so its watch and body IDs never collide with the live runtime's viewer session. A viewer is watched while
 * it has a ghost and forgotten when its last ghost ends. Call per-viewer methods on that viewer's owner.
 */
final class GhostBodies implements AutoCloseable {
    private static final String PLAYER = "minecraft:player";
    private final JavaPlugin plugin;
    private final String version;
    private final RecorderOwners owners;
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    private NativePacketAdapter packets;
    private String unavailable;
    private boolean closed;

    private static final class Watch {
        final Player player;
        int ghosts;
        Watch(Player player) { this.player = player; }
    }

    GhostBodies(JavaPlugin plugin, String version, RecorderOwners owners) {
        this.plugin = plugin; this.version = version; this.owners = owners;
    }

    /** Empty when ghosts can be shown; binds the adapter on first use. */
    synchronized Optional<String> unavailable() {
        if (closed) return Optional.of("ghosts are closed");
        if (packets == null && unavailable == null) {
            try { packets = NativePacketAdapter.bind(plugin, version); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                unavailable = "packet adapter unavailable on " + version + ": " + failure.getClass().getSimpleName();
                plugin.getLogger().log(Level.WARNING, "Recording ghosts unavailable", failure);
            }
        }
        return Optional.ofNullable(unavailable);
    }

    /**
     * A ghost of {@code document} for {@code viewer} alone, named {@code name} and wearing {@code skin} (a plain body when
     * empty). The caller ticks it and hands it back to {@link #release} once it is finished.
     */
    GhostReplay<Player, ItemStack> play(Player viewer, RecordingDocument document, String name, Optional<BodySkin> skin, RecordingSettings settings) {
        NativePacketAdapter adapter = adapter();
        Watch watch = watches.get(viewer.getUniqueId());
        if (watch == null || watch.player != viewer) {
            watch = new Watch(viewer);
            watches.put(viewer.getUniqueId(), watch);
            try {
                adapter.watch(viewer, (Player player, Runnable task) -> { owners.later(player, task, () -> {}); return true; },
                        (BodyTransport.Interaction ignored) -> {});
            } catch (ReflectiveOperationException failure) {
                watches.remove(viewer.getUniqueId(), watch);
                throw new IllegalStateException("Cannot watch the viewer: " + failure.getClass().getSimpleName(), failure);
            }
        }
        watch.ghosts++;
        String world = viewer.getWorld().getUID().toString();
        BodyTraits traits = new BodyTraits(settings.ghostGlow(), settings.ghostGlow() ? Optional.of(settings.ghostColor()) : Optional.empty(),
                false, false, false, true);
        BodySkin textures = skin.orElse(null);
        GhostReplay.Bodies<ItemStack> bodies = new GhostReplay.Bodies<>() {
            @Override public BodyTransport.Body create(NpcPose pose) {
                NpcAppearance appearance = new NpcAppearance(Map.of(), List.of(), false, pose, Optional.empty(), HologramStyle.DEFAULT, traits);
                return adapter.createBody(UUID.randomUUID(), PLAYER, name, appearance, textures);
            }
            @Override public ItemStack item(Optional<NpcItem> item) { return RecorderLook.stack(item); }
            @Override public String world() { return world; }
        };
        return new GhostReplay<>(adapter, viewer, document, bodies, (Runnable task) -> owners.later(viewer, task, () -> {}));
    }

    /** A ghost of the viewer ended; the viewer's watch closes with its last ghost. */
    void release(Player viewer) {
        Watch watch = watches.get(viewer.getUniqueId());
        if (watch == null || watch.player != viewer || --watch.ghosts > 0) return;
        watches.remove(viewer.getUniqueId(), watch);
        NativePacketAdapter adapter;
        synchronized (this) { adapter = packets; }
        if (adapter != null) adapter.forget(viewer);
    }

    private synchronized NativePacketAdapter adapter() {
        Optional<String> reason = unavailable();
        if (reason.isPresent()) throw new IllegalStateException("Ghosts are unavailable: " + reason.get());
        return packets;
    }

    @Override public synchronized void close() {
        closed = true;
        watches.clear();
        if (packets != null) packets.close();
        packets = null;
    }
}
