package art.arcane.animus.core.runtime;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigCatalog;
import art.arcane.animus.core.rig.RigPlayback;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rig playback of the active NPCs whose definition names a rig: binding, catalog reloads, clip endings within the
 * tick's simulation turns, and a bounded number of new segments planned each tick. Owner thread only.
 */
final class RigTurns<P, I> {
    private final PersistedNpcService<P, I> service;
    /** Active NPCs whose definition names a rig; planning takes turns through it. */
    private final Deque<PersistedNpcService<P, I>.Value> order = new ArrayDeque<>();
    /** The same NPCs in their own rotation for playback upkeep, which {@code [npcs] simulationPerTick} bounds. */
    private final Deque<PersistedNpcService<P, I>.Value> ticks = new ArrayDeque<>();

    RigTurns(PersistedNpcService<P, I> service) { this.service = service; }

    void add(PersistedNpcService<P, I>.Value value) { bind(value); order.addLast(value); ticks.addLast(value); }
    void remove(PersistedNpcService<P, I>.Value value) { order.remove(value); ticks.remove(value); }
    void clear() { order.clear(); ticks.clear(); }
    /** Rigs were switched on or off in config.toml: every rig binds again on its next turn. */
    void toggled() { for (PersistedNpcService<P, I>.Value value : order) value.rigGeneration = Long.MIN_VALUE; }

    void play(PersistedNpcService<P, I>.Value value, String animation, double speed, Optional<String> loop, int layer) {
        Optional<AnimationDocument.Loop> mode = loop.map((String text) -> AnimationDocument.Loop.parse(text)
                .orElseThrow(() -> new IllegalArgumentException("loop must be once, loop, pingpong or hold")));
        value.rig.play(animation, speed, mode, layer, service.tickNumber);
    }
    void bind(PersistedNpcService<P, I>.Value value) {
        value.rig = null; value.rigProblem = null;
        if (value.rigId == null) return;
        if (!service.rigSettings.enabled()) { value.rigProblem = "rigs are disabled in config.toml"; return; }
        if (!service.packets.bonesAvailable()) { value.rigProblem = "display bones are unavailable on this server build"; return; }
        RigCatalog catalog = service.runtime.rigs().catalog();
        value.rigGeneration = catalog.generation();
        List<String> problems = new ArrayList<>();
        value.rig = NpcRig.bind(value.rigId, catalog, value.seat, value.attributes.scale(), (NpcItem item, String path, List<String> errors) -> service.host.item(item, path, errors), service.host.emptyItem(), problems).orElse(null);
        if (value.rig == null) value.rigProblem = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
    }
    private static boolean walking(PersistedNpcService<?, ?>.Value value) {
        return value.route != null && value.route.execution != null && !value.route.execution.finished();
    }
    /** Clip endings and walking state for every rigged NPC, then a bounded number of new segments, taking turns. */
    void tick() {
        if (order.isEmpty() || !service.runtime.isRunning()) return;
        RigCatalog catalog = service.runtime.rigs().catalog();
        boolean sampling = service.metrics.sampling();
        TickBudget budget = service.budget;
        int count = ticks.size(), turns = Math.min(count, budget.simulationLeft);
        budget.simulationLeft -= turns;
        if (turns < count) service.pressure.busy(service.tickNumber);
        for (int i = 0; i < turns; i++) {
            PersistedNpcService<P, I>.Value value = ticks.removeFirst(); ticks.addLast(value);
            try { tick(value, catalog, sampling); }
            catch (RuntimeException failure) { service.quarantine(value, "rig", failure); }
        }
        // Sampling new segments is cosmetic; clip endings above still fire their triggers under load.
        if (!service.pressure.allows(PresentationPressure.Tier.COSMETIC, service.tickNumber)) return;
        long nanos = System.nanoTime();
        int plans = 0;
        // A rig quarantined above has left both queues, so this pass counts the queue again.
        for (int i = 0, planned = order.size(); i < planned && plans < service.rigSettings.plansPerTick(); i++) {
            PersistedNpcService<P, I>.Value value = order.removeFirst(); order.addLast(value);
            NpcRig<I> rig = value.rig;
            if (rig == null || !value.state.snapshot().active() || !rig.due(service.tickNumber, value.yaw)) continue;
            try { rig.plan(service.tickNumber, value.yaw, service.rigSettings.sampleTicks(), nanos); }
            catch (RuntimeException failure) { service.quarantine(value, "rig", failure); }
            plans++;
        }
    }
    private void tick(PersistedNpcService<P, I>.Value value, RigCatalog catalog, boolean sampling) {
        if (!service.valueCurrent(value)) return;
        long tickNumber = service.tickNumber;
        if (value.rigGeneration != catalog.generation()) {
            boolean had = value.rig != null, toggled = value.rigGeneration == Long.MIN_VALUE;
            value.rigGeneration = catalog.generation();
            // A hotload that switched rigs on or off binds again; a catalog reload refreshes a rig in place when it can.
            if (toggled || value.rig == null || !value.rig.refresh(catalog, tickNumber)) {
                bind(value);
                if (had || value.rig != null) service.views.represent(value);
            }
        }
        if (value.rig == null || !value.state.snapshot().active()) return;
        AnimusMetrics metrics = service.metrics;
        long started = sampling ? metrics.start() : 0;
        value.rig.equip(value.equipment, service.host::copy);
        NpcRig.Step step = value.rig.tick(tickNumber, walking(value));
        UUID npc = value.spec.definition().uuid();
        NpcRig.Events events = service.animationEvents;
        if (events != null) for (RigPlayback.Fired fired : step.events()) events.fired(npc, fired);
        for (RigPlayback.Finished done : step.finished())
            if (service.triggers != null) service.triggers.animationFinished(npc, done.animation(), done.layer());
        if (sampling) metrics.npcCost(metrics.npc(value.spec.definition().uuid()), metrics.start() - started);
    }
}
