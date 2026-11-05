package art.arcane.animus.core.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Always-on operator diagnostics: outgoing packets and estimated bytes by kind, viewer and NPC; inbound interactions;
 * per-subsystem time; and runtime gauges. Recording is lock-free and allocation-free once a viewer or NPC has its
 * {@link Load}. Once a second the platform's server tick rolls the counters into a 60-second window and publishes an
 * immutable {@link MetricsSnapshot}. Values describe this server only.
 * <p>
 * Per-NPC cost is timed only on every {@link #SAMPLE_EVERY}th tick and scaled up, so it is an estimate.
 */
public final class AnimusMetrics {
    public static final int SAMPLE_EVERY = 4;
    /** A viewer or NPC with no traffic for this many seconds leaves the registry. */
    public static final int IDLE_SECONDS = 60;
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final PacketKind[] KINDS = PacketKind.values();
    private static final Subsystem[] SUBSYSTEMS = Subsystem.values();
    private static final int INBOUND = KINDS.length * 2, DROPPED = INBOUND + 1, COSTS = DROPPED + 1, CHANNELS = COSTS + SUBSYSTEMS.length;
    /** Rough sizes for {@link MetricsSnapshot#memoryEstimateBytes()}. */
    static final long DEFINITION_BYTES = 2048, ACTIVE_BYTES = 4096, VISIBLE_BYTES = 384, ROUTE_BYTES = 1536, VIEWER_BYTES = 2048, LOAD_BYTES = 512;

    private static volatile AnimusMetrics current = new AnimusMetrics(System::nanoTime, System::currentTimeMillis);

    private final LongSupplier clock, wallClock;
    private final Traffic traffic = new Traffic();
    private final LongAdder inbound = new LongAdder(), inboundDropped = new LongAdder();
    private final LongAdder[] costs = new LongAdder[SUBSYSTEMS.length];
    private final Map<UUID, Load> npcs = new ConcurrentHashMap<>(), viewers = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> extras = new ConcurrentHashMap<>();
    private final Map<Object, Region> regions = new ConcurrentHashMap<>();
    private final List<Consumer<MetricsSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final RollingWindow window = new RollingWindow(CHANNELS);
    private final AtomicLong ticks = new AtomicLong();
    private volatile Supplier<RuntimeGauges> gauges = () -> RuntimeGauges.NONE;
    private volatile MetricsSnapshot snapshot;
    // Roller only.
    private final long[] seen = new long[CHANNELS];
    private long ticksAtRoll, rollDue, sequence;

    public AnimusMetrics(LongSupplier nanoClock, LongSupplier wallClock) {
        this.clock = Objects.requireNonNull(nanoClock); this.wallClock = Objects.requireNonNull(wallClock);
        for (int i = 0; i < costs.length; i++) costs[i] = new LongAdder();
        rollDue = clock.getAsLong() + SECOND_NANOS;
        snapshot = empty();
    }

    /** The metrics of this server start. Hot paths read this once and keep the reference. */
    public static AnimusMetrics current() { return current; }
    /** A platform start installs fresh metrics so a restart does not mix counters. */
    public static AnimusMetrics install(AnimusMetrics metrics) { current = Objects.requireNonNull(metrics); return metrics; }
    public static AnimusMetrics restart() { return install(new AnimusMetrics(System::nanoTime, System::currentTimeMillis)); }

    // ---- Recording ----

    /** The load of one viewer; keep it with the viewer's session. */
    public Load viewer(UUID id, String name) {
        Load load = viewers.computeIfAbsent(id, (UUID key) -> new Load(key, name));
        load.name(name);
        return load;
    }

    /** The load of one NPC; keep it with the NPC's body. */
    public Load npc(UUID id) { return npcs.computeIfAbsent(id, (UUID key) -> new Load(key, "")); }

    /** Outgoing packets. Either load may be null when the packets belong to no single viewer or NPC. */
    public void sent(Load viewer, Load npc, PacketKind kind, int count) { sent(viewer, npc, kind, count, (long) count * kind.typicalBytes()); }

    public void sent(Load viewer, Load npc, PacketKind kind, int count, long estimatedBytes) {
        if (count <= 0) return;
        traffic.record(kind, count, estimatedBytes);
        if (viewer != null) { revive(viewers, viewer); viewer.traffic.record(kind, count, estimatedBytes); }
        if (npc != null) { revive(npcs, npc); npc.traffic.record(kind, count, estimatedBytes); }
    }

    /** One inbound attack or interact packet aimed at an Animus body; {@code accepted} false when rate-limited. */
    public void inbound(Load viewer, boolean accepted) {
        if (accepted) inbound.increment(); else inboundDropped.increment();
        if (viewer != null && accepted) { revive(viewers, viewer); viewer.inbound.increment(); }
    }

    private static void revive(Map<UUID, Load> registry, Load load) {
        if (load.retired) { load.retired = false; registry.putIfAbsent(load.id(), load); }
    }

    /** {@code long started = metrics.start(); ...; metrics.stop(Subsystem.X, started);} */
    public long start() { return clock.getAsLong(); }
    public void stop(Subsystem subsystem, long started) { cost(subsystem, clock.getAsLong() - started); }
    public void cost(Subsystem subsystem, long nanos) { if (nanos > 0) costs[subsystem.ordinal()].add(nanos); }
    /** The work, charging each run to the subsystem. */
    public Runnable timed(Subsystem subsystem, Runnable work) {
        Objects.requireNonNull(subsystem); Objects.requireNonNull(work);
        return () -> {
            long started = clock.getAsLong();
            try { work.run(); }
            finally { cost(subsystem, clock.getAsLong() - started); }
        };
    }
    /** Charges the time since {@code started} and returns now, for consecutive sections. */
    public long lap(Subsystem subsystem, long started) {
        long now = clock.getAsLong();
        cost(subsystem, now - started);
        return now;
    }

    /** Like {@link #lap}, for work timed only on sampled ticks: the time is scaled by {@link #SAMPLE_EVERY}. */
    public long sampledLap(Subsystem subsystem, long started) {
        long now = clock.getAsLong();
        cost(subsystem, (now - started) * SAMPLE_EVERY);
        return now;
    }

    /** NPC time measured on a sampled tick of one Folia region; {@code region} is the region's identity. */
    public void regionCost(Object region, long nanos) {
        if (region == null || nanos <= 0) return;
        regions.computeIfAbsent(region, (Object key) -> new Region()).nanos.add(nanos);
    }

    private static final class Region {
        final LongAdder nanos = new LongAdder();
        long seen;
        int idle;
    }

    /** True on ticks whose per-NPC work is timed. */
    public boolean sampling() { return ticks.get() % SAMPLE_EVERY == 0; }
    /** Per-NPC time measured on a sampled tick. */
    public void npcCost(Load npc, long nanos) {
        if (npc == null || nanos <= 0) return;
        revive(npcs, npc);
        npc.sampledNanos.add(nanos);
    }

    // ---- Sources ----

    /** The NPC runtime's counts, read on the rolling thread once a second. */
    public void gauges(Supplier<RuntimeGauges> source) { gauges = Objects.requireNonNull(source); }
    /** A named gauge from another service, such as {@code combat.waiting}; read on the rolling thread. */
    public void extra(String key, LongSupplier source) { extras.put(Objects.requireNonNull(key), Objects.requireNonNull(source)); }
    /** Called on the rolling thread after each roll; keep it quick. */
    public void listen(Consumer<MetricsSnapshot> listener) { listeners.add(Objects.requireNonNull(listener)); }

    // ---- Rolling ----

    /**
     * Once per server tick on one thread: the main thread, the mod server thread or Folia's global region. Rolls the
     * window when a second has passed.
     */
    public void serverTick() {
        ticks.incrementAndGet();
        long now = clock.getAsLong();
        if (now - rollDue < 0) return;
        rollDue = now + SECOND_NANOS;
        roll();
    }

    public long ticks() { return ticks.get(); }
    public MetricsSnapshot snapshot() { return snapshot; }
    public Traffic traffic() { return traffic; }
    public java.util.Optional<Load> npcLoad(UUID id) { return java.util.Optional.ofNullable(npcs.get(id)); }
    public java.util.Optional<Load> viewerLoad(UUID id) { return java.util.Optional.ofNullable(viewers.get(id)); }
    public List<Load> viewerLoads() { return List.copyOf(viewers.values()); }

    private void roll() {
        long[] totals = new long[CHANNELS];
        for (PacketKind kind : KINDS) { totals[kind.ordinal()] = traffic.packets(kind); totals[KINDS.length + kind.ordinal()] = traffic.bytes(kind); }
        totals[INBOUND] = inbound.sum(); totals[DROPPED] = inboundDropped.sum();
        for (Subsystem subsystem : SUBSYSTEMS) totals[COSTS + subsystem.ordinal()] = costs[subsystem.ordinal()].sum();
        long[] deltas = new long[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) { deltas[i] = Math.max(0, totals[i] - seen[i]); seen[i] = totals[i]; }
        long tickCount = ticks.get();
        window.push(deltas, tickCount - ticksAtRoll);
        ticksAtRoll = tickCount;

        RuntimeGauges counts;
        try { counts = Objects.requireNonNullElse(gauges.get(), RuntimeGauges.NONE); }
        catch (RuntimeException failure) { counts = RuntimeGauges.NONE; }
        Map<String, Long> extraValues = new HashMap<>();
        for (Map.Entry<String, LongSupplier> entry : extras.entrySet()) {
            try { extraValues.put(entry.getKey(), entry.getValue().getAsLong()); }
            catch (RuntimeException failure) { /* A failing gauge is left out of this roll. */ }
        }
        java.util.Set<UUID> placed = new java.util.HashSet<>();
        for (RuntimeGauges.NpcPlace place : counts.places()) placed.add(place.npc());
        Map<UUID, MetricsSnapshot.LoadRow> npcRows = new HashMap<>();
        for (Load load : advance(npcs, placed)) npcRows.put(load.id(), row(load));
        List<MetricsSnapshot.LoadRow> viewerRows = new ArrayList<>();
        for (Load load : advance(viewers, java.util.Set.of())) viewerRows.add(row(load));
        viewerRows.sort(Comparator.comparingLong(MetricsSnapshot.LoadRow::bytes).reversed().thenComparing(MetricsSnapshot.LoadRow::name));
        snapshot = new MetricsSnapshot(++sequence, wallClock.getAsLong(), rates(), counts, extraValues, npcRows, viewerRows,
                chunks(counts, npcRows), memoryEstimate(counts), regionSeconds());
        for (Consumer<MetricsSnapshot> listener : listeners) {
            try { listener.accept(snapshot); }
            catch (RuntimeException failure) { /* A listener cannot stop the roll. */ }
        }
    }

    /** Updates each load's last-second deltas; drops loads idle for {@link #IDLE_SECONDS} that the runtime no longer places. */
    private static List<Load> advance(Map<UUID, Load> registry, java.util.Set<UUID> kept) {
        List<Load> live = new ArrayList<>(registry.size());
        for (Iterator<Load> loads = registry.values().iterator(); loads.hasNext(); ) {
            Load load = loads.next();
            long packets = load.traffic.packets(), bytes = load.traffic.bytes(), in = load.inbound.sum(), nanos = load.sampledNanos.sum();
            load.secondPackets = packets - load.seenPackets; load.secondBytes = bytes - load.seenBytes;
            load.secondInbound = in - load.seenInbound; load.secondNanos = nanos - load.seenNanos;
            load.seenPackets = packets; load.seenBytes = bytes; load.seenInbound = in; load.seenNanos = nanos;
            boolean busy = load.secondPackets > 0 || load.secondInbound > 0 || load.secondNanos > 0 || kept.contains(load.id());
            load.idleSeconds = busy ? 0 : load.idleSeconds + 1;
            if (load.idleSeconds >= IDLE_SECONDS) { load.retired = true; loads.remove(); continue; }
            live.add(load);
        }
        return live;
    }

    /** Each region's sampled time over the last second, scaled, busiest first; regions idle for a minute are forgotten. */
    private List<Long> regionSeconds() {
        List<Long> seconds = new ArrayList<>(regions.size());
        for (Iterator<Region> entries = regions.values().iterator(); entries.hasNext(); ) {
            Region region = entries.next();
            long total = region.nanos.sum(), delta = total - region.seen;
            region.seen = total;
            region.idle = delta > 0 ? 0 : region.idle + 1;
            if (region.idle >= IDLE_SECONDS) { entries.remove(); continue; }
            seconds.add(delta * SAMPLE_EVERY);
        }
        seconds.sort(Comparator.reverseOrder());
        return seconds;
    }

    private static MetricsSnapshot.LoadRow row(Load load) {
        return new MetricsSnapshot.LoadRow(load.id(), load.name(), load.secondPackets, load.secondBytes, load.secondInbound,
                load.secondNanos * SAMPLE_EVERY, load.seenPackets, load.seenBytes, load.seenInbound);
    }

    private List<MetricsSnapshot.Rates> rates() {
        List<MetricsSnapshot.Rates> rates = new ArrayList<>(MetricsSnapshot.WINDOW_SECONDS.length);
        for (int seconds : MetricsSnapshot.WINDOW_SECONDS) {
            long[] packets = new long[KINDS.length], bytes = new long[KINDS.length], nanos = new long[SUBSYSTEMS.length];
            for (int i = 0; i < KINDS.length; i++) { packets[i] = window.total(i, seconds); bytes[i] = window.total(KINDS.length + i, seconds); }
            for (int i = 0; i < SUBSYSTEMS.length; i++) nanos[i] = window.total(COSTS + i, seconds);
            rates.add(new MetricsSnapshot.Rates(window.covered(seconds), window.ticks(seconds), packets, bytes,
                    window.total(INBOUND, seconds), window.total(DROPPED, seconds), nanos));
        }
        return rates;
    }

    private record ChunkKey(String world, int x, int z) {}

    private static List<MetricsSnapshot.ChunkRow> chunks(RuntimeGauges counts, Map<UUID, MetricsSnapshot.LoadRow> npcRows) {
        Map<ChunkKey, long[]> sums = new HashMap<>();
        for (RuntimeGauges.NpcPlace place : counts.places()) {
            long[] sum = sums.computeIfAbsent(new ChunkKey(place.world(), place.chunkX(), place.chunkZ()), (ChunkKey key) -> new long[4]);
            sum[0]++;
            MetricsSnapshot.LoadRow row = npcRows.get(place.npc());
            if (row != null) { sum[1] += row.packets(); sum[2] += row.bytes(); sum[3] += row.estimatedNanos(); }
        }
        List<MetricsSnapshot.ChunkRow> rows = new ArrayList<>(sums.size());
        for (Map.Entry<ChunkKey, long[]> entry : sums.entrySet()) {
            long[] sum = entry.getValue();
            rows.add(new MetricsSnapshot.ChunkRow(entry.getKey().world(), entry.getKey().x(), entry.getKey().z(), (int) sum[0], sum[1], sum[2], sum[3]));
        }
        rows.sort(Comparator.comparingInt(MetricsSnapshot.ChunkRow::npcs).reversed().thenComparing(Comparator.comparingLong(MetricsSnapshot.ChunkRow::estimatedNanos).reversed())
                .thenComparing(MetricsSnapshot.ChunkRow::world).thenComparingInt(MetricsSnapshot.ChunkRow::chunkX).thenComparingInt(MetricsSnapshot.ChunkRow::chunkZ));
        return rows;
    }

    private long memoryEstimate(RuntimeGauges counts) {
        return counts.definitions() * DEFINITION_BYTES + counts.active() * ACTIVE_BYTES + counts.visible() * VISIBLE_BYTES
                + counts.routes() * ROUTE_BYTES + counts.viewers() * VIEWER_BYTES + (long) (npcs.size() + viewers.size()) * LOAD_BYTES;
    }

    private MetricsSnapshot empty() {
        return new MetricsSnapshot(0, wallClock.getAsLong(), rates(), RuntimeGauges.NONE, Map.of(), Map.of(), List.of(), List.of(), 0, List.of());
    }
}
