package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.behavior.trigger.ProximityTracker;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Owner-thread translation of NPC runtime facts into trigger events. Holds no Bukkit objects. */
final class TriggerBridge {
    private final TriggerDispatcher dispatcher;
    private final ProximityTracker proximity;

    TriggerBridge(TriggerDispatcher dispatcher, SpatialNpcIndex index) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        proximity = new ProximityTracker(dispatcher.settings().proximityLimits(), index);
    }
    void activated(UUID npc) { dispatcher.activated(npc); }
    void removed(UUID npc) { dispatcher.deactivated(npc); }
    void interaction(UUID npc, UUID viewer, TriggerEvent.Action action, boolean preview) {
        if (!preview) dispatcher.publish(new TriggerEvent.Interaction(npc, viewer, action));
    }
    void waypoint(UUID npc, int waypoint) { dispatcher.publish(new TriggerEvent.Waypoint(npc, waypoint)); }
    /** before is null when the NPC was not indexed, such as a change after its death. */
    void changed(VirtualNpcState.Snapshot before, VirtualNpcState.Snapshot after) {
        if (before == null || after.health() >= before.health()) return;
        UUID npc = after.npcId();
        dispatcher.publish(new TriggerEvent.Damage(npc, before.health() - after.health()));
        if (after.health() == 0) { dispatcher.publish(new TriggerEvent.Death(npc)); dispatcher.dead(npc); }
    }
    void proximity(Collection<UUID> viewers, Function<UUID, Optional<WorldPosition>> positions) {
        proximity.tick(dispatcher.proximityPlan(), viewers, positions, dispatcher::publish);
    }
    void forget(UUID viewer) { proximity.forget(viewer); }
    void failed(String reason) { dispatcher.unavailable(reason); }
    long proximityScans() { return proximity.scans(); }
}
