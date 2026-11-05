package art.arcane.animus.core.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the metrics knew at one roll. Immutable; safe to read from any thread.
 *
 * @param sequence      rolls since start; 0 before the first
 * @param takenAtMillis wall clock of the roll
 * @param windows       rates over the last 1, 10 and 60 seconds, in that order
 * @param gauges        the runtime's counts at the roll
 * @param extras        other services' gauges by key, such as {@code combat.waiting}
 * @param npcs          per-NPC load over the last second, by NPC UUID
 * @param viewers       per-viewer load over the last second, busiest first
 * @param chunks        active NPCs and their measured load by chunk, busiest first
 * @param memoryEstimateBytes a rough size of Animus's runtime structures, from counts times typical sizes
 * @param regionNanos   on Folia, each region's estimated NPC time over the last second, busiest first; empty elsewhere
 */
public record MetricsSnapshot(long sequence, long takenAtMillis, List<Rates> windows, RuntimeGauges gauges, Map<String, Long> extras,
                              Map<UUID, LoadRow> npcs, List<LoadRow> viewers, List<ChunkRow> chunks, long memoryEstimateBytes, List<Long> regionNanos) {
    public static final int[] WINDOW_SECONDS = {1, 10, 60};
    public static final double TICK_MILLIS = 50.0;

    public MetricsSnapshot {
        windows = List.copyOf(windows); Objects.requireNonNull(gauges);
        extras = Map.copyOf(extras); npcs = Map.copyOf(npcs); viewers = List.copyOf(viewers); chunks = List.copyOf(chunks);
        regionNanos = List.copyOf(regionNanos);
    }

    public Rates second() { return windows.get(0); }
    public Rates tenSeconds() { return windows.get(1); }
    public Rates minute() { return windows.get(2); }
    public Optional<LoadRow> npc(UUID id) { return Optional.ofNullable(npcs.get(id)); }
    public long extra(String key) { return extras.getOrDefault(key, 0L); }

    /**
     * Totals over a window. {@code seconds} is how much of the window was filled; {@code ticks} the server ticks it
     * spanned. Arrays are indexed by {@link PacketKind} and {@link Subsystem} ordinals.
     */
    public record Rates(int seconds, long ticks, long[] packets, long[] bytes, long inbound, long inboundDropped, long[] costNanos) {
        public Rates {
            packets = packets.clone(); bytes = bytes.clone(); costNanos = costNanos.clone();
        }
        private double perSecond(long total) { return seconds == 0 ? 0 : (double) total / seconds; }
        private double perTick(long total) { return ticks == 0 ? 0 : (double) total / ticks; }
        public long packets(PacketKind kind) { return packets[kind.ordinal()]; }
        public long bytes(PacketKind kind) { return bytes[kind.ordinal()]; }
        public long totalPackets() { long sum = 0; for (long value : packets) sum += value; return sum; }
        public long totalBytes() { long sum = 0; for (long value : bytes) sum += value; return sum; }
        public double packetsPerSecond() { return perSecond(totalPackets()); }
        public double bytesPerSecond() { return perSecond(totalBytes()); }
        public double packetsPerTick() { return perTick(totalPackets()); }
        public double bytesPerTick() { return perTick(totalBytes()); }
        public double packetsPerSecond(PacketKind kind) { return perSecond(packets(kind)); }
        public double bytesPerSecond(PacketKind kind) { return perSecond(bytes(kind)); }
        public double inboundPerSecond() { return perSecond(inbound); }
        public double inboundDroppedPerSecond() { return perSecond(inboundDropped); }
        /** Milliseconds per server tick spent in one subsystem; on Folia, summed over every region. */
        public double millisPerTick(Subsystem subsystem) { return perTick(costNanos[subsystem.ordinal()]) / 1_000_000.0; }
        /** Milliseconds per second for work off the tick thread. */
        public double millisPerSecond(Subsystem subsystem) { return perSecond(costNanos[subsystem.ordinal()]) / 1_000_000.0; }
        /** Server-thread subsystems only. */
        public double serverMillisPerTick() {
            double sum = 0;
            for (Subsystem subsystem : Subsystem.values()) if (subsystem.serverThread()) sum += millisPerTick(subsystem);
            return sum;
        }
        /** Share of a 50 ms tick, 0..1 and above when over budget. */
        public double tickShare(Subsystem subsystem) { return millisPerTick(subsystem) / TICK_MILLIS; }
        public double serverTickShare() { return serverMillisPerTick() / TICK_MILLIS; }
        public double ticksPerSecond() { return perSecond(ticks); }
    }

    /**
     * One NPC's or viewer's load over the last second. Cost is measured on sampled ticks and scaled up, so it is an
     * estimate. Totals are since start.
     */
    public record LoadRow(UUID id, String name, long packets, long bytes, long inbound, long estimatedNanos,
                          long totalPackets, long totalBytes, long totalInbound) {
        public LoadRow { Objects.requireNonNull(id); Objects.requireNonNull(name); }
    }

    /** Active NPCs in one chunk, with their traffic and estimated cost over the last second. */
    public record ChunkRow(String world, int chunkX, int chunkZ, int npcs, long packets, long bytes, long estimatedNanos) {
        public ChunkRow { Objects.requireNonNull(world); }
    }
}
