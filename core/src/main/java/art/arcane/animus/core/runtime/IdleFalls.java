package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.FallTracker;
import art.arcane.animus.core.navigation.IdleFall;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Falls of walking NPCs that stand in the air: idle NPCs whose footing changed, and a route's body before its leg
 * plans. Each falls one step per turn and reports a landing that hurts. Owner thread only.
 */
final class IdleFalls<P, I> {
    /** Terrain cells idle falls may sample in one tick. */
    private static final int SUPPORT_CELLS = 4096;
    private final PersistedNpcService<P, I> service;
    /** Idle NPCs whose footing changed, and NPCs still falling; each is queued at most once. */
    private final Deque<PersistedNpcService<P, I>.Value> order = new ArrayDeque<>();

    IdleFalls(PersistedNpcService<P, I> service) { this.service = service; }

    void clear() { order.clear(); }

    /**
     * A block changed in a loaded chunk of this world, or is about to change in this tick. Idle walking NPCs over it check
     * their footing on a later tick and fall when nothing holds them up. Reads at most four index buckets.
     */
    void terrainChanged(String world, BlockPos at) {
        for (UUID npc : IdleFall.standingOn(service.index, world, at)) {
            PersistedNpcService<P, I>.Entry entry = service.byUuid.get(npc); PersistedNpcService<P, I>.Value value = entry == null ? null : entry.value;
            if (value == null || value.supportQueued) continue;
            value.supportQueued = true; order.addLast(value);
        }
    }
    /**
     * A walking body that stands in the air (its ground changed, or it was imported onto other terrain) falls one step per
     * turn before it plans a leg, so the leg starts on the ground instead of failing from the air. True while it falls.
     */
    boolean beforeLeg(RouteNavigator<P, I>.Route route) {
        PersistedNpcService<P, I>.Value value = route.value;
        if (route.mode != MovementProfile.Mode.WALK || !value.falls || service.mountRuntime.vehicleOf(value.spec.definition().id()) != null
                || service.riders.riderOf(value.spec.definition().uuid()) != null) return false;
        int wanted = IdleFall.cells(PersistedNpcService.moveWidth(value), PersistedNpcService.moveHeight(value));
        if (!service.budget.cells(wanted, service.navigationSettings)) return false;
        IdleFall.Step step = fall(value, value.state.snapshot().position());
        service.budget.motionCells -= step.sampled();
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling) service.routes.status(route, "falling", "to the ground before the leg starts");
        return falling;
    }
    /** Idle falls, taking turns within the tick's simulation turns and a bound on terrain cells. */
    void tick() {
        TickBudget budget = service.budget;
        int cells = SUPPORT_CELLS, waiting = order.size(), visited = 0;
        for (; visited < waiting && cells > 0 && budget.simulationLeft > 0 && service.available(); visited++) {
            PersistedNpcService<P, I>.Value value = order.removeFirst(); budget.simulationLeft--;
            try { cells -= support(value); }
            catch (RuntimeException failure) { value.supportQueued = false; service.quarantine(value, "idle fall", failure); }
        }
        if (visited < waiting) service.pressure.busy(service.tickNumber);
    }
    /** One idle-fall step of one NPC; it stays queued while it falls. Returns the terrain cells sampled. */
    private int support(PersistedNpcService<P, I>.Value value) {
        WorldPosition at = value.state.snapshot().position();
        if (!falls(value) || !service.host.worldLoaded(at.world())) { value.supportQueued = false; value.fallSpeed = 0; value.fall.reset(); return 0; }
        IdleFall.Step step = fall(value, at);
        if (step.outcome() == IdleFall.Outcome.FALLING) order.addLast(value);
        else value.supportQueued = false;
        return step.sampled();
    }
    private boolean falls(PersistedNpcService<P, I>.Value value) {
        return service.valueCurrent(value) && value.state.snapshot().active() && value.route == null && value.routeTask == null
                && value.falls && service.mountRuntime.vehicleOf(value.spec.definition().id()) == null
                && service.riders.riderOf(value.spec.definition().uuid()) == null;
    }
    /** One fall step from {@code at}: the body moves when it falls or lands, and a landing that hurts is reported. */
    private IdleFall.Step fall(PersistedNpcService<P, I>.Value value, WorldPosition at) {
        double width = PersistedNpcService.moveWidth(value), height = PersistedNpcService.moveHeight(value);
        boolean swims = BodyMobility.forBody(value.spec.type(), width, height).supports(MovementProfile.Mode.SWIM);
        IdleFall.Step step = IdleFall.step(at.point(), value.fallSpeed, value.movement.gravity(), width, height, swims, service.host.collision(at.world()));
        value.fallSpeed = step.speed();
        landed(value, at, step);
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
            value.state.move(new WorldPosition(at.world(), step.to())); service.index.upsert(value.state.snapshot());
            value.poseRevision++; PersistedNpcService.setPose(value, value.yaw, value.pitch, !falling);
        }
        return step;
    }
    /** Measures the idle fall and reports a landing that hurts. */
    private void landed(PersistedNpcService<P, I>.Value value, WorldPosition at, IdleFall.Step step) {
        if (step.outcome() == IdleFall.Outcome.HELD) { value.fall.reset(); return; }
        OptionalDouble distance = value.fall.step(at.point().y(), step.to().y(), step.outcome() != IdleFall.Outcome.FALLING, step.caught());
        if (distance.isEmpty()) return;
        double damage = FallTracker.damage(distance.getAsDouble(), value.attributes);
        if (damage > 0) service.landings.accept(value.spec.definition().uuid(), damage);
    }
}
