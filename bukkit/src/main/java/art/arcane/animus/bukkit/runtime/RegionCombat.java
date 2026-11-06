package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.command.CombatControl;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.hire.HireDesk;
import art.arcane.animus.core.runtime.CombatDeletions;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

/**
 * The combat module on Folia. Each combat NPC runs its own engine on its owner ({@link ActorCombat}); this class only
 * routes between owners: hits, heals and squad alerts are messages to the receiving NPC's owner, and fights reach the
 * guards and witnesses that care through their mailboxes. Bukkit events arrive on the thread that owns their subject
 * and touch only that subject before they hand off. Engines draw their per-tick work from the region's budgets.
 * <p>
 * {@code [combat]} settings apply live: a hotload publishes them here, the region budgets take them at each region's next
 * tick, and every NPC's engine swaps them in on its own next turn. Turning combat off winds each engine down on its owner
 * and closes it once its helpers are gone; turning it on lets each NPC open its engine again.
 */
public final class RegionCombat implements Listener, AutoCloseable {
    /** At most this many owners hear of one fight or chat line; the rest are dropped and counted. */
    private static final int DELIVERIES_PER_EVENT = 64;
    private final RegionNpcRuntime host;
    /** The budgets in effect; replaced by a hotload, read by each NPC's owner at the start of its combat turn. */
    volatile CombatBudgets budgets;
    private final AutoCloseable settingsListener;
    final ProtectionAdapter protection;
    final Set<UUID> suppressedDrops = ConcurrentHashMap.newKeySet();
    private final String protectionName;
    private final NamespacedKey helperKey, ownerKey;
    private final Map<UUID, Set<RegionActor>> guardians = new ConcurrentHashMap<>();
    private final Set<RegionActor> watchers = ConcurrentHashMap.newKeySet();
    private final Set<RegionActor> combatants = ConcurrentHashMap.newKeySet();
    private final AtomicLong denials = new AtomicLong(), failures = new AtomicLong(), messages = new AtomicLong(), dropped = new AtomicLong(), deleted = new AtomicLong();
    private volatile Executor files;
    private volatile boolean closed;

    RegionCombat(RegionNpcRuntime host, CombatBudgets budgets, ProtectionAdapter adapter, String protectionName) {
        this.host = host; this.protectionName = protectionName;
        apply(budgets);
        protection = request -> {
            ProtectionAdapter.Decision decision;
            try { decision = adapter.decide(request); }
            catch (RuntimeException | LinkageError failure) { failures.incrementAndGet(); throw failure; }
            if (decision != ProtectionAdapter.Decision.ALLOW) denials.incrementAndGet();
            return decision;
        };
        helperKey = new NamespacedKey(host.plugin, "combat_helper");
        ownerKey = new NamespacedKey(host.plugin, "combat_helper_owner");
        host.plugin.getServer().getPluginManager().registerEvents(this, host.plugin);
        settingsListener = host.runtime.onSettings((art.arcane.animus.core.config.PluginSettings next) -> {
            boolean was = this.budgets.enabled();
            apply(next.combat());
            if (was != next.combat().enabled())
                warn(next.combat().enabled() ? "Combat started: enabled in config.toml" : "Combat stopping: disabled in config.toml; helpers in flight land or expire first");
        });
    }

    /** Any thread: publishes budgets. Region limits take them at each region's next tick, engines at their next turn. */
    private void apply(CombatBudgets next) {
        host.budget.limit(RegionBudget.Kind.COMBAT_BRAINS, next.brainsPerTick());
        host.budget.limit(RegionBudget.Kind.COMBAT_SCANS, next.targetScansPerTick());
        host.budget.limit(RegionBudget.Kind.COMBAT_PATHS, next.pathRequestsPerTick());
        host.budget.limit(RegionBudget.Kind.COMBAT_ALERTS, next.squadAlertsPerTick());
        host.budget.limit(RegionBudget.Kind.COMBAT_HELPERS, next.helperUpdatesPerTick());
        budgets = next;
    }
    boolean enabled() { return budgets.enabled(); }

    String status() {
        int helpers = 0;
        for (RegionActor actor : combatants) { RegionActor.Publication view = actor.publication; if (view != null && view.combat() != null) helpers += view.combat().helpers(); }
        CombatBudgets budgets = this.budgets;
        return (budgets.enabled() ? "Combat ready on Folia regions" : "Combat unavailable: disabled in config.toml") + " | module npcs=" + combatants.size() + " | helpers=" + helpers + " | protection=" + protectionName
                + " | protection-denials=" + denials.get() + " | protection-failures=" + failures.get() + " | owner-messages=" + messages.get()
                + " | messages-dropped=" + dropped.get() + " | deleted-on-death=" + deleted.get()
                + " | limits per region tick: brains=" + budgets.brainsPerTick() + ", scans=" + budgets.targetScansPerTick() + ", paths=" + budgets.pathRequestsPerTick();
    }

    /** Engine work for one region tick, shared by every combat NPC that region runs. */
    CombatEngine.Allowance allowance() {
        return new CombatEngine.Allowance() {
            @Override public int take(Kind kind, int wanted) { return host.budget.take(kind(kind), wanted); }
            @Override public void refund(Kind kind, int unused) { host.budget.refund(kind(kind), unused); }
        };
    }
    private static RegionBudget.Kind kind(CombatEngine.Allowance.Kind kind) {
        return switch (kind) {
            case BRAINS -> RegionBudget.Kind.COMBAT_BRAINS;
            case SCANS -> RegionBudget.Kind.COMBAT_SCANS;
            case PATHS -> RegionBudget.Kind.COMBAT_PATHS;
            case ALERTS -> RegionBudget.Kind.COMBAT_ALERTS;
            case HELPER_UPDATES -> RegionBudget.Kind.COMBAT_HELPERS;
        };
    }
    /** One brain turn this region tick for an NPC refused {@code waited} ticks in a row. */
    boolean turn(int waited) { return host.budget.takeCombatTurn(waited); }
    void warn(String message) { host.plugin.getLogger().warning(message); }

    // ---- Owners and messages ----

    /** Owner only: the actor's combat starts here. */
    ActorCombat open(RegionActor actor, art.arcane.animus.core.combat.CombatSettings settings, art.arcane.animus.core.combat.CombatLoadout loadout) {
        if (closed || !enabled()) return null;
        combatants.add(actor);
        return new ActorCombat(actor, this, settings, loadout);
    }
    void closed(RegionActor actor) { combatants.remove(actor); }
    RegionActor actor(UUID npc) { return host.byUuid.get(npc); }
    /**
     * Runs work on the target NPC's owner, at once when this thread owns it. The work sees the NPC's combat as it is on
     * that owner then; an NPC without combat by then ignores it. False when the owner refused or its mailbox is full.
     */
    boolean send(RegionActor target, String kind, Consumer<ActorCombat> work) {
        messages.incrementAndGet();
        boolean accepted = target.owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { ActorCombat combat = target.combat(); if (combat != null) work.accept(combat); }
            @Override public void refuse(String reason) { dropped.incrementAndGet(); }
        });
        if (!accepted) dropped.incrementAndGet();
        return accepted;
    }
    void guarding(RegionActor actor, UUID before, UUID after) {
        if (before != null) guardians.computeIfPresent(before, (UUID key, Set<RegionActor> set) -> { set.remove(actor); return set.isEmpty() ? null : set; });
        if (after != null) guardians.computeIfAbsent(after, (UUID key) -> ConcurrentHashMap.newKeySet()).add(actor);
    }
    void watching(RegionActor actor, boolean watches) { if (watches) watchers.add(actor); else watchers.remove(actor); }

    /** Idle squad members near the alert, wherever their owners are; each is asked on its own owner. */
    int alertSquad(RegionActor source, String squad, WorldPosition from, double range, TargetRef target, int limit) {
        SpatialNpcIndex.Scan near = scan(from, range, null, new SpatialNpcIndex.Budget(budgets.scanCells(), budgets.scanCandidates()));
        int asked = 0;
        for (VirtualNpcState.Snapshot candidate : near.candidates()) {
            if (asked >= limit) break;
            RegionActor member = actor(candidate.npcId());
            RegionActor.Publication view = member == null || member == source ? null : member.publication;
            if (view == null || view.combat() == null || !view.combat().settings().squad().name().equals(Optional.of(squad))) continue;
            UUID id = member.uuid;
            if (send(member, "alert", (ActorCombat combat) -> combat.engine.alerted(id, target))) asked++;
        }
        return asked;
    }
    /** A fight at {@code at}: guards of either side, and NPCs near it that watch fights, hear of it on their own owners. */
    void fought(RegionActor except, TargetRef attacker, TargetRef victim, WorldPosition at) {
        if (closed || !enabled()) return;
        List<RegionActor> audience = new ArrayList<>();
        for (UUID party : List.of(victim.id(), attacker.id())) {
            Set<RegionActor> guards = guardians.get(party);
            if (guards != null) audience.addAll(guards);
        }
        audience.addAll(watchersNear(at));
        int sent = 0;
        for (RegionActor actor : audience.stream().distinct().toList()) {
            if (actor == except) continue;
            if (sent++ >= DELIVERIES_PER_EVENT) { dropped.incrementAndGet(); continue; }
            send(actor, "fight", (ActorCombat combat) -> combat.engine.heardFight(victim, attacker));
        }
    }
    /**
     * Watching NPCs that could notice something at this point, from the NPC spatial index instead of every watcher on the
     * server, so far watchers cannot use up the delivery cap. A watcher reacts only within its scan or avoidance range.
     */
    private List<RegionActor> watchersNear(WorldPosition at) {
        if (watchers.isEmpty()) return List.of();
        double radius = Math.max(budgets.maximumScanRange(), art.arcane.animus.core.combat.CombatSettings.MAXIMUM_AVOIDANCE_RANGE);
        int side = (int) Math.ceil(radius * 2 / 16) + 2;
        List<RegionActor> found = new ArrayList<>();
        for (VirtualNpcState.Snapshot candidate : scan(at, radius, null, new SpatialNpcIndex.Budget(side * side, 65536)).candidates()) {
            RegionActor actor = actor(candidate.npcId());
            if (actor != null && watchers.contains(actor)) found.add(actor);
        }
        return found;
    }
    /** Deletes a dead NPC's definition on the file lane as one transaction, as the single-owner module does. */
    void deleteDead(UUID npc) {
        try {
            if (files == null) files = host.runtime.fileExecutor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
                if (event == MutationLane.EngineTaskEvent.FAILED) warn("Combat could not delete a dead NPC: " + failure);
            });
            files.execute(() -> {
                try {
                    CombatDeletions.delete(host.runtime.npcs(), npc).ifPresent((CombatDeletions.Deleted done) -> {
                        deleted.incrementAndGet();
                        warn("Combat NPC " + done.id() + " died and was deleted (respawn.deleteOnDeath); undo with /animus rollback " + done.transaction());
                    });
                } catch (java.io.IOException failure) { warn("Combat could not delete dead NPC " + npc + ": " + failure.getMessage()); }
            });
        } catch (IllegalStateException | RejectedExecutionException stopping) {
            warn("Combat could not delete dead NPC " + npc + " while Animus stops: " + stopping.getMessage());
        }
    }

    // ---- Ownership of the real world ----

    Plugin plugin() { return host.plugin; }
    World world(String id) { return host.world(id); }
    boolean owns(Entity entity) { return host.regions.owns(entity); }
    boolean owns(World world, int minX, int minZ, int maxX, int maxZ) { return host.regions.owns(world, minX, minZ, maxX, maxZ); }
    Player online(UUID id) { return host.onlinePlayer(id); }
    /** An entity by identity: only a handle. Its state is read only by a thread that owns it. */
    Entity lookup(UUID id) {
        try { return host.plugin.getServer().getEntity(id); }
        catch (RuntimeException unavailable) { return null; }
    }
    /** Runs on the entity's own scheduler; nothing runs when the entity is gone by then. */
    boolean onEntity(Entity entity, Runnable work) { return host.regions.execute(entity, work, () -> {}); }
    List<Player> ownedNear(WorldPosition center, double radius) { return host.players().ownedNear(center, radius, 1); }
    void deliver(UUID player, Consumer<Player> action) { host.players().deliver(player, action); }
    SpatialNpcIndex.Scan scan(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        return host.index.scan(new SpatialNpcIndex.Query(center, radius), cursor, budget, host::knownLoaded);
    }
    /** On the victim's owner: protection for a hit an NPC's owner handed over. */
    boolean allowsNpcHit(VirtualNpcState.Snapshot npc, LivingEntity victim) {
        TargetRef self = new TargetRef(TargetKind.NPC, npc.npcId());
        TargetRef other = new TargetRef(victim instanceof Player ? TargetKind.PLAYER : TargetKind.MOB, victim.getUniqueId());
        Set<TargetCategory> categories = BukkitCombatWorld.observe(victim, this::helper).map(CombatTarget::categories).orElse(Set.of());
        try {
            return protection.decide(new ProtectionAdapter.Request(ProtectionAdapter.Action.NPC_ATTACK, self, npc.position(), other, RegionViewer.position(victim.getLocation()), categories))
                    == ProtectionAdapter.Decision.ALLOW;
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
    void tag(Projectile projectile, UUID owner) {
        BukkitCombatWorld.tag(projectile, helperKey, (byte) 1);
        projectile.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
    }
    boolean helper(Entity entity) { return entity.getPersistentDataContainer().has(helperKey, PersistentDataType.BYTE); }
    private RegionActor helperOwner(Entity entity) {
        String owner = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try { return owner == null ? null : actor(UUID.fromString(owner)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    // ---- Commands and behaviors ----

    /** {@code combat <operation> <npc>}. Status reads the NPC's publication; every change runs on the NPC's owner and is logged. */
    String command(String id, CombatControl.Operation operation, Optional<UUID> targetId) {
        if (!enabled()) throw new IllegalStateException("Combat module disabled in config.toml");
        RegionActor actor = host.actors.get(id);
        if (actor == null) throw new IllegalArgumentException("Unknown or inactive NPC: " + id);
        if (operation == CombatControl.Operation.STATUS) {
            RegionActor.Publication view = actor.publication;
            return "Combat " + id + ": " + (view.combat() == null ? "NOT_COMBATANT not a combat NPC (combat disabled, review not accepted, or not active)" : "APPLIED " + view.combat().status());
        }
        if (operation == CombatControl.Operation.TARGET && targetId.isEmpty()) throw new IllegalArgumentException("Name a target UUID");
        Optional<TargetRef> target = targetId.map(this::participant);
        String word = operation.name().toLowerCase(java.util.Locale.ROOT);
        boolean queued = host.onOwner(id, () -> {
            ActorCombat combat = actor.combat();
            String result = combat == null ? "NOT_COMBATANT not a combat NPC" : describe(combat.operate(word, target));
            host.plugin.getLogger().info("Combat " + id + ": " + result);
        }, (String reason) -> host.plugin.getLogger().info("Combat " + id + ": UNAVAILABLE " + reason));
        if (!queued) return "Combat " + id + ": UNAVAILABLE the NPC is not active on a region";
        return "Combat " + id + ": QUEUED on the NPC's region; the result follows in the server log";
    }
    private static String describe(CombatOperations.Result result) { return result.status() + " " + result.detail(); }
    /** A combat NPC, an online player or a loaded mob. */
    TargetRef participant(UUID id) {
        RegionActor npc = actor(id);
        if (npc != null && npc.publication.combat() != null) return new TargetRef(TargetKind.NPC, id);
        return new TargetRef(online(id) != null ? TargetKind.PLAYER : TargetKind.MOB, id);
    }
    /** Behavior operations, on the NPC's owner. A refusal carries the reason back to the behavior. */
    String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) {
        if (!enabled()) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", "Combat module disabled in config.toml");
        RegionActor actor = actor(npc);
        if (actor == null || !actor.owner.owned()) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", "Combat runs on the NPC's region owner");
        ActorCombat combat = actor.combat();
        if (combat == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-not-enabled", "NPC has no enabled combat");
        CombatOperations.Result result = combat.operate(switch (operation) {
            case ENABLE -> "engage";
            case DISABLE -> "disengage";
            case RELEASE -> "release";
            case TARGET -> "target";
            case GUARD -> "guard";
        }, target.map(this::participant));
        return switch (result.status()) {
            case APPLIED -> result.detail();
            case INVALID_TARGET -> throw new CapabilityRefusal(Outcome.Status.INVALID, "combat-target", result.detail());
            case NOT_COMBATANT -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-not-enabled", result.detail());
            case DEAD -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-dead", result.detail());
            case UNAVAILABLE -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", result.detail());
        };
    }
    /** Hiring: the engage and guard run on the NPC's owner; whether it guards reads its publication. */
    HireDesk.Guarding guarding() {
        return new HireDesk.Guarding() {
            @Override public void hire(UUID npc, UUID player) {
                host.onOwner(npc, () -> { operate(npc, BehaviorPlatform.CombatOperation.ENABLE, Optional.empty()); operate(npc, BehaviorPlatform.CombatOperation.GUARD, Optional.of(player)); }, (String reason) -> {});
            }
            @Override public void dismiss(UUID npc) {
                host.onOwner(npc, () -> { operate(npc, BehaviorPlatform.CombatOperation.GUARD, Optional.empty()); operate(npc, BehaviorPlatform.CombatOperation.RELEASE, Optional.empty()); }, (String reason) -> {});
            }
            @Override public boolean guards(UUID npc, UUID player) {
                RegionActor actor = actor(npc);
                RegionActor.Publication view = actor == null ? null : actor.publication;
                return enabled() && view != null && view.combat() != null && player.equals(view.combat().guarded());
            }
        };
    }

    // ---- Events, each on the thread that owns its subject ----

    /** On the player's region, which also owns the NPC: an interaction is honoured only there. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerHit(VirtualNpcInteractionEvent event) {
        if (event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.ATTACK) return;
        RegionActor actor = actor(event.getNpcId());
        ActorCombat combat = actor == null || !actor.owner.owned() ? null : actor.combat();
        if (combat == null) return;
        Player player = event.getViewer();
        AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attack == null ? 1 : attack.getValue(), charge = player.getAttackCooldown();
        combat.playerAttack(player, base * (.2 + charge * charge * .8));
    }
    /** On the projectile's region: the hit is observed here and handed to the NPC that launched it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!helper(projectile)) return;
        event.setCancelled(true);
        RegionActor owner = helperOwner(projectile);
        Entity struck = event.getHitEntity();
        TargetRef reference = struck == null ? null : reference(struck);
        CombatTarget observed = reference == null ? null : BukkitCombatWorld.observe(struck, this::helper).orElse(null);
        UUID helper = projectile.getUniqueId();
        projectile.remove();
        if (owner != null) send(owner, "helper", (ActorCombat combat) -> combat.helperHit(helper, reference, observed));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperDamage(EntityDamageByEntityEvent event) { if (helper(event.getDamager())) event.setCancelled(true); }
    /** On the victim's region. Guards and witnesses hear of it on their own owners. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void realDamage(EntityDamageByEntityEvent event) {
        TargetRef victim = reference(event.getEntity());
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            damager = shooter instanceof Entity source ? source : null;
        }
        TargetRef attacker = damager == null ? null : reference(damager);
        if (victim != null && attacker != null) fought(null, attacker, victim, RegionViewer.position(event.getEntity().getLocation()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    /** Chat arrives off every region; the speaker's own region reads where they stand and tells the watchers near them. */
    public void chat(AsyncPlayerChatEvent event) {
        if (closed || !enabled() || watchers.isEmpty()) return;
        Player player = event.getPlayer();
        TargetRef speaker = new TargetRef(TargetKind.PLAYER, player.getUniqueId());
        String message = event.getMessage();
        host.regions.execute(player, () -> {
            if (closed || !enabled() || !player.isOnline()) return;
            int sent = 0;
            for (RegionActor actor : watchersNear(RegionViewer.position(player.getLocation()))) {
                if (sent++ >= DELIVERIES_PER_EVENT) { dropped.incrementAndGet(); continue; }
                send(actor, "chat", (ActorCombat combat) -> combat.engine.chat(speaker, message));
            }
        }, () -> {});
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void helperExplosion(ExplosionPrimeEvent event) { if (helper(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent event) {
        if (!suppressedDrops.contains(event.getEntity().getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!helper(entity)) continue;
            RegionActor owner = helperOwner(entity);
            UUID helper = entity.getUniqueId();
            if (owner != null) send(owner, "helper", (ActorCombat combat) -> combat.helperGone(helper));
        }
    }
    private static TargetRef reference(Entity entity) {
        if (entity instanceof Player) return new TargetRef(TargetKind.PLAYER, entity.getUniqueId());
        return entity instanceof Mob ? new TargetRef(TargetKind.MOB, entity.getUniqueId()) : null;
    }

    /** Engines and their helpers belong to the NPCs' owners and stop with them; this stops the routing. */
    @Override public void close() {
        closed = true;
        try { settingsListener.close(); } catch (Exception ignored) { }
        HandlerList.unregisterAll(this);
        guardians.clear(); watchers.clear(); combatants.clear();
    }
}
