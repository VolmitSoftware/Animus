package art.arcane.animus.bukkit.folia;

import java.util.concurrent.atomic.*;

/** Pure lifetime gate. Retirement never accesses a player, world or mutable owner state. */
final class LeaseResources implements AutoCloseable {
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<AutoCloseable> transport = new AtomicReference<>(), task = new AtomicReference<>();
    private final Runnable released;
    LeaseResources(Runnable released) { this.released = released; }
    boolean active() { return active.get(); }
    // A transport is published ONLY AFTER watch has installed its session into the adapter's map.
    // This prevents NativePacketAdapter.watch racing its close-before-map-insertion window.
    void transport(AutoCloseable resource) { publish(transport, resource); }
    void task(AutoCloseable resource) { publish(task, resource); }
    private void publish(AtomicReference<AutoCloseable> slot, AutoCloseable value) {
        if (!slot.compareAndSet(null, value)) { quietClose(value); throw new IllegalStateException("Resource already published"); }
        if (!active.get()) quietClose(slot.getAndSet(null));
    }
    @Override public void close() {
        if (!active.compareAndSet(true, false)) return;
        try { quietClose(task.getAndSet(null)); quietClose(transport.getAndSet(null)); }
        finally { released.run(); }
    }
    private static void quietClose(AutoCloseable value) {
        if (value != null) try { value.close(); } catch (Exception ignored) { /* Gate remains retired. */ }
    }
}
