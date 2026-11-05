package art.arcane.animus.core.runtime;

import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.navigation.BodyTerrain;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.TerrainReader;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The NPC side of a combat world on a single-owner runtime: movement, facing, knockback, healing and virtual damage
 * go through the persisted runtime and its live collision reads. A platform adds the real entities.
 */
public abstract class RuntimeCombatWorld<P, I> implements CombatWorld {
    private static final int MOVE_HORIZONTAL = 30;
    private static final int MOVE_VERTICAL = 7;
    private static final int PROBE_CELLS = 256;
    private static final double MAXIMUM_KNOCKBACK = 1.5;
    private static final int[] TELEPORT_LIFTS = {0, 1, -1, 2, -2};
    private static final double SETTLE_CLIMB = 1 - 0.0001;
    protected final PersistedNpcService<P, I> living;
    private final TerrainReader host;
    /** The last refusal per NPC, cleared by the next accepted move; one entry per combat NPC at most. */
    private final Map<UUID, String> refusals = new HashMap<>();

    protected RuntimeCombatWorld(PersistedNpcService<P, I> living, TerrainReader host) {
        this.living = Objects.requireNonNull(living);
        this.host = Objects.requireNonNull(host);
    }

    @Override public Optional<VirtualNpcState.Snapshot> npc(UUID npc) { return living.combatant(npc).map(NpcRuntime.ActiveNpc::state); }
    @Override public String npcName(UUID npc) { return living.combatant(npc).map(NpcRuntime.ActiveNpc::name).orElse(""); }
    @Override public SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        return living.scanNpcs(center, radius, cursor, budget);
    }

    @Override public MoveStatus move(UUID npc, WorldPosition goal, double speed) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        if (active.isEmpty()) return MoveStatus.REJECTED;
        Vec3 origin = active.get().state().position().point();
        Vec3 delta = goal.point().subtract(origin);
        Vec3 clamped = new Vec3(origin.x() + clamp(delta.x(), MOVE_HORIZONTAL), Math.floor(origin.y() + clamp(delta.y(), MOVE_VERTICAL)), origin.z() + clamp(delta.z(), MOVE_HORIZONTAL));
        try {
            living.navigate(active.get().id(), new WorldPosition(goal.world(), clamped), MovementProfile.Mode.WALK, speed);
            refusals.remove(npc);
            return MoveStatus.ACCEPTED;
        } catch (IllegalArgumentException refused) {
            refusals.put(npc, "refused: " + refused.getMessage());
            return MoveStatus.REJECTED;
        } catch (IllegalStateException busy) {
            refusals.put(npc, "busy: " + busy.getMessage());
            return MoveStatus.BUSY;
        }
    }
    @Override public String movement(UUID npc) {
        String refused = refusals.get(npc);
        if (refused != null) return refused;
        return living.combatant(npc).map((NpcRuntime.ActiveNpc active) -> living.navigationStatus(active.id())).orElse("inactive");
    }
    @Override public void stop(UUID npc) {
        living.combatant(npc).ifPresent((NpcRuntime.ActiveNpc active) -> {
            try { living.stopNavigation(active.id()); }
            catch (IllegalStateException inactive) { /* nothing is moving it */ }
        });
    }
    @Override public void release(UUID npc) {
        refusals.remove(npc);
        living.combatant(npc).ifPresent((NpcRuntime.ActiveNpc active) -> {
            try { living.resumePatrol(active.id()); }
            catch (IllegalArgumentException | IllegalStateException inactive) { /* no patrol to resume */ }
        });
    }
    /**
     * Places the NPC on clear, supported ground at the destination, trying the destination's block column a little up
     * and down, in any loaded world. Unlike a step this is not swept, so it can cover any distance.
     */
    @Override public boolean teleport(UUID npc, WorldPosition destination) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        if (active.isEmpty()) return false;
        Vec3 point = destination.point();
        Hitbox bounds = active.get().state().bounds();
        double width = PersistedNpcService.hullWidth(bounds.max().x() - bounds.min().x()), height = PersistedNpcService.hullHeight(bounds.max().y() - bounds.min().y());
        boolean readable = host.worldLoaded(destination.world());
        for (int lift : TELEPORT_LIFTS) {
            WorldPosition spot = new WorldPosition(destination.world(), new Vec3(point.x(), Math.floor(point.y()) + lift, point.z()));
            // A block whose top is below a full block (a path, farmland, a slab) holds the body at its own top.
            WorldPosition settled = readable ? settle(host.collision(destination.world()), width, height, spot).orElse(spot) : spot;
            if (living.relocate(active.get().id(), settled)) return true;
        }
        return false;
    }
    /**
     * The surface a body would stand on in the block named by the spot: a path, farmland, slab or full block top inside
     * {@code [y, y + 1)}. Empty when nothing there holds it or the terrain cannot be read.
     */
    static Optional<WorldPosition> settle(MotionValidator.Sampler terrain, double width, double height, WorldPosition spot) {
        Vec3 point = spot.point();
        BodyTerrain.Rest rest = new BodyTerrain(width, height, false, PROBE_CELLS, terrain).rest(point.x(), point.z(), Math.floor(point.y()), SETTLE_CLIMB, 0, false);
        if (rest.kind() != BodyTerrain.Kind.GROUND) return Optional.empty();
        return Optional.of(new WorldPosition(spot.world(), new Vec3(point.x(), rest.feet(), point.z())));
    }
    @Override public void face(UUID npc, Vec3 point) {
        living.combatant(npc).ifPresent((NpcRuntime.ActiveNpc active) -> {
            try { living.lookAt(active.id(), new WorldPosition(active.state().position().world(), point)); }
            catch (IllegalArgumentException | IllegalStateException ignored) { /* facing is cosmetic */ }
        });
    }
    @Override public double yaw(UUID npc) { return living.yaw(npc); }
    @Override public void knockback(UUID npc, Vec3 direction, double strength) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        double horizontal = Math.hypot(direction.x(), direction.z());
        if (active.isEmpty() || horizontal < .0001) return;
        double distance = Math.min(MAXIMUM_KNOCKBACK, strength);
        WorldPosition from = active.get().state().position();
        relocate(npc, new WorldPosition(from.world(), from.point().add(new Vec3(direction.x() / horizontal * distance, 0, direction.z() / horizontal * distance))));
    }
    /** Moves the NPC only onto loaded, clear and supported ground; anything else leaves it in place. */
    private boolean relocate(UUID npc, WorldPosition destination) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        if (active.isEmpty()) return false;
        Optional<PersistedNpcService.CombatBinding> binding = living.combat(active.get().id());
        if (binding.isEmpty() || !host.worldLoaded(destination.world()) || !destination.world().equals(active.get().state().position().world())) return false;
        Hitbox bounds = active.get().state().bounds();
        double width = bounds.max().x() - bounds.min().x();
        double height = bounds.max().y() - bounds.min().y();
        Vec3 from = active.get().state().position().point();
        MotionValidator.Result path = MotionValidator.check(from, destination.point(), width, height, MotionValidator.Medium.GROUND, true, PROBE_CELLS, host.collision(destination.world()));
        if (!path.clear()) return false;
        binding.get().changed().accept(binding.get().state().move(destination));
        return true;
    }
    @Override public void heal(UUID npc, double amount) {
        living.combatant(npc).flatMap((NpcRuntime.ActiveNpc active) -> living.combat(active.id())).ifPresent(
                (PersistedNpcService.CombatBinding binding) -> binding.changed().accept(binding.state().heal(amount)));
    }
    @Override public boolean revive(UUID npc, WorldPosition at) { return living.revive(npc, at); }
    @Override public DamageOutcome damageVirtual(UUID npc, art.arcane.animus.core.combat.TargetRef attacker, double amount) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        Optional<PersistedNpcService.CombatBinding> binding = active.flatMap((NpcRuntime.ActiveNpc value) -> living.combat(value.id()));
        if (binding.isEmpty()) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        double before = binding.get().state().snapshot().health();
        VirtualNpcState.Snapshot after = binding.get().state().damage(amount);
        binding.get().changed().accept(after);
        return new DamageOutcome(DamageStatus.APPLIED, before - after.health(), after.health() == 0);
    }

    /** Every chunk the straight line between the points crosses is loaded. */
    protected boolean loaded(String world, Vec3 from, Vec3 to) {
        for (int x = ((int) Math.floor(Math.min(from.x(), to.x()))) >> 4, lastX = ((int) Math.floor(Math.max(from.x(), to.x()))) >> 4; x <= lastX; x++)
            for (int z = ((int) Math.floor(Math.min(from.z(), to.z()))) >> 4, lastZ = ((int) Math.floor(Math.max(from.z(), to.z()))) >> 4; z <= lastZ; z++)
                if (!host.chunkLoaded(world, x, z)) return false;
        return true;
    }
    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }
}
