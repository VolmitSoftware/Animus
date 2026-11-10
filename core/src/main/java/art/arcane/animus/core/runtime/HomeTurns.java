package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.HomeSettings;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * NPCs with animus:home going back once they have stood idle away from it long enough: a bounded slice per tick, each
 * NPC checked about once a second. Owner thread only.
 */
final class HomeTurns<P, I> {
    /** NPCs checked per tick at most. */
    private static final int CHECKS_PER_TICK = 64;
    /** The walking speed home when the NPC's movement sets none, in blocks per second. */
    private static final double WALK_SPEED = 2;
    private final PersistedNpcService<P, I> service;
    private final Deque<PersistedNpcService<P, I>.Value> order = new ArrayDeque<>();

    HomeTurns(PersistedNpcService<P, I> service) { this.service = service; }

    void add(PersistedNpcService<P, I>.Value value) { value.idleSince = service.tickNumber; order.addLast(value); }
    void remove(PersistedNpcService<P, I>.Value value) { order.remove(value); }
    void clear() { order.clear(); }

    void tick() {
        for (int i = 0, count = Math.min(CHECKS_PER_TICK, order.size()); i < count && service.available(); i++) {
            PersistedNpcService<P, I>.Value value = order.removeFirst(); order.addLast(value);
            if (service.tickNumber < value.nextHome) continue;
            value.nextHome = service.tickNumber + HomeSettings.CHECK_TICKS;
            try { check(value); }
            catch (RuntimeException failure) { service.quarantine(value, "home", failure); }
        }
    }
    private void check(PersistedNpcService<P, I>.Value value) {
        HomeSettings home = value.home;
        long tick = service.tickNumber;
        String id = value.spec.definition().id();
        if (!service.valueCurrent(value) || !value.state.snapshot().active() || busy(value, id)) { value.idleSince = tick; return; }
        WorldPosition at = value.state.snapshot().position();
        if (!at.world().equals(value.spec.position().world()) || at.point().distance(home.position()) < HomeSettings.AT_HOME) { value.idleSince = tick; return; }
        if (!home.due(at.point(), tick - value.idleSince)) return;
        value.idleSince = tick;
        WorldPosition destination = new WorldPosition(at.world(), home.position());
        if (home.strategy() == HomeSettings.Return.WALK) {
            try { service.navigate(id, destination, MovementProfile.Mode.WALK, value.movement.routeSpeed(false, WALK_SPEED)); value.homeWarned = false; }
            catch (IllegalArgumentException | IllegalStateException refused) { failed(value, "NPC " + id + " could not walk home: " + refused.getMessage()); }
            return;
        }
        if (!service.relocate(id, destination)) { failed(value, "NPC " + id + " could not teleport home: the place is not loaded or not clear"); return; }
        value.homeWarned = false;
        PersistedNpcService.setPose(value, home.yaw() == null ? value.yaw : home.yaw(), home.pitch() == null ? value.pitch : home.pitch(), true);
    }
    /** Warns once for a run of failed returns home; a home that stays out of reach retries quietly. */
    private void failed(PersistedNpcService<P, I>.Value value, String warning) {
        if (value.homeWarned) return;
        value.homeWarned = true;
        service.host.warn(warning);
    }
    /** Walking, following a route or a recording, guarding, fighting, riding or carrying a rider: the NPC is not idle. */
    private boolean busy(PersistedNpcService<P, I>.Value value, String id) {
        return value.guards || service.tickNumber - value.foughtAt < HomeSettings.FIGHT_TICKS || value.route != null || value.routeTask != null || value.recording != null || (value.waypoints != null && !value.waypoints.complete())
                || service.mountRuntime.vehicleOf(id) != null || service.riders.riderOf(value.spec.definition().uuid()) != null;
    }
}
