package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.CombatControl;
import art.arcane.animus.core.hire.CombatGuarding;
import art.arcane.animus.core.hire.HireDesk;
import art.arcane.animus.bukkit.runtime.RegionManualCombat;
import art.arcane.animus.bukkit.runtime.RegionNpcRuntime;
import org.bukkit.inventory.ItemStack;
import art.arcane.animus.core.runtime.CombatModule;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.bukkit.combat.BukkitCombatBridge;
import art.arcane.animus.bukkit.combat.CombatHits;
import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.bukkit.combat.BukkitShots;
import art.arcane.animus.bukkit.combat.CombatLimits;
import art.arcane.animus.bukkit.combat.MobProfile;
import art.arcane.animus.bukkit.combat.Explosions;
import art.arcane.animus.bukkit.combat.NpcDecoys;
import art.arcane.animus.bukkit.combat.ProjectileStrikes;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.VanillaMelee;
import art.arcane.animus.bukkit.protection.RegionCombatProtection;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.RealShot;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.World;
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
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The combat module on Bukkit: runs the core engine for NPCs whose definitions enable combat, and keeps the
 * explicit administrator adapter (one manual hit, mob control). NPCs whose combat sets {@code targetableByMobs} draw
 * nearby mobs that would fight a player in their place through {@link NpcDecoys}. Explosions reach every combat NPC
 * that takes them. Preview services never reach this.
 */
final class RuntimeCombatService implements Listener, AutoCloseable {
    private record Registration(String id, VirtualNpcState state, BukkitCombatBridge.Combatant combatant) {}
    /** A real projectile from a player or mob, followed past NPC bodies until it lands, expires or strikes. */
    private record Flying(Projectile projectile, RealShot.Kind kind, TargetRef shooter, ProjectileFlight flight, long expires) {}
    /** At most this many projectiles are followed at once, each for at most this many ticks. */
    static final int MAXIMUM_FLYING = 2048, FLIGHT_TICKS = 1200;
    /** How often targetable NPCs look for mobs that would fight them, and how many NPCs one pass visits. */
    static final int TARGETABLE_SCAN_TICKS = 20, TARGETABLE_NPCS_PER_PASS = 32;
    /** How long a fight between a player and an NPC turns the player's wolves on the NPC. */
    static final long FOUGHT_TICKS = 200;
    /** How far an explosion can reach an NPC: twice the largest vanilla power. */
    static final double EXPLOSION_REACH = 14;
    private final JavaPlugin plugin;
    private final AnimusRuntime runtime;
    private final PersistedNpcService<Player, ItemStack> living;
    private final BukkitCombatBridge bridge;
    private final BukkitCombatWorld world;
    private final CombatModule module;
    private final String protectionName;
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private final Map<UUID, BukkitCombatBridge.MobLease> leases = new HashMap<>();
    private final Map<UUID, Flying> flying = new HashMap<>();
    private final Map<UUID, CombatHits.Swing> swings = new ConcurrentHashMap<>();
    private final SplittableRandom random = new SplittableRandom();
    private BukkitTask task;
    private long ticks;
    private int targetableCursor;
    private Map<String, NpcDefinition> targetableSource;
    private List<NpcDefinition> targetableCache = List.of();
    private Map<UUID, NpcDefinition> targetableByUuid = Map.of();
    private final NpcDecoys decoys;
    /** Marks each projectile that struck an NPC, so a decoy it touches again counts nothing. */
    private final org.bukkit.NamespacedKey struckKey;
    /** When each player and NPC last fought, either way round, by NPC; pruned as they age. */
    private final Map<UUID, Map<UUID, Long>> fought = new HashMap<>();
    /** Folia: combat runs on each NPC's region owner; null elsewhere. */
    private final RegionNpcRuntime regions;
    /** Why region combat cannot run, or null while it runs. */
    private final String regionUnavailable;
    /** Folia: manual attack and mob control on each subject's region; null elsewhere. */
    private final RegionManualCombat regionManual;
    private volatile ProjectileStrikes strikes = ProjectileStrikes.NONE;

    /** {@code living} is the main-thread runtime and null on Folia, where {@code regions} runs combat on each NPC's owner. */
    RuntimeCombatService(JavaPlugin plugin, AnimusRuntime runtime, PersistedNpcService<Player, ItemStack> living, String version, boolean folia, RegionNpcRuntime regions) {
        this.plugin = plugin; this.runtime = runtime; this.living = living;
        RegionFlags.Binding flags = RegionFlags.bind(plugin.getServer());
        ProtectionAdapter adapter = runtime.moduleHooks().protection(flags == null ? ProtectionAdapter.NONE : new RegionCombatProtection(flags.flags()));
        protectionName = flags == null ? "none installed" : flags.describe("all combat damage is denied");
        this.regions = folia ? regions : null;
        regionUnavailable = this.regions == null ? null : this.regions.enableCombat(adapter, protectionName);
        regionManual = this.regions == null ? null : new RegionManualCombat(this.regions, version, adapter);
        world = folia ? null : new BukkitCombatWorld(plugin, living);
        module = new CombatModule(runtime, living, world, adapter, folia ? (regionUnavailable == null ? "runs on each NPC's region on Folia" : regionUnavailable) : null,
                (String message) -> plugin.getLogger().warning(message), (UUID id) -> plugin.getServer().getPlayer(id) != null);
        bridge = new BukkitCombatBridge(plugin, version, folia, CombatLimits.DEFAULT, this::bridgeAllows,
                module.supported() ? new EngineDefense() : BukkitCombatBridge.Defense.NONE);
        decoys = folia ? null : new NpcDecoys(plugin, new DecoyHost());
        struckKey = new org.bukkit.NamespacedKey(plugin, "struck_npc");
        if (module.supported()) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    String status() {
        if (regions != null) {
            if (regionUnavailable != null) return "Combat unavailable: " + regionUnavailable + " | protection=" + protectionName;
            return regions.combatStatus() + " | " + regionManual.status();
        }
        String modulePart = module.status() + " | protection=" + protectionName;
        BukkitCombatBridge.Capabilities capability = bridge.capabilities();
        if (!bridge.available()) return "Combat unavailable: " + capability.unavailableReason() + modulePart;
        prune();
        return "Combat ready" + modulePart + " | manual registrations=" + bridge.combatantCount() + " | controlled-mobs=" + bridge.controlledMobCount()
                + " | mob-types=" + String.join(",", capability.controlledMobTypes()) + " | decoys=" + decoys.size()
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
                if (regions != null) return regionManual.attack(npc, target, damage);
                LivingEntity victim = manual(target);
                AttackProfile attack = new AttackProfile(damage, AttackProfile.DEFAULT.reach(), AttackProfile.DEFAULT.cooldownTicks());
                BukkitCombatBridge.AttackResult result = registration(npc).combatant().attack(victim, attack);
                return "Attack " + npc + ": " + result.status() + " applied=" + result.appliedDamage()
                        + " health-before=" + result.healthBefore() + " health-after=" + result.healthAfter();
            }
            @Override public String control(String npc, UUID mob) {
                if (regions != null) return regionManual.control(npc, mob);
                if (!(manual(mob) instanceof Mob controlled)) throw new IllegalArgumentException("Target control requires an existing supported mob");
                BukkitCombatBridge.MobLease lease = registration(npc).combatant().control(controlled, MobProfile.DEFAULT);
                leases.put(mob, lease);
                return "Mob control started npc=" + npc + " mob=" + mob + " lifetime-ticks=" + MobProfile.DEFAULT.lifetimeTicks();
            }
            @Override public String releaseMobs(String npc) {
                if (regions != null) return regionManual.releaseMobs(npc);
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

    /** Manual attack and mob control on the main-thread bridge; Folia uses {@link RegionManualCombat} instead. */
    private void manualAvailable() {
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

    private void tick() {
        module.tick();
        flights();
        if (!module.running()) { decoys.close(); return; }
        decoys.tick();
        if (++ticks % TARGETABLE_SCAN_TICKS == 0) drawMobs();
    }

    /** A share of the targetable NPCs draws mobs each pass, so every one is visited within a few seconds. */
    private void drawMobs() {
        List<NpcDefinition> targetable = targetable();
        fought.values().forEach((Map<UUID, Long> players) -> players.values().removeIf((Long at) -> ticks - at > FOUGHT_TICKS));
        fought.values().removeIf(Map::isEmpty);
        if (targetable.isEmpty()) return;
        int passes = Math.min(TARGETABLE_NPCS_PER_PASS, targetable.size());
        List<UUID> visit = new ArrayList<>();
        for (int i = 0; i < passes; i++) visit.add(targetable.get(Math.floorMod(targetableCursor++, targetable.size())).uuid());
        decoys.draw(visit);
    }

    /** Definitions that mobs may target, read again only when the definition snapshot changes. */
    private List<NpcDefinition> targetable() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != targetableSource) {
            List<NpcDefinition> found = new ArrayList<>();
            Map<UUID, NpcDefinition> byUuid = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) if (CombatModule.targetableByMobs(definition)) { found.add(definition); byUuid.put(definition.uuid(), definition); }
            targetableCache = List.copyOf(found); targetableByUuid = Map.copyOf(byUuid); targetableSource = snapshot;
        }
        return targetableCache;
    }

    /** What the decoys read: targetable NPCs the engine runs, their body type, protection and who fought whom. */
    private final class DecoyHost implements NpcDecoys.Host {
        @Override public Optional<VirtualNpcState.Snapshot> targetable(UUID npc) {
            RuntimeCombatService.this.targetable();
            if (!module.running() || !targetableByUuid.containsKey(npc) || !module.engine().registered(npc)) return Optional.empty();
            return world.npc(npc).filter((VirtualNpcState.Snapshot self) -> self.active() && self.health() > 0);
        }
        @Override public String body(UUID npc) {
            NpcDefinition definition = targetableByUuid.get(npc);
            com.google.gson.JsonElement type = definition == null ? null : definition.json().get("type");
            return type != null && type.isJsonPrimitive() ? type.getAsString() : "minecraft:player";
        }
        @Override public boolean allows(Mob mob, VirtualNpcState.Snapshot npc) {
            return module.protection().allows(new ProtectionAdapter.Request(ProtectionAdapter.Action.MOB_ATTACK, new TargetRef(TargetKind.MOB, mob.getUniqueId()),
                    position(mob.getLocation()), new TargetRef(TargetKind.NPC, npc.npcId()), npc.position(), Set.of(TargetCategory.NPCS)));
        }
        @Override public boolean fought(UUID npc, UUID player) {
            Long at = RuntimeCombatService.this.fought.getOrDefault(npc, Map.of()).get(player);
            return at != null && ticks - at <= FOUGHT_TICKS;
        }
    }
    private void foughtBetween(UUID npc, UUID player) { fought.computeIfAbsent(npc, (UUID key) -> new HashMap<>()).put(player, ticks); }

    /**
     * Whether combat follows this real projectile and stops it in the NPC's body: combat runs for the NPC, it is alive and
     * its defense takes projectiles. Any other projectile flies on through. On the projectile's owner.
     */
    boolean stops(Projectile projectile, UUID npc) {
        if (regions != null) return regionUnavailable == null && regions.combatStops(projectile, npc);
        return module.running() && flying.containsKey(projectile.getUniqueId()) && module.engine().shootable(npc);
    }
    /** Whether this entity stands in for an NPC body: a decoy that mobs fight. On the entity's owner. */
    boolean standIn(Entity entity) { return decoys != null && decoys.decoy(entity); }
    /** Who hears each projectile combat stops in an NPC's body, before the strike is dealt. */
    void strikes(ProjectileStrikes listener) {
        strikes = listener;
        if (regions != null) regions.combatStrikes(listener);
    }
    /** Combat NPCs waiting for a slot on the main thread; 0 on Folia, where each region keeps its own queue. */
    int waiting() { return regions != null ? 0 : module.waiting(); }
    int load() { return regions != null ? 0 : module.load(); }

    /**
     * A player's hit on a combat NPC, worked out as vanilla works it out from the player's state and held weapon, with
     * vanilla's attack sounds. A sword's sweep also strikes the real mobs and players beside the NPC.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerHit(VirtualNpcInteractionEvent event) {
        if (event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.ATTACK || !module.running() || !module.engine().registered(event.getNpcId())) return;
        Player player = event.getViewer();
        VanillaMelee.Weapon weapon = CombatHits.weapon(player.getInventory().getItemInMainHand());
        CombatEngine.PlayerHit hit = module.engine().playerAttack(event.getNpcId(), new TargetRef(TargetKind.PLAYER, player.getUniqueId()), position(player.getLocation()),
                CombatHits.swing(player, swings.remove(player.getUniqueId())), weapon);
        if (hit.strike().isEmpty()) return;
        CombatHits.sounds(player, hit.strike().get());
        if (hit.outcome().status() != CombatWorld.DamageStatus.APPLIED) return;
        foughtBetween(event.getNpcId(), player.getUniqueId());
        if (hit.strike().get().sweep() > 0) world.npc(event.getNpcId()).ifPresent((VirtualNpcState.Snapshot struck) -> sweepReal(player, struck, hit.strike().get(), weapon));
    }
    /** The real living entities a sweep beside this NPC reaches: within a block of its body and three blocks of the player. */
    private void sweepReal(Player player, VirtualNpcState.Snapshot struck, VanillaMelee.Strike strike, VanillaMelee.Weapon weapon) {
        art.arcane.animus.core.virtual.Hitbox reach = struck.bounds().inflated(1, .25, 1);
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(reach.min().x(), reach.min().y(), reach.min().z(), reach.max().x(), reach.max().y(), reach.max().z());
        for (Entity entity : player.getWorld().getNearbyEntities(box, (Entity candidate) -> candidate instanceof LivingEntity)) {
            if (entity == player || world.helper(entity) || decoys.decoy(entity) || entity instanceof org.bukkit.entity.ArmorStand stand && stand.isMarker()
                    || entity.getLocation().distanceSquared(player.getLocation()) >= 9) continue;
            ((LivingEntity) entity).damage(VanillaMelee.swept(strike, weapon, VanillaMelee.groups(entity.getType().getKey().toString())), player);
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void swing(PlayerAnimationEvent event) { swings.put(event.getPlayer().getUniqueId(), CombatHits.Swing.of(event.getPlayer())); }
    @EventHandler
    public void quit(PlayerQuitEvent event) { swings.remove(event.getPlayer().getUniqueId()); }
    /** A player or mob fired a projectile that can hurt an NPC; it is followed from where it left. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launched(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!module.running() || flying.size() >= MAXIMUM_FLYING || world.helper(projectile)) return;
        Optional<RealShot.Kind> kind = BukkitShots.kind(projectile);
        TargetRef shooter = BukkitShots.shooter(projectile);
        if (kind.isEmpty() || shooter == null) return;
        ProjectileFlight flight = new ProjectileFlight(BukkitShots.pierce(projectile));
        flight.launched(BukkitShots.position(projectile));
        flying.put(projectile.getUniqueId(), new Flying(projectile, kind.get(), shooter, flight, ticks + FLIGHT_TICKS));
    }
    private static final art.arcane.animus.core.virtual.SpatialNpcIndex.Scan EMPTY_SCAN = new art.arcane.animus.core.virtual.SpatialNpcIndex.Scan(List.of(), Optional.empty(), 0, 0);
    private static final ProjectileFlight.Scanner NOTHING_NEAR = (WorldPosition center, double radius, art.arcane.animus.core.virtual.SpatialNpcIndex.Budget budget) -> EMPTY_SCAN;
    /** Each followed projectile's last move is tested against NPC bodies; a strike is a projectile hit by its shooter. */
    private void flights() {
        if (flying.isEmpty()) return;
        if (!module.running()) { flying.clear(); return; }
        // With no combat NPC registered nothing can be struck: flights still advance, but skip the spatial query.
        ProjectileFlight.Scanner scanner = module.engine().size() == 0 ? NOTHING_NEAR
                : (WorldPosition center, double radius, art.arcane.animus.core.virtual.SpatialNpcIndex.Budget budget) -> world.nearbyNpcs(center, radius, null, budget);
        for (java.util.Iterator<Flying> each = flying.values().iterator(); each.hasNext();) {
            Flying next = each.next();
            Projectile projectile = next.projectile();
            if (!projectile.isValid() || ticks > next.expires() || projectile instanceof org.bukkit.entity.AbstractArrow arrow && arrow.isInBlock()) { each.remove(); continue; }
            Optional<ProjectileFlight.Hit> hit = next.flight().advance(BukkitShots.position(projectile), BukkitShots.velocity(projectile), scanner, module.engine()::shootable);
            if (hit.isEmpty()) continue;
            NpcDecoys.firstStrike(projectile, struckKey);
            strikes.struck(projectile, hit.get().npc(), hit.get().point(), next.flight().spent());
            module.engine().shot(hit.get().npc(), BukkitShots.shot(projectile, next.kind(), next.shooter(), next.flight(), random));
            BukkitShots.land(projectile, next.kind(), hit.get().point(), next.flight().spent());
            if (next.flight().spent()) each.remove();
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!world.helper(projectile) || decoys.decoy(event.getHitEntity())) return;
        event.setCancelled(true);
        TargetRef struck = event.getHitEntity() == null ? null : CombatHits.reference(event.getHitEntity());
        if (module.running() && (struck == null || !module.engine().helperHitReal(projectile.getUniqueId(), struck))) module.engine().helperHitBlock(projectile.getUniqueId());
        projectile.remove();
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperDamage(EntityDamageByEntityEvent event) {
        if (world.helper(event.getDamager())) event.setCancelled(true);
    }
    /**
     * Nothing hurts a decoy. A mob's melee hit on one is dealt to its NPC through the NPC's defense, after the API's
     * mob damage event; projectiles and explosions reach the NPC through their own handlers.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void decoyDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!decoys.decoy(event.getEntity())) return;
        event.setCancelled(true);
        Optional<UUID> npc = decoys.npc(event.getEntity());
        if (npc.isEmpty() || !module.running() || !(event instanceof EntityDamageByEntityEvent byEntity) || !(byEntity.getDamager() instanceof Mob mob)) return;
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        art.arcane.animus.bukkit.event.VirtualNpcDamagedByMobEvent api = new art.arcane.animus.bukkit.event.VirtualNpcDamagedByMobEvent(npc.get(), mob, event.getDamage());
        plugin.getServer().getPluginManager().callEvent(api);
        if (api.isCancelled()) return;
        AttributeInstance knockback = mob.getAttribute(Attribute.ATTACK_KNOCKBACK);
        module.engine().realHit(npc.get(), new TargetRef(TargetKind.MOB, mob.getUniqueId()), position(mob.getLocation()), api.getDamage(),
                VanillaMelee.BASE_KNOCKBACK + (knockback == null ? 0 : knockback.getValue() / 2), CombatWorld.DamageKind.MELEE);
    }
    /**
     * A projectile that touches a decoy struck its NPC: a player's or mob's is a shot by its shooter, as one striking the
     * body is, and lands as it would on a body. An NPC's own helper passes through, as its flight is followed already.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void decoyHit(ProjectileHitEvent event) {
        if (!decoys.decoy(event.getHitEntity())) return;
        event.setCancelled(true);
        Projectile projectile = event.getEntity();
        Optional<UUID> npc = decoys.npc(event.getHitEntity());
        if (npc.isEmpty() || !module.running() || world.helper(projectile) || !module.engine().shootable(npc.get())) return;
        if (!NpcDecoys.firstStrike(projectile, struckKey)) return;
        Optional<RealShot.Kind> kind = BukkitShots.kind(projectile);
        TargetRef shooter = BukkitShots.shooter(projectile);
        if (kind.isEmpty() || shooter == null) return;
        Flying followed = flying.remove(projectile.getUniqueId());
        ProjectileFlight flight = followed == null ? new ProjectileFlight(BukkitShots.pierce(projectile)) : followed.flight();
        if (followed == null) flight.launched(BukkitShots.position(projectile));
        strikes.struck(projectile, npc.get(), BukkitShots.position(projectile).point(), true);
        module.engine().shot(npc.get(), BukkitShots.shot(projectile, kind.get(), shooter, flight, random));
        BukkitShots.land(projectile, kind.get(), BukkitShots.position(projectile).point(), true);
    }
    /** An explosion hurts every combat NPC in reach that takes explosions, by vanilla's damage for its power. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exploded(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (world.helper(event.getEntity())) return;
        Entity source = event.getEntity();
        Entity cause = source instanceof org.bukkit.entity.TNTPrimed tnt ? tnt.getSource() : source;
        blast(event.getLocation(), Explosions.power(source), Optional.ofNullable(cause == null ? null : CombatHits.reference(cause)));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExploded(org.bukkit.event.block.BlockExplodeEvent event) {
        blast(event.getBlock().getLocation().add(.5, .5, .5), Explosions.BLOCK_POWER, Optional.empty());
    }
    private void blast(Location centre, float power, Optional<TargetRef> source) {
        if (!module.running() || module.engine().size() == 0) return;
        WorldPosition at = position(centre);
        art.arcane.animus.core.virtual.SpatialNpcIndex.Scan near = world.nearbyNpcs(at, Math.min(EXPLOSION_REACH, power * 2), null,
                new art.arcane.animus.core.virtual.SpatialNpcIndex.Budget(64, 256));
        for (VirtualNpcState.Snapshot npc : near.candidates()) {
            if (!npc.active() || !module.engine().registered(npc.npcId())) continue;
            double damage = Explosions.damage(centre.getWorld(), at.point(), power, npc.bounds());
            if (damage > 0) module.engine().exploded(npc.npcId(), source, at, damage);
        }
    }
    /** An NPC hit a player: the player's wolves may turn on it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void npcHitPlayer(art.arcane.animus.bukkit.event.VirtualNpcDamageEvent event) {
        if (event.getTarget() instanceof Player player) foughtBetween(event.getNpcId(), player.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void realDamage(EntityDamageByEntityEvent event) {
        TargetRef victim = CombatHits.reference(event.getEntity());
        TargetRef attacker = CombatHits.attacker(event.getDamager());
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
            TargetRef mob = new TargetRef(TargetKind.MOB, attacker.getUniqueId());
            living.blame(npc, art.arcane.animus.core.runtime.RuntimeCombatWorld.attacker(mob));
            if (!module.running()) return amount;
            return module.engine().mitigateExternal(npc, mob, position(attacker.getLocation()), amount);
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
        if (!policy.allowed()) throw new IllegalStateException("NPC combat is disabled");
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
        if (regionManual != null) regionManual.close();
        if (module.supported()) HandlerList.unregisterAll(this);
        if (decoys != null) decoys.close();
        module.close();
        bridge.close(); registrations.clear(); leases.clear(); flying.clear();
    }
}
