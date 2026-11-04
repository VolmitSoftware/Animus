package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.combat.BukkitCombatBridge;
import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.bukkit.combat.CombatLimits;
import art.arcane.animus.bukkit.combat.MobProfile;
import art.arcane.animus.bukkit.combat.WorldGuardProtection;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.runtime.PersistedNpcService;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.ProtectionGate;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
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
import org.bukkit.event.entity.ProjectileHitEvent;
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
    private record Candidate(String id, CombatSettings settings) {}
    private static final int DEFINITIONS_PER_TICK = 64;
    private static final int ACTIVATIONS_PER_TICK = 16;
    private static final String USAGE = "combat status | combat <npc-id> status|engage|disengage|release | combat <npc-id> target <uuid> | combat <npc-id> guard <uuid>|stop"
            + " | attack <npc-id> <target-uuid> [damage] | target <npc-id> <zombie-uuid>|stop";
    private final JavaPlugin plugin;
    private final AnimusRuntime runtime;
    private final PersistedNpcService living;
    private final BukkitCombatBridge bridge;
    private final BukkitCombatWorld world;
    private final CombatEngine engine;
    private final ProtectionGate protection;
    private final String protectionName;
    private final String moduleUnavailable;
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private final Map<UUID, BukkitCombatBridge.MobLease> leases = new HashMap<>();
    private final Map<UUID, Candidate> candidates = new HashMap<>();
    private Map<UUID, Candidate> nextCandidates = new HashMap<>();
    private Map<String, NpcDefinition> observed;
    private Iterator<NpcDefinition> pass;
    private BukkitTask task;

    RuntimeCombatService(JavaPlugin plugin, AnimusRuntime runtime, PersistedNpcService living, String version, boolean folia) {
        this.plugin = plugin; this.runtime = runtime; this.living = living;
        ProtectionAdapter adapter = ProtectionAdapter.NONE;
        String name = "none installed";
        if (plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                adapter = WorldGuardProtection.create(plugin.getServer());
                name = "WorldGuard region flags";
            } catch (RuntimeException | LinkageError failure) {
                String reason = "WorldGuard is installed but its API could not be used (" + failure.getClass().getSimpleName() + "); all combat damage is denied";
                adapter = request -> { throw new IllegalStateException(reason); };
                name = reason;
            }
        }
        protectionName = name;
        world = new BukkitCombatWorld(plugin, living);
        CombatBudgets budgets = runtime.combatBudgets();
        String unavailable = null;
        if (folia) unavailable = "unavailable on Folia, where the general NPC runtime is disabled";
        else if (!budgets.enabled()) unavailable = "disabled in config.toml";
        else if (!living.available()) unavailable = "unavailable because the NPC runtime is: " + living.status();
        moduleUnavailable = unavailable;
        engine = unavailable == null ? new CombatEngine(budgets, world, adapter, new SplittableRandom(), message -> plugin.getLogger().warning(message)) : null;
        protection = engine != null ? engine.protection() : new ProtectionGate(adapter, message -> plugin.getLogger().warning(message));
        bridge = new BukkitCombatBridge(plugin, version, folia, CombatLimits.DEFAULT, this::bridgeAllows,
                engine == null ? BukkitCombatBridge.Defense.NONE : new EngineDefense());
        if (engine != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    String status() {
        String module = engine == null ? " | module " + moduleUnavailable
                : " | module npcs=" + engine.size() + " | helpers=" + engine.helperCount()
                + " | protection-denials=" + protection.denials() + " | protection-failures=" + protection.failures();
        module += " | protection=" + protectionName;
        BukkitCombatBridge.Capabilities capability = bridge.capabilities();
        if (!bridge.available()) return "Combat unavailable: " + capability.unavailableReason() + module;
        prune();
        return "Combat ready" + module + " | manual registrations=" + bridge.combatantCount() + " | controlled-mobs=" + bridge.controlledMobCount()
                + " | mob-types=" + String.join(",", capability.controlledMobTypes())
                + (capability.mobControlUnavailableReason() == null ? "" : " | " + capability.mobControlUnavailableReason());
    }

    String execute(String[] args) {
        if (args.length > 0 && args[0].equals("combat")) {
            if (args.length == 1 || args.length == 2 && args[1].equals("status")) return status();
            return module(args);
        }
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
        UUID targetId = uuid(args[2]);
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

    private String module(String[] args) {
        if (engine == null) throw new IllegalStateException("Combat module " + moduleUnavailable);
        if (args.length < 3) throw new IllegalArgumentException(USAGE);
        NpcDefinition definition = runtime.npcs().find(args[1]).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + args[1]));
        UUID npc = definition.uuid();
        CombatOperations.Result result = switch (args[2]) {
            case "status" -> {
                if (args.length != 3) throw new IllegalArgumentException(USAGE);
                yield engine.status(npc).map((CombatEngine.Status state) -> new CombatOperations.Result(CombatOperations.Status.APPLIED,
                        state.state() + " target=" + state.target().map((TargetRef ref) -> ref.kind() + ":" + ref.id()).orElse("none") + (state.disengaged() ? " disengaged" : "")))
                        .orElse(new CombatOperations.Result(CombatOperations.Status.NOT_COMBATANT, "not a combat NPC (combat disabled, review not accepted, or not active)"));
            }
            case "engage" -> engine.engage(npc);
            case "disengage" -> engine.disengage(npc);
            case "release" -> engine.release(npc);
            case "target" -> {
                if (args.length != 4) throw new IllegalArgumentException(USAGE);
                yield engine.target(npc, participant(uuid(args[3])));
            }
            case "guard" -> {
                if (args.length != 4) throw new IllegalArgumentException(USAGE);
                yield engine.guard(npc, args[3].equals("stop") ? Optional.empty() : Optional.of(participant(uuid(args[3]))));
            }
            default -> throw new IllegalArgumentException(USAGE);
        };
        return "Combat " + args[1] + ": " + result.status() + " " + result.detail();
    }

    /** Behavior operations. A refusal carries the reason back to the behavior. Owner thread only. */
    String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) {
        if (engine == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", "Combat module " + moduleUnavailable);
        CombatOperations.Result result = switch (operation) {
            case ENABLE -> engine.engage(npc);
            case DISABLE -> engine.disengage(npc);
            case RELEASE -> engine.release(npc);
            case TARGET -> engine.target(npc, participant(target.orElseThrow()));
            case GUARD -> engine.guard(npc, target.map(this::participant));
        };
        return switch (result.status()) {
            case APPLIED -> result.detail();
            case INVALID_TARGET -> throw new CapabilityRefusal(Outcome.Status.INVALID, "combat-target", result.detail());
            case NOT_COMBATANT -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-not-enabled", result.detail());
            case DEAD -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-dead", result.detail());
            case UNAVAILABLE -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", result.detail());
        };
    }

    /** A combat NPC, an online player or a loaded mob; anything else is refused by the engine as not available. */
    private TargetRef participant(UUID id) {
        if (engine != null && engine.registered(id)) return new TargetRef(TargetKind.NPC, id);
        Entity entity = plugin.getServer().getEntity(id);
        return new TargetRef(entity instanceof Player ? TargetKind.PLAYER : TargetKind.MOB, id);
    }
    private TargetRef reference(Entity entity) {
        if (entity instanceof Player) return new TargetRef(TargetKind.PLAYER, entity.getUniqueId());
        return entity instanceof Mob ? new TargetRef(TargetKind.MOB, entity.getUniqueId()) : null;
    }
    private static UUID uuid(String text) {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Expected an entity or NPC UUID: " + text); }
    }

    private void tick() {
        reconcile();
        engine.tick();
    }

    /**
     * Bounded discovery of NPCs whose definitions enable combat. A full pass runs only after a definition
     * publication; activation and despawn are checked every tick for the few NPCs involved.
     */
    private void reconcile() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != observed) {
            observed = snapshot;
            pass = snapshot.values().iterator();
            nextCandidates = new HashMap<>();
        }
        if (pass != null) {
            for (int examined = 0; examined < DEFINITIONS_PER_TICK && pass.hasNext(); examined++) {
                NpcDefinition definition = pass.next();
                if (!definition.spawned()) continue;
                CombatSettings.active(definition).ifPresent((CombatSettings settings) -> nextCandidates.put(definition.uuid(), new Candidate(definition.id(), settings)));
            }
            if (!pass.hasNext()) {
                pass = null;
                candidates.clear();
                candidates.putAll(nextCandidates);
                for (UUID npc : engine.npcs()) if (!candidates.containsKey(npc)) engine.unregister(npc);
            }
        }
        List<UUID> retired = new ArrayList<>();
        for (Map.Entry<UUID, Candidate> entry : candidates.entrySet()) {
            if (living.combatant(entry.getKey()).isEmpty()) retired.add(entry.getKey());
        }
        retired.forEach(engine::unregister);
        int activations = 0;
        for (Map.Entry<UUID, Candidate> entry : candidates.entrySet()) {
            if (activations >= ACTIVATIONS_PER_TICK) break;
            if (retired.contains(entry.getKey())) continue;
            if (engine.status(entry.getKey()).isEmpty()) activations++;
            if (!engine.register(entry.getKey(), entry.getValue().id(), entry.getValue().settings())) break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerHit(VirtualNpcInteractionEvent event) {
        if (event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.ATTACK || !engine.registered(event.getNpcId())) return;
        Player player = event.getViewer();
        AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attack == null ? 1 : attack.getValue();
        double charge = player.getAttackCooldown();
        engine.playerAttack(event.getNpcId(), new TargetRef(TargetKind.PLAYER, player.getUniqueId()), position(player.getLocation()), base * (.2 + charge * charge * .8));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!world.helper(projectile)) return;
        event.setCancelled(true);
        TargetRef struck = event.getHitEntity() == null ? null : reference(event.getHitEntity());
        if (struck == null || !engine.helperHitReal(projectile.getUniqueId(), struck)) engine.helperHitBlock(projectile.getUniqueId());
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
        if (victim != null && attacker != null) engine.realDamaged(victim, attacker);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent event) {
        if (!world.suppressesDrops(event.getEntity().getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (world.helper(entity)) engine.retire(entity.getUniqueId());
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
        return protection.allows(request);
    }
    private final class EngineDefense implements BukkitCombatBridge.Defense {
        @Override public double mitigate(UUID npc, LivingEntity attacker, double amount) {
            return engine.mitigateExternal(npc, new TargetRef(TargetKind.MOB, attacker.getUniqueId()), position(attacker.getLocation()), amount);
        }
        @Override public void damaged(UUID npc, LivingEntity attacker, VirtualNpcState.Snapshot after) {
            engine.externalHit(npc, new TargetRef(TargetKind.MOB, attacker.getUniqueId()), after);
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

    private static boolean combatAllowed(NpcDefinition definition) {
        if (definition == null) return false;
        JsonObject document = definition.json();
        if (!document.has("modules") || !document.getAsJsonObject("modules").has(CombatSettings.MODULE)) return true;
        JsonElement value = document.getAsJsonObject("modules").get(CombatSettings.MODULE);
        return value.isJsonObject() && enabled(value.getAsJsonObject(), "enabled") && enabled(value.getAsJsonObject(), "reviewAccepted");
    }

    /** Immutable publication identity avoids copying module JSON on every combat tick. */
    private final class CombatPolicy {
        private final String id;
        private NpcDefinition observedDefinition;
        private boolean accepted;
        private CombatPolicy(String id) { this.id = id; }
        private boolean allowed() {
            NpcDefinition current = runtime.npcs().find(id).orElse(null);
            if (current != observedDefinition) { accepted = combatAllowed(current); observedDefinition = current; }
            return current != null && accepted;
        }
    }

    private static boolean enabled(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
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
        if (engine != null) {
            HandlerList.unregisterAll(this);
            engine.close();
        }
        bridge.close(); registrations.clear(); leases.clear(); candidates.clear();
    }
}
