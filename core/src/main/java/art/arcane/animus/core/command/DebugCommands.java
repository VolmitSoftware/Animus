package art.arcane.animus.core.command;

import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugFormat;
import art.arcane.animus.core.metrics.DebugWatch;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /animus debug summary|network [player=]|performance|npc <npc>|watch}: what Animus measured on this server. Byte counts
 * are estimates from typical packet sizes; per-NPC time is sampled. Requires {@link #PERMISSION}.
 */
public final class DebugCommands {
    public static final String PERMISSION = "animus.debug";
    private static final String HEADER = "&8&m        &r " + Palette.PINK + "Animus debug &8&m        ";
    private static final String HONEST = "&8Measured on this server. Bytes are estimates from typical packet sizes.";
    private final Supplier<AnimusMetrics> metrics;
    private final DebugWatch watch;
    private final Function<String, Optional<UUID>> npcIds;
    private final Supplier<List<String>> services;

    /**
     * @param npcIds   an NPC id to its UUID, when the definition exists
     * @param services extra status lines for the summary, such as the editor's connections
     */
    public DebugCommands(Supplier<AnimusMetrics> metrics, DebugWatch watch, Function<String, Optional<UUID>> npcIds, Supplier<List<String>> services) {
        this.metrics = Objects.requireNonNull(metrics); this.watch = Objects.requireNonNull(watch);
        this.npcIds = Objects.requireNonNull(npcIds); this.services = Objects.requireNonNull(services);
    }

    /** Viewer names with recent Animus traffic, for completion. */
    public List<String> viewers() {
        return metrics.get().viewerLoads().stream().map(Load::name).filter((String name) -> !name.isEmpty()).sorted().limit(100).toList();
    }

    public void summary(CommandActor actor) { tell(actor, summary()); }

    /** The whole server, or one viewer by name or UUID. */
    public void network(CommandActor actor, Optional<String> player) { tell(actor, network(player.orElse(null))); }

    public void performance(CommandActor actor) { tell(actor, performance()); }

    public void npc(CommandActor actor, String id) { tell(actor, npc(id)); }

    public void watch(CommandActor actor) { tell(actor, watchToggle(actor)); }

    private static void tell(CommandActor actor, List<String> lines) { for (String line : lines) actor.tell(RichText.colorize(line)); }

    private MetricsSnapshot snapshot() { return metrics.get().snapshot(); }

    private static String bytes(double value) { return DebugFormat.bytes(value); }

    private static String fmt(String pattern, Object... values) { return String.format(Locale.ROOT, pattern, values); }

    private List<String> summary() {
        MetricsSnapshot snapshot = snapshot();
        MetricsSnapshot.Rates ten = snapshot.tenSeconds();
        RuntimeGauges gauges = snapshot.gauges();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        if (snapshot.sequence() == 0) lines.add("&7No measurement has completed yet; try again in a second.");
        lines.add(fmt("&7NPCs &8(%s)&7: &f%d &7definitions, &f%d &7active, &f%d &7dormant, &f%d &7sleeping patrols",
                gauges.runtime(), gauges.definitions(), gauges.active(), gauges.dormant(), gauges.sleepingRoutes()));
        lines.add(fmt("&7Viewers: &f%d &7watching, &f%d &7bodies shown", gauges.viewers(), gauges.visible()));
        lines.add(fmt("&7Network &8(last %ds)&7: &f%.1f &7pkt/s, &f~%s&7/s, &f%.2f &7pkt/tick, NPC clicks in &f%.1f&7/s",
                ten.seconds(), ten.packetsPerSecond(), bytes(ten.bytesPerSecond()), ten.packetsPerTick(), ten.inboundPerSecond()));
        lines.add(fmt("&7Server time &8(last %ds)&7: &f%.3f &7ms/tick = &f%.2f%% &7of a 50 ms tick; most: %s",
                ten.seconds(), ten.serverMillisPerTick(), ten.serverTickShare() * 100, top(ten)));
        lines.add(fmt("&7Pressure: navigation &f%s &7(%d routes waiting, %d path jobs), presentation &f%s&7, combat waiting &f%d",
                gauges.navigationPressured() ? "&cshort" : "ok", gauges.routesWaiting(), gauges.pathJobs(),
                gauges.presentationStrained() ? "&cstrained" : "normal", snapshot.extra("combat.waiting")));
        lines.add(fmt("&7Memory: &f~%s &7rough estimate of runtime structures", bytes(snapshot.memoryEstimateBytes())));
        for (String line : services.get()) lines.add("&7" + line);
        lines.add("&7More: &f/animus debug network&7, &fperformance&7, &fnpc <npc>&7, &fwatch");
        lines.add(HONEST);
        return lines;
    }

    private static String top(MetricsSnapshot.Rates rates) {
        List<Subsystem> ranked = new ArrayList<>();
        for (Subsystem subsystem : Subsystem.values()) if (subsystem.serverThread() && rates.millisPerTick(subsystem) > 0) ranked.add(subsystem);
        ranked.sort(Comparator.comparingDouble(rates::millisPerTick).reversed());
        if (ranked.isEmpty()) return "&fnothing measurable";
        List<String> parts = new ArrayList<>();
        for (Subsystem subsystem : ranked.subList(0, Math.min(3, ranked.size()))) parts.add(fmt("&f%s %.3f", subsystem.key(), rates.millisPerTick(subsystem)));
        return String.join("&7, ", parts);
    }

    private List<String> network(String player) {
        MetricsSnapshot snapshot = snapshot();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        if (player != null) {
            Optional<Load> viewer = metrics.get().viewerLoads().stream().filter((Load load) -> load.name().equalsIgnoreCase(player) || load.id().toString().equalsIgnoreCase(player)).findFirst();
            if (viewer.isEmpty()) { lines.add("&7No Animus traffic is recorded for &f" + player + "&7 in the last minute."); return lines; }
            Load load = viewer.get();
            Optional<MetricsSnapshot.LoadRow> row = snapshot.viewers().stream().filter((MetricsSnapshot.LoadRow candidate) -> candidate.id().equals(load.id())).findFirst();
            lines.add(fmt("&7Viewer &f%s&7: last second &f%d &7pkt, &f~%s&7, &f%d &7clicks in", load.name(),
                    row.map(MetricsSnapshot.LoadRow::packets).orElse(0L), bytes(row.map(MetricsSnapshot.LoadRow::bytes).orElse(0L)), row.map(MetricsSnapshot.LoadRow::inbound).orElse(0L)));
            lines.add(fmt("&7Since tracking began: &f%d &7pkt, &f~%s&7, &f%d &7clicks in", load.traffic().packets(), bytes(load.traffic().bytes()), load.inbound()));
            for (PacketKind kind : PacketKind.values()) {
                long packets = load.traffic().packets(kind);
                if (packets > 0) lines.add(fmt("&8- &7%s: &f%d &7pkt, &f~%s", kind.key(), packets, bytes(load.traffic().bytes(kind))));
            }
            lines.add(HONEST);
            return lines;
        }
        lines.add("&7Outgoing by kind, pkt/s &8(1s / 10s / 60s)&7, est. bytes/s over 10s:");
        for (PacketKind kind : PacketKind.values()) {
            double ten = snapshot.tenSeconds().packetsPerSecond(kind), minute = snapshot.minute().packetsPerSecond(kind);
            if (ten == 0 && minute == 0 && snapshot.second().packets(kind) == 0) continue;
            lines.add(fmt("&8- &7%s: &f%.0f &8/ &f%.1f &8/ &f%.1f &7pkt/s, &f~%s&7/s", kind.key(), snapshot.second().packetsPerSecond(kind), ten, minute,
                    bytes(snapshot.tenSeconds().bytesPerSecond(kind))));
        }
        MetricsSnapshot.Rates ten = snapshot.tenSeconds();
        lines.add(fmt("&7Total: &f%.1f &7pkt/s, &f~%s&7/s, &f%.2f &7pkt/tick over %ds; clicks in &f%.1f&7/s, rate-limited &f%.1f&7/s",
                ten.packetsPerSecond(), bytes(ten.bytesPerSecond()), ten.packetsPerTick(), ten.seconds(), ten.inboundPerSecond(), ten.inboundDroppedPerSecond()));
        List<MetricsSnapshot.LoadRow> viewers = snapshot.viewers();
        lines.add("&7Busiest viewers, last second:" + (viewers.isEmpty() ? " &fnone" : ""));
        for (MetricsSnapshot.LoadRow row : viewers.subList(0, Math.min(8, viewers.size())))
            lines.add(fmt("&8- &f%s&7: &f%d &7pkt, &f~%s&7, &f%d &7clicks in", row.name().isEmpty() ? row.id().toString() : row.name(), row.packets(), bytes(row.bytes()), row.inbound()));
        lines.add(HONEST);
        return lines;
    }

    private List<String> performance() {
        MetricsSnapshot snapshot = snapshot();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add("&7Server-thread time, ms/tick and share of 50 ms &8(1s / 10s / 60s)&7:");
        for (Subsystem subsystem : Subsystem.values()) {
            if (!subsystem.serverThread()) continue;
            lines.add(fmt("&8- &7%s: &f%.3f &8/ &f%.3f &8/ &f%.3f &7ms &8(&f%.2f%%&8 over 10s)", subsystem.label(),
                    snapshot.second().millisPerTick(subsystem), snapshot.tenSeconds().millisPerTick(subsystem), snapshot.minute().millisPerTick(subsystem),
                    snapshot.tenSeconds().tickShare(subsystem) * 100));
        }
        lines.add(fmt("&7All server-thread work: &f%.3f &8/ &f%.3f &8/ &f%.3f &7ms/tick = &f%.2f%% &7of a 50 ms tick over 10s",
                snapshot.second().serverMillisPerTick(), snapshot.tenSeconds().serverMillisPerTick(), snapshot.minute().serverMillisPerTick(),
                snapshot.tenSeconds().serverTickShare() * 100));
        lines.add("&7Off-thread work, ms per second &8(1s / 10s / 60s)&7:");
        for (Subsystem subsystem : Subsystem.values()) {
            if (subsystem.serverThread()) continue;
            lines.add(fmt("&8- &7%s: &f%.2f &8/ &f%.2f &8/ &f%.2f &7ms/s", subsystem.label(), snapshot.second().millisPerSecond(subsystem),
                    snapshot.tenSeconds().millisPerSecond(subsystem), snapshot.minute().millisPerSecond(subsystem)));
        }
        lines.add(fmt("&7Ticks seen: &f%.1f&7/s over %ds. On Folia, region times are summed across regions.", snapshot.tenSeconds().ticksPerSecond(), snapshot.tenSeconds().seconds()));
        if (!snapshot.regionNanos().isEmpty()) {
            double ticks = Math.max(1, snapshot.second().ticks());
            lines.add(fmt("&7Folia regions with NPC work: &f%d&7; busiest &f~%.3f &7ms/tick, quietest &f~%.3f &7ms/tick (sampled, last second)",
                    snapshot.regionNanos().size(), snapshot.regionNanos().get(0) / 1_000_000.0 / ticks,
                    snapshot.regionNanos().get(snapshot.regionNanos().size() - 1) / 1_000_000.0 / ticks));
        }
        lines.add(HONEST);
        return lines;
    }

    private List<String> npc(String id) {
        Optional<UUID> uuid = npcIds.apply(id);
        if (uuid.isEmpty()) return List.of("&7Unknown NPC: &f" + id);
        MetricsSnapshot snapshot = snapshot();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        Optional<Load> load = metrics.get().npcLoad(uuid.get());
        Optional<MetricsSnapshot.LoadRow> row = snapshot.npc(uuid.get());
        boolean placed = snapshot.gauges().places().stream().anyMatch((RuntimeGauges.NpcPlace place) -> place.npc().equals(uuid.get()));
        lines.add(fmt("&7NPC &f%s &8(%s)&7: %s", id, uuid.get(), placed ? "active" : "not active"));
        if (load.isEmpty()) { lines.add("&7No packets or time are recorded for this NPC in the last minute."); lines.add(HONEST); return lines; }
        MetricsSnapshot.Rates second = snapshot.second();
        long packets = row.map(MetricsSnapshot.LoadRow::packets).orElse(0L), bytesSent = row.map(MetricsSnapshot.LoadRow::bytes).orElse(0L);
        long nanos = row.map(MetricsSnapshot.LoadRow::estimatedNanos).orElse(0L);
        double share = second.totalPackets() == 0 ? 0 : 100.0 * packets / second.totalPackets();
        lines.add(fmt("&7Last second: &f%d &7pkt (&f%.1f%% &7of Animus), &f~%s&7, &f~%.1f &7µs server time (sampled, every %d ticks)",
                packets, share, bytes(bytesSent), nanos / 1000.0, AnimusMetrics.SAMPLE_EVERY));
        double ticks = Math.max(1, snapshot.second().ticks());
        lines.add(fmt("&7That is about &f%.4f &7ms/tick, &f%.3f%% &7of a 50 ms tick", nanos / 1_000_000.0 / ticks, nanos / 1_000_000.0 / ticks / MetricsSnapshot.TICK_MILLIS * 100));
        lines.add(fmt("&7Since tracking began: &f%d &7pkt, &f~%s", load.get().traffic().packets(), bytes(load.get().traffic().bytes())));
        for (PacketKind kind : PacketKind.values()) {
            long count = load.get().traffic().packets(kind);
            if (count > 0) lines.add(fmt("&8- &7%s: &f%d &7pkt, &f~%s", kind.key(), count, bytes(load.get().traffic().bytes(kind))));
        }
        lines.add(HONEST);
        return lines;
    }

    private List<String> watchToggle(CommandActor actor) {
        Optional<UUID> player = actor.player();
        if (player.isEmpty()) return List.of("&7The live watch belongs to a player; use &f/animus debug summary &7from the console.");
        return List.of(watch.toggle(player.get()) ? "&7Animus debug watch &aon&7; run it again to stop." : "&7Animus debug watch &coff&7.");
    }
}
