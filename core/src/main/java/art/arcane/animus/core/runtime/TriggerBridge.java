package art.arcane.animus.core.runtime;

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
public final class TriggerBridge {
    private final TriggerDispatcher dispatcher;
    private final ProximityTracker proximity;

    public TriggerBridge(TriggerDispatcher dispatcher, SpatialNpcIndex index) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        proximity = new ProximityTracker(dispatcher::proximityLimits, index, dispatcher::hides);
    }
    public void activated(UUID npc) { dispatcher.activated(npc); }
    public void removed(UUID npc) { dispatcher.deactivated(npc); }
    /** Publishes an accepted click; true when a behavior consumes it, so default handling must skip it. Previews never fire or consume. */
    public boolean interaction(UUID npc, UUID viewer, TriggerEvent.Action action, boolean sneaking, TriggerEvent.Hand hand, boolean preview) {
        return !preview && dispatcher.click(new TriggerEvent.Interaction(npc, viewer, action, sneaking, hand));
    }
    public void waypoint(UUID npc, int waypoint) { dispatcher.publish(new TriggerEvent.Waypoint(npc, waypoint)); }
    public void animationFinished(UUID npc, String animation, int layer) { dispatcher.publish(new TriggerEvent.AnimationFinished(npc, animation, layer)); }
    /** before is null when the NPC was not indexed, such as a change after its death. */
    public void changed(VirtualNpcState.Snapshot before, VirtualNpcState.Snapshot after) {
        if (before == null || after.health() >= before.health()) return;
        UUID npc = after.npcId();
        dispatcher.publish(new TriggerEvent.Damage(npc, before.health() - after.health()));
        if (after.health() == 0) { dispatcher.publish(new TriggerEvent.Death(npc)); dispatcher.dead(npc); }
    }
    public void proximity(Collection<UUID> viewers, Function<UUID, Optional<WorldPosition>> positions) {
        proximity.tick(dispatcher.proximityPlan(), viewers, positions, dispatcher::publish);
    }
    public void forget(UUID viewer) { proximity.forget(viewer); }
    public void failed(String reason) { dispatcher.unavailable(reason); }
    public long proximityScans() { return proximity.scans(); }
    /** Queued behavior runs waited for their start budget within the last second. */
    public boolean backlogged() { return dispatcher.backlogged(); }
}
