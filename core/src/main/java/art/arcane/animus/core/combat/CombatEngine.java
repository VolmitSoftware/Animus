package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Autonomous combat for registered NPCs. Single owner thread; all work is capped per tick and idle NPCs
 * sleep. All damage in either direction goes through {@link #hit}, which asks protection first.
 */
public final class CombatEngine implements CombatOperations, AutoCloseable {
    public record Status(UUID npc, CombatState state, Optional<TargetRef> target, boolean disengaged) {}

    private record Scheduled(long wake, long order, CombatBrain brain) {}

    private final CombatBudgets budgets;
    private final CombatWorld world;
    private final ProtectionGate protection;
    private final RandomGenerator random;
    private final Map<UUID, CombatBrain> brains = new HashMap<>();
    private final Map<String, List<CombatBrain>> squads = new HashMap<>();
    private final Map<TargetRef, List<CombatBrain>> guardians = new HashMap<>();
    private final PriorityQueue<Scheduled> schedule = new PriorityQueue<>(
            Comparator.comparingLong(Scheduled::wake).thenComparingLong(Scheduled::order));
    private final HelperLedger helpers;
    private long tick;
    private long order;
    private int scansLeft;
    private int pathsLeft;
    private int alertsLeft;
    private boolean closed;

    public CombatEngine(CombatBudgets budgets, CombatWorld world, ProtectionAdapter adapter, RandomGenerator random, Consumer<String> diagnostics) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.world = Objects.requireNonNull(world, "world");
        this.protection = new ProtectionGate(adapter, diagnostics);
        this.random = Objects.requireNonNull(random, "random");
        helpers = new HelperLedger(budgets.maximumHelpers(), budgets.helpersPerNpc());
    }

    public int size() { return brains.size(); }
    public int helperCount() { return helpers.size(); }
    public long protectionDenials() { return protection.denials(); }
    public long protectionFailures() { return protection.failures(); }
    public ProtectionGate protection() { return protection; }
    public boolean registered(UUID npc) { return brains.containsKey(npc); }
    public Set<UUID> npcs() { return Set.copyOf(brains.keySet()); }
    public boolean ownsHelper(UUID helper) { return helpers.tracks(helper); }

    public Optional<Status> status(UUID npc) {
        CombatBrain brain = brains.get(npc);
        if (brain == null) return Optional.empty();
        return Optional.of(new Status(npc, brain.state(), Optional.ofNullable(brain.target()), brain.disengaged()));
    }

    /** Starts or replaces this NPC's combat. False when the NPC budget is full. */
    public boolean register(UUID npc, String handle, CombatSettings settings) {
        if (closed) return false;
        CombatBrain previous = brains.get(npc);
        if (previous != null && previous.settings().equals(settings)) return true;
        if (previous == null && brains.size() >= budgets.maximumNpcs()) return false;
        if (previous != null) unregister(npc);
        CombatBrain brain = new CombatBrain(this, world, npc, handle, settings);
        brains.put(npc, brain);
        settings.squad().name().ifPresent((String squad) -> squads.computeIfAbsent(squad, (String key) -> new ArrayList<>()).add(brain));
        indexGuard(brain);
        schedule(brain, tick + 1);
        return true;
    }

    /** Ends this NPC's combat and removes every helper it owns. Its runtime has gone, so nothing is commanded. */
    public void unregister(UUID npc) {
        CombatBrain brain = brains.remove(npc);
        if (brain == null) return;
        brain.retire();
        removeHelpers(npc);
        brain.settings().squad().name().ifPresent((String squad) -> {
            List<CombatBrain> members = squads.get(squad);
            if (members != null) {
                members.remove(brain);
                if (members.isEmpty()) squads.remove(squad);
            }
        });
        unindexGuard(brain);
    }

    public void tick() {
        if (closed) return;
        tick++;
        scansLeft = budgets.targetScansPerTick();
        pathsLeft = budgets.pathRequestsPerTick();
        alertsLeft = budgets.squadAlertsPerTick();
        updateHelpers();
        for (int visited = 0; visited < budgets.brainsPerTick(); visited++) {
            Scheduled next = schedule.peek();
            if (next == null || next.wake() > tick) break;
            schedule.poll();
            CombatBrain brain = next.brain();
            if (brain.retired() || brain.wake() != next.wake()) { visited--; continue; }
            schedule(brain, tick + Math.max(1, brain.update(tick)));
        }
    }

    long now() { return tick; }
    CombatBudgets budgets() { return budgets; }
    RandomGenerator random() { return random; }
    boolean takeScan() { return scansLeft > 0 && scansLeft-- > 0; }
    boolean takePath() { return pathsLeft > 0 && pathsLeft-- > 0; }
    boolean helperCapacity(UUID owner) { return helpers.hasCapacity(owner); }

    private void schedule(CombatBrain brain, long wake) {
        brain.scheduled(wake);
        schedule.add(new Scheduled(wake, order++, brain));
    }
    /** Brings a sleeping NPC forward to the next tick. Never postpones one that is already due. */
    void wake(CombatBrain brain) {
        if (!brain.retired() && brain.wake() > tick + 1) schedule(brain, tick + 1);
    }

    /** Real entities and combat NPCs near the center, capped by the scan budgets. */
    List<CombatTarget> scan(CombatBrain brain, WorldPosition center, double radius) {
        List<CombatTarget> found = new ArrayList<>(world.nearbyReal(center, radius, budgets.scanCandidates()));
        SpatialNpcIndex.Scan npcs = world.nearbyNpcs(center, radius, brain.npcCursor(),
                new SpatialNpcIndex.Budget(budgets.scanCells(), budgets.scanCandidates()));
        brain.npcCursor(npcs.next().orElse(null));
        for (VirtualNpcState.Snapshot snapshot : npcs.candidates()) {
            CombatBrain other = brains.get(snapshot.npcId());
            if (other != null && other != brain) found.add(view(other, snapshot));
        }
        return found;
    }

    Optional<CombatTarget> resolve(TargetRef ref) {
        if (ref.kind() != TargetKind.NPC) return world.real(ref);
        CombatBrain brain = brains.get(ref.id());
        if (brain == null) return Optional.empty();
        Optional<VirtualNpcState.Snapshot> snapshot = world.npc(ref.id());
        if (snapshot.isEmpty() || !snapshot.get().active()) return Optional.empty();
        return Optional.of(view(brain, snapshot.get()));
    }

    private CombatTarget view(CombatBrain brain, VirtualNpcState.Snapshot snapshot) {
        String name = world.npcName(brain.npc());
        return new CombatTarget(new TargetRef(TargetKind.NPC, brain.npc()), "animus:npc", name.isBlank() ? brain.handle() : name, snapshot.position(),
                snapshot.bounds(), snapshot.health(), Set.of(TargetCategory.NPCS), brain.handle(), brain.settings().squad().name());
    }

    /** Offers this target to the NPC's squad, spending the shared per-tick alert budget. */
    void alertSquad(CombatBrain source, VirtualNpcState.Snapshot self, TargetRef target) {
        Optional<String> squad = source.settings().squad().name();
        if (squad.isEmpty()) return;
        double range = source.settings().squad().alertRange();
        for (CombatBrain member : squads.getOrDefault(squad.get(), List.of())) {
            if (alertsLeft <= 0) return;
            if (member == source || !member.awaitsTarget()) continue;
            Optional<VirtualNpcState.Snapshot> other = world.npc(member.npc());
            if (other.isEmpty() || !other.get().active() || !other.get().position().world().equals(self.position().world())
                    || other.get().position().point().distance(self.position().point()) > range) continue;
            alertsLeft--;
            member.alerted(target);
        }
    }

    /** A real entity hurt another. Guards of the victim respond at no cost when no one guards it. */
    public void realDamaged(TargetRef victim, TargetRef attacker) {
        for (CombatBrain guardian : guardians.getOrDefault(victim, List.of())) guardian.alerted(attacker);
    }

    /** All damage goes through here: protection first, then defense, then exactly one damage authority. */
    private CombatWorld.DamageOutcome hit(TargetRef attacker, WorldPosition from, CombatTarget target, double amount,
                                          CombatWorld.DamageKind kind, double reach, double knockback, boolean victimDrops) {
        ProtectionAdapter.Action action = switch (attacker.kind()) {
            case NPC -> ProtectionAdapter.Action.NPC_ATTACK;
            case PLAYER -> ProtectionAdapter.Action.PLAYER_ATTACK;
            case MOB -> ProtectionAdapter.Action.MOB_ATTACK;
        };
        if (!protection.allows(new ProtectionAdapter.Request(action, attacker, from, target.ref(), target.position(), target.categories())))
            return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.PROTECTED, 0, false);
        if (target.ref().kind() != TargetKind.NPC) {
            CombatWorld.DamageOutcome outcome = world.damageReal(attacker.id(), target, amount, kind, reach, knockback, victimDrops);
            if (outcome.status() == CombatWorld.DamageStatus.APPLIED) realDamaged(target.ref(), attacker);
            return outcome;
        }
        CombatBrain defender = brains.get(target.ref().id());
        if (defender == null) return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        double mitigated = defender.mitigate(attacker, from, amount, resolve(attacker));
        if (mitigated <= 0) return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        CombatWorld.DamageOutcome outcome = world.damageVirtual(defender.npc(), attacker, mitigated);
        if (outcome.status() != CombatWorld.DamageStatus.APPLIED) return outcome;
        afterVirtualHit(defender, attacker, from, target, knockback, outcome.killed());
        return outcome;
    }

    private void afterVirtualHit(CombatBrain defender, TargetRef attacker, WorldPosition from, CombatTarget target, double knockback, boolean killed) {
        if (killed) {
            defender.die(target.position());
            return;
        }
        if (knockback > 0 && defender.settings().defense().knockback()) {
            Vec3 away = target.position().point().subtract(from.point());
            if (Math.abs(away.x()) + Math.abs(away.z()) > .0001) world.knockback(defender.npc(), new Vec3(away.x(), 0, away.z()), knockback);
        }
        defender.attacked(attacker);
        for (CombatBrain guardian : guardians.getOrDefault(target.ref(), List.of())) guardian.alerted(attacker);
    }

    CombatWorld.DamageOutcome melee(CombatBrain brain, VirtualNpcState.Snapshot self, CombatTarget target) {
        CombatSettings.Melee melee = brain.settings().melee();
        return hit(new TargetRef(TargetKind.NPC, brain.npc()), self.position(), target, melee.damage(), CombatWorld.DamageKind.MELEE,
                melee.reach(), melee.knockback(), brain.settings().death().victimDrops());
    }

    /** Launches one real arrow toward the target and records it before anything can touch it. */
    boolean shoot(CombatBrain brain, WorldPosition from, CombatTarget target) {
        CombatSettings.Ranged ranged = brain.settings().ranged();
        Vec3 aim = CombatGeometry.center(target.bounds());
        Vec3 line = aim.subtract(from.point());
        double distance = line.distance(Vec3.ZERO);
        if (distance < .0001) return false;
        Vec3 direction = line.normalized();
        double spread = ranged.inaccuracy() * .0075;
        Vec3 velocity = new Vec3(
                direction.x() * ranged.velocity() + random.nextGaussian() * spread,
                direction.y() * ranged.velocity() + random.nextGaussian() * spread + .025 * distance / ranged.velocity(),
                direction.z() * ranged.velocity() + random.nextGaussian() * spread);
        Optional<UUID> helper = world.launchHelper(brain.npc(), from, velocity);
        if (helper.isEmpty()) return false;
        helpers.track(new HelperLedger.Shot(helper.get(), brain.npc(), Optional.of(target.ref()), ranged.damage(), ranged.knockback(),
                ranged.safeShot(), tick + budgets.helperLifetimeTicks(), from.point()));
        return true;
    }

    private void updateHelpers() {
        int visits = Math.min(budgets.helperUpdatesPerTick(), helpers.size());
        for (int i = 0; i < visits; i++) {
            Optional<HelperLedger.Shot> next = helpers.next();
            if (next.isEmpty()) return;
            HelperLedger.Shot shot = next.get();
            Optional<Vec3> position = world.helperPosition(shot.helper());
            if (position.isEmpty()) {
                helpers.claim(shot.helper());
                continue;
            }
            if (tick >= shot.expiresAt()) {
                retire(shot.helper());
                continue;
            }
            if (!collide(shot, position.get())) shot.moved(position.get());
        }
    }

    /** Real arrows cannot touch virtual bodies, so their flight segment is tested against nearby NPC bounds. */
    private boolean collide(HelperLedger.Shot shot, Vec3 position) {
        Vec3 from = shot.lastPosition();
        double length = from.distance(position);
        if (length < .0001) return false;
        CombatBrain owner = brains.get(shot.owner());
        Optional<VirtualNpcState.Snapshot> self = world.npc(shot.owner());
        if (owner == null || self.isEmpty()) return false;
        Vec3 middle = from.add(position).multiply(.5);
        SpatialNpcIndex.Scan near = world.nearbyNpcs(new WorldPosition(self.get().position().world(), middle), length / 2 + 4, null,
                new SpatialNpcIndex.Budget(budgets.scanCells(), budgets.scanCandidates()));
        Vec3 direction = position.subtract(from);
        CombatBrain struck = null;
        VirtualNpcState.Snapshot struckState = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (VirtualNpcState.Snapshot candidate : near.candidates()) {
            CombatBrain other = brains.get(candidate.npcId());
            if (other == null || other == owner || owner.friendly(new TargetRef(TargetKind.NPC, other.npc()), other.settings().squad().name())) continue;
            if (shot.safeShot() && !shot.intended().equals(Optional.of(new TargetRef(TargetKind.NPC, other.npc())))) continue;
            OptionalDouble distance = candidate.bounds().rayDistance(from, direction, length);
            if (distance.isPresent() && distance.getAsDouble() < nearest) {
                nearest = distance.getAsDouble();
                struck = other;
                struckState = candidate;
            }
        }
        if (struck == null || helpers.claim(shot.helper()).isEmpty()) return false;
        world.removeHelper(shot.helper());
        hit(new TargetRef(TargetKind.NPC, shot.owner()), self.get().position(), view(struck, struckState), shot.damage(),
                CombatWorld.DamageKind.PROJECTILE, 0, shot.knockback(), owner.settings().death().victimDrops());
        return true;
    }

    /**
     * A tracked helper touched a real entity. Its vanilla damage must be cancelled by the platform; this is
     * the only damage it causes. True when the helper was live and has been spent.
     */
    public boolean helperHitReal(UUID helper, TargetRef struck) {
        Optional<HelperLedger.Shot> claimed = helpers.claim(helper);
        if (claimed.isEmpty()) return false;
        world.removeHelper(helper);
        HelperLedger.Shot shot = claimed.get();
        CombatBrain owner = brains.get(shot.owner());
        Optional<VirtualNpcState.Snapshot> self = world.npc(shot.owner());
        Optional<CombatTarget> target = resolve(struck);
        if (owner == null || self.isEmpty() || target.isEmpty()) return true;
        if (owner.friendly(struck, target.get().squad())) return true;
        if (shot.safeShot() && !shot.intended().equals(Optional.of(struck))) return true;
        hit(new TargetRef(TargetKind.NPC, shot.owner()), self.get().position(), target.get(), shot.damage(),
                CombatWorld.DamageKind.PROJECTILE, 0, shot.knockback(), owner.settings().death().victimDrops());
        return true;
    }

    public boolean helperHitBlock(UUID helper) { return retire(helper); }

    /** Removes this helper if it is still tracked. For cancellation and unload paths in the platform. */
    public boolean retire(UUID helper) {
        if (helpers.claim(helper).isEmpty()) return false;
        world.removeHelper(helper);
        return true;
    }

    void removeHelpers(UUID owner) {
        for (UUID helper : helpers.retireOwner(owner)) world.removeHelper(helper);
    }

    /** A real player hit this NPC's validated hitbox. NPCs with no enabled combat cannot be hurt. */
    public CombatWorld.DamageOutcome playerAttack(UUID npc, TargetRef player, WorldPosition from, double amount) {
        CombatBrain brain = brains.get(npc);
        Optional<VirtualNpcState.Snapshot> snapshot = world.npc(npc);
        if (brain == null || snapshot.isEmpty() || !snapshot.get().active() || !(amount > 0))
            return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        return hit(player, from, view(brain, snapshot.get()), amount, CombatWorld.DamageKind.MELEE, 0, .4, true);
    }

    /** Applies this NPC's defense to a hit from outside the engine. Returns the amount to apply, or 0. */
    public double mitigateExternal(UUID npc, TargetRef attacker, WorldPosition from, double amount) {
        CombatBrain brain = brains.get(npc);
        return brain == null ? amount : brain.mitigate(attacker, from, amount, resolve(attacker));
    }

    /** Reports a hit applied outside the engine, such as a controlled mob's. */
    public void externalHit(UUID npc, TargetRef attacker, VirtualNpcState.Snapshot after) {
        CombatBrain brain = brains.get(npc);
        if (brain == null) return;
        if (after.health() == 0) brain.die(after.position());
        else brain.attacked(attacker);
    }

    private void indexGuard(CombatBrain brain) {
        TargetRef guarded = brain.guarded();
        if (guarded != null) guardians.computeIfAbsent(guarded, (TargetRef key) -> new ArrayList<>()).add(brain);
    }
    private void unindexGuard(CombatBrain brain) {
        TargetRef guarded = brain.guarded();
        if (guarded == null) return;
        List<CombatBrain> list = guardians.get(guarded);
        if (list == null) return;
        list.remove(brain);
        if (list.isEmpty()) guardians.remove(guarded);
    }

    @Override public Result engage(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.engage();
            return new Result(CombatOperations.Status.APPLIED, "Combat engaged");
        });
    }
    @Override public Result disengage(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.disengage();
            removeHelpers(npc);
            return new Result(CombatOperations.Status.APPLIED, "Combat disengaged");
        });
    }
    @Override public Result target(UUID npc, TargetRef target) {
        return control(npc, (CombatBrain brain) -> {
            Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
            Optional<CombatTarget> seen = target.id().equals(npc) ? Optional.empty() : resolve(target);
            if (self.isEmpty() || seen.isEmpty() || !seen.get().position().world().equals(self.get().position().world()))
                return new Result(CombatOperations.Status.INVALID_TARGET, "Target is not available in this NPC's world");
            brain.force(target);
            return new Result(CombatOperations.Status.APPLIED, "Target set");
        });
    }
    @Override public Result release(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.release();
            return new Result(CombatOperations.Status.APPLIED, "Target released");
        });
    }
    @Override public Result guard(UUID npc, Optional<TargetRef> guarded) {
        return control(npc, (CombatBrain brain) -> {
            if (guarded.isPresent() && (guarded.get().id().equals(npc) || resolve(guarded.get()).isEmpty()))
                return new Result(CombatOperations.Status.INVALID_TARGET, "Guarded participant is not available");
            unindexGuard(brain);
            brain.guard(guarded.orElse(null));
            indexGuard(brain);
            return new Result(CombatOperations.Status.APPLIED, guarded.isPresent() ? "Guarding" : "Guard cleared");
        });
    }
    private interface Control { Result apply(CombatBrain brain); }
    private Result control(UUID npc, Control control) {
        if (closed) return new Result(CombatOperations.Status.UNAVAILABLE, "Combat is shutting down");
        CombatBrain brain = brains.get(npc);
        if (brain == null) return new Result(CombatOperations.Status.NOT_COMBATANT, "NPC has no enabled combat");
        if (brain.state() == CombatState.DEAD) return new Result(CombatOperations.Status.DEAD, "NPC is dead");
        Result result = control.apply(brain);
        wake(brain);
        return result;
    }

    /** Removes every helper and ends all combat. Safe to call twice. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (UUID helper : helpers.retireAll()) world.removeHelper(helper);
        for (CombatBrain brain : List.copyOf(brains.values())) brain.retire();
        brains.clear();
        squads.clear();
        guardians.clear();
        schedule.clear();
    }

    /** Whether the line from a shooter to a target crosses this body. */
    static boolean crosses(Hitbox body, Vec3 from, Vec3 to) {
        double length = from.distance(to);
        return length > .0001 && body.rayDistance(from, to.subtract(from), length).isPresent();
    }
}
