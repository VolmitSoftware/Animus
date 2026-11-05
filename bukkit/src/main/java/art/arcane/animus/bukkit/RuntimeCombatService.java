package art.arcane.animus.bukkit;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * explicit administrator adapter (one manual hit, ordinary-zombie control). Preview services never reach this.
 */
final class RuntimeCombatService implements Listener, AutoCloseable {
    private record Registration(String id, VirtualNpcState state, BukkitCombatBridge.Combatant combatant) {}
    private static final String USAGE = CombatModule.MODULE_USAGE + " | attack <npc-id> <target-uuid> [damage] | target <npc-id> <zombie-uuid>|stop";
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

    String execute(String[] args) {
        if (args.length > 0 && args[0].equals("combat")) {
            if (args.length == 1 || args.length == 2 && args[1].equals("status")) return status();
            if (regions != null) {
                if (regionUnavailable != null) throw new IllegalStateException("Combat module " + regionUnavailable);
                return regions.combatCommand(args);
            }
            return module.execute(args);
        }
        if (regions != null) throw new IllegalStateException("Manual attack and mob control are " + BukkitCombatBridge.FOLIA_UNAVAILABLE);
        if (!bridge.available()) throw new IllegalStateException(status());
        prune();
        if (args.length == 3 && args[0].equals("target") && args[2].equals("stop")) {
            int released = 0;
            for (Registration registration : List.copyOf(registrations.values())) if (registration.id().equals(args[1])) {
                for (Map.Entry<UUID, BukkitCombatBridge.MobLease> entry : List.copyOf(leases.entrySet())) if (entry.getValue().npcId().equals(registration.combatant().npcId())) {
                    BukkitCombatBridge.MobLease previous = entry.getValue();
                    previous.close(); leases.remove(entry.getKey(), previous); released++;
                }
            }
            return "Released " + released + " mob control leases for " + args[1];
        }
        if (args.length < 3 || args.length > 4 || args[0].equals("target") && args.length != 3) throw new IllegalArgumentException(USAGE);
        UUID targetId = CombatModule.uuid(args[2]);
        Entity entity = plugin.getServer().getEntity(targetId);
        if (!(entity instanceof LivingEntity target)) throw new IllegalArgumentException("Target must be a loaded living entity");
        if (args[0].equals("attack")) {
            double amount = args.length == 4 ? Double.parseDouble(args[3]) : AttackProfile.DEFAULT.damage();
            AttackProfile attack = new AttackProfile(amount, AttackProfile.DEFAULT.reach(), AttackProfile.DEFAULT.cooldownTicks());
            Registration npc = registration(args[1]);
            BukkitCombatBridge.AttackResult result = npc.combatant().attack(target, attack);
            return "Attack " + args[1] + ": " + result.status() + " applied=" + result.appliedDamage()
                    + " health-before=" + result.healthBefore() + " health-after=" + result.healthAfter();
        }
        if (!args[0].equals("target") || !(target instanceof Mob mob)) throw new IllegalArgumentException("Target control requires an existing supported mob");
        Registration npc = registration(args[1]);
        BukkitCombatBridge.MobLease lease = npc.combatant().control(mob, MobProfile.DEFAULT);
        leases.put(targetId, lease);
        return "Mob control started npc=" + args[1] + " mob=" + targetId + " lifetime-ticks=" + MobProfile.DEFAULT.lifetimeTicks();
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
    private void tick() { module.tick(); }

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

    private Registration registration(String id) {
        runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        CombatPolicy policy = new CombatPolicy(id);
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
        private NpcDefinition observedDefinition;
        private boolean accepted;
        private CombatPolicy(String id) { this.id = id; }
        private boolean allowed() {
            NpcDefinition current = runtime.npcs().find(id).orElse(null);
            if (current != observedDefinition) { accepted = CombatModule.manualAllowed(current); observedDefinition = current; }
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
