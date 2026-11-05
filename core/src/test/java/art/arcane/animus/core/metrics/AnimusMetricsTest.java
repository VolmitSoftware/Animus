package art.arcane.animus.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AnimusMetricsTest {
    private final AtomicLong nanos = new AtomicLong(1_000);
    private final AnimusMetrics metrics = new AnimusMetrics(nanos::get, () -> 42L);

    /** Twenty ticks spread over one second, so the last tick rolls. */
    private void second() {
        for (int i = 0; i < 20; i++) { nanos.addAndGet(50_000_000L); metrics.serverTick(); }
    }

    @Test void nothingIsPublishedBeforeTheFirstSecond() {
        metrics.sent(null, null, PacketKind.MOVE, 5);
        metrics.serverTick();
        assertEquals(0, metrics.snapshot().sequence());
        assertEquals(0, metrics.snapshot().second().totalPackets());
    }

    @Test void rollPublishesPerSecondAndPerTickRatesByKind() {
        metrics.sent(null, null, PacketKind.MOVE, 40);
        metrics.sent(null, null, PacketKind.SPAWN, 2, 500);
        second();
        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.sequence());
        MetricsSnapshot.Rates rates = snapshot.second();
        assertEquals(40, rates.packets(PacketKind.MOVE));
        assertEquals(40L * PacketKind.MOVE.typicalBytes(), rates.bytes(PacketKind.MOVE));
        assertEquals(500, rates.bytes(PacketKind.SPAWN));
        assertEquals(42.0, rates.packetsPerSecond());
        assertEquals(20, rates.ticks());
        assertEquals(2.1, rates.packetsPerTick(), 1e-9);
    }

    @Test void secondsAreDeltasAndLongerWindowsAverage() {
        metrics.sent(null, null, PacketKind.MOVE, 10);
        second();
        metrics.sent(null, null, PacketKind.MOVE, 30);
        second();
        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(30, snapshot.second().packets(PacketKind.MOVE));
        assertEquals(40, snapshot.tenSeconds().packets(PacketKind.MOVE));
        assertEquals(2, snapshot.tenSeconds().seconds());
        assertEquals(20.0, snapshot.tenSeconds().packetsPerSecond(PacketKind.MOVE));
    }

    @Test void subsystemTimeIsChargedPerTickAndAsShareOfFiftyMillis() {
        long started = metrics.start();
        nanos.addAndGet(5_000_000L);
        long next = metrics.lap(Subsystem.NAVIGATION, started);
        nanos.addAndGet(5_000_000L);
        metrics.stop(Subsystem.PRESENTATION, next);
        metrics.cost(Subsystem.EDITOR, 7_000_000L);
        second();
        MetricsSnapshot.Rates rates = metrics.snapshot().second();
        assertEquals(0.25, rates.millisPerTick(Subsystem.NAVIGATION), 1e-9);
        assertEquals(0.5, rates.serverMillisPerTick(), 1e-9, "off-thread editor time is not tick time");
        assertEquals(0.01, rates.serverTickShare(), 1e-9);
        assertEquals(7.0, rates.millisPerSecond(Subsystem.EDITOR), 1e-9);
    }

    @Test void viewerAndNpcLoadsTrackTheirOwnTraffic() {
        UUID player = UUID.randomUUID(), npc = UUID.randomUUID();
        Load viewer = metrics.viewer(player, "Alex");
        Load body = metrics.npc(npc);
        metrics.sent(viewer, body, PacketKind.EQUIPMENT, 3);
        metrics.sent(viewer, null, PacketKind.HOLOGRAM, 1);
        metrics.inbound(viewer, true);
        metrics.inbound(viewer, false);
        second();
        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(4, snapshot.viewers().get(0).packets());
        assertEquals("Alex", snapshot.viewers().get(0).name());
        assertEquals(1, snapshot.viewers().get(0).inbound());
        assertEquals(3, snapshot.npc(npc).orElseThrow().packets());
        assertEquals(1, snapshot.second().inbound());
        assertEquals(1, snapshot.second().inboundDropped());
        assertSame(viewer, metrics.viewer(player, "Alex"));
    }

    @Test void npcCostIsScaledBySampling() {
        UUID npc = UUID.randomUUID();
        metrics.npcCost(metrics.npc(npc), 1_000);
        second();
        assertEquals(1_000L * AnimusMetrics.SAMPLE_EVERY, metrics.snapshot().npc(npc).orElseThrow().estimatedNanos());
    }

    @Test void samplingRunsOnEveryFourthTick() {
        int sampled = 0;
        for (int i = 0; i < 40; i++) { if (metrics.sampling()) sampled++; metrics.serverTick(); }
        assertEquals(40 / AnimusMetrics.SAMPLE_EVERY, sampled);
    }

    @Test void gaugesAndChunksComeFromTheRuntime() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        metrics.sent(null, metrics.npc(first), PacketKind.MOVE, 4);
        metrics.gauges(() -> new RuntimeGauges("test", 5, 3, 2, 1, 1, 3, 2, 1, 0, true, false, 7, 40, 60,
                List.of(new RuntimeGauges.NpcPlace(first, "world", 0, 0), new RuntimeGauges.NpcPlace(second, "world", 0, 0),
                        new RuntimeGauges.NpcPlace(third, "world", 4, -2))));
        metrics.extra("combat.waiting", () -> 6);
        second();
        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3, snapshot.gauges().active());
        assertTrue(snapshot.gauges().navigationPressured());
        assertEquals(6, snapshot.extra("combat.waiting"));
        assertEquals(2, snapshot.chunks().size());
        MetricsSnapshot.ChunkRow busiest = snapshot.chunks().get(0);
        assertEquals(2, busiest.npcs());
        assertEquals(4, busiest.packets());
        assertEquals(4, snapshot.chunks().get(1).chunkX());
        assertTrue(snapshot.memoryEstimateBytes() > 0);
    }

    @Test void failingSourcesDoNotStopTheRoll() {
        metrics.gauges(() -> { throw new IllegalStateException("broken"); });
        metrics.extra("bad", () -> { throw new IllegalStateException("broken"); });
        metrics.listen((MetricsSnapshot snapshot) -> { throw new IllegalStateException("listener"); });
        second();
        assertEquals(1, metrics.snapshot().sequence());
        assertEquals(RuntimeGauges.NONE, metrics.snapshot().gauges());
        assertFalse(metrics.snapshot().extras().containsKey("bad"));
    }

    @Test void idleLoadsLeaveAndReturnOnTheirNextRecord() {
        UUID player = UUID.randomUUID();
        Load viewer = metrics.viewer(player, "Sam");
        metrics.sent(viewer, null, PacketKind.MOVE, 1);
        for (int i = 0; i <= AnimusMetrics.IDLE_SECONDS; i++) second();
        assertTrue(metrics.viewerLoad(player).isEmpty());
        metrics.sent(viewer, null, PacketKind.MOVE, 2);
        assertSame(viewer, metrics.viewerLoad(player).orElseThrow());
        second();
        assertEquals(2, metrics.snapshot().viewers().get(0).packets());
    }

    @Test void listenersReceiveEachRoll() {
        List<Long> sequences = new ArrayList<>();
        metrics.listen((MetricsSnapshot snapshot) -> sequences.add(snapshot.sequence()));
        second(); second();
        assertEquals(List.of(1L, 2L), sequences);
    }
}
