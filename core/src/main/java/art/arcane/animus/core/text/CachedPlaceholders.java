package art.arcane.animus.core.text;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Placeholders filled through a plugin and kept per viewer and text for a second, so a body, hologram or window shown
 * to the same player again does not ask the plugin twice. Text without a {@code %...%} pair never reaches the plugin.
 * A plugin failure leaves the text as written. Thread-safe; the plugin itself is asked on the caller's thread.
 */
public final class CachedPlaceholders implements Placeholders {
    /** How long a filled text is reused: shorter than the smallest {@code [placeholders] refreshTicks}, so each refresh asks again. */
    public static final long TTL_NANOS = 750_000_000L;
    static final int MAXIMUM_ENTRIES = 8192;

    /** The placeholder plugin. {@code fill} may return null for a viewer it cannot find. */
    public interface Source {
        boolean available();
        String fill(UUID viewer, String text);
    }

    private record Key(UUID viewer, String text) {}
    private record Filled(String text, long expires) {}

    private final Source source;
    private final LongSupplier clock;
    private final Map<Key, Filled> filled = new ConcurrentHashMap<>();
    private volatile int refreshTicks;

    public CachedPlaceholders(Source source) { this(source, System::nanoTime); }

    public CachedPlaceholders(Source source, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public boolean active() { return source.available(); }
    @Override public int refreshTicks() { return refreshTicks; }
    /** Follows {@code [placeholders] refreshTicks}, at startup and on each hotload of {@code config.toml}. */
    public void refreshTicks(int ticks) { refreshTicks = Math.max(0, ticks); }

    @Override public String fill(UUID viewer, String text) {
        if (viewer == null || !Placeholders.marked(text) || !source.available()) return text;
        Key key = new Key(viewer, text);
        long now = clock.getAsLong();
        Filled cached = filled.get(key);
        if (cached != null && now - cached.expires() < 0) return cached.text();
        String result;
        try { result = source.fill(viewer, text); }
        catch (RuntimeException failed) { result = null; }
        if (result == null) result = text;
        if (filled.size() >= MAXIMUM_ENTRIES) filled.clear();
        filled.put(key, new Filled(result, now + TTL_NANOS));
        return result;
    }

    /** Drops what was kept for a player who left. */
    public void forget(UUID viewer) { filled.keySet().removeIf((Key key) -> key.viewer().equals(viewer)); }
}
