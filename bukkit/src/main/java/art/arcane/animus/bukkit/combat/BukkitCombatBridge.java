package art.arcane.animus.bukkit.combat;

import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDamagedByMobEvent;
import art.arcane.animus.core.combat.*;
import art.arcane.animus.core.virtual.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Explicit main-thread combat authority. It never constructs an NPC body or a fake Bukkit attacker.
 * The caller owns permission/import-review policy and must close registrations when their runtime retires.
 * Unavailable on Folia, where the combat module runs on region owners instead: see {@link #FOLIA_UNAVAILABLE}.
 */
public final class BukkitCombatBridge implements Listener, AutoCloseable {
    public enum Action { NPC_ATTACK, MOB_CONTROL, MOB_ATTACK }
    public enum Status { APPLIED, NO_CHANGE, INACTIVE, INVALID_TARGET, DIFFERENT_WORLD, UNAVAILABLE_CHUNK, OUT_OF_REACH,
        OBSTRUCTED, COOLDOWN, PROTECTED, CANCELLED, STALE, FAILED }
    @FunctionalInterface public interface Protection {
        /** Fail closed on exceptions. Called again after cancellable events, on the owning main thread. */
        boolean allows(Action action, VirtualNpcState.Snapshot npc, LivingEntity realEntity);
    }
    /** Lets the combat module apply an NPC's defense to controlled-mob hits and learn of the result. Owner thread only. */
    public interface Defense {
        Defense NONE = new Defense() {
            @Override public double mitigate(UUID npc, LivingEntity attacker, double amount) { return amount; }
            @Override public void damaged(UUID npc, LivingEntity attacker, VirtualNpcState.Snapshot after) {}
        };
        /** The damage left after defense, or 0 for none. */
        double mitigate(UUID npc, LivingEntity attacker, double amount);
        void damaged(UUID npc, LivingEntity attacker, VirtualNpcState.Snapshot after);
    }
    /** Health values are absent on rejected attempts; appliedDamage includes absorption consumed by Bukkit. */
    public record AttackResult(Status status, double appliedDamage, Double healthBefore, Double healthAfter, String detail) {}
    public record Capabilities(boolean realDamage, Set<String> controlledMobTypes, String unavailableReason, String mobControlUnavailableReason) {
        public Capabilities { controlledMobTypes = Set.copyOf(controlledMobTypes); }
    }
    private final Plugin plugin;
    private final CombatLimits limits;
    private final Protection protection;
    private final Defense defense;
    private final NativeMobController nativeMobs;
    private final Capabilities capabilities;
    private final Map<UUID, Combatant> combatants = new HashMap<>();
    private final Map<UUID, MobLease> controlled = new HashMap<>();
    private final ArrayDeque<MobLease> work = new ArrayDeque<>();
    private BukkitTask clock;
    private long tick;
    private int remainingPlans;
    private boolean closed;

    public static final String FOLIA_UNAVAILABLE = "unavailable on Folia: the manual attack and mob control commands drive a real mob's goals from the NPC's region while the mob's AI runs on its own region";
    public BukkitCombatBridge(Plugin plugin, String exactVersion, boolean folia, CombatLimits limits, Protection protection, Defense defense) {
        this.plugin = Objects.requireNonNull(plugin); this.limits = Objects.requireNonNull(limits); this.protection = Objects.requireNonNull(protection);
        this.defense = Objects.requireNonNull(defense);
        String unavailable = folia || foliaPresent() ? "Combat " + FOLIA_UNAVAILABLE :
                !Set.of("26.1.2", "26.2", "26.3").contains(exactVersion) ? "Combat is not validated for " + exactVersion : null;
        NativeMobController adapter = null;
        String mobUnavailable = unavailable;
        if (unavailable == null) {
            requireOwner();
            try { adapter = new NativeMobController(plugin, exactVersion); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                mobUnavailable = "Zombie control binding failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
        nativeMobs = adapter;
        capabilities = new Capabilities(unavailable == null, adapter == null ? Set.of() : Set.of("minecraft:zombie"), unavailable, mobUnavailable);
    }
    public Capabilities capabilities() { return capabilities; }
    public boolean available() { return capabilities.realDamage() && !closed; }
    public int controlledMobCount() { return controlled.size(); }
    public int combatantCount() { return combatants.size(); }

    public Combatant register(VirtualNpcState state, BooleanSupplier current, Consumer<VirtualNpcState.Snapshot> onChange) {
        requireAvailable(); Objects.requireNonNull(state); Objects.requireNonNull(current); Objects.requireNonNull(onChange);
        VirtualNpcState.Snapshot snapshot = state.snapshot();
        if (!snapshot.active() || !current.getAsBoolean()) throw new IllegalArgumentException("NPC runtime is not active");
        if (combatants.size() >= limits.maximumCombatants()) throw new IllegalStateException("Combat registration limit reached");
        if (combatants.containsKey(snapshot.npcId())) throw new IllegalStateException("NPC is already registered for combat");
        Combatant result = new Combatant(state, current, onChange);
        combatants.put(snapshot.npcId(), result);
        if (clock == null) clock = plugin.getServer().getScheduler().runTaskTimer(plugin, this::advance, 1, 1);
        return result;
    }

    public final class Combatant implements AutoCloseable {
        private final VirtualNpcState state;
        private final BooleanSupplier current;
        private final Consumer<VirtualNpcState.Snapshot> onChange;
        private final UUID npcId;
        private final StrikeWindow attacks = new StrikeWindow();
        private boolean retired;
        private Combatant(VirtualNpcState state, BooleanSupplier current, Consumer<VirtualNpcState.Snapshot> onChange) {
            this.state = state; this.current = current; this.onChange = onChange; npcId = state.snapshot().npcId();
        }
        public UUID npcId() { return npcId; }
        public boolean active() {
            if (closed || retired || combatants.get(npcId) != this || !state.snapshot().active()) return false;
            try { return current.getAsBoolean(); } catch (RuntimeException error) { return false; }
        }
        public AttackResult attack(LivingEntity target, AttackProfile profile) {
            requireOwner(); Objects.requireNonNull(target); Objects.requireNonNull(profile);
            VirtualNpcState.Snapshot before = state.snapshot();
            Status validation = validate(this, before, target, profile.reach(), false);
            if (validation != null) return rejected(validation);
            if (!attacks.reserve(tick, profile.cooldownTicks())) return rejected(Status.COOLDOWN);
            try {
                if (!allowed(Action.NPC_ATTACK, before, target)) return rejected(Status.PROTECTED);
                if (!unchanged(this, before)) return rejected(Status.STALE);
                VirtualNpcDamageEvent event = new VirtualNpcDamageEvent(npcId, target, profile.damage());
                plugin.getServer().getPluginManager().callEvent(event);
                if (event.isCancelled()) return rejected(Status.CANCELLED);
                if (!unchanged(this, before)) return rejected(Status.STALE);
                if (!allowed(Action.NPC_ATTACK, before, target)) return rejected(Status.PROTECTED);
                if (!unchanged(this, before)) return rejected(Status.STALE);
                validation = validate(this, before, target, profile.reach(), false);
                if (validation != null) return rejected(validation);
                double oldHealth = target.getHealth(); double oldTotal = oldHealth + target.getAbsorptionAmount();
                Vec3 origin = CombatGeometry.center(before.bounds());
                DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK)
                        .withDamageLocation(new Location(target.getWorld(), origin.x(), origin.y(), origin.z())).build();
                target.damage(event.getDamage(), source);
                double newHealth = target.getHealth();
                double applied = Math.max(0, oldTotal - newHealth - target.getAbsorptionAmount());
                return new AttackResult(applied > 0 ? Status.APPLIED : Status.NO_CHANGE, applied, oldHealth, newHealth,
                        applied > 0 ? "Bukkit damage applied; virtual attribution is in VirtualNpcDamageEvent" : "Bukkit/protection/immunity caused no observed health or absorption loss");
            } catch (RuntimeException error) {
                reportFailure("Virtual attack failed", error);
                return rejected(Status.FAILED);
            } finally { attacks.release(); }
        }
        public MobLease control(Mob mob, MobProfile profile) {
            requireAvailable(); Objects.requireNonNull(mob); Objects.requireNonNull(profile);
            if (nativeMobs == null) throw new IllegalStateException(capabilities.mobControlUnavailableReason());
            if (!active()) throw new IllegalStateException("NPC runtime is not active");
            if (controlled.size() >= limits.maximumControlledMobs() || controlled.containsKey(mob.getUniqueId()))
                throw new IllegalStateException("Mob already controlled or control limit reached");
            VirtualNpcState.Snapshot snapshot = state.snapshot();
            if (basicTarget(snapshot, mob) != null || mob.getLocation().toVector().distanceSquared(vector(snapshot.position().point())) > profile.pursuitRange() * profile.pursuitRange())
                throw new IllegalArgumentException("Mob must be active in the same loaded world within pursuit range");
            if (!allowed(Action.MOB_CONTROL, snapshot, mob) || !unchanged(this, snapshot)) throw new IllegalStateException("Mob control denied");
            try {
                NativeMobController.Lease nativeLease = nativeMobs.acquire(mob);
                boolean published = false;
                try {
                    if (!allowed(Action.MOB_CONTROL, snapshot, mob) || !unchanged(this, snapshot) || basicTarget(snapshot, mob) != null
                            || !nativeLease.owned() || mob.getLocation().toVector().distanceSquared(vector(snapshot.position().point())) > profile.pursuitRange() * profile.pursuitRange())
                        throw new IllegalStateException("NPC or mob changed during control acquisition");
                    MobLease lease = new MobLease(this, mob, profile, nativeLease);
                    controlled.put(mob.getUniqueId(), lease); work.addLast(lease); published = true;
                    return lease;
                } finally {
                    if (!published) nativeLease.close();
                }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot bind real mob controller", error); }
        }
        @Override public void close() {
            requireOwner();
            if (retired) return;
            retired = true; attacks.close(); combatants.remove(npcId, this);
            for (MobLease lease : List.copyOf(controlled.values())) if (lease.npc == this) lease.close();
            stopClockWhenIdle();
        }
    }

    public final class MobLease implements AutoCloseable {
        private final Combatant npc;
        private final Mob mob;
        private final MobProfile profile;
        private final NativeMobController.Lease nativeLease;
        private final StrikeWindow attacks = new StrikeWindow();
        private final long expiresAt;
        private long nextPlan;
        private boolean retired;
        private Status lastStatus = Status.NO_CHANGE;
        private MobLease(Combatant npc, Mob mob, MobProfile profile, NativeMobController.Lease nativeLease) {
            this.npc = npc; this.mob = mob; this.profile = profile; this.nativeLease = nativeLease;
            expiresAt = tick > Long.MAX_VALUE - profile.lifetimeTicks() ? Long.MAX_VALUE : tick + profile.lifetimeTicks();
        }
        public UUID mobId() { return mob.getUniqueId(); }
        public UUID npcId() { return npc.npcId(); }
        public boolean active() { return !retired && !closed && npc.active() && controlled.get(mob.getUniqueId()) == this; }
        public Status lastStatus() { return lastStatus; }
        private void update() throws ReflectiveOperationException {
            VirtualNpcState.Snapshot before = npc.state.snapshot();
            if (!active() || tick >= expiresAt || basicTarget(before, mob) != null || !nativeLease.owned()
                    || mob.getLocation().toVector().distanceSquared(vector(before.position().point())) > profile.pursuitRange() * profile.pursuitRange()) {
                lastStatus = Status.INACTIVE; close(); return;
            }
            if (!allowed(Action.MOB_CONTROL, before, mob) || !unchanged(npc, before)) { lastStatus = Status.PROTECTED; close(); return; }
            if (!active() || !nativeLease.owned()) { lastStatus = Status.STALE; close(); return; }
            if (basicTarget(before, mob) != null || mob.getLocation().toVector().distanceSquared(vector(before.position().point())) > profile.pursuitRange() * profile.pursuitRange()) {
                lastStatus = Status.STALE; close(); return;
            }
            Status hit = validate(npc, before, mob, profile.attack().reach(), true);
            if (hit == null) {
                nativeLease.halt();
                attackVirtual(before);
            } else {
                lastStatus = hit;
                if (hit != Status.OUT_OF_REACH && hit != Status.OBSTRUCTED) { close(); return; }
                if (tick >= nextPlan && remainingPlans > 0) {
                    if (!loadedArea(mob.getLocation(), (int) Math.ceil(profile.pursuitRange()) + 8)) { lastStatus = Status.UNAVAILABLE_CHUNK; nativeLease.halt(); return; }
                    remainingPlans--; nextPlan = tick + profile.replanTicks();
                    nativeLease.plan(before.position().point(), (int) Math.ceil(profile.pursuitRange()), limits.visitedNodesPerPath(), profile.speed());
                    if (!unchanged(npc, before) || !active()) close();
                }
            }
        }
        private void attackVirtual(VirtualNpcState.Snapshot before) throws ReflectiveOperationException {
            if (!attacks.reserve(tick, profile.attack().cooldownTicks())) { lastStatus = Status.COOLDOWN; return; }
            try {
                if (!allowed(Action.MOB_ATTACK, before, mob)) { lastStatus = Status.PROTECTED; return; }
                if (!active() || !unchanged(npc, before) || !nativeLease.owned()) { lastStatus = Status.STALE; return; }
                VirtualNpcDamagedByMobEvent event = new VirtualNpcDamagedByMobEvent(npc.npcId(), mob, profile.attack().damage());
                plugin.getServer().getPluginManager().callEvent(event);
                if (event.isCancelled()) { lastStatus = Status.CANCELLED; return; }
                if (!active() || !unchanged(npc, before) || !nativeLease.owned()) { lastStatus = Status.STALE; return; }
                if (!allowed(Action.MOB_ATTACK, before, mob)) { lastStatus = Status.PROTECTED; return; }
                if (!active() || !unchanged(npc, before) || !nativeLease.owned()) { lastStatus = Status.STALE; return; }
                Status validation = validate(npc, before, mob, profile.attack().reach(), true);
                if (validation != null) { lastStatus = validation; return; }
                double amount = defense.mitigate(npc.npcId(), mob, event.getDamage());
                if (!(amount > 0)) { lastStatus = Status.NO_CHANGE; return; }
                VirtualNpcState.Snapshot after = npc.state.damage(amount);
                lastStatus = Status.APPLIED;
                mob.swingMainHand();
                npc.onChange.accept(after);
                defense.damaged(npc.npcId(), mob, after);
                if (!after.active() || !npc.active()) close();
            } finally { attacks.release(); }
        }
        @Override public void close() {
            close(false);
        }
        private void close(boolean unavailableTerrain) {
            requireOwner();
            if (retired) return;
            retired = true; attacks.close(); controlled.remove(mob.getUniqueId(), this); work.remove(this);
            try { if (unavailableTerrain) nativeLease.closeForUnavailableTerrain(); else nativeLease.close(); }
            catch (ReflectiveOperationException | RuntimeException error) { lastStatus = Status.FAILED; reportFailure("Cannot completely restore mob control", error); }
        }
    }

    private void advance() {
        requireOwner();
        if (closed) return;
        if (tick == Long.MAX_VALUE) { close(); return; }
        tick++;
        for (Combatant npc : List.copyOf(combatants.values())) if (!npc.active()) npc.close();
        remainingPlans = limits.pathPlansPerTick();
        long start = System.nanoTime();
        for (int count = 0, budget = Math.min(work.size(), limits.mobUpdatesPerTick()); count < budget; count++) {
            if (System.nanoTime() - start >= limits.workNanosPerTick()) break;
            MobLease lease = work.pollFirst();
            if (lease == null) break;
            try { lease.update(); }
            catch (CancellationException expected) { lease.lastStatus = Status.STALE; lease.close(); }
            catch (ReflectiveOperationException | RuntimeException error) { reportFailure("Real mob controller failed", error); lease.close(); }
            if (lease.active()) work.addLast(lease);
        }
    }
    private Status validate(Combatant npc, VirtualNpcState.Snapshot snapshot, LivingEntity real, double reach, boolean incoming) {
        if (!npc.active() || !snapshot.active()) return Status.INACTIVE;
        Status basic = basicTarget(snapshot, real);
        if (basic != null) return basic;
        Vec3 origin = incoming ? point(real.getEyeLocation()) : CombatGeometry.center(snapshot.bounds());
        Hitbox target = incoming ? snapshot.bounds() : bounds(real.getBoundingBox());
        Vec3 closest = CombatGeometry.nearest(target, origin);
        double distance = origin.distance(closest);
        if (distance > reach) return Status.OUT_OF_REACH;
        if (!loadedSegment(real.getWorld(), origin, closest)) return Status.UNAVAILABLE_CHUNK;
        if (distance > 0.000001 && real.getWorld().rayTraceBlocks(location(real.getWorld(), origin), vector(closest.subtract(origin)),
                Math.max(0, distance - 0.0001), FluidCollisionMode.NEVER, true) != null) return Status.OBSTRUCTED;
        return null;
    }
    private static Status basicTarget(VirtualNpcState.Snapshot snapshot, LivingEntity real) {
        if (!real.isValid() || real.isDead() || real.getHealth() <= 0) return Status.INVALID_TARGET;
        World world = real.getWorld();
        if (!world.getUID().toString().equals(snapshot.position().world())) return Status.DIFFERENT_WORLD;
        Vec3 npc = snapshot.position().point(); Location target = real.getLocation();
        if (!world.isChunkLoaded(((int) Math.floor(npc.x())) >> 4, ((int) Math.floor(npc.z())) >> 4)
                || !world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return Status.UNAVAILABLE_CHUNK;
        return null;
    }
    private boolean allowed(Action action, VirtualNpcState.Snapshot npc, LivingEntity real) {
        try { return protection.allows(action, npc, real); }
        catch (RuntimeException error) { reportFailure("Combat protection callback failed closed", error); return false; }
    }
    private static boolean unchanged(Combatant npc, VirtualNpcState.Snapshot before) { return npc.active() && npc.state.snapshot().revision() == before.revision(); }
    private static boolean loadedSegment(World world, Vec3 start, Vec3 end) {
        for (int x = ((int) Math.floor(Math.min(start.x(), end.x()))) >> 4, lastX = ((int) Math.floor(Math.max(start.x(), end.x()))) >> 4; x <= lastX; x++)
            for (int z = ((int) Math.floor(Math.min(start.z(), end.z()))) >> 4, lastZ = ((int) Math.floor(Math.max(start.z(), end.z()))) >> 4; z <= lastZ; z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
    private static boolean loadedArea(Location location, int radius) {
        return loadedSegment(location.getWorld(), new Vec3(location.getX() - radius, location.getY(), location.getZ() - radius),
                new Vec3(location.getX() + radius, location.getY(), location.getZ() + radius));
    }
    private static Vec3 point(Location value) { return new Vec3(value.getX(), value.getY(), value.getZ()); }
    private static Vector vector(Vec3 point) { return new Vector(point.x(), point.y(), point.z()); }
    private static Location location(World world, Vec3 point) { return new Location(world, point.x(), point.y(), point.z()); }
    private static Hitbox bounds(BoundingBox box) { return new Hitbox(new Vec3(box.getMinX(), box.getMinY(), box.getMinZ()), new Vec3(box.getMaxX(), box.getMaxY(), box.getMaxZ())); }
    private static AttackResult rejected(Status status) { return new AttackResult(status, 0, null, null, status.name()); }
    private void requireOwner() { if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Combat requires the owning main thread"); }
    private void requireAvailable() { if (!available()) throw new IllegalStateException(closed ? "Combat bridge closed" : capabilities.unavailableReason()); requireOwner(); }
    private static boolean foliaPresent() {
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException ignored) { return false; }
    }
    private void reportFailure(String message, Throwable error) { plugin.getLogger().log(java.util.logging.Level.WARNING, message, error); }
    private void stopClockWhenIdle() { if (combatants.isEmpty() && clock != null) { clock.cancel(); clock = null; } }
    @EventHandler public void onDeath(EntityDeathEvent event) { MobLease lease = controlled.get(event.getEntity().getUniqueId()); if (lease != null) lease.close(); }
    @EventHandler public void onUnload(EntitiesUnloadEvent event) { for (Entity entity : event.getEntities()) { MobLease lease = controlled.get(entity.getUniqueId()); if (lease != null) lease.close(); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onChunkUnload(ChunkUnloadEvent event) {
        for (MobLease lease : List.copyOf(controlled.values())) {
            if (!lease.mob.getWorld().equals(event.getWorld())) continue;
            Location point = lease.mob.getLocation(); int radius = (int) Math.ceil(lease.profile.pursuitRange()) + 8;
            boolean affectsRoute = true;
            try { affectsRoute = lease.nativeLease.usesChunk(event.getChunk().getX(), event.getChunk().getZ()); }
            catch (ReflectiveOperationException error) { reportFailure("Cannot inspect unloading controlled route", error); }
            if (affectsRoute || event.getChunk().getX() >= ((point.getBlockX() - radius) >> 4) && event.getChunk().getX() <= ((point.getBlockX() + radius) >> 4)
                    && event.getChunk().getZ() >= ((point.getBlockZ() - radius) >> 4) && event.getChunk().getZ() <= ((point.getBlockZ() + radius) >> 4)) {
                lease.lastStatus = Status.UNAVAILABLE_CHUNK; lease.close(true);
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onWorldUnload(WorldUnloadEvent event) {
        for (MobLease lease : List.copyOf(controlled.values())) if (lease.mob.getWorld().equals(event.getWorld())) lease.close();
    }
    @Override public void close() {
        if (!capabilities.realDamage()) { closed = true; return; }
        requireOwner();
        if (closed) return;
        // Revoke registrations before cleanup; platform adapters must not reopen a closing bridge.
        closed = true;
        for (Combatant npc : List.copyOf(combatants.values())) npc.close();
        work.clear(); stopClockWhenIdle(); HandlerList.unregisterAll(this);
    }
}
