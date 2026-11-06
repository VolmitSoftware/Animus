package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Combat of one NPC on Folia: an engine that holds only this NPC, run on the NPC's owner and moved with it on every
 * handoff. It reads only entities this region owns, other NPCs only through the views their owners published, and
 * real entities others own only through snapshots their owners delivered. Damage to an entity another region holds,
 * a hit on another NPC, a squad alert and a heal for another NPC are messages to that owner, checked again there.
 * Per-tick work comes from the region's combat budgets. Owner only.
 */
final class ActorCombat implements CombatWorld, AutoCloseable {
    private static final int SEEN = 128;
    /** How far a delivered hit may have come from, so a stale hit after a teleport does nothing. */
    private static final double HIT_SLACK = 8;
    private final RegionActor actor;
    private final RegionCombat combat;
    final CombatSettings settings;
    final CombatLoadout loadout;
    final CombatEngine engine;
    /** Entities this NPC looked at recently; read again only while this region owns them. */
    private final Map<UUID, Entity> seen = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Entity> eldest) { return size() > SEEN; }
    };
    /** Observations other owners delivered with a message, valid for the message they came with. */
    private final Map<TargetRef, CombatTarget> delivered = new HashMap<>();
    private final Map<UUID, Projectile> helpers = new HashMap<>();
    private final Map<UUID, Vec3> helperPositions = new HashMap<>();
    private UUID guarded;
    /** Ticks in a row this NPC's brain wanted a turn and the region had none left. */
    private int waited;

    ActorCombat(RegionActor actor, RegionCombat combat, CombatSettings settings, CombatLoadout loadout) {
        this.actor = actor; this.combat = combat; this.settings = settings; this.loadout = loadout;
        engine = new CombatEngine(combat.budgets, this, combat.protection, new SplittableRandom(), combat::warn, allowance(combat.allowance()));
        engine.register(actor.uuid, actor.id, settings, loadout);
        refreshIndexes();
    }

    /**
     * The region's shared allowance, except that this NPC's single brain asks for its turn longest-waiting-first: Folia
     * runs the NPC tasks of a region in the same order every tick, so the last ones would otherwise never get a turn.
     */
    private CombatEngine.Allowance allowance(CombatEngine.Allowance shared) {
        return new CombatEngine.Allowance() {
            @Override public int take(Kind kind, int wanted) {
                if (kind != Kind.BRAINS) return shared.take(kind, wanted);
                if (wanted > 0 && combat.turn(waited)) { waited = 0; return 1; }
                waited++;
                return 0;
            }
            @Override public void refund(Kind kind, int unused) {
                if (kind != Kind.BRAINS) { shared.refund(kind, unused); return; }
                if (unused > 0) { shared.refund(kind, unused); waited = 0; }
            }
        };
    }
    boolean matches(CombatSettings nextSettings, CombatLoadout nextLoadout) { return settings.equals(nextSettings) && loadout.equals(nextLoadout); }
    UUID guarded() { return guarded; }

    /** One combat turn. False once combat was turned off and this engine has nothing left in flight: close it then. */
    boolean tick() {
        art.arcane.animus.core.config.CombatBudgets next = combat.budgets;
        if (next != engine.budgets()) engine.reconfigure(next);
        if (!next.enabled()) engine.stop();
        else if (engine.stopping()) engine.resume();
        delivered.clear();
        engine.tick();
        for (UUID npc : engine.drainDeletions()) combat.deleteDead(npc);
        refreshIndexes();
        return !engine.drained();
    }
    /** A status line for commands and the publication. */
    String status() {
        return engine.status(actor.uuid).map((CombatEngine.Status state) -> state.state() + " target=" + state.target().map((TargetRef ref) -> ref.kind() + ":" + ref.id()).orElse("none")
                + (state.disengaged() ? " disengaged" : "") + (state.ammunition() >= 0 ? " ammunition=" + state.ammunition() : "")).orElse("not a combatant");
    }
    CombatOperations.Result operate(String operation, Optional<TargetRef> target) {
        CombatOperations.Result result = switch (operation) {
            case "engage" -> engine.engage(actor.uuid);
            case "disengage" -> engine.disengage(actor.uuid);
            case "release" -> engine.release(actor.uuid);
            case "target" -> engine.target(actor.uuid, target.orElseThrow());
            case "guard" -> engine.guard(actor.uuid, target);
            default -> throw new IllegalArgumentException("Unknown combat operation: " + operation);
        };
        refreshIndexes();
        return result;
    }
    private void refreshIndexes() {
        UUID now = engine.guarded(actor.uuid).map(TargetRef::id).orElse(null);
        if (!java.util.Objects.equals(now, guarded)) { combat.guarding(actor, guarded, now); guarded = now; }
        combat.watching(actor, engine.watches());
    }

    /** A message from another owner: a hit on this NPC. Checked against where this NPC stands now. */
    void struck(TargetRef attacker, WorldPosition from, double amount, double knockback) {
        WorldPosition at = actor.state.snapshot().position();
        if (!at.world().equals(from.world()) || at.point().distance(from.point()) > combat.budgets.maximumScanRange() + HIT_SLACK) return;
        engine.struck(actor.uuid, attacker, from, amount, knockback);
    }
    /** A message from the projectile's owner: a helper this NPC launched touched a real entity, observed there. */
    void helperHit(UUID helper, TargetRef struck, CombatTarget observed) {
        helpers.remove(helper); helperPositions.remove(helper);
        if (struck == null) { engine.helperHitBlock(helper); return; }
        if (observed != null) delivered.put(struck, observed);
        if (!engine.helperHitReal(helper, struck)) engine.helperHitBlock(helper);
    }
    void helperGone(UUID helper) { if (engine.retire(helper)) { helpers.remove(helper); helperPositions.remove(helper); } }
    void playerAttack(Player player, double amount) {
        Location at = player.getLocation();
        TargetRef attacker = new TargetRef(TargetKind.PLAYER, player.getUniqueId());
        BukkitCombatWorld.observe(player, combat::helper).ifPresent((CombatTarget target) -> delivered.put(attacker, target));
        engine.playerAttack(actor.uuid, attacker, RegionViewer.position(at), amount);
    }

    int helpers() { return helpers.size(); }
    @Override public void close() {
        engine.close();
        combat.closed(actor);
        combat.guarding(actor, guarded, null); guarded = null;
        combat.watching(actor, false);
        helpers.clear(); helperPositions.clear(); seen.clear(); delivered.clear();
    }

    // ---- CombatWorld: this NPC's own state, on its owner ----

    @Override public Optional<VirtualNpcState.Snapshot> npc(UUID npc) {
        if (npc.equals(actor.uuid)) return Optional.of(actor.state.snapshot());
        RegionActor other = combat.actor(npc);
        RegionActor.Publication view = other == null ? null : other.publication;
        return view == null || view.combat() == null ? Optional.empty() : Optional.of(view.snapshot());
    }
    @Override public String npcName(UUID npc) {
        if (npc.equals(actor.uuid)) return actor.displayName();
        RegionActor other = combat.actor(npc);
        return other == null ? "" : other.publication.name();
    }
    @Override public Optional<CombatTarget> peer(UUID npc) {
        RegionActor other = combat.actor(npc);
        RegionActor.Publication view = other == null ? null : other.publication;
        if (view == null || view.combat() == null || !view.snapshot().active()) return Optional.empty();
        return Optional.of(CombatEngine.npcTarget(npc, view.name(), view.snapshot(), other.id, view.combat().settings(), view.combat().loadout()));
    }
    @Override public DamageOutcome strikePeer(TargetRef attacker, WorldPosition from, CombatTarget target, double amount, double knockback) {
        RegionActor other = combat.actor(target.ref().id());
        if (other == null) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        if (!combat.send(other, "hit", (ActorCombat defender) -> defender.struck(attacker, from, amount, knockback))) return new DamageOutcome(DamageStatus.FAILED, 0, false);
        return new DamageOutcome(DamageStatus.SCHEDULED, 0, false);
    }
    @Override public int alertPeers(String squad, UUID source, WorldPosition from, double range, TargetRef target, int limit) {
        return combat.alertSquad(actor, squad, from, range, target, limit);
    }
    @Override public void foughtElsewhere(TargetRef attacker, TargetRef victim) { combat.fought(actor, attacker, victim, actor.state.snapshot().position()); }

    @Override public MoveStatus move(UUID npc, WorldPosition goal, double speed) { return npc.equals(actor.uuid) ? actor.combatMove(goal, speed) : MoveStatus.REJECTED; }
    @Override public String movement(UUID npc) { return npc.equals(actor.uuid) ? actor.navigationStatus() : "inactive"; }
    @Override public void stop(UUID npc) { if (npc.equals(actor.uuid)) actor.stopNavigation(); }
    /** A patrol resumes only once no route runs, so the chase path ends first or the NPC walks it out. */
    @Override public void release(UUID npc) { if (npc.equals(actor.uuid)) { actor.stopNavigation(); actor.resumePatrol(); } }
    @Override public boolean teleport(UUID npc, WorldPosition destination) { return npc.equals(actor.uuid) && actor.combatTeleport(destination); }
    @Override public Optional<WorldPosition> post(UUID npc) { return npc.equals(actor.uuid) ? actor.post() : Optional.empty(); }
    @Override public boolean step(UUID npc, WorldPosition to) { return npc.equals(actor.uuid) && actor.combatStep(to); }
    @Override public void restorePose(UUID npc) { if (npc.equals(actor.uuid)) actor.restorePose(); }
    @Override public void face(UUID npc, Vec3 point) {
        if (!npc.equals(actor.uuid)) return;
        try { actor.lookAt(new WorldPosition(actor.state.snapshot().position().world(), point)); }
        catch (IllegalArgumentException | IllegalStateException ignored) { /* facing is cosmetic */ }
    }
    @Override public double yaw(UUID npc) { return npc.equals(actor.uuid) ? actor.yaw : 0; }
    @Override public void knockback(UUID npc, Vec3 direction, double strength) { if (npc.equals(actor.uuid)) actor.combatKnockback(direction, strength); }
    @Override public void heal(UUID npc, double amount) {
        if (npc.equals(actor.uuid)) { actor.combatHeal(amount); return; }
        RegionActor other = combat.actor(npc);
        if (other != null) combat.send(other, "heal", (ActorCombat friend) -> friend.actor.combatHeal(amount));
    }
    @Override public boolean revive(UUID npc, WorldPosition at) { return npc.equals(actor.uuid) && actor.combatRevive(at); }
    @Override public DamageOutcome damageVirtual(UUID npc, TargetRef attacker, double amount) {
        if (!npc.equals(actor.uuid) || !actor.state.snapshot().active()) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        VirtualNpcState.Snapshot before = actor.state.snapshot();
        VirtualNpcState.Snapshot after = actor.state.damage(amount);
        actor.combatChanged(before, after);
        return new DamageOutcome(DamageStatus.APPLIED, before.health() - after.health(), after.health() == 0);
    }

    // ---- Real entities: read only while this region owns them ----

    private Entity entity(UUID id) {
        Entity known = seen.get(id);
        if (known == null) known = combat.online(id);
        if (known == null) known = combat.lookup(id);
        if (known != null) seen.put(id, known);
        return known;
    }
    @Override public Optional<CombatTarget> real(TargetRef ref) {
        if (ref.kind() == TargetKind.NPC) return Optional.empty();
        CombatTarget handed = delivered.get(ref);
        if (handed != null) return Optional.of(handed);
        Entity entity = entity(ref.id());
        if (entity == null || !combat.owns(entity)) return Optional.empty();
        return BukkitCombatWorld.observe(entity, combat::helper).filter((CombatTarget target) -> target.ref().kind() == ref.kind());
    }
    @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
        World world = combat.world(center.world());
        if (world == null) return List.of();
        Vec3 point = center.point();
        int minX = ((int) Math.floor(point.x() - radius)) >> 4, maxX = ((int) Math.floor(point.x() + radius)) >> 4;
        int minZ = ((int) Math.floor(point.z() - radius)) >> 4, maxZ = ((int) Math.floor(point.z() + radius)) >> 4;
        List<Entity> candidates = new ArrayList<>();
        if (combat.owns(world, minX, minZ, maxX, maxZ) && loaded(world, minX, minZ, maxX, maxZ)) {
            BoundingBox area = new BoundingBox(point.x() - radius, point.y() - radius, point.z() - radius, point.x() + radius, point.y() + radius, point.z() + radius);
            candidates.addAll(world.getNearbyEntities(area, (Entity candidate) -> candidate instanceof LivingEntity));
        } else {
            // Only the chunks this region holds are read; the rest of the scan area belongs to someone else.
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!combat.owns(world, x, z, x, z) || !world.isChunkLoaded(x, z)) continue;
                Chunk chunk = world.getChunkAt(x, z);
                for (Entity entity : chunk.getEntities()) if (entity instanceof LivingEntity) candidates.add(entity);
            }
        }
        List<CombatTarget> found = new ArrayList<>();
        for (Entity entity : candidates) {
            if (found.size() >= limit) break;
            if (!combat.owns(entity)) continue;
            Optional<CombatTarget> target = BukkitCombatWorld.observe(entity, combat::helper);
            if (target.isPresent() && target.get().position().point().distance(point) <= radius) { found.add(target.get()); seen.put(entity.getUniqueId(), entity); }
        }
        return found;
    }
    private static boolean loaded(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
    @Override public boolean playersNear(WorldPosition center, double radius) { return !combat.ownedNear(center, radius).isEmpty(); }
    @Override public SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        return combat.scan(center, radius, cursor, budget);
    }
    @Override public Visibility lineOfSight(String worldId, Vec3 from, Vec3 to) {
        World world = combat.world(worldId);
        if (world == null) return Visibility.UNAVAILABLE;
        int minX = ((int) Math.floor(Math.min(from.x(), to.x()))) >> 4, maxX = ((int) Math.floor(Math.max(from.x(), to.x()))) >> 4;
        int minZ = ((int) Math.floor(Math.min(from.z(), to.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(from.z(), to.z()))) >> 4;
        if (!combat.owns(world, minX, minZ, maxX, maxZ) || !loaded(world, minX, minZ, maxX, maxZ)) return Visibility.UNAVAILABLE;
        double distance = from.distance(to);
        if (distance < .0001) return Visibility.CLEAR;
        Vec3 direction = to.subtract(from);
        return world.rayTraceBlocks(new Location(world, from.x(), from.y(), from.z()), new Vector(direction.x(), direction.y(), direction.z()), distance, FluidCollisionMode.NEVER, true) == null
                ? Visibility.CLEAR : Visibility.BLOCKED;
    }

    /**
     * Damage to a real entity: at once when this region owns it, otherwise handed to the entity's own scheduler, which
     * checks that it is still valid and in reach of where the NPC stood and asks protection again before it applies.
     */
    @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
        Entity entity = entity(target.ref().id());
        VirtualNpcState.Snapshot self = actor.state.snapshot();
        if (!(entity instanceof LivingEntity victim) || !attacker.equals(actor.uuid)) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        if (combat.owns(victim))
            return BukkitCombatWorld.damage(combat.plugin(), attacker, self, victim, amount, kind, knockback, victimDrops, combat.suppressedDrops, () -> actor.state.snapshot().active());
        double range = (kind == DamageKind.MELEE ? reach : combat.budgets.maximumScanRange()) + HIT_SLACK;
        boolean handed = combat.onEntity(victim, () -> {
            Location at = victim.getLocation();
            if (!victim.isValid() || victim.isDead() || !at.getWorld().getUID().toString().equals(self.position().world())
                    || RegionViewer.position(at).point().distance(self.position().point()) > range) return;
            if (!combat.allowsNpcHit(self, victim)) return;
            DamageOutcome outcome = BukkitCombatWorld.damage(combat.plugin(), attacker, self, victim, amount, kind, knockback, victimDrops, combat.suppressedDrops, () -> true);
            if (outcome.status() == DamageStatus.APPLIED) combat.fought(null, new TargetRef(TargetKind.NPC, attacker), target.ref(), RegionViewer.position(at));
        });
        return new DamageOutcome(handed ? DamageStatus.SCHEDULED : DamageStatus.FAILED, 0, false);
    }
    @Override public boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect) {
        Entity entity = entity(target.ref().id());
        if (entity == null) return false;
        if (combat.owns(entity)) return BukkitCombatWorld.effect(entity, effect);
        return combat.onEntity(entity, () -> BukkitCombatWorld.effect(entity, effect));
    }
    @Override public boolean permitted(TargetRef player, String node) {
        if (player.kind() != TargetKind.PLAYER) return false;
        Player online = combat.online(player.id());
        return online != null && combat.owns(online) && online.hasPermission(node);
    }
    @Override public void tell(UUID player, String speaker, String message) { combat.deliver(player, (Player online) -> online.sendMessage(DialogueFormat.speech(speaker, message))); }

    /** Helpers are spawned only in a chunk this region holds and tagged with their NPC, so a hit anywhere finds its owner. */
    @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon) {
        World world = combat.world(from.world());
        Vec3 point = from.point();
        int chunkX = ((int) Math.floor(point.x())) >> 4, chunkZ = ((int) Math.floor(point.z())) >> 4;
        if (world == null || !combat.owns(world, chunkX, chunkZ, chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ)) return Optional.empty();
        Optional<Projectile> launched = BukkitCombatWorld.launch(world, point, velocity, weapon, (Projectile projectile) -> combat.tag(projectile, owner));
        launched.ifPresent((Projectile projectile) -> { helpers.put(projectile.getUniqueId(), projectile); helperPositions.put(projectile.getUniqueId(), point); });
        return launched.map(Projectile::getUniqueId);
    }
    /** A helper that flew into another region keeps its last known position until it lands there or expires. */
    @Override public Optional<Vec3> helperPosition(UUID helper) {
        Projectile projectile = helpers.get(helper);
        if (projectile == null) return Optional.empty();
        if (!combat.owns(projectile)) return Optional.ofNullable(helperPositions.get(helper));
        if (!projectile.isValid()) { helpers.remove(helper); helperPositions.remove(helper); return Optional.empty(); }
        Location at = projectile.getLocation();
        Vec3 point = new Vec3(at.getX(), at.getY(), at.getZ());
        helperPositions.put(helper, point);
        return Optional.of(point);
    }
    @Override public void removeHelper(UUID helper) {
        Projectile projectile = helpers.remove(helper);
        helperPositions.remove(helper);
        if (projectile == null) return;
        if (combat.owns(projectile)) projectile.remove();
        else combat.onEntity(projectile, projectile::remove);
    }
    @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) {
        World world = combat.world(location.world());
        return world == null ? Optional.empty() : Optional.of(new WorldPosition(world.getUID().toString(), new Vec3(location.x(), location.y(), location.z())));
    }
    @Override public void drop(WorldPosition at, List<Drop> items, int experience) {
        World world = combat.world(at.world());
        Vec3 point = at.point();
        int chunkX = ((int) Math.floor(point.x())) >> 4, chunkZ = ((int) Math.floor(point.z())) >> 4;
        if (world == null || !combat.owns(world, chunkX, chunkZ, chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ)) return;
        BukkitCombatWorld.drop(world, point, items, experience);
    }
}
