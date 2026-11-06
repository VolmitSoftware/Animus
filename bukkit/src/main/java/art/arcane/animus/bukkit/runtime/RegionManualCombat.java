package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.bukkit.combat.MobProfile;
import art.arcane.animus.bukkit.combat.NativeMobController;
import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDamagedByMobEvent;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.CombatGeometry;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.CombatModule;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * {@code /animus attack} and {@code /animus target} on Folia. The NPC's region captures where the NPC stands and hands
 * the hit to the target's own entity scheduler, which checks the target, reach, line of sight over chunks it owns and
 * protection, fires {@link VirtualNpcDamageEvent} and applies the damage there. A controlled mob runs on its own entity
 * scheduler and reads the NPC only through its published state; its hits on the NPC go to the NPC's mailbox, where the
 * NPC's own defense applies. Nothing reads an entity or a chunk another region owns. Results go to the server log.
 */
public final class RegionManualCombat implements AutoCloseable {
    public static final String USAGE = "attack <npc-id> <target-uuid> [damage] | target <npc-id> <mob-uuid>|stop";
    /** NPCs with a hit in flight at once, and controlled mobs on the whole server. */
    static final int MAXIMUM_ATTACKERS = 64, MAXIMUM_MOBS = 32;
    /** How far an NPC may have moved between its strike and the hit landing, or a mob's hit and its arrival. */
    private static final double HIT_SLACK = 2;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final Set<String> VALIDATED = Set.of("26.1.2", "26.2", "26.3");
    private final RegionNpcRuntime host;
    private final ProtectionAdapter protection;
    private final NativeMobController mobs;
    private final String unavailable, mobUnavailable;
    private final StrikeGate strikes = new StrikeGate(MAXIMUM_ATTACKERS);
    private final MobLeases leases = new MobLeases(MAXIMUM_MOBS);
    private final Map<MobLeases.Claim, Control> controls = new ConcurrentHashMap<>();
    private final AtomicLong attacks = new AtomicLong(), applied = new AtomicLong(), refused = new AtomicLong(), mobHits = new AtomicLong();
    private volatile boolean closed;

    public RegionManualCombat(RegionNpcRuntime host, String version, ProtectionAdapter protection) {
        this.host = Objects.requireNonNull(host); this.protection = Objects.requireNonNull(protection);
        String reason = !host.available() ? "the NPC runtime is " + host.status() : !VALIDATED.contains(version) ? "not validated for " + version : null;
        NativeMobController controller = null;
        String mobReason = reason;
        if (reason == null) {
            try { controller = new NativeMobController(host.plugin, version, (World world, int x, int z) -> host.regions.owns(world, x, z, x, z) && world.isChunkLoaded(x, z)); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { mobReason = "mob control binding failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
        }
        unavailable = reason; mobUnavailable = mobReason; mobs = controller;
        if (reason != null) host.plugin.getLogger().info("Manual attack and mob control unavailable on Folia: " + reason);
    }

    public String status() {
        if (unavailable != null) return "manual attack and mob control unavailable: " + unavailable;
        return "manual attack and mob control on each region | attacks=" + attacks.get() + " applied=" + applied.get() + " refused=" + refused.get()
                + " | controlled-mobs=" + leases.size() + "/" + MAXIMUM_MOBS + " mob-hits=" + mobHits.get()
                + (mobUnavailable == null ? "" : " | " + mobUnavailable);
    }

    /** {@code attack} and {@code target} from a command on any thread. Checks run where their subject lives; results go to the log. */
    public String execute(String[] args) {
        if (closed) throw new IllegalStateException("Combat has stopped");
        if (unavailable != null) throw new IllegalStateException("Manual attack and mob control are unavailable: " + unavailable);
        if (args.length == 3 && args[0].equals("target") && args[2].equals("stop")) return stop(args[1]);
        if (args.length < 3 || args.length > 4 || args[0].equals("target") && args.length != 3) throw new IllegalArgumentException(USAGE);
        RegionActor actor = actor(args[1]);
        UUID targetId = CombatModule.uuid(args[2]);
        Entity entity;
        try { entity = host.plugin.getServer().getEntity(targetId); }
        catch (RuntimeException unavailableHandle) { entity = null; }
        if (!(entity instanceof LivingEntity target)) throw new IllegalArgumentException("Target must be a loaded living entity");
        if (args[0].equals("attack")) {
            double amount = args.length == 4 ? Double.parseDouble(args[3]) : AttackProfile.DEFAULT.damage();
            return attack(args[1], actor, target, new AttackProfile(amount, AttackProfile.DEFAULT.reach(), AttackProfile.DEFAULT.cooldownTicks()));
        }
        if (!args[0].equals("target") || !(target instanceof Mob mob)) throw new IllegalArgumentException("Target control requires an existing supported mob");
        return control(args[1], actor, mob);
    }

    private RegionActor actor(String id) {
        NpcDefinition definition = host.runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        if (!CombatModule.manualAllowed(definition)) throw new IllegalStateException("NPC combat is disabled or its migration review has not been accepted");
        RegionActor actor = host.actors.get(id);
        RegionActor.Publication view = actor == null ? null : actor.publication;
        if (view == null || !view.live()) throw new IllegalStateException("NPC must have an active runtime before combat");
        return actor;
    }
    private void log(String line) { host.plugin.getLogger().info(line); }

    // ---- One hit: captured on the NPC's region, applied on the target's ----

    private String attack(String id, RegionActor actor, LivingEntity target, AttackProfile attack) {
        StrikeGate.Admission admission = strikes.reserve(actor.uuid, System.nanoTime(), attack.cooldownTicks() * TICK_NANOS);
        if (admission == StrikeGate.Admission.IN_FLIGHT || admission == StrikeGate.Admission.COOLDOWN) return "Attack " + id + ": COOLDOWN";
        if (admission == StrikeGate.Admission.FULL) return "Attack " + id + ": UNAVAILABLE too many attacks in flight; try again";
        attacks.incrementAndGet();
        boolean queued = actor.owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { strike(id, actor, target, attack); }
            @Override public void refuse(String reason) { finish(id, actor, "INACTIVE " + reason, false); }
        });
        if (!queued) { strikes.release(actor.uuid); refused.incrementAndGet(); return "Attack " + id + ": INACTIVE the NPC is not active on a region"; }
        return "Attack " + id + ": QUEUED on the NPC's region; the result follows in the server log";
    }
    /** On the NPC's region: where the NPC stands now goes with the hit to the target's own scheduler. */
    private void strike(String id, RegionActor actor, LivingEntity target, AttackProfile attack) {
        VirtualNpcState.Snapshot self = actor.state.snapshot();
        if (!self.active() || !actor.navigable()) { finish(id, actor, "INACTIVE", false); return; }
        // A refused hand-off runs the retirement callback, which settles the attack.
        host.regions.execute(target, () -> land(id, actor, self, target, attack), () -> finish(id, actor, "INVALID_TARGET the target is gone", false));
    }
    /** On the target's region. */
    private void land(String id, RegionActor actor, VirtualNpcState.Snapshot self, LivingEntity target, AttackProfile attack) {
        try {
            String invalid = validate(actor, self, target, attack.reach());
            if (invalid != null) { finish(id, actor, invalid, false); return; }
            if (!allows(ProtectionAdapter.Action.NPC_ATTACK, self, target)) { finish(id, actor, "PROTECTED", false); return; }
            VirtualNpcDamageEvent event = new VirtualNpcDamageEvent(self.npcId(), target, attack.damage());
            host.plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) { finish(id, actor, "CANCELLED", false); return; }
            invalid = validate(actor, self, target, attack.reach());
            if (invalid != null) { finish(id, actor, invalid, false); return; }
            if (!allows(ProtectionAdapter.Action.NPC_ATTACK, self, target)) { finish(id, actor, "PROTECTED", false); return; }
            double oldHealth = target.getHealth(), oldTotal = oldHealth + target.getAbsorptionAmount();
            Vec3 origin = CombatGeometry.center(self.bounds());
            DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK).withDamageLocation(new Location(target.getWorld(), origin.x(), origin.y(), origin.z())).build();
            target.damage(event.getDamage(), source);
            double newHealth = target.getHealth(), done = Math.max(0, oldTotal - newHealth - target.getAbsorptionAmount());
            finish(id, actor, (done > 0 ? "APPLIED" : "NO_CHANGE") + " applied=" + done + " health-before=" + oldHealth + " health-after=" + newHealth, done > 0);
        } catch (RuntimeException failure) {
            finish(id, actor, "FAILED " + PersistedNpcService.concise(failure), false);
        }
    }
    private void finish(String id, RegionActor actor, String result, boolean struck) {
        strikes.release(actor.uuid);
        if (struck) applied.incrementAndGet(); else refused.incrementAndGet();
        log("Attack " + id + ": " + result);
    }
    /**
     * On the target's region: the target is alive in the NPC's world, the NPC is still active within a short step of
     * where it struck, and the target is within reach over a clear line through chunks this region owns.
     */
    private String validate(RegionActor actor, VirtualNpcState.Snapshot self, LivingEntity target, double reach) {
        if (!target.isValid() || target.isDead() || target.getHealth() <= 0) return "INVALID_TARGET";
        World world = target.getWorld();
        if (!world.getUID().toString().equals(self.position().world())) return "DIFFERENT_WORLD";
        RegionActor.Publication now = actor.publication;
        if (now == null || !now.live() || now.snapshot().health() <= 0 || !now.snapshot().position().world().equals(self.position().world())
                || now.snapshot().position().point().distance(self.position().point()) > HIT_SLACK) return "STALE the NPC moved away";
        Vec3 origin = CombatGeometry.center(self.bounds());
        Vec3 closest = CombatGeometry.nearest(bounds(target.getBoundingBox()), origin);
        double distance = origin.distance(closest);
        if (distance > reach) return "OUT_OF_REACH";
        if (!readable(world, origin, closest)) return "UNAVAILABLE_CHUNK the line to the target crosses chunks another region owns";
        if (distance > 0.000001 && world.rayTraceBlocks(new Location(world, origin.x(), origin.y(), origin.z()), vector(closest.subtract(origin)),
                Math.max(0, distance - 0.0001), FluidCollisionMode.NEVER, true) != null) return "OBSTRUCTED";
        return null;
    }

    // ---- Mob control: the mob's own scheduler drives it ----

    private String control(String id, RegionActor actor, Mob mob) {
        if (mobs == null) throw new IllegalStateException(mobUnavailable);
        MobLeases.Claim claim = leases.claim(mob.getUniqueId(), actor.uuid);
        if (claim == null) throw new IllegalStateException("Mob already controlled or control limit reached");
        boolean handed = host.regions.execute(mob, () -> begin(id, actor, mob, claim), () -> { leases.release(claim); log("Mob control " + id + ": INVALID_TARGET the mob is gone"); });
        if (!handed) { leases.release(claim); throw new IllegalStateException("The mob's region refused the control request"); }
        return "Mob control " + id + ": QUEUED on the mob's region; the result follows in the server log";
    }
    /** On the mob's region. */
    private void begin(String id, RegionActor actor, Mob mob, MobLeases.Claim claim) {
        MobProfile profile = MobProfile.DEFAULT;
        try {
            if (closed || !leases.current(claim)) { leases.release(claim); return; }
            RegionActor.Publication view = actor.publication;
            if (view == null || !view.live() || !mob.isValid() || mob.isDead() || !mob.getWorld().getUID().toString().equals(view.snapshot().position().world())
                    || mob.getLocation().toVector().distance(vector(view.snapshot().position().point())) > profile.pursuitRange())
                throw new IllegalArgumentException("Mob must be active in the same loaded world within pursuit range");
            if (!allows(ProtectionAdapter.Action.MOB_CONTROL, view.snapshot(), mob)) throw new IllegalStateException("Mob control denied");
            NativeMobController.Lease lease = mobs.acquire(mob);
            Control control = new Control(id, actor, mob, claim, lease, profile);
            controls.put(claim, control);
            control.task = host.regions.repeat(mob, control::tick, control::retired, 1, 1);
            log("Mob control started npc=" + id + " mob=" + mob.getUniqueId() + " lifetime-ticks=" + profile.lifetimeTicks());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            leases.release(claim);
            log("Mob control " + id + ": REFUSED " + (failure instanceof ReflectiveOperationException ? PersistedNpcService.concise(failure) : failure.getMessage()));
        }
    }
    /** Ends this NPC's mob control; each mob is let go on its own region. */
    private String stop(String id) {
        NpcDefinition definition = host.runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        int released = 0;
        for (MobLeases.Claim claim : leases.of(definition.uuid())) {
            Control control = controls.get(claim);
            if (control == null) { leases.release(claim); released++; continue; }
            if (host.regions.execute(control.mob, () -> control.end("STOPPED"), control::retired)) released++;
        }
        return "Released " + released + " mob control leases for " + id;
    }

    /** One controlled mob, confined to the mob's own scheduler. */
    private final class Control {
        final String id; final RegionActor actor; final Mob mob; final MobLeases.Claim claim; final NativeMobController.Lease lease; final MobProfile profile;
        /** At most one hit on its way to the NPC's region. */
        final AtomicBoolean hitPending = new AtomicBoolean();
        volatile AutoCloseable task;
        long ticks, nextPlan, nextHit;
        private NpcDefinition policyDefinition;
        private boolean policyAllows;
        /** Set on the mob's region, or at shutdown. */
        volatile boolean ended;
        Control(String id, RegionActor actor, Mob mob, MobLeases.Claim claim, NativeMobController.Lease lease, MobProfile profile) {
            this.id = id; this.actor = actor; this.mob = mob; this.claim = claim; this.lease = lease; this.profile = profile;
        }

        void tick() {
            if (ended) return;
            try {
                RegionActor.Publication view = actor.publication;
                if (closed || !leases.current(claim) || ++ticks > profile.lifetimeTicks()) { end("EXPIRED"); return; }
                if (view == null || !view.live() || !mob.isValid() || mob.isDead() || !mob.getWorld().getUID().toString().equals(view.snapshot().position().world())) { end("INACTIVE"); return; }
                if (!allowed()) { end("INACTIVE combat is no longer allowed for the NPC"); return; }
                if (!lease.owned()) { end("STALE"); return; }
                VirtualNpcState.Snapshot npc = view.snapshot();
                if (mob.getLocation().toVector().distance(vector(npc.position().point())) > profile.pursuitRange()) { end("INACTIVE out of pursuit range"); return; }
                if (!allows(ProtectionAdapter.Action.MOB_CONTROL, npc, mob)) { end("PROTECTED"); return; }
                Location eye = mob.getEyeLocation();
                Vec3 from = new Vec3(eye.getX(), eye.getY(), eye.getZ());
                Vec3 closest = CombatGeometry.nearest(npc.bounds(), from);
                double distance = from.distance(closest);
                World world = mob.getWorld();
                boolean clear = distance <= profile.attack().reach() && readable(world, from, closest) && (distance < 0.000001
                        || world.rayTraceBlocks(eye, vector(closest.subtract(from)), Math.max(0, distance - 0.0001), FluidCollisionMode.NEVER, true) == null);
                if (clear) { lease.halt(); hit(npc, from); return; }
                if (ticks < nextPlan) return;
                int radius = (int) Math.ceil(profile.pursuitRange()) + 8;
                Location at = mob.getLocation();
                if (!readable(world, new Vec3(at.getX() - radius, at.getY(), at.getZ() - radius), new Vec3(at.getX() + radius, at.getY(), at.getZ() + radius))) { lease.halt(); return; }
                if (host.budget.take(RegionBudget.Kind.COMBAT_PATHS, 1) == 0) return;
                nextPlan = ticks + profile.replanTicks();
                lease.plan(npc.position().point(), (int) Math.ceil(profile.pursuitRange()), 256, profile.speed());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                end("FAILED " + PersistedNpcService.concise(failure));
            }
        }
        /** The NPC's definition still allows manual combat; read again only when a new definition is published. */
        private boolean allowed() {
            NpcDefinition current = host.runtime.npcs().find(id).orElse(null);
            if (current != policyDefinition) { policyDefinition = current; policyAllows = current != null && current.uuid().equals(actor.uuid) && CombatModule.manualAllowed(current); }
            return policyAllows;
        }
        /** On the mob's region: the hit is checked here and sent to the NPC's mailbox, which applies the NPC's own defense. */
        private void hit(VirtualNpcState.Snapshot npc, Vec3 from) {
            if (ticks < nextHit || !allows(ProtectionAdapter.Action.MOB_ATTACK, npc, mob) || !hitPending.compareAndSet(false, true)) return;
            nextHit = ticks + profile.attack().cooldownTicks();
            VirtualNpcDamagedByMobEvent event = new VirtualNpcDamagedByMobEvent(npc.npcId(), mob, profile.attack().damage());
            host.plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled() || !(event.getDamage() > 0)) { hitPending.set(false); return; }
            double amount = event.getDamage(), reach = profile.attack().reach() + HIT_SLACK;
            TargetRef attacker = new TargetRef(TargetKind.MOB, mob.getUniqueId());
            WorldPosition origin = new WorldPosition(npc.position().world(), from);
            mob.swingMainHand();
            boolean sent = actor.owner.submit(new OwnerMailbox.Work() {
                @Override public void run() {
                    try { struck(attacker, origin, amount, reach); }
                    finally { hitPending.set(false); }
                }
                @Override public void refuse(String reason) { hitPending.set(false); }
            });
            if (!sent) hitPending.set(false);
        }
        /** On the NPC's region. */
        private void struck(TargetRef attacker, WorldPosition origin, double amount, double reach) {
            VirtualNpcState.Snapshot before = actor.state.snapshot();
            if (!before.active() || !before.position().world().equals(origin.world()) || CombatGeometry.reach(origin.point(), before.bounds()) > reach) return;
            ActorCombat combat = actor.combat();
            double left = combat == null ? amount : combat.engine.mitigateExternal(actor.uuid, attacker, origin, amount);
            if (!(left > 0)) return;
            VirtualNpcState.Snapshot after = actor.state.damage(left);
            actor.combatChanged(before, after);
            if (combat != null) combat.engine.externalHit(actor.uuid, attacker, after);
            mobHits.incrementAndGet();
        }
        /** On the mob's region: the mob gets its own goals back. */
        void end(String status) {
            if (ended) return;
            ended = true;
            cancel();
            try { lease.close(); }
            catch (ReflectiveOperationException | RuntimeException failure) { host.plugin.getLogger().warning("Cannot completely restore mob control of " + mob.getUniqueId() + ": " + PersistedNpcService.concise(failure)); }
            finally { release(); }
            log("Mob control " + id + " ended: " + status + " mob=" + mob.getUniqueId());
        }
        /** The mob's scheduler retired it (removed or unloaded): only bookkeeping, no world access. */
        void retired() {
            if (ended) return;
            ended = true;
            cancel();
            lease.forget();
            release();
        }
        private void release() { controls.remove(claim, this); leases.release(claim); }
        private void cancel() {
            AutoCloseable current = task;
            if (current != null) try { current.close(); } catch (Exception ignored) { /* Already finished. */ }
        }
    }

    // ---- Shared checks ----

    /** Every chunk under the segment is owned by the calling region and loaded. */
    private boolean readable(World world, Vec3 start, Vec3 end) {
        int minX = ((int) Math.floor(Math.min(start.x(), end.x()))) >> 4, maxX = ((int) Math.floor(Math.max(start.x(), end.x()))) >> 4;
        int minZ = ((int) Math.floor(Math.min(start.z(), end.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(start.z(), end.z()))) >> 4;
        if (!host.regions.owns(world, minX, minZ, maxX, maxZ)) return false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
    /** On the real entity's region. Fails closed when the adapter throws. */
    private boolean allows(ProtectionAdapter.Action action, VirtualNpcState.Snapshot npc, LivingEntity real) {
        TargetRef self = new TargetRef(TargetKind.NPC, npc.npcId());
        TargetRef other = new TargetRef(real instanceof Player ? TargetKind.PLAYER : TargetKind.MOB, real.getUniqueId());
        WorldPosition at = RegionViewer.position(real.getLocation());
        RegionCombat module = host.combat;
        Predicate<Entity> helper = module == null ? (Entity entity) -> false : module::helper;
        ProtectionAdapter.Request request = switch (action) {
            case NPC_ATTACK -> new ProtectionAdapter.Request(action, self, npc.position(), other, at,
                    BukkitCombatWorld.observe(real, helper).map(CombatTarget::categories).orElse(Set.<TargetCategory>of()));
            case MOB_CONTROL, MOB_ATTACK -> new ProtectionAdapter.Request(action, other, at, self, npc.position(), Set.of(TargetCategory.NPCS));
            default -> null;
        };
        if (request == null) return false;
        try { return protection.decide(request) == ProtectionAdapter.Decision.ALLOW; }
        catch (RuntimeException | LinkageError failure) { return false; }
    }
    private static Vector vector(Vec3 point) { return new Vector(point.x(), point.y(), point.z()); }
    private static Hitbox bounds(BoundingBox box) { return new Hitbox(new Vec3(box.getMinX(), box.getMinY(), box.getMinZ()), new Vec3(box.getMaxX(), box.getMaxY(), box.getMaxZ())); }

    /**
     * Plugin shutdown: no region takes work any more, so controlled mobs cannot be handed their goals back here. Their
     * claims are released; a mob gets its goals again when its chunk loads next.
     */
    @Override public void close() {
        closed = true;
        int kept = 0;
        for (Control control : controls.values()) { control.cancel(); control.lease.forget(); control.ended = true; kept++; }
        controls.clear();
        strikes.clear();
        if (kept > 0) log("Mob control stopped with " + kept + " mobs still held; they get their own goals back when their chunks load again");
    }
}
