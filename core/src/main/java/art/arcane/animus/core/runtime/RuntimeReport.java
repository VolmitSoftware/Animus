package art.arcane.animus.core.runtime;

import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.List;

/** What one runtime reports: the status line, one NPC's inspection and the metrics gauges with their budget load. Owner thread only. */
final class RuntimeReport<P, I> {
    private final PersistedNpcService<P, I> service;
    /** Budget use summed per tick since the last metrics roll, as percentages. */
    private long navigationUse, packetUse, loadTicks;

    RuntimeReport(PersistedNpcService<P, I> service) { this.service = service; }

    /** This tick's navigation budget use, as a percentage. */
    void navigation(int percent) { navigationUse += percent; }
    /** This tick's packet budget use, as a percentage; it ends the tick's load sample. */
    void presentation(int percent) { packetUse += percent; loadTicks++; }
    String status() {
        return "NPC runtime ready | active=" + service.index.size() + " | viewers=" + service.views.viewers.size() + " | visible=" + service.views.visible()
                + " | skin-reads=" + service.reads.skinReads() + " | accepted=" + service.views.accepted + " | rejected=" + service.views.rejected
                + " | routes=" + service.routes.order.size() + " | routes-sleeping=" + service.routes.order.sleeping() + " | routes-waiting=" + service.routes.order.waiting()
                + " | presentation=" + service.pressure.describe(service.tickNumber) + " | navigation-pressure=" + service.routes.pressure.describe() + " | path-jobs=" + service.navigation.outstandingJobs() + " | route-reads=" + service.reads.routeReads() + " | recordings=" + service.recordings.playing()
                + " | mounted=" + service.mountRuntime.mountedCount() + " | mount-problems=" + service.mountRuntime.problemCount() + " | riders=" + service.riders.count()
                + " | chunks=" + service.host.chunkDelivery() + (service.failures.last() == null ? "" : " | last-error=" + service.failures.last());
    }
    /** Counts for the metrics roll; the load since the last roll starts again. */
    RuntimeGauges gauges() {
        int visible = service.views.visible();
        List<RuntimeGauges.NpcPlace> places = new ArrayList<>(service.index.size());
        int live = 0;
        for (PersistedNpcService<P, I>.Entry entry : service.entries.values()) {
            if (entry.value == null) continue;
            VirtualNpcState.Snapshot state = entry.value.state.snapshot();
            if (!state.active()) continue;
            live++;
            ChunkPosition chunk = state.position().chunk();
            places.add(new RuntimeGauges.NpcPlace(state.npcId(), chunk.world(), chunk.x(), chunk.z()));
        }
        int navigationLoad = loadTicks == 0 ? 0 : (int) (navigationUse / loadTicks), presentationLoad = loadTicks == 0 ? 0 : (int) (packetUse / loadTicks);
        navigationUse = 0; packetUse = 0; loadTicks = 0;
        return new RuntimeGauges("single owner thread", service.runtime.npcs().snapshot().size(), live, Math.max(0, service.entries.size() - live), service.routes.order.sleeping(),
                service.views.viewers.size(), visible, service.routes.order.size(), service.routes.order.waiting(), service.navigation.outstandingJobs(), service.routes.pressure.pressured(),
                service.pressure.strained(service.tickNumber), service.pressure.deferred(), navigationLoad, presentationLoad, places);
    }
    String inspect(String id) {
        NpcDefinition definition = service.runtime.npcs().snapshot().get(id);
        if (definition == null) return "Runtime " + id + ": unknown";
        if (!DefinitionSpec.spawned(definition)) return "Runtime " + id + ": despawned; /animus spawn " + id + " shows it";
        PersistedNpcService<P, I>.Entry entry = service.entries.get(id);
        if (entry == null) return "Runtime " + id + ": spawned, waiting to become active (at most " + PersistedNpcService.MAX_ACTIVE + " active)";
        String state = entry.value == null ? (entry.task == null ? "unavailable" : "loading skin")
                : entry.value.state.snapshot().active() ? "active" : entry.value.state.snapshot().health() == 0 ? "dead" : "spawned, not active";
        int visible = service.views.showing.count(definition.uuid());
        return "Runtime " + id + ": " + state + "; uuid=" + definition.uuid() + "; revision="
                + (entry.value == null ? definition.revision() : entry.value.spec.definition().revision()) + "; viewers=" + visible
                + "; world-registered=" + (service.host.registeredEntity(entry.value == null ? definition.uuid() : entry.value.spec.definition().uuid()) ? 1 : 0)
                + (entry.value == null ? "" : "; health=" + entry.value.state.snapshot().health())
                + (entry.value == null ? "" : "; position=" + entry.value.state.snapshot().position().point() + "; navigation=" + entry.value.navigationStatus)
                + (entry.value == null || entry.value.routeError == null ? "" : "; route-error=" + entry.value.routeError)
                + (entry.value == null ? "" : RecordingPlayer.status(entry.value))
                + (entry.value == null ? "" : appearance(entry.value))
                + (entry.value == null ? "" : rigStatus(entry.value))
                + (entry.value == null ? "" : service.mountRuntime.summary(entry.value))
                + (entry.error == null ? "" : "; last-valid-retained=" + (entry.value != null) + "; " + entry.error);
    }
    private static String rigStatus(PersistedNpcService<?, ?>.Value value) {
        if (value.rigId == null) return "";
        return value.rig != null ? "; " + value.rig.status() : "; rig=" + value.rigId + " unavailable: " + value.rigProblem;
    }
    private String appearance(PersistedNpcService<P, I>.Value value) { return NpcPresentation.describe(value.spec, value.equipment.size(), service.packets.presentationAvailable()); }
}
