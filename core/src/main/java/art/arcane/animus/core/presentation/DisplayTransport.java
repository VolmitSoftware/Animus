package art.arcane.animus.core.presentation;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-viewer packet presentation of holograms and holographic menus. Nothing here creates a server entity. Call
 * every method on the viewer's owner. A click is only matched to an ID the viewer currently sees and rate limited:
 * the consumer must validate reach, aim and the current document state before acting on it.
 *
 * @param <P> the platform's online player
 */
public interface DisplayTransport<P> extends AutoCloseable {
    /** A left ({@code attack}) or right click on an entity this viewer sees. {@code secondary} is the sneak flag of a right click. */
    record Click(int entityId, boolean attack, boolean secondary, long receivedAtNanos) {}

    /** Null when displays can be presented; otherwise why they cannot. */
    String unavailable();
    /** A native entity ID from the server's own allocator, so it never collides with a real entity. */
    int reserve();
    /** Clicks on this viewer's displays arrive on the viewer's owner. A new watch replaces the previous one. */
    void watch(P viewer, Consumer<Click> clicks);
    void spawn(P viewer, List<DisplayEntity> entities);
    /** Sends the content, style and position of entities already spawned for this viewer. */
    void update(P viewer, List<DisplayEntity> entities);
    void remove(P viewer, int[] ids);
    /** The viewer left or the presentation was reset; nothing more is sent to them and their clicks stop. */
    void forget(P viewer);
    @Override void close();

    /** A transport for servers where displays are unavailable; every call is a no-op. */
    static <P> DisplayTransport<P> unavailable(String reason) {
        return new DisplayTransport<>() {
            @Override public String unavailable() { return reason; }
            @Override public int reserve() { throw new IllegalStateException(reason); }
            @Override public void watch(P viewer, Consumer<Click> clicks) {}
            @Override public void spawn(P viewer, List<DisplayEntity> entities) {}
            @Override public void update(P viewer, List<DisplayEntity> entities) {}
            @Override public void remove(P viewer, int[] ids) {}
            @Override public void forget(P viewer) {}
            @Override public void close() {}
        };
    }
}
