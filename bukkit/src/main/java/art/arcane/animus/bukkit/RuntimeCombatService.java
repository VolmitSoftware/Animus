package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.CombatControl;
import art.arcane.animus.core.hire.CombatGuarding;
import art.arcane.animus.core.hire.HireDesk;
import art.arcane.animus.bukkit.runtime.RegionNpcRuntime;
import org.bukkit.inventory.ItemStack;
import art.arcane.animus.core.runtime.CombatModule;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.bukkit.combat.BukkitCombatBridge;
import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.bukkit.combat.CombatLimits;
import art.arcane.animus.bukkit.combat.MobProfile;
import art.arcane.animus.bukkit.protection.RegionCombatProtection;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

/**
 * The combat module on Bukkit: runs the core engine for NPCs whose definitions enable combat, and keeps the
 * explicit administrator adapter (one manual hit, mob control). NPCs whose combat sets {@code targetableByMobs} draw
 * idle nearby melee hostile mobs through the same bounded, restorable mob control. Preview services never reach this.
 */
final class RuntimeCombatService implements Listener, AutoCloseable {
    private record Registration(String id, VirtualNpcState state, BukkitCombatBridge.Combatant combatant) {}
    /** How often targetable NPCs look for idle mobs, how far, how many mobs one NPC draws at once, and NPCs per pass. */
    static final int TARGETABLE_SCAN_TICKS = 20, TARGETABLE_PER_NPC = 3, TARGETABLE_NPCS_PER_PASS = 32;
    static final double TARGETABLE_RANGE = 16;
    /** A drawn mob returns to its own AI after this long, so it can choose a player again. */
    static final int TARGETABLE_LEASE_TICKS = 200;
    private final JavaPlugin plugin;
    private final AnimusRuntime runtime;
    private final PersistedNpcService<Player, ItemStack> living;
    private final BukkitCombatBridge bridge;
    private final BukkitCombatWorld world;
    private final CombatModule module;
    private final String protectionName;
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private final Map<UUID, BukkitCombatBridge.MobLease> leases = new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private int targetableCursor;
    /** Folia: combat runs on each NPC's region owner; null elsewhere. */
    private final RegionNpcRuntime regions;
    /** Why region combat cannot run, or null while it runs. */
    private final String regionUnavailable;

    /** {@code living} is the main-thread runtime and null on Folia, where {@code regions} runs combat on each NPC's owner. */
    RuntimeCombatService(JavaPlugin plugin, AnimusRuntime runtime, PersistedNpcService<Player, ItemStack> living, String version, boolean folia, RegionNpcRuntime regions) {
        this.plugin = plugin; this.runtime = runtime; this.living = living;
        RegionFlags.Binding flags = RegionFlags.bind(plugin.getServer());
        ProtectionAdapter adapter = runtime.moduleHooks().protection(flags == null ? ProtectionAdapter.NONE : new RegionCombatProtection(flags.flags()));
        protectionName = flags == null ? "none installed" : flags.describe("all combat damage is denied");
        this.regions = folia ? regions : null;
        regionUnavailable = this.regions == null ? null : this.regions.enableCombat(adapter, protectionName);
        world = folia ? null : new BukkitCombatWorld(plugin, living);
        module = new CombatModule(runtime, living, world, adapter, folia ? (regionUnavailable == null ? "runs on each NPC's region on Folia" : regionUnavailable) : null,
                (String message) -> plugin.getLogger().warning(message), (UUID id) -> plugin.getServer().getPlayer(id) != null);
        bridge = new BukkitCombatBridge(plugin, version, folia, CombatLimits.DEFAULT, this::bridgeAllows,
                module.supported() ? new EngineDefense() : BukkitCombatBridge.Defense.NONE);
        if (module.supported()) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    String status() {
        if (regions != null) {
            if (regionUnavailable != null) return "Combat unavailable: " + regionUnavailable + " | protection=" + protectionName;
            return regions.combatStatus() + " | manual attack and mob control " + BukkitCombatBridge.FOLIA_UNAVAILABLE;
        }
        String modulePart = module.status() + " | protection=" + protectionName;
        BukkitCombatBridge.Capabilities capability = bridge.capabilities();
        if (!bridge.available()) return "Combat unavailable: " + capability.unavailableReason() + modulePart;
        prune();
        return "Combat ready" + modulePart + " | manual registrations=" + bridge.combatantCount() + " | controlled-mobs=" + bridge.controlledMobCount()
                + " | mob-types=" + String.join(",", capability.controlledMobTypes())
                + (capability.mobControlUnavailableReason() == null ? "" : " | " + capability.mobControlUnavailableReason());
    }

    /** The platform side of {@code /animus combat}, {@code attack} and {@code target}. Owner thread only. */
    CombatControl control() {
        return new CombatControl() {
            @Override public String status() { return RuntimeCombatService.this.status(); }
            @Override public String npc(String npc, Operation operation, Optional<UUID> target) {
                if (regions != null) {
                    if (regionUnavailable != null) throw new IllegalStateException("Combat module " + regionUnavailable);
                    return regions.combatCommand(npc, operation, target);
                }
                return module.command(npc, operation, target);
            }
            @Override public String attack(String npc, UUID target, double damage) {
                LivingEntity victim = manual(target);
                AttackProfile attack = new AttackProfile(damage, AttackProfile.DEFAULT.reach(), AttackProfile.DEFAULT.cooldownTicks());
                BukkitCombatBridge.AttackResult result = registration(npc).combatant().attack(victim, attack);
                return "Attack " + npc + ": " + result.status() + " applied=" + result.appliedDamage()
                        + " health-before=" + result.healthBefore() + " health-after=" + result.healthAfter();
            }
            @Override public String control(String npc, UUID mob) {
                if (!(manual(mob) instanceof Mob controlled)) throw new IllegalArgumentException("Target control requires an existing supported mob");
                BukkitCombatBridge.MobLease lease = registration(npc).combatant().control(controlled, MobProfile.DEFAULT);
                leases.put(mob, lease);
                return "Mob control started npc=" + npc + " mob=" + mob + " lifetime-ticks=" + MobProfile.DEFAULT.lifetimeTicks();
            }
            @Override public String releaseMobs(String npc) {
                manualAvailable();
                int released = 0;
                for (Registration registration : List.copyOf(registrations.values())) if (registration.id().equals(npc)) {
                    for (Map.Entry<UUID, BukkitCombatBridge.MobLease> entry : List.copyOf(leases.entrySet())) if (entry.getValue().npcId().equals(registration.combatant().npcId())) {
                        BukkitCombatBridge.MobLease previous = entry.getValue();
                        previous.close(); leases.remove(entry.getKey(), previous); released++;
                    }
                }
                return "Released " + released + " mob control leases for " + npc;
            }
        };
    }

    /** Manual attack and mob control run on the main-thread bridge only. */
    private void manualAvailable() {
        if (regions != null) throw new IllegalStateException("Manual attack and mob control are " + BukkitCombatBridge.FOLIA_UNAVAILABLE);
        if (!bridge.available()) throw new IllegalStateException(status());
        prune();
    }

    private LivingEntity manual(UUID target) {
        manualAvailable();
        Entity entity = plugin.getServer().getEntity(target);
        if (!(entity instanceof LivingEntity living)) throw new IllegalArgumentException("Target must be a loaded living entity");
        return living;
    }

    /** Behavior operations. A refusal carries the reason back to the behavior. Owner thread only. */
    String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) {
        if (regions != null && regionUnavailable == null) return regions.combatOperate(npc, operation, target);
        return module.operate(npc, operation, target);
    }
    /** Hiring a bodyguard through this module. Owner thread only. */
    HireDesk.Guarding hireGuarding() { return regions != null && regionUnavailable == null ? regions.combatGuarding() : new CombatGuarding(module); }

    private TargetRef reference(Entity entity) {
        if (entity instanceof Player) return new TargetRef(TargetKind.PLAYER, entity.getUniqueId());
        return entity instanceof Mob ? new TargetRef(TargetKind.MOB, entity.getUniqueId()) : null;
    }
    private void tick() {
        module.tick();
        if (++ticks % TARGETABLE_SCAN_TICKS == 0) drawMobs();
    }

    /**
     * Each targetable NPC draws nearby melee hostile mobs that have no target of their own and can see it, up to a few at
     * once. A drawn mob walks to the NPC and attacks it under the NPC's defense, then returns to its own AI.
     */
    private void drawMobs() {
        if (!bridge.available() || bridge.capabilities().controlledMobTypes().isEmpty()) return;
        prune();
        List<NpcDefinition> targetable = new ArrayList<>();
        for (NpcDefinition definition : runtime.npcs().snapshot().values())
            if (CombatModule.targetableByMobs(definition)) targetable.add(definition);
        if (targetable.isEmpty()) return;
        int passes = Math.min(TARGETABLE_NPCS_PER_PASS, targetable.size());
        for (int i = 0; i < passes; i++) {
            NpcDefinition definition = targetable.get(Math.floorMod(targetableCursor++, targetable.size()));
            try { draw(definition); }
            catch (RuntimeException refused) { /* Inactive, protected or at the control limit: the next pass tries again. */ }
        }
    }

    private void draw(NpcDefinition definition) {
        if (living.combat(definition.id()).isEmpty()) return;
        Registration npc = registration(definition.id(), CombatModule::targetableByMobs);
        UUID npcId = npc.combatant().npcId();
        int drawn = 0;
        for (BukkitCombatBridge.MobLease lease : leases.values()) if (lease.npcId().equals(npcId)) drawn++;
        if (drawn >= TARGETABLE_PER_NPC) return;
        VirtualNpcState.Snapshot snapshot = npc.state().snapshot();
        World world;
        try { world = plugin.getServer().getWorld(UUID.fromString(snapshot.position().world())); }
        catch (IllegalArgumentException notUuid) { return; }
        Vec3 at = snapshot.position().point();
        if (world == null || !world.isChunkLoaded(((int) Math.floor(at.x())) >> 4, ((int) Math.floor(at.z())) >> 4)) return;
        Location centre = new Location(world, at.x(), at.y() + 1, at.z());
        List<Mob> idle = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(centre, TARGETABLE_RANGE, TARGETABLE_RANGE / 2, TARGETABLE_RANGE))
            if (entity instanceof Mob mob && bridge.capabilities().controlledMobTypes().contains(mob.getType().getKey().toString())
                    && mob.getTarget() == null && !leases.containsKey(mob.getUniqueId()) && sees(mob, centre)) idle.add(mob);
        idle.sort(Comparator.comparingDouble((Mob mob) -> mob.getLocation().distanceSquared(centre)));
        for (Mob mob : idle) {
            if (drawn >= TARGETABLE_PER_NPC) return;
            try {
                AttributeInstance attack = mob.getAttribute(Attribute.ATTACK_DAMAGE);
                leases.put(mob.getUniqueId(), npc.combatant().control(mob, drawnProfile(attack == null ? OptionalDouble.empty() : OptionalDouble.of(attack.getValue()))));
                drawn++;
            } catch (RuntimeException refused) { /* Protection, ownership or limits refused this mob; try the next. */ }
        }
    }

    private static boolean sees(Mob mob, Location target) {
        Location eye = mob.getEyeLocation();
        Vector direction = target.toVector().subtract(eye.toVector());
        double distance = direction.length();
        return distance < 0.0001 || eye.getWorld().rayTraceBlocks(eye, direction.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    /** The mob's own melee damage, a vanilla melee reach and attack interval, and a lease that ends after ten seconds. */
    static MobProfile drawnProfile(OptionalDouble attackDamage) {
        double damage = attackDamage.isEmpty() || !Double.isFinite(attackDamage.getAsDouble()) ? AttackProfile.DEFAULT.damage()
                : Math.max(0.5, Math.min(100, attackDamage.getAsDouble()));
        return new MobProfile(new AttackProfile(damage, AttackProfile.DEFAULT.reach(), 20), 1.0, TARGETABLE_RANGE, 20, TARGETABLE_LEASE_TICKS);
    }
    /** Combat NPCs waiting for a slot on the main thread; 0 on Folia, where each region keeps its own queue. */
    int waiting() { return regions != null ? 0 : module.waiting(); }
    int load() { return regions != null ? 0 : module.load(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerHit(VirtualNpcInteractionEvent event) {
        if (event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.ATTACK || !module.running() || !module.engine().registered(event.getNpcId())) return;
        Player player = event.getViewer();
        AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attack == null ? 1 : attack.getValue();
        double charge = player.getAttackCooldown();
        module.engine().playerAttack(event.getNpcId(), new TargetRef(TargetKind.PLAYER, player.getUniqueId()), position(player.getLocation()), base * (.2 + charge * charge * .8));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!world.helper(projectile)) return;
        event.setCancelled(true);
        TargetRef struck = event.getHitEntity() == null ? null : reference(event.getHitEntity());
        if (module.running() && (struck == null || !module.engine().helperHitReal(projectile.getUniqueId(), struck))) module.engine().helperHitBlock(projectile.getUniqueId());
        projectile.remove();
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperDamage(EntityDamageByEntityEvent event) {
        if (world.helper(event.getDamager())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void realDamage(EntityDamageByEntityEvent event) {
        TargetRef victim = reference(event.getEntity());
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            damager = shooter instanceof Entity source ? source : null;
        }
        TargetRef attacker = damager == null ? null : reference(damager);
        if (victim != null && attacker != null && module.running()) module.engine().realDamaged(victim, attacker);
    }
    /** Chat arrives off the main thread; the engine takes it on its own thread and bounds its queue. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        TargetRef speaker = new TargetRef(TargetKind.PLAYER, event.getPlayer().getUniqueId());
        String message = event.getMessage();
        Runnable deliver = () -> { if (module.running()) module.engine().chat(speaker, message); };
        if (plugin.getServer().isPrimaryThread()) deliver.run();
        else if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, deliver);
    }
    /** Helper fireballs and skulls never explode, even if something else makes them hit. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperExplosion(ExplosionPrimeEvent event) {
        if (world.helper(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent event) {
        if (!world.suppressesDrops(event.getEntity().getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        if (!module.running()) return;
        for (Entity entity : event.getEntities()) if (world.helper(entity)) module.engine().retire(entity.getUniqueId());
    }

    private boolean bridgeAllows(BukkitCombatBridge.Action action, VirtualNpcState.Snapshot npc, LivingEntity real) {
        TargetRef self = new TargetRef(TargetKind.NPC, npc.npcId());
        TargetRef other = new TargetRef(real instanceof Player ? TargetKind.PLAYER : TargetKind.MOB, real.getUniqueId());
        WorldPosition at = position(real.getLocation());
        ProtectionAdapter.Request request = switch (action) {
            case NPC_ATTACK -> new ProtectionAdapter.Request(ProtectionAdapter.Action.NPC_ATTACK, self, npc.position(), other, at,
                    world.observe(real).map((art.arcane.animus.core.combat.CombatTarget target) -> target.categories()).orElse(Set.<TargetCategory>of()));
            case MOB_CONTROL -> new ProtectionAdapter.Request(ProtectionAdapter.Action.MOB_CONTROL, other, at, self, npc.position(), Set.of(TargetCategory.NPCS));
            case MOB_ATTACK -> new ProtectionAdapter.Request(ProtectionAdapter.Action.MOB_ATTACK, other, at, self, npc.position(), Set.of(TargetCategory.NPCS));
        };
        return module.protection().allows(request);
    }
    private final class EngineDefense implements BukkitCombatBridge.Defense {
        @Override public double mitigate(UUID npc, LivingEntity attacker, double amount) {
            if (!module.running()) return amount;
            return module.engine().mitigateExternal(npc, new TargetRef(TargetKind.MOB, attacker.getUniqueId()), position(attacker.getLocation()), amount);
        }
        @Override public void damaged(UUID npc, LivingEntity attacker, VirtualNpcState.Snapshot after) {
            if (module.running()) module.engine().externalHit(npc, new TargetRef(TargetKind.MOB, attacker.getUniqueId()), after);
        }
    }
    private static WorldPosition position(Location location) {
        return new WorldPosition(location.getWorld().getUID().toString(), new Vec3(location.getX(), location.getY(), location.getZ()));
    }

    private Registration registration(String id) { return registration(id, CombatModule::manualAllowed); }

    private Registration registration(String id, Predicate<NpcDefinition> allowed) {
        runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        CombatPolicy policy = new CombatPolicy(id, allowed);
        if (!policy.allowed()) throw new IllegalStateException("NPC combat is disabled or its migration review has not been accepted");
        PersistedNpcService.CombatBinding binding = living.combat(id).orElseThrow(() -> new IllegalStateException("NPC must have an active runtime before combat"));
        UUID uuid = binding.state().snapshot().npcId();
        Registration previous = registrations.get(uuid);
        if (previous != null && previous.state() == binding.state() && previous.combatant().active()) return previous;
        if (previous != null) previous.combatant().close();
        Registration next = new Registration(id, binding.state(), bridge.register(binding.state(),
                () -> binding.current().getAsBoolean() && policy.allowed(), binding.changed()));
        registrations.put(uuid, next);
        return next;
    }

    /** Immutable publication identity avoids copying module JSON on every combat tick. */
    private final class CombatPolicy {
        private final String id;
        private final Predicate<NpcDefinition> rule;
        private NpcDefinition observedDefinition;
        private boolean accepted;
        private CombatPolicy(String id, Predicate<NpcDefinition> rule) { this.id = id; this.rule = rule; }
        private boolean allowed() {
            NpcDefinition current = runtime.npcs().find(id).orElse(null);
            if (current != observedDefinition) { accepted = current != null && rule.test(current); observedDefinition = current; }
            return current != null && accepted;
        }
    }

    private void prune() {
        for (Map.Entry<UUID, Registration> entry : List.copyOf(registrations.entrySet())) if (!entry.getValue().combatant().active()) {
            Registration previous = entry.getValue();
            previous.combatant().close(); registrations.remove(entry.getKey(), previous);
        }
        leases.values().removeIf((BukkitCombatBridge.MobLease lease) -> !lease.active());
    }
    /** Removes every helper arrow and ends all combat before the NPC runtime closes. */
    @Override public void close() {
        if (task != null) task.cancel();
        if (module.supported()) HandlerList.unregisterAll(this);
        module.close();
        bridge.close(); registrations.clear(); leases.clear();
    }
}
