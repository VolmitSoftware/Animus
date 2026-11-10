package art.arcane.animus.core.runtime;

import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPCs with animus:look-close turning toward the nearest player in range: a bounded slice per tick, each NPC at most
 * every few ticks. Owner thread only.
 */
final class LookTurns<P, I> {
    /** Players a look-close NPC considers per look, as on Folia. */
    private static final int LOOK_CANDIDATES = 32;
    /** NPCs scanned for a look-close NPC target: a few nearby chunks and candidates at most. */
    private static final SpatialNpcIndex.Budget NPC_LOOK_BUDGET = new SpatialNpcIndex.Budget(16, 32);
    private final PersistedNpcService<P, I> service;
    private final Deque<PersistedNpcService<P, I>.Value> order = new ArrayDeque<>();

    LookTurns(PersistedNpcService<P, I> service) { this.service = service; }

    void add(PersistedNpcService<P, I>.Value value) { order.addLast(value); }
    void remove(PersistedNpcService<P, I>.Value value) { order.remove(value); }
    void clear() { order.clear(); }

    void tick() {
        if (order.isEmpty() || !service.pressure.allows(PresentationPressure.Tier.COSMETIC, service.tickNumber)) return;
        for (int i = 0, count = Math.min(service.settings.lookUpdatesPerTick(), order.size()); i < count && service.available(); i++) {
            PersistedNpcService<P, I>.Value value = order.removeFirst(); order.addLast(value);
            if (service.tickNumber < value.nextLook) continue;
            value.nextLook = service.tickNumber + LookClose.INTERVAL_TICKS;
            try { lookClose(value); }
            catch (RuntimeException failure) { service.quarantine(value, "look-close", failure); }
        }
    }
    /** One look-close turn of one NPC. A per-player NPC that stops looking at its viewers shows them its own pose again. */
    private void lookClose(PersistedNpcService<P, I>.Value value) {
        boolean viewers = look(value);
        if (viewers != value.lookingAtViewers || viewers) value.poseRevision++;
        value.lookingAtViewers = viewers;
    }
    /** True when a per-player NPC has viewers in range to look at. */
    private boolean look(PersistedNpcService<P, I>.Value value) {
        if (!service.valueCurrent(value) || !value.state.snapshot().active()) return false;
        LookClose look = value.lookClose;
        if (service.mountRuntime.vehicleOf(value.spec.definition().id()) != null || service.riders.riderOf(value.spec.definition().uuid()) != null) return false;
        // A paused route stands still, so the NPC looks around as an idle one does.
        if (!look.whileNavigating() && !value.pauseClock.paused(service.tickNumber)
                && (value.routeTask != null || (value.route != null && (value.route.plan != null || value.route.execution != null)))) return false;
        // A playing recording faces where the recorder looked.
        if (value.recording != null) return false;
        WorldPosition at = value.state.snapshot().position();
        Vec3 eye = LookClose.eye(at.point(), PersistedNpcService.bodyHeight(value));
        List<LookClose.Candidate<P>> candidates = new ArrayList<>();
        // Players near the NPC from the chunk index, not every viewer; only admitted viewers are candidates, as before.
        for (P player : service.playersNear(new WorldPosition(at.world(), eye), look.range() + 2, LOOK_CANDIDATES)) {
            ViewerPresenter<P, I>.Viewer viewer = service.views.viewers.get(service.host.id(player));
            if (viewer == null || viewer.failed || !viewer.world.equals(at.world()) || service.host.dead(viewer.player)) continue;
            if (!look.admits((String node) -> service.host.permitted(viewer.player, node))) continue;
            ViewerPresenter<P, I>.Presented body = viewer.visible.get(value.spec.definition().uuid());
            candidates.add(new LookClose.Candidate<>(viewer.player, service.host.eye(viewer.player), body != null && body.value == value && body.bound && !body.retired));
        }
        List<LookClose.Candidate<P>> ranked = look.ranked(eye, candidates);
        if (look.perPlayer() && !ranked.isEmpty()) return true;
        boolean looked = false, due = look.switchTicks() > 0 && service.tickNumber >= value.nextSwitch;
        Optional<LookClose.Candidate<P>> chosen = look.choose(ranked, value.lookingAt, due, ThreadLocalRandom.current());
        List<LookClose.Candidate<P>> order = new ArrayList<>(ranked);
        chosen.ifPresent((LookClose.Candidate<P> first) -> { order.remove(first); order.addFirst(first); });
        value.lookingAt = null;
        for (LookClose.Candidate<P> candidate : order) {
            double distance = candidate.eye().distance(eye);
            if (look.lineOfSight() && distance > .000001
                    && !service.host.clearSight(candidate.player(), candidate.eye(), eye.subtract(candidate.eye()).normalized(), Math.max(0, distance - .0001))) continue;
            LookClose.facing(eye, candidate.eye()).ifPresent((LookClose.Facing facing) -> turn(value, look, facing));
            value.lookingAt = candidate.player();
            if (due) value.nextSwitch = service.tickNumber + look.switchTicks();
            looked = true;
            break;
        }
        if (!looked && look.npcs()) looked = lookAtNpc(value, at, eye, look.range());
        if (!looked && look.randomLook().isPresent() && look.randomLook().get().active() && service.tickNumber >= value.nextRandomLook) {
            LookClose.RandomLook random = look.randomLook().get();
            turn(value, look, random.pick(ThreadLocalRandom.current()));
            value.nextRandomLook = service.tickNumber + random.delayTicks();
        }
        return false;
    }
    /** Turns the head, or head and body, toward a facing, no faster than the module's turn rate. */
    private static void turn(PersistedNpcService<?, ?>.Value value, LookClose look, LookClose.Facing target) {
        LookClose.Facing facing = look.turn(look.headOnly() ? value.headYaw : value.yaw, value.pitch, target, LookClose.INTERVAL_TICKS);
        if (look.headOnly()) PersistedNpcService.setHead(value, facing.yaw(), facing.pitch());
        else PersistedNpcService.setPose(value, facing.yaw(), facing.pitch(), value.onGround);
    }
    /** Turns toward the nearest other active NPC in range; false when there is none. */
    private boolean lookAtNpc(PersistedNpcService<P, I>.Value value, WorldPosition at, Vec3 eye, double range) {
        UUID self = value.spec.definition().uuid();
        Vec3 nearest = null;
        double best = range;
        for (VirtualNpcState.Snapshot other : service.scanNpcs(at, range, null, NPC_LOOK_BUDGET).candidates()) {
            if (other.npcId().equals(self) || !other.active() || !other.position().world().equals(at.world())) continue;
            PersistedNpcService<P, I>.Entry entry = service.byUuid.get(other.npcId());
            double height = entry == null || entry.value == null ? 1.8 : PersistedNpcService.bodyHeight(entry.value);
            Vec3 target = LookClose.eye(other.position().point(), height);
            double distance = target.distance(eye);
            if (distance <= best) { best = distance; nearest = target; }
        }
        if (nearest == null) return false;
        LookClose.facing(eye, nearest).ifPresent((LookClose.Facing facing) -> turn(value, value.lookClose, facing));
        return true;
    }
}
