package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Viewer-driven enter/leave detection on the platform owner thread. Work follows viewers through the
 * spatial index, never the NPC population: an empty plan or no viewers does no scan at all, and an NPC
 * with no viewer in range is never visited. Not thread-safe.
 */
public final class ProximityTracker {
    /** Leaving needs this much extra distance so a viewer standing on the radius does not refire. */
    private static final double LEAVE_MARGIN = 0.5;
    private static final int SCAN_CELLS = 256;

    public record Limits(int intervalTicks, int viewersPerTick, int candidates, int trackedPerViewer) {}
    public record Binding(String key, TriggerBinding.Phase phase, double radius) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(phase); }
    }
    public record Plan(Map<UUID, List<Binding>> npcs, double maximumRadius) {
        public static final Plan EMPTY = new Plan(Map.of(), 0);
        public Plan { npcs = Map.copyOf(npcs); }
    }
    private record Inside(UUID npc, String key) {}
    private static final class ViewerState {
        final Set<Inside> inside = new HashSet<>();
        SpatialNpcIndex.Cursor cursor;
    }

    private final Limits limits;
    private final SpatialNpcIndex index;
    private final Map<UUID, ViewerState> states = new HashMap<>();
    private final ArrayDeque<UUID> pending = new ArrayDeque<>();
    private long tick, nextPass, scans, candidates, overflow;

    public ProximityTracker(Limits limits, SpatialNpcIndex index) {
        this.limits = Objects.requireNonNull(limits); this.index = Objects.requireNonNull(index);
    }

    public void tick(Plan plan, Collection<UUID> viewers, Function<UUID, Optional<WorldPosition>> positions, Consumer<TriggerEvent> sink) {
        tick++;
        if (plan.npcs().isEmpty()) {
            if (!states.isEmpty()) states.clear();
            if (!pending.isEmpty()) pending.clear();
            return;
        }
        if (pending.isEmpty()) {
            if (tick < nextPass) return;
            nextPass = tick + limits.intervalTicks();
            pending.addAll(viewers);
        }
        for (int count = 0; count < limits.viewersPerTick() && !pending.isEmpty(); count++) {
            UUID viewer = pending.pollFirst();
            positions.apply(viewer).ifPresent(position -> check(viewer, position, plan, sink));
        }
    }

    private void check(UUID viewer, WorldPosition position, Plan plan, Consumer<TriggerEvent> sink) {
        ViewerState state = states.computeIfAbsent(viewer, ignored -> new ViewerState());
        Iterator<Inside> inside = state.inside.iterator();
        while (inside.hasNext()) {
            Inside entry = inside.next();
            Binding binding = binding(plan, entry);
            Optional<VirtualNpcState.Snapshot> npc = index.find(entry.npc());
            if (binding == null || npc.isEmpty()) { inside.remove(); continue; }
            WorldPosition at = npc.get().position();
            if (!at.world().equals(position.world()) || at.point().distance(position.point()) > binding.radius() + LEAVE_MARGIN) {
                inside.remove();
                if (binding.phase() == TriggerBinding.Phase.LEAVE) sink.accept(new TriggerEvent.Proximity(entry.npc(), viewer, binding.key()));
            }
        }
        SpatialNpcIndex.Scan scan = index.scan(new SpatialNpcIndex.Query(position, plan.maximumRadius()), state.cursor,
                new SpatialNpcIndex.Budget(SCAN_CELLS, limits.candidates()), chunk -> true);
        scans++; candidates += scan.candidatesVisited(); state.cursor = scan.next().orElse(null);
        Vec3 point = position.point();
        for (VirtualNpcState.Snapshot candidate : scan.candidates()) {
            List<Binding> bindings = plan.npcs().get(candidate.npcId());
            if (bindings == null) continue;
            double distance = point.distance(candidate.position().point());
            for (Binding binding : bindings) {
                Inside entry = new Inside(candidate.npcId(), binding.key());
                if (distance > binding.radius() || state.inside.contains(entry)) continue;
                if (state.inside.size() >= limits.trackedPerViewer()) { overflow++; continue; }
                state.inside.add(entry);
                if (binding.phase() == TriggerBinding.Phase.ENTER) sink.accept(new TriggerEvent.Proximity(candidate.npcId(), viewer, binding.key()));
            }
        }
    }
    private static Binding binding(Plan plan, Inside entry) {
        List<Binding> bindings = plan.npcs().get(entry.npc());
        if (bindings == null) return null;
        for (Binding binding : bindings) if (binding.key().equals(entry.key())) return binding;
        return null;
    }

    /** Disconnects are silent: leave triggers do not run for a player who is no longer online. */
    public void forget(UUID viewer) { states.remove(viewer); pending.remove(viewer); }
    public long scans() { return scans; }
    public long candidates() { return candidates; }
    public long overflow() { return overflow; }
    public int tracked() { int total = 0; for (ViewerState state : states.values()) total += state.inside.size(); return total; }
}
