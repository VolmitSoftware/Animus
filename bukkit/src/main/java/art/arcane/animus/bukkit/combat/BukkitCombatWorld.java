package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Bukkit side of the combat engine, on the main thread. NPC bodies stay virtual: damage dealt by an NPC
 * uses a damage source with a location and no causing entity, and its arrows have no shooter.
 */
public final class BukkitCombatWorld implements CombatWorld {
    private static final int MOVE_HORIZONTAL = 30;
    private static final int MOVE_VERTICAL = 7;
    private static final int PROBE_CELLS = 256;
    private static final double MAXIMUM_KNOCKBACK = 1.5;
    private final Plugin plugin;
    private final PersistedNpcService<Player, ItemStack> living;
    private final NamespacedKey helperKey;
    private final Set<UUID> suppressedDrops = new HashSet<>();

    public BukkitCombatWorld(Plugin plugin, PersistedNpcService<Player, ItemStack> living) {
        this.plugin = plugin;
        this.living = living;
        helperKey = new NamespacedKey(plugin, "combat_helper");
    }

    /** Whether this entity is a combat helper launched by an NPC. */
    public boolean helper(Entity entity) {
        return entity.getPersistentDataContainer().has(helperKey, PersistentDataType.BYTE);
    }
    /** Whether a death in progress should drop nothing because an NPC that disallows victim drops caused it. */
    public boolean suppressesDrops(UUID entity) { return suppressedDrops.contains(entity); }

    @Override public Optional<VirtualNpcState.Snapshot> npc(UUID npc) { return living.combatant(npc).map(NpcRuntime.ActiveNpc::state); }
    @Override public String npcName(UUID npc) { return living.combatant(npc).map(NpcRuntime.ActiveNpc::name).orElse(""); }

    @Override public Optional<CombatTarget> real(TargetRef ref) {
        if (ref.kind() == TargetKind.NPC) return Optional.empty();
        return observe(plugin.getServer().getEntity(ref.id())).filter((CombatTarget target) -> target.ref().kind() == ref.kind());
    }

    /** A combat observation of a living player or mob, or empty when it cannot be a participant. */
    public Optional<CombatTarget> observe(Entity entity) {
        if (!(entity instanceof LivingEntity creature) || !creature.isValid() || creature.isDead() || creature.getHealth() <= 0 || helper(entity)) return Optional.empty();
        TargetKind kind;
        Set<TargetCategory> categories;
        String name;
        if (creature instanceof Player player) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return Optional.empty();
            kind = TargetKind.PLAYER;
            categories = Set.of(TargetCategory.PLAYERS);
            name = player.getName();
        } else if (creature instanceof Mob) {
            kind = TargetKind.MOB;
            categories = creature instanceof Enemy ? Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS) : Set.of(TargetCategory.MOBS, TargetCategory.ANIMALS);
            name = creature.getCustomName() == null ? "" : creature.getCustomName();
        } else return Optional.empty();
        BoundingBox box = creature.getBoundingBox();
        if (box.getWidthX() <= 0 || box.getHeight() <= 0 || box.getWidthZ() <= 0) return Optional.empty();
        Location at = creature.getLocation();
        return Optional.of(new CombatTarget(new TargetRef(kind, creature.getUniqueId()), creature.getType().getKey().toString(), name,
                new WorldPosition(at.getWorld().getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ())),
                new Hitbox(new Vec3(box.getMinX(), box.getMinY(), box.getMinZ()), new Vec3(box.getMaxX(), box.getMaxY(), box.getMaxZ())),
                creature.getHealth(), categories, "", Optional.empty()));
    }

    @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
        World world = world(center.world());
        if (world == null) return List.of();
        Vec3 point = center.point();
        BoundingBox area = new BoundingBox(point.x() - radius, point.y() - radius, point.z() - radius, point.x() + radius, point.y() + radius, point.z() + radius);
        List<CombatTarget> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(area, (Entity candidate) -> candidate instanceof LivingEntity)) {
            if (found.size() >= limit) break;
            Optional<CombatTarget> target = observe(entity);
            if (target.isPresent() && target.get().position().point().distance(point) <= radius) found.add(target.get());
        }
        return found;
    }
    @Override public SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        return living.scanNpcs(center, radius, cursor, budget);
    }
    @Override public Visibility lineOfSight(String worldId, Vec3 from, Vec3 to) {
        World world = world(worldId);
        if (world == null || !loaded(world, from, to)) return Visibility.UNAVAILABLE;
        double distance = from.distance(to);
        if (distance < .0001) return Visibility.CLEAR;
        return world.rayTraceBlocks(location(world, from), vector(to.subtract(from)), distance, FluidCollisionMode.NEVER, true) == null
                ? Visibility.CLEAR : Visibility.BLOCKED;
    }

    @Override public MoveStatus move(UUID npc, WorldPosition goal, double speed) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        if (active.isEmpty()) return MoveStatus.REJECTED;
        Vec3 origin = active.get().state().position().point();
        Vec3 delta = goal.point().subtract(origin);
        Vec3 clamped = new Vec3(origin.x() + clamp(delta.x(), MOVE_HORIZONTAL), Math.floor(origin.y() + clamp(delta.y(), MOVE_VERTICAL)), origin.z() + clamp(delta.z(), MOVE_HORIZONTAL));
        try {
            living.navigate(active.get().id(), new WorldPosition(goal.world(), clamped), MovementProfile.Mode.WALK, speed);
            return MoveStatus.ACCEPTED;
        } catch (IllegalArgumentException refused) {
            return MoveStatus.REJECTED;
        } catch (IllegalStateException busy) {
            return MoveStatus.BUSY;
        }
    }
    @Override public void stop(UUID npc) {
        living.combatant(npc).ifPresent((NpcRuntime.ActiveNpc active) -> {
            try { living.stopNavigation(active.id()); }
            catch (IllegalStateException inactive) { /* nothing is moving it */ }
        });
    }
    @Override public void release(UUID npc) {
        living.combatant(npc).ifPresent((NpcRuntime.ActiveNpc active) -> {
            try { living.resumePatrol(active.id()); }
            catch (IllegalArgumentException | IllegalStateException inactive) { /* no patrol to resume */ }
        });
    }
    @Override public boolean teleport(UUID npc, WorldPosition destination) {
        return relocate(npc, destination);
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
        World world = world(destination.world());
        if (binding.isEmpty() || world == null || !destination.world().equals(active.get().state().position().world())) return false;
        Hitbox bounds = active.get().state().bounds();
        double width = bounds.max().x() - bounds.min().x();
        double height = bounds.max().y() - bounds.min().y();
        Vec3 from = active.get().state().position().point();
        MotionValidator.Result path = LiveMovementProbe.check(world, from, destination.point(), width, height, MotionValidator.Medium.GROUND, true, PROBE_CELLS);
        if (!path.clear()) return false;
        binding.get().changed().accept(binding.get().state().move(destination));
        return true;
    }
    @Override public void heal(UUID npc, double amount) {
        living.combatant(npc).flatMap((NpcRuntime.ActiveNpc active) -> living.combat(active.id())).ifPresent(
                (PersistedNpcService.CombatBinding binding) -> binding.changed().accept(binding.state().heal(amount)));
    }
    @Override public boolean revive(UUID npc, WorldPosition at) { return living.revive(npc, at); }

    @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
        Entity entity = plugin.getServer().getEntity(target.ref().id());
        Optional<VirtualNpcState.Snapshot> self = npc(attacker);
        if (!(entity instanceof LivingEntity victim) || !victim.isValid() || victim.isDead() || self.isEmpty() || !self.get().active())
            return new DamageOutcome(DamageStatus.INVALID, 0, false);
        VirtualNpcDamageEvent event = new VirtualNpcDamageEvent(attacker, victim, Math.min(100, amount));
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return new DamageOutcome(DamageStatus.CANCELLED, 0, false);
        if (!victim.isValid() || victim.isDead() || npc(attacker).filter(VirtualNpcState.Snapshot::active).isEmpty())
            return new DamageOutcome(DamageStatus.INVALID, 0, false);
        Vec3 origin = self.get().bounds().min().add(self.get().bounds().max()).multiply(.5);
        DamageSource source = DamageSource.builder(kind == DamageKind.PROJECTILE ? DamageType.ARROW : DamageType.MOB_ATTACK)
                .withDamageLocation(location(victim.getWorld(), origin)).build();
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        boolean suppress = !victimDrops && !(victim instanceof Player);
        if (suppress) suppressedDrops.add(victim.getUniqueId());
        try {
            victim.damage(event.getDamage(), source);
        } finally {
            if (suppress) suppressedDrops.remove(victim.getUniqueId());
        }
        double after = victim.isDead() ? 0 : victim.getHealth() + victim.getAbsorptionAmount();
        double applied = Math.max(0, before - after);
        if (applied <= 0) return new DamageOutcome(DamageStatus.NO_CHANGE, 0, false);
        if (knockback > 0 && victim.isValid() && !victim.isDead()) {
            Location at = victim.getLocation();
            Vector away = new Vector(at.getX() - origin.x(), 0, at.getZ() - origin.z());
            if (away.lengthSquared() > .0001) victim.setVelocity(victim.getVelocity().add(away.normalize().multiply(knockback)).setY(Math.max(victim.getVelocity().getY(), .25)));
        }
        return new DamageOutcome(DamageStatus.APPLIED, applied, victim.isDead() || victim.getHealth() <= 0);
    }
    @Override public DamageOutcome damageVirtual(UUID npc, TargetRef attacker, double amount) {
        Optional<NpcRuntime.ActiveNpc> active = living.combatant(npc);
        Optional<PersistedNpcService.CombatBinding> binding = active.flatMap((NpcRuntime.ActiveNpc value) -> living.combat(value.id()));
        if (binding.isEmpty()) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        double before = binding.get().state().snapshot().health();
        VirtualNpcState.Snapshot after = binding.get().state().damage(amount);
        binding.get().changed().accept(after);
        return new DamageOutcome(DamageStatus.APPLIED, before - after.health(), after.health() == 0);
    }

    @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity) {
        World world = world(from.world());
        Vec3 point = from.point();
        if (world == null || !world.isChunkLoaded(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return Optional.empty();
        double speed = velocity.distance(Vec3.ZERO);
        if (speed < .0001) return Optional.empty();
        Arrow arrow = world.spawnArrow(location(world, point), vector(velocity), (float) speed, 0);
        if (!arrow.isValid()) return Optional.empty();
        arrow.getPersistentDataContainer().set(helperKey, PersistentDataType.BYTE, (byte) 1);
        arrow.setPersistent(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setVelocity(vector(velocity));
        return Optional.of(arrow.getUniqueId());
    }
    @Override public Optional<Vec3> helperPosition(UUID helper) {
        Entity entity = plugin.getServer().getEntity(helper);
        if (entity == null || !entity.isValid()) return Optional.empty();
        Location at = entity.getLocation();
        return Optional.of(new Vec3(at.getX(), at.getY(), at.getZ()));
    }
    @Override public void removeHelper(UUID helper) {
        Entity entity = plugin.getServer().getEntity(helper);
        if (entity != null) entity.remove();
    }

    @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) {
        World world = world(location.world());
        return world == null ? Optional.empty() : Optional.of(new WorldPosition(world.getUID().toString(), new Vec3(location.x(), location.y(), location.z())));
    }
    @Override public void drop(WorldPosition at, List<Drop> items, int experience) {
        World world = world(at.world());
        Vec3 point = at.point();
        if (world == null || !world.isChunkLoaded(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return;
        Location place = location(world, point);
        for (Drop drop : items) {
            Material material = Material.matchMaterial(drop.item());
            if (material != null && material.isItem()) world.dropItemNaturally(place, new ItemStack(material, drop.amount()));
        }
        if (experience > 0) world.spawn(place, ExperienceOrb.class, (ExperienceOrb orb) -> orb.setExperience(experience));
    }

    private World world(String id) {
        try { return plugin.getServer().getWorld(UUID.fromString(id)); }
        catch (IllegalArgumentException name) { return plugin.getServer().getWorld(id); }
    }
    private static boolean loaded(World world, Vec3 from, Vec3 to) {
        for (int x = ((int) Math.floor(Math.min(from.x(), to.x()))) >> 4, lastX = ((int) Math.floor(Math.max(from.x(), to.x()))) >> 4; x <= lastX; x++)
            for (int z = ((int) Math.floor(Math.min(from.z(), to.z()))) >> 4, lastZ = ((int) Math.floor(Math.max(from.z(), to.z()))) >> 4; z <= lastZ; z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }
    private static Location location(World world, Vec3 point) { return new Location(world, point.x(), point.y(), point.z()); }
    private static Vector vector(Vec3 point) { return new Vector(point.x(), point.y(), point.z()); }
}
