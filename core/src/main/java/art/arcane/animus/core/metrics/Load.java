package art.arcane.animus.core.metrics;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * Traffic and sampled cost one NPC or one viewer caused. Hot paths hold a reference and record into it; the roller
 * keeps the per-second deltas. A load that sat idle for a minute is dropped from the registry and re-registers itself
 * on its next record, so a long-lived holder never writes into a forgotten object.
 */
public final class Load {
    private final UUID id;
    private volatile String name;
    final Traffic traffic = new Traffic();
    final LongAdder inbound = new LongAdder();
    /** Nanoseconds measured on sampled ticks only. */
    final LongAdder sampledNanos = new LongAdder();
    volatile boolean retired;
    // Roller thread only.
    long seenPackets, seenBytes, seenInbound, seenNanos;
    long secondPackets, secondBytes, secondInbound, secondNanos;
    int idleSeconds;

    Load(UUID id, String name) { this.id = Objects.requireNonNull(id); this.name = name == null ? "" : name; }

    public UUID id() { return id; }
    public String name() { return name; }
    void name(String replacement) { if (replacement != null && !replacement.isEmpty()) name = replacement; }
    public Traffic traffic() { return traffic; }
    public long inbound() { return inbound.sum(); }
    public long sampledNanos() { return sampledNanos.sum(); }
}
