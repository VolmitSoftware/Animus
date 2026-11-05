package art.arcane.animus.core.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Animus announces every file it writes into its data folder here, before and after the write, so the hotload watcher
 * can tell its own writes from an operator's hand edits. Listeners must be quick and must not throw.
 */
public final class WriteNotices {
    /** Hears Animus's own writes. */
    public interface Listener {
        /** {@code path} now holds {@code text}, or is gone when {@code text} is null. */
        void written(Path path, String text);
        /** {@code path} changed in a way only its bytes on disk can describe, such as a copied binary file. */
        void touched(Path path);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private WriteNotices() {}

    /**
     * Loads this class and its listener type now. {@link DocumentStore} calls it when it loads, so a final state flush
     * after the plugin archive has closed can still announce its write.
     */
    static Class<Listener> preload() { return Listener.class; }

    /** Registers a listener until the returned handle is closed. */
    public static AutoCloseable listen(Listener listener) {
        Listener added = Objects.requireNonNull(listener, "listener");
        LISTENERS.add(added);
        return () -> LISTENERS.remove(added);
    }

    public static void written(Path path, String text) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Listener listener : LISTENERS) {
            try { listener.written(absolute, text); }
            catch (RuntimeException ignored) { /* A listener failure must never fail the write itself. */ }
        }
    }

    public static void touched(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Listener listener : LISTENERS) {
            try { listener.touched(absolute); }
            catch (RuntimeException ignored) { /* A listener failure must never fail the write itself. */ }
        }
    }
}
