package art.arcane.animus.core.metrics;

import java.util.concurrent.atomic.LongAdder;

/** Cumulative packet and estimated byte counters by {@link PacketKind}. Thread-safe; recording never allocates. */
public final class Traffic {
    private static final PacketKind[] KINDS = PacketKind.values();
    private final LongAdder[] packets = adders(), bytes = adders();

    private static LongAdder[] adders() {
        LongAdder[] adders = new LongAdder[KINDS.length];
        for (int i = 0; i < adders.length; i++) adders[i] = new LongAdder();
        return adders;
    }

    /** {@code count} packets of one kind, sized by the kind's typical estimate. */
    public void record(PacketKind kind, int count) { record(kind, count, (long) count * kind.typicalBytes()); }

    public void record(PacketKind kind, int count, long estimatedBytes) {
        if (count <= 0) return;
        packets[kind.ordinal()].add(count);
        bytes[kind.ordinal()].add(Math.max(0, estimatedBytes));
    }

    public long packets(PacketKind kind) { return packets[kind.ordinal()].sum(); }
    public long bytes(PacketKind kind) { return bytes[kind.ordinal()].sum(); }

    public long packets() {
        long total = 0;
        for (LongAdder adder : packets) total += adder.sum();
        return total;
    }

    public long bytes() {
        long total = 0;
        for (LongAdder adder : bytes) total += adder.sum();
        return total;
    }
}
